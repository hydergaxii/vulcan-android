package com.vulcan.app.launcher

import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.vulcan.app.data.model.*
import com.vulcan.app.data.database.VulcanDatabase
import com.vulcan.app.data.database.entities.SlotEntity
import com.vulcan.app.icon.IconResolver
import com.vulcan.app.service.PrivilegedClient
import com.vulcan.app.util.VulcanLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * VULCAN LAUNCHER BRIDGE — Home screen domination.
 *
 * SLOT MODE: 10 ActivityAlias entries = 10 real launcher icons.
 * Daemon enables/disables silently. Icons feel like real apps.
 *
 * SHORTCUT MODE: Pinned shortcuts via ShortcutManager.
 * Works on all launchers. No slot limit. No daemon required.
 */
class VulcanLauncherBridge(private val context: Context) {

    private val iconResolver = IconResolver(context)

    // ─── INSTALL TO HOME SCREEN ───────────────────────────────────────────────

    suspend fun installToHomeScreen(app: InstalledApp, mode: LaunchMode) {
        val icon = iconResolver.resolveIcon(app.manifest)

        when (mode) {
            LaunchMode.SLOT     -> installViaSlot(app, icon)
            LaunchMode.SHORTCUT -> installViaShortcut(app, icon)
        }
    }

    // ─── REMOVE FROM HOME SCREEN ──────────────────────────────────────────────

    suspend fun removeFromHomeScreen(app: InstalledApp, mode: LaunchMode) {
        when (mode) {
            LaunchMode.SLOT     -> clearSlot(app.slotIndex)
            LaunchMode.SHORTCUT -> removeShortcut(app.id)
        }
    }

    // ─── SLOT MODE ────────────────────────────────────────────────────────────

    private suspend fun installViaSlot(app: InstalledApp, icon: Bitmap) {
        val db         = VulcanDatabase.getInstance(context)
        val slotIndex  = if (app.slotIndex >= 0) app.slotIndex
                         else db.slotDao().getFirstFreeSlot()?.index ?: run {
                             VulcanLogger.w("LauncherBridge: no free slots available")
                             return
                         }

        // Persist slot assignment
        db.slotDao().insert(SlotEntity(
            index    = slotIndex,
            appId    = app.id,
            appLabel = app.label,
            iconPath = null,
            isActive = true
        ))

        // Enable the ActivityAlias via daemon (silent — no dialog)
        val component = "${context.packageName}.slots.Slot$slotIndex"
        PrivilegedClient.instance?.enableActivityAlias(component)
            ?: enableSlotFallback(slotIndex)   // Fallback without daemon

        VulcanLogger.i("LauncherBridge: ${app.id} installed to slot $slotIndex")
    }

    private fun enableSlotFallback(slotIndex: Int) {
        // Without daemon, we can't silently enable the alias.
        // Notify the user to manually add the shortcut instead.
        VulcanLogger.w("LauncherBridge: daemon not available — slot $slotIndex requires daemon or ADB tier")
    }

    private suspend fun clearSlot(slotIndex: Int) {
        if (slotIndex < 0) return
        val db = VulcanDatabase.getInstance(context)
        db.slotDao().clearSlot(slotIndex)

        val component = "${context.packageName}.slots.Slot$slotIndex"
        PrivilegedClient.instance?.disableActivityAlias(component) ?: run {
            // Fallback: disable via PackageManager (requires shell permission)
            try {
                context.packageManager.setComponentEnabledSetting(
                    ComponentName(context, "$component"),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            } catch (e: Exception) {
                VulcanLogger.w("LauncherBridge: cannot disable slot $slotIndex without daemon")
            }
        }
    }

    // ─── SHORTCUT MODE ────────────────────────────────────────────────────────

    private fun installViaShortcut(app: InstalledApp, icon: Bitmap) {
        val intent = Intent(context, SlotActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra("app_id", app.id)
            putExtra("app_port", app.port)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val shortcut = ShortcutInfoCompat.Builder(context, "vulcan_app_${app.id}")
            .setShortLabel(app.label)
            .setLongLabel("${app.label} — Vulcan")
            .setIcon(IconCompat.createWithBitmap(icon))
            .setIntent(intent)
            .build()

        if (ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
            VulcanLogger.i("LauncherBridge: shortcut pin requested for ${app.id}")
        } else {
            VulcanLogger.w("LauncherBridge: launcher doesn't support pinned shortcuts")
        }
    }

    private fun removeShortcut(appId: String) {
        ShortcutManagerCompat.removeDynamicShortcuts(context, listOf("vulcan_app_$appId"))
    }

    // ─── LAN URL ──────────────────────────────────────────────────────────────

    fun getLanAccessUrl(app: InstalledApp): String {
        val lanIP = getLanIP() ?: "localhost"
        return "http://$lanIP:${app.port}"
    }

    fun getLocalUrl(app: InstalledApp): String = "http://127.0.0.1:${app.port}"

    private fun getLanIP(): String? {
        return try {
            val wm = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val ip = wm.connectionInfo.ipAddress
            if (ip == 0) null
            else String.format("%d.%d.%d.%d",
                ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff)
        } catch (e: Exception) { null }
    }

    // ─── SLOT REGISTRY ────────────────────────────────────────────────────────

    suspend fun getSlotForApp(appId: String): Int? {
        val db = VulcanDatabase.getInstance(context)
        return db.slotDao().getByAppId(appId)?.index
    }

    suspend fun clearAllSlots() {
        val db = VulcanDatabase.getInstance(context)
        (0..9).forEach { db.slotDao().clearSlot(it) }
        for (i in 0..9) {
            val component = "${context.packageName}.slots.Slot$i"
            PrivilegedClient.instance?.disableActivityAlias(component)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SLOT ACTIVITY — Launched when user taps a home screen slot icon
// ─────────────────────────────────────────────────────────────────────────────

class SlotActivity : Activity() {

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)

        // Determine which app to open
        val appId = intent.getStringExtra("app_id")
            ?: getAppIdFromSlotIndex()
            ?: run { finish(); return }

        if (VulcanCoreService.isAppRunning(appId)) {
            openWebView(appId)
        } else {
            // App isn't running — start it, show splash while waiting
            VulcanCoreService.startApp(appId, this)
            showStartingSplash(appId) { openWebView(appId) }
        }
    }

    private fun getAppIdFromSlotIndex(): String? {
        // Read slot index from the activity alias metadata, then look up appId
        val slotIndex = try {
            val ai = packageManager.getActivityInfo(
                ComponentName(this, this::class.java),
                PackageManager.GET_META_DATA
            )
            ai.metaData?.getInt("vulcan.slot.index", -1) ?: -1
        } catch (e: Exception) { -1 }

        if (slotIndex < 0) return null

        // Look up synchronously (we're on main thread, keep it fast)
        return try {
            android.os.Handler(mainLooper).post {}
            // Get from SharedPreferences slot cache
            getSharedPreferences("slot_cache", Context.MODE_PRIVATE)
                .getString("slot_$slotIndex", null)
        } catch (e: Exception) { null }
    }

    private fun openWebView(appId: String) {
        val port = VulcanCoreService.getRunningApps()[appId]?.let {
            // Get port from DB — for now use a cached value
            getSharedPreferences("app_ports", Context.MODE_PRIVATE).getInt(appId, -1)
        } ?: -1

        val intent = Intent(this, WebViewActivity::class.java).apply {
            putExtra("app_id", appId)
            putExtra("url", if (port > 0) "http://127.0.0.1:$port" else "http://127.0.0.1:8080/app/$appId")
        }
        startActivity(intent)
        finish()
    }

    private fun showStartingSplash(appId: String, onReady: () -> Unit) {
        // Show a simple "Starting {app}..." screen while we wait
        // Real implementation shows a Compose splash screen
        setContentView(android.R.layout.activity_list_item)
        // Poll until the app is running
        val handler = android.os.Handler(mainLooper)
        val check = object : Runnable {
            override fun run() {
                if (VulcanCoreService.isAppRunning(appId)) {
                    onReady()
                } else {
                    handler.postDelayed(this, 1_000)
                }
            }
        }
        handler.postDelayed(check, 1_000)
    }
}

// Placeholder — full implementation in ui/screens/WebViewScreen.kt
class WebViewActivity : Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra("url") ?: "http://127.0.0.1:7777"
        val webView = android.webkit.WebView(this).apply {
            settings.javaScriptEnabled   = true
            settings.domStorageEnabled   = true
            settings.allowFileAccess     = true
            loadUrl(url)
        }
        setContentView(webView)
    }
}
