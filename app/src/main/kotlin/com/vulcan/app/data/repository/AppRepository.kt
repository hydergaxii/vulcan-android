package com.vulcan.app.data.repository

import android.content.Context
import com.google.gson.Gson
import com.vulcan.app.data.database.dao.AppDao
import com.vulcan.app.data.database.dao.SlotDao
import com.vulcan.app.data.database.entities.AppEntity
import com.vulcan.app.data.model.*
import com.vulcan.app.launcher.VulcanLauncherBridge
import com.vulcan.app.runtime.PortManager
import com.vulcan.app.security.SecretsVault
import com.vulcan.app.service.VulcanCoreService
import com.vulcan.app.storage.StorageManager
import com.vulcan.app.util.AppStatusBus
import com.vulcan.app.util.VulcanLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * APP REPOSITORY — Single data source for the UI layer.
 *
 * Merges:
 *  - Room DB (persisted installed apps)
 *  - VulcanCoreService (live running state)
 *  - AppStatusBus (real-time status events)
 *
 * ViewModels observe Flows from here. Never touch DAOs directly from UI.
 */
@Singleton
class AppRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appDao: AppDao,
    private val slotDao: SlotDao,
    private val portManager: PortManager,
    private val vault: SecretsVault,
    private val launcherBridge: VulcanLauncherBridge,
    private val gson: Gson
) {

    // ─── OBSERVE ALL INSTALLED APPS ───────────────────────────────────────────

    fun observeInstalledApps(): Flow<List<InstalledApp>> =
        appDao.observeAll()
            .map { entities -> entities.map { it.toDomain(gson) } }
            .combine(AppStatusBus.statusMap) { apps, statusMap ->
                apps   // Apps from DB — status overlaid by ViewModel
            }

    // ─── OBSERVE WITH LIVE STATUS ─────────────────────────────────────────────

    fun observeAppsWithStatus(): Flow<List<AppWithStatus>> =
        appDao.observeAll()
            .map { it.map { e -> e.toDomain(gson) } }
            .combine(AppStatusBus.statusMap) { apps, statusMap ->
                apps.map { app ->
                    AppWithStatus(
                        app    = app,
                        status = statusMap[app.id] ?: AppStatus.STOPPED
                    )
                }
            }

    data class AppWithStatus(val app: InstalledApp, val status: AppStatus)

    // ─── SINGLE APP ───────────────────────────────────────────────────────────

    suspend fun getApp(appId: String): InstalledApp? =
        appDao.getById(appId)?.toDomain(gson)

    fun observeAppStatus(appId: String): Flow<AppStatus> =
        AppStatusBus.observeApp(appId)

    // ─── INSTALL APP ──────────────────────────────────────────────────────────

    suspend fun saveInstalledApp(manifest: AppManifest, port: Int, slotIndex: Int = -1): InstalledApp {
        val app = InstalledApp(
            id             = manifest.id,
            label          = manifest.label,
            version        = manifest.version,
            port           = port,
            runtimeType    = manifest.runtime.type,
            runtimeEngine  = manifest.runtime.engine,
            runtimeVersion = manifest.runtime.version,
            prootDistro    = manifest.runtime.distro.takeIf { it.isNotBlank() },
            startCommand   = manifest.install.startCommand,
            installPath    = StorageManager.appDir(context, manifest.id).canonicalPath,
            isAutoStart    = false,
            slotIndex      = slotIndex,
            brandColor     = 0xFF6B35.toInt(),
            installedAt    = System.currentTimeMillis(),
            lastStartedAt  = 0L,
            updateAvailable = false,
            latestVersion  = null,
            manifest       = manifest
        )

        appDao.insert(AppEntity.fromDomain(app, gson))
        VulcanLogger.i("AppRepository: saved ${manifest.id} to DB")
        return app
    }

    suspend fun uninstallApp(appId: String) {
        // Stop if running
        if (VulcanCoreService.isAppRunning(appId)) {
            VulcanCoreService.stopApp(appId, context)
        }

        // Remove from DB
        appDao.deleteById(appId)

        // Remove from launcher
        val launchMode = getLaunchMode()
        val app = appDao.getById(appId)?.toDomain(gson)
        app?.let { launcherBridge.removeFromHomeScreen(it, launchMode) }

        // Clear vault secrets
        vault.deleteAppSecrets(appId)

        // Remove app directory
        StorageManager.appDir(context, appId).deleteRecursively()

        // Clear status
        AppStatusBus.clearApp(appId)

        VulcanLogger.i("AppRepository: uninstalled $appId")
    }

    // ─── SETTINGS ─────────────────────────────────────────────────────────────

    suspend fun setAutoStart(appId: String, autoStart: Boolean) {
        appDao.getById(appId)?.let { entity ->
            appDao.update(entity.copy(isAutoStart = autoStart))
        }
    }

    fun getPermissionTier(): PermissionTier {
        val prefs = context.getSharedPreferences("vulcan_prefs", Context.MODE_PRIVATE)
        return PermissionTier.valueOf(prefs.getString("permission_tier", PermissionTier.NORMAL.name)!!)
    }

    fun setPermissionTier(tier: PermissionTier) {
        context.getSharedPreferences("vulcan_prefs", Context.MODE_PRIVATE)
            .edit().putString("permission_tier", tier.name).apply()
    }

    fun getLaunchMode(): LaunchMode {
        val prefs = context.getSharedPreferences("vulcan_prefs", Context.MODE_PRIVATE)
        return LaunchMode.valueOf(prefs.getString("launch_mode", LaunchMode.SLOT.name)!!)
    }

    fun setLaunchMode(mode: LaunchMode) {
        context.getSharedPreferences("vulcan_prefs", Context.MODE_PRIVATE)
            .edit().putString("launch_mode", mode.name).apply()
    }

    fun isFirstRun(): Boolean {
        val prefs = context.getSharedPreferences("vulcan_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("first_run", true)
    }

    fun completeFirstRun() {
        context.getSharedPreferences("vulcan_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("first_run", false).apply()
    }

    // ─── ENV ──────────────────────────────────────────────────────────────────

    fun getEnv(appId: String): Map<String, String> =
        StorageManager.readEnvFile(context, appId)

    fun setEnv(appId: String, key: String, value: String) {
        val current = StorageManager.readEnvFile(context, appId).toMutableMap()
        current[key] = value
        StorageManager.writeEnvFile(context, appId, current)
    }

    fun getSecret(appId: String, key: String): String? = vault.getSecret(appId, key)
    fun setSecret(appId: String, key: String, value: String) = vault.putSecret(appId, key, value)
    fun getSecretKeys(appId: String): List<String> = vault.listKeys(appId)
}
