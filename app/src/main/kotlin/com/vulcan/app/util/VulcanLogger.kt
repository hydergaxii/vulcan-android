package com.vulcan.app.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * VULCAN LOGGER — Writes to both Android logcat and /sdcard/Vulcan/logs/
 *
 * All logs are visible to users in any text editor. Total transparency.
 * Per-app logs go to /sdcard/Vulcan/apps/{id}/logs/YYYY-MM-DD.log
 * System logs go to /sdcard/Vulcan/logs/vulcan.log
 */
object VulcanLogger {

    private const val TAG = "Vulcan"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val logDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val queue = LinkedBlockingQueue<LogEntry>(10_000)
    private val running = AtomicBoolean(false)
    private var context: Context? = null
    private var writerThread: Thread? = null

    data class LogEntry(
        val timestamp: Long,
        val level: String,
        val tag: String,
        val message: String,
        val appId: String? = null
    )

    fun init(ctx: Context) {
        context = ctx.applicationContext
        if (running.compareAndSet(false, true)) {
            startWriterThread()
        }
    }

    // ─── PUBLIC API ───────────────────────────────────────────────────────────

    fun v(msg: String, appId: String? = null) = log("VERBOSE", msg, appId)
    fun d(msg: String, appId: String? = null) = log("DEBUG", msg, appId)
    fun i(msg: String, appId: String? = null) = log("INFO", msg, appId)
    fun w(msg: String, appId: String? = null) = log("WARN", msg, appId)
    fun e(msg: String, appId: String? = null) = log("ERROR", msg, appId)
    fun crash(msg: String, t: Throwable, appId: String? = null) {
        log("CRASH", "$msg\n${t.stackTraceToString()}", appId)
    }

    private fun log(level: String, message: String, appId: String?) {
        // Always log to logcat immediately (non-blocking)
        when (level) {
            "VERBOSE" -> Log.v(TAG, message)
            "DEBUG"   -> Log.d(TAG, message)
            "INFO"    -> Log.i(TAG, message)
            "WARN"    -> Log.w(TAG, message)
            "ERROR"   -> Log.e(TAG, message)
            "CRASH"   -> Log.e(TAG, message)
        }
        // Enqueue for async file write
        queue.offer(LogEntry(System.currentTimeMillis(), level, TAG, message, appId))
    }

    // ─── ASYNC WRITER THREAD ──────────────────────────────────────────────────

    private fun startWriterThread() {
        writerThread = thread(name = "VulcanLogger", isDaemon = true) {
            while (running.get()) {
                try {
                    val entry = queue.take()
                    writeToFile(entry)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Logger write error: ${e.message}")
                }
            }
        }
    }

    private fun writeToFile(entry: LogEntry) {
        val ctx = context ?: return
        val line = "[${dateFormat.format(Date(entry.timestamp))}] [${entry.level}] ${entry.message}\n"
        val dateStr = logDateFormat.format(Date(entry.timestamp))

        try {
            // Write to per-app log if appId provided
            if (entry.appId != null) {
                val appLogDir = File(
                    android.os.Environment.getExternalStorageDirectory(),
                    "Vulcan/apps/${entry.appId}/logs"
                )
                appLogDir.mkdirs()
                File(appLogDir, "$dateStr.log").appendText(line)
            }

            // Always write to system log
            val systemLogDir = File(
                android.os.Environment.getExternalStorageDirectory(),
                "Vulcan/logs"
            )
            systemLogDir.mkdirs()
            File(systemLogDir, "vulcan.log").appendText(line)

            // Rotate: keep last 5MB of system log
            val sysLog = File(systemLogDir, "vulcan.log")
            if (sysLog.length() > 5 * 1024 * 1024) {
                sysLog.renameTo(File(systemLogDir, "vulcan.log.old"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write log: ${e.message}")
        }
    }

    fun getRecentLines(appId: String? = null, lineCount: Int = 100): List<String> {
        val ctx = context ?: return emptyList()
        val logFile = if (appId != null) {
            val dateStr = logDateFormat.format(Date())
            File(android.os.Environment.getExternalStorageDirectory(),
                "Vulcan/apps/$appId/logs/$dateStr.log")
        } else {
            File(android.os.Environment.getExternalStorageDirectory(), "Vulcan/logs/vulcan.log")
        }
        if (!logFile.exists()) return emptyList()
        return logFile.readLines().takeLast(lineCount)
    }

    fun stop() {
        running.set(false)
        writerThread?.interrupt()
    }
}
