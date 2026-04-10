package com.vulcan.app.service

import android.app.*
import android.content.*
import android.os.*
import androidx.core.app.NotificationCompat
import com.vulcan.app.data.model.*
import com.vulcan.app.data.database.VulcanDatabase
import com.vulcan.app.launcher.VulcanLauncherBridge
import com.vulcan.app.monitoring.MetricsCollector
import com.vulcan.app.network.*
import com.vulcan.app.runtime.*
import com.vulcan.app.storage.StorageManager
import com.vulcan.app.util.*
import com.vulcan.app.web.VulcanWebServer
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * VULCAN CORE SERVICE — The heartbeat of the forge.
 *
 * Runs as a FOREGROUND service (notification pinned = Android won't kill it).
 * START_STICKY = Android automatically restarts it after OOM kills.
 * WakeLock = CPU stays awake while apps are serving requests.
 * 7-layer Doze defense = survives deep sleep.
 */
class VulcanCoreService : Service() {

    companion object {
        private const val NOTIF_ID      = 1001
        private const val CHANNEL_ID    = "vulcan_core"
        private const val CHANNEL_NAME  = "Vulcan — Running Apps"

        private val _runningApps = ConcurrentHashMap<String, AppProcess>()
        private var _serviceRunning = false

        fun isAppRunning(appId: String): Boolean  = _runningApps.containsKey(appId)
        fun getRunningApps(): Map<String, AppProcess> = _runningApps.toMap()
        fun isRunning(context: Context): Boolean  = _serviceRunning

        fun startApp(appId: String, context: Context) {
            val intent = Intent(context, VulcanCoreService::class.java)
                .putExtra("action", "start_app")
                .putExtra("app_id", appId)
            context.startForegroundService(intent)
        }

        fun stopApp(appId: String, context: Context) {
            val intent = Intent(context, VulcanCoreService::class.java)
                .putExtra("action", "stop_app")
                .putExtra("app_id", appId)
            context.startForegroundService(intent)
        }
    }

    private lateinit var runtimeEngine: RuntimeEngine
    private lateinit var metricsCollector: MetricsCollector
    private lateinit var vulcanProxy: VulcanProxy
    private lateinit var mDnsAdvertiser: MDNSAdvertiser
    private lateinit var vulcanWebServer: VulcanWebServer
    private lateinit var vulcanConnect: VulcanConnect
    private lateinit var wakeLock: PowerManager.WakeLock
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ─── LIFECYCLE ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        _serviceRunning = true
        createNotificationChannel()

        runtimeEngine     = RuntimeEngine(this)
        metricsCollector  = MetricsCollector(this)
        vulcanProxy       = VulcanProxy()
        mDnsAdvertiser    = MDNSAdvertiser(this)
        vulcanWebServer   = VulcanWebServer(this)
        vulcanConnect     = VulcanConnect(this)

        // Acquire partial wake lock — CPU stays on while apps are serving
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Vulcan:CoreService")
            .apply { acquire() }

        VulcanLogger.i("VulcanCoreService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())

        when (intent?.getStringExtra("action")) {
            "start_app" -> {
                val appId = intent.getStringExtra("app_id") ?: return START_STICKY
                scope.launch { startAppInternal(appId) }
            }
            "stop_app" -> {
                val appId = intent.getStringExtra("app_id") ?: return START_STICKY
                stopAppInternal(appId)
            }
            else -> {
                // Service (re)started — boot subsystems and restore apps
                bootSubsystems()
                scope.launch { restoreRunningApps() }
            }
        }

        return START_STICKY     // Android WILL restart this if killed
    }

    override fun onDestroy() {
        _serviceRunning = false
        wakeLock.release()
        vulcanProxy.stop()
        vulcanWebServer.stop()
        metricsCollector.stop()
        mDnsAdvertiser.stopAll()
        vulcanConnect.stopAll()
        scope.cancel()
        VulcanLogger.i("VulcanCoreService destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    // ─── SUBSYSTEM BOOT ───────────────────────────────────────────────────────

    private fun bootSubsystems() {
        vulcanProxy.start()
        vulcanWebServer.start()
        mDnsAdvertiser.advertiseDashboard(VulcanWebServer.WEB_PORT)
        metricsCollector.start { _runningApps }
        DozeWorkaround(this).scheduleAlarmPing()
        VulcanLogger.i("All Vulcan subsystems started")
    }

    // ─── APP MANAGEMENT ───────────────────────────────────────────────────────

    suspend fun startAppInternal(appId: String) {
        val db  = VulcanDatabase.getInstance(this)
        val entity = db.appDao().getById(appId)
        if (entity == null) {
            VulcanLogger.e("startApp: app '$appId' not found in database")
            return
        }
        val app = entity.toDomain(com.google.gson.Gson())

        if (_runningApps.containsKey(appId)) {
            VulcanLogger.w("startApp: $appId is already running")
            return
        }

        AppStatusBus.emit(appId, AppStatus.STARTING)

        try {
            val process = runtimeEngine.start(app)
            _runningApps[appId] = process

            // Register with all subsystems
            vulcanProxy.registerApp(app)
            InternalMesh.register(appId, app.port)
            mDnsAdvertiser.advertise(appId, app.port)

            // Wait for health check
            val ready = runtimeEngine.waitForReady(app, timeoutMs = 90_000)
            AppStatusBus.emit(appId, if (ready) AppStatus.RUNNING else AppStatus.ERROR)

            // Protect from OOM killer
            PrivilegedClient.instance?.setOomAdj(process.pid, -100)

            // Persist running state for boot restore
            saveRunningState(appId, true)

            // Update last-started timestamp
            db.appDao().updateLastStarted(appId, System.currentTimeMillis())

            updateNotification()
            AuditLogHelper.info(this, "APP_STARTED", appId)
        } catch (e: Exception) {
            _runningApps.remove(appId)
            AppStatusBus.emit(appId, AppStatus.ERROR, e.message ?: "Unknown error")
            VulcanLogger.e("Failed to start $appId: ${e.message}", appId)
            AuditLogHelper.warn(this, "APP_START_FAILED", "$appId: ${e.message}")
        }
    }

    private fun stopAppInternal(appId: String) {
        val process = _runningApps.remove(appId) ?: return
        AppStatusBus.emit_(appId, AppStatus.STOPPING)
        runtimeEngine.stop(process)
        vulcanProxy.unregisterApp(appId)
        InternalMesh.unregister(appId)
        mDnsAdvertiser.stopApp(appId)
        saveRunningState(appId, false)
        scope.launch { AppStatusBus.emit(appId, AppStatus.STOPPED) }
        updateNotification()
        AuditLogHelper.info(this, "APP_STOPPED", appId)
        VulcanLogger.i("$appId stopped")
    }

    // ─── RESTORE AFTER BOOT/KILL ──────────────────────────────────────────────

    private suspend fun restoreRunningApps() {
        val db = VulcanDatabase.getInstance(this)
        val prefs = getSharedPreferences("running_apps", MODE_PRIVATE)
        val wasRunning = prefs.getStringSet("app_ids", emptySet()) ?: return

        VulcanLogger.i("Restoring ${wasRunning.size} apps after restart")

        wasRunning.forEach { appId ->
            try {
                delay(2_000)    // Stagger restarts — don't hammer CPU at boot
                startAppInternal(appId)
            } catch (e: Exception) {
                VulcanLogger.e("Failed to restore $appId: ${e.message}")
            }
        }
    }

    private fun saveRunningState(appId: String, isRunning: Boolean) {
        val prefs   = getSharedPreferences("running_apps", MODE_PRIVATE)
        val current = prefs.getStringSet("app_ids", mutableSetOf())!!.toMutableSet()
        if (isRunning) current.add(appId) else current.remove(appId)
        prefs.edit().putStringSet("app_ids", current).apply()
    }

    // ─── NOTIFICATION ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows which Vulcan apps are running"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val runningCount = _runningApps.size
        val runningNames = _runningApps.keys.joinToString(", ")

        val mainIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Vulcan — ${runningCount} app${if (runningCount != 1) "s" else ""} running")
            .setContentText(if (runningNames.isNotBlank()) runningNames else "Forge is ready")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification())
    }
}

// Simple sync emit for stopping (called from non-coroutine context)
private fun AppStatusBus.emit_(appId: String, status: AppStatus) {
    kotlinx.coroutines.runBlocking { emit(appId, status) }
}

// Thin wrapper so we don't pass Context everywhere
private object AuditLogHelper {
    fun info(ctx: Context, event: String, details: String) =
        com.vulcan.app.security.AuditLogger.info(ctx, event, details)
    fun warn(ctx: Context, event: String, details: String) =
        com.vulcan.app.security.AuditLogger.warn(ctx, event, details)
}
