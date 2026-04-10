package com.vulcan.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.net.Uri
import androidx.work.*
import com.vulcan.app.util.VulcanLogger
import java.util.concurrent.TimeUnit

// ─────────────────────────────────────────────────────────────────────────────
// BOOT RECEIVER — Restart Vulcan after device reboot
// ─────────────────────────────────────────────────────────────────────────────

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in listOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )) return

        VulcanLogger.i("Boot received — starting VulcanCoreService")
        context.startForegroundService(Intent(context, VulcanCoreService::class.java))

        // Schedule watchdog
        ServiceWatchdog.schedule(context)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ALARM RECEIVER — Doze-mode last-resort ping (Layer 7)
// ─────────────────────────────────────────────────────────────────────────────

class VulcanAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!VulcanCoreService.isRunning(context)) {
            VulcanLogger.w("AlarmReceiver: VulcanCoreService was dead. Restarting...")
            context.startForegroundService(Intent(context, VulcanCoreService::class.java))
        }
        // Reschedule the next alarm
        DozeWorkaround(context).scheduleAlarmPing()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// DOZE WORKAROUND — 7-layer defense against Android background killing
//
// Layer 1: Foreground Service (guaranteed while notification shown)
// Layer 2: START_STICKY (Android restarts after kill, usually <10s)
// Layer 3: WorkManager Watchdog (every 15 min minimum, WorkManager's floor)
// Layer 4: BootReceiver (restart after device reboot)
// Layer 5: Battery Optimization Exemption (user grant, once)
// Layer 6: WakeLock (CPU stays on while apps are serving)
// Layer 7: AlarmManager exact alarm (works even in deep Doze — 1/hour limit)
// ─────────────────────────────────────────────────────────────────────────────

class DozeWorkaround(private val context: Context) {

    // Layer 5: Request battery optimization exemption
    fun requestBatteryOptimizationExemption(activity: android.app.Activity) {
        val pm = context.getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${context.packageName}"))
            activity.startActivity(intent)
        }
    }

    fun isBatteryOptimizationExempt(): Boolean {
        val pm = context.getSystemService(PowerManager::class.java)
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    // Layer 7: AlarmManager exact alarm — survives deep Doze
    fun scheduleAlarmPing() {
        val am = context.getSystemService(AlarmManager::class.java)
        val intent = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, VulcanAlarmReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // setExactAndAllowWhileIdle — fires even during Doze (once per ~30 min in deep Doze)
        am.setExactAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 30 * 60 * 1000L,   // 30 minutes
            intent
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SERVICE WATCHDOG — WorkManager periodic check (Layer 3)
// Checks every 15 minutes (WorkManager's minimum interval).
// If VulcanCoreService is dead, restarts it.
// ─────────────────────────────────────────────────────────────────────────────

class ServiceWatchdogWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        if (!VulcanCoreService.isRunning(applicationContext)) {
            VulcanLogger.w("Watchdog: VulcanCoreService is dead. Restarting...")
            applicationContext.startForegroundService(
                Intent(applicationContext, VulcanCoreService::class.java)
            )
        }
        return Result.success()
    }
}

object ServiceWatchdog {
    private const val WORK_TAG = "vulcan_watchdog"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<ServiceWatchdogWorker>(
            15, TimeUnit.MINUTES
        )
            .addTag(WORK_TAG)
            .setConstraints(Constraints.Builder().build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 5, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_TAG,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
        VulcanLogger.i("ServiceWatchdog scheduled (every 15 min)")
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(WORK_TAG)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// PRIVILEGED CLIENT — Stub for daemon IPC (real impl in daemon/ module)
// ─────────────────────────────────────────────────────────────────────────────

object PrivilegedClient {
    var instance: IPrivilegedDaemon? = null

    interface IPrivilegedDaemon {
        fun ping(): Boolean
        fun setOomAdj(pid: Int, adj: Int)
        fun execShell(cmd: String): String
        fun installApk(path: String)
        fun grantPermission(packageName: String, permission: String)
        fun enableActivityAlias(component: String)
        fun disableActivityAlias(component: String)
    }
}
