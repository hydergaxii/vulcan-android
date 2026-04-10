package com.vulcan.app.monitoring

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import com.vulcan.app.data.database.VulcanDatabase
import com.vulcan.app.data.database.entities.MetricsEntry
import com.vulcan.app.data.model.AppMetrics
import com.vulcan.app.data.model.AppProcess
import com.vulcan.app.data.model.DeviceMetrics
import com.vulcan.app.util.VulcanLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * METRICS COLLECTOR — Real-time resource monitoring. Every 10 seconds.
 *
 * Tracks per-app: CPU%, RAM MB, Net RX/TX bytes, uptime.
 * Tracks device: total RAM, CPU%, storage, battery.
 * Stores 24h history in Room (MetricsEntry). Prunes older records.
 * Emits real-time StateFlow for the UI to observe.
 */
class MetricsCollector(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var collectorJob: Job? = null

    private val _appMetrics    = MutableStateFlow<Map<String, AppMetrics>>(emptyMap())
    private val _deviceMetrics = MutableStateFlow<DeviceMetrics?>(null)

    val appMetrics: StateFlow<Map<String, AppMetrics>>  = _appMetrics.asStateFlow()
    val deviceMetrics: StateFlow<DeviceMetrics?>        = _deviceMetrics.asStateFlow()

    // Net RX/TX snapshots for delta calculation
    private val prevNetRx = ConcurrentHashMap<String, Long>()
    private val prevNetTx = ConcurrentHashMap<String, Long>()

    // ─── LIFECYCLE ────────────────────────────────────────────────────────────

    fun start(runningApps: () -> Map<String, AppProcess>) {
        collectorJob?.cancel()
        collectorJob = scope.launch {
            while (isActive) {
                try {
                    collectAll(runningApps())
                    pruneOldMetrics()
                } catch (e: Exception) {
                    VulcanLogger.e("MetricsCollector error: ${e.message}")
                }
                delay(10_000)   // Collect every 10 seconds
            }
        }
        VulcanLogger.i("MetricsCollector started")
    }

    fun stop() {
        collectorJob?.cancel()
        VulcanLogger.i("MetricsCollector stopped")
    }

    // ─── COLLECTION ───────────────────────────────────────────────────────────

    private suspend fun collectAll(runningApps: Map<String, AppProcess>) {
        val device  = collectDeviceMetrics()
        _deviceMetrics.emit(device)

        val appMap = mutableMapOf<String, AppMetrics>()
        val db = VulcanDatabase.getInstance(context)

        runningApps.forEach { (appId, process) ->
            try {
                val metrics = collectAppMetrics(appId, process)
                appMap[appId] = metrics

                // Persist to Room for historical charts
                db.metricsDao().insert(
                    MetricsEntry(
                        appId      = appId,
                        timestamp  = metrics.timestamp,
                        cpuPercent = metrics.cpuPercent,
                        ramMB      = metrics.ramMB,
                        netRxBytes = metrics.netRxBytes,
                        netTxBytes = metrics.netTxBytes
                    )
                )
            } catch (e: Exception) {
                VulcanLogger.d("Metrics: error collecting for $appId: ${e.message}")
            }
        }

        _appMetrics.emit(appMap)
    }

    // ─── APP METRICS ──────────────────────────────────────────────────────────

    private fun collectAppMetrics(appId: String, process: AppProcess): AppMetrics {
        val pid = process.pid
        val ram = readProcMemory(pid)
        val cpu = readProcCpu(pid)
        val (rx, tx) = readProcNet(pid)

        // Delta net bytes since last collection
        val prevRx = prevNetRx.getOrDefault(appId, rx)
        val prevTx = prevNetTx.getOrDefault(appId, tx)
        prevNetRx[appId] = rx
        prevNetTx[appId] = tx

        return AppMetrics(
            appId      = appId,
            cpuPercent = cpu,
            ramMB      = ram / 1024f,
            netRxBytes = maxOf(0L, rx - prevRx),
            netTxBytes = maxOf(0L, tx - prevTx),
            uptimeMs   = System.currentTimeMillis() - process.startedAt
        )
    }

    /** Read RSS memory from /proc/{pid}/status (VmRSS line) in KB */
    private fun readProcMemory(pid: Int): Long {
        return try {
            File("/proc/$pid/status").readLines()
                .firstOrNull { it.startsWith("VmRSS:") }
                ?.split("\\s+".toRegex())
                ?.getOrNull(1)
                ?.toLongOrNull() ?: 0L
        } catch (e: Exception) { 0L }
    }

    /** Read CPU usage from /proc/{pid}/stat — two-point sampling */
    private fun readProcCpu(pid: Int): Float {
        return try {
            val stat1 = readProcStat(pid)
            Thread.sleep(200)
            val stat2 = readProcStat(pid)

            val pidTime1   = stat1.first
            val totalTime1 = stat1.second
            val pidTime2   = stat2.first
            val totalTime2 = stat2.second

            val pidDelta   = (pidTime2 - pidTime1).toFloat()
            val totalDelta = (totalTime2 - totalTime1).toFloat()

            if (totalDelta == 0f) 0f
            else (pidDelta / totalDelta * 100f).coerceIn(0f, 100f)
        } catch (e: Exception) { 0f }
    }

    private fun readProcStat(pid: Int): Pair<Long, Long> {
        val statLine   = File("/proc/$pid/stat").readText().split(" ")
        val utime      = statLine.getOrNull(13)?.toLongOrNull() ?: 0L
        val stime      = statLine.getOrNull(14)?.toLongOrNull() ?: 0L
        val pidTime    = utime + stime

        val cpuTotal   = File("/proc/stat").readLines().firstOrNull { it.startsWith("cpu ") }
            ?.split("\\s+".toRegex())
            ?.drop(1)
            ?.mapNotNull { it.toLongOrNull() }
            ?.sum() ?: 1L

        return Pair(pidTime, cpuTotal)
    }

    /** Read network bytes from /proc/{pid}/net/dev if available */
    private fun readProcNet(pid: Int): Pair<Long, Long> {
        return try {
            val netDev = File("/proc/$pid/net/dev")
            if (!netDev.exists()) return Pair(0L, 0L)

            var totalRx = 0L; var totalTx = 0L
            netDev.readLines().drop(2).forEach { line ->
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.size >= 10) {
                    totalRx += parts[1].toLongOrNull() ?: 0L
                    totalTx += parts[9].toLongOrNull() ?: 0L
                }
            }
            Pair(totalRx, totalTx)
        } catch (e: Exception) { Pair(0L, 0L) }
    }

    // ─── DEVICE METRICS ───────────────────────────────────────────────────────

    private fun collectDeviceMetrics(): DeviceMetrics {
        val am  = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi  = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)

        val stat     = StatFs(Environment.getExternalStorageDirectory().path)
        val storageTotalMB = stat.totalBytes / 1024 / 1024
        val storageFreeMB  = stat.freeBytes  / 1024 / 1024

        val bm       = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val battery  = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging

        return DeviceMetrics(
            totalRamMB     = mi.totalMem / 1024 / 1024,
            availableRamMB = mi.availMem / 1024 / 1024,
            cpuPercent     = readSystemCpu(),
            storageTotalMB = storageTotalMB,
            storageUsedMB  = storageTotalMB - storageFreeMB,
            batteryPercent = battery,
            isCharging     = charging
        )
    }

    private fun readSystemCpu(): Float {
        return try {
            val line = File("/proc/stat").readLines().firstOrNull { it.startsWith("cpu ") } ?: return 0f
            val vals = line.split("\\s+".toRegex()).drop(1).mapNotNull { it.toLongOrNull() }
            val idle = vals.getOrNull(3) ?: return 0f
            val total = vals.sum()
            if (total == 0L) 0f else ((total - idle).toFloat() / total * 100f).coerceIn(0f, 100f)
        } catch (e: Exception) { 0f }
    }

    // ─── PRUNING ─────────────────────────────────────────────────────────────

    private suspend fun pruneOldMetrics() {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L   // 24 hours
        VulcanDatabase.getInstance(context).metricsDao().pruneOlderThan(cutoff)
    }

    // ─── CONVENIENCE ACCESSORS ────────────────────────────────────────────────

    fun getAppMetrics(appId: String): AppMetrics? = _appMetrics.value[appId]

    fun getDeviceMetrics(): DeviceMetrics? = _deviceMetrics.value

    suspend fun getHistoricalMetrics(appId: String, since: Long): List<MetricsEntry> {
        return VulcanDatabase.getInstance(context).metricsDao().getForAppSince(appId, since)
    }
}
