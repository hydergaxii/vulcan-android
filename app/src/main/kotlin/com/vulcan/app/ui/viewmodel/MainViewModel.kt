package com.vulcan.app.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vulcan.app.backup.BackupManager
import com.vulcan.app.data.model.*
import com.vulcan.app.data.repository.AppRepository
import com.vulcan.app.monitoring.MetricsCollector
import com.vulcan.app.network.VulcanConnect
import com.vulcan.app.runtime.RuntimeDownloader
import com.vulcan.app.runtime.RuntimeEngine
import com.vulcan.app.service.VulcanCoreService
import com.vulcan.app.store.StoreRepository
import com.vulcan.app.util.AppStatusBus
import com.vulcan.app.util.VulcanLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appRepository: AppRepository,
    private val storeRepository: StoreRepository,
    private val metricsCollector: MetricsCollector,
    private val runtimeEngine: RuntimeEngine,
    private val runtimeDownloader: RuntimeDownloader,
    private val backupManager: BackupManager,
    private val vulcanConnect: VulcanConnect
) : ViewModel() {

    // ─── INSTALLED APPS ───────────────────────────────────────────────────────

    val appsWithStatus: StateFlow<List<AppRepository.AppWithStatus>> =
        appRepository.observeAppsWithStatus()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ─── STORE ────────────────────────────────────────────────────────────────

    val registry         = storeRepository.registry.stateIn(viewModelScope, SharingStarted.Lazily, null)
    val storeLoading     = storeRepository.isLoading.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)
    val storeError       = storeRepository.error.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    // ─── METRICS ─────────────────────────────────────────────────────────────

    val appMetrics    = metricsCollector.appMetrics
    val deviceMetrics = metricsCollector.deviceMetrics

    // ─── INSTALL PROGRESS ─────────────────────────────────────────────────────

    private val _installProgress = MutableStateFlow<InstallState>(InstallState.Idle)
    val installProgress: StateFlow<InstallState> = _installProgress.asStateFlow()

    sealed class InstallState {
        object Idle : InstallState()
        data class Installing(val appId: String, val step: String, val progress: Float = 0f) : InstallState()
        data class Success(val appId: String) : InstallState()
        data class Error(val appId: String, val message: String) : InstallState()
    }

    // ─── SETUP WIZARD ─────────────────────────────────────────────────────────

    val isFirstRun: Boolean get() = appRepository.isFirstRun()
    val permissionTier: PermissionTier get() = appRepository.getPermissionTier()
    val launchMode: LaunchMode get() = appRepository.getLaunchMode()

    // ─── INIT ─────────────────────────────────────────────────────────────────

    init {
        viewModelScope.launch { storeRepository.loadRegistry() }
    }

    // ─── ACTIONS ──────────────────────────────────────────────────────────────

    fun startApp(appId: String) {
        VulcanCoreService.startApp(appId, context)
    }

    fun stopApp(appId: String) {
        VulcanCoreService.stopApp(appId, context)
    }

    fun restartApp(appId: String) {
        viewModelScope.launch {
            VulcanCoreService.stopApp(appId, context)
            kotlinx.coroutines.delay(2_000)
            VulcanCoreService.startApp(appId, context)
        }
    }

    fun installApp(manifest: AppManifest) {
        viewModelScope.launch {
            _installProgress.emit(InstallState.Installing(manifest.id, "Preparing...", 0f))
            try {
                // 1. Download runtime if needed
                val runtime = com.vulcan.app.runtime.RuntimeCatalog.findById(manifest.runtime.engine)
                if (runtime != null) {
                    runtimeDownloader.downloadRuntime(runtime, manifest.runtime.version) { step, progress ->
                        viewModelScope.launch {
                            _installProgress.emit(InstallState.Installing(manifest.id, step, progress * 0.4f))
                        }
                    }
                }

                // 2. Install the app
                runtimeEngine.install(manifest) { step ->
                    viewModelScope.launch {
                        _installProgress.emit(InstallState.Installing(manifest.id, step, 0.7f))
                    }
                }

                // 3. Assign port
                val port = com.vulcan.app.runtime.PortManager(context).assignPort(manifest.id)

                // 4. Persist to DB
                appRepository.saveInstalledApp(manifest, port)

                _installProgress.emit(InstallState.Success(manifest.id))
                VulcanLogger.i("Install complete: ${manifest.id}")

            } catch (e: Exception) {
                _installProgress.emit(InstallState.Error(manifest.id, e.message ?: "Unknown error"))
                VulcanLogger.e("Install failed: ${manifest.id} — ${e.message}")
            }
        }
    }

    fun uninstallApp(appId: String) {
        viewModelScope.launch { appRepository.uninstallApp(appId) }
    }

    fun refreshStore() {
        viewModelScope.launch { storeRepository.loadRegistry(forceRefresh = true) }
    }

    fun backupApp(appId: String) {
        viewModelScope.launch {
            try {
                val file = backupManager.backupApp(appId, manual = true)
                VulcanLogger.i("Manual backup created: ${file.name}")
            } catch (e: Exception) {
                VulcanLogger.e("Backup failed: ${e.message}")
            }
        }
    }

    fun setAutoStart(appId: String, enabled: Boolean) {
        viewModelScope.launch { appRepository.setAutoStart(appId, enabled) }
    }

    fun setPermissionTier(tier: PermissionTier) = appRepository.setPermissionTier(tier)
    fun setLaunchMode(mode: LaunchMode) = appRepository.setLaunchMode(mode)
    fun completeSetup() = appRepository.completeFirstRun()

    fun dismissInstallProgress() {
        viewModelScope.launch { _installProgress.emit(InstallState.Idle) }
    }

    // ─── CLOUDFLARE TUNNEL ────────────────────────────────────────────────────

    private val _tunnelUrl = MutableStateFlow<String?>(null)
    val tunnelUrl: StateFlow<String?> = _tunnelUrl.asStateFlow()

    fun startCloudflareTunnel(token: String) {
        vulcanConnect.startCloudflareTunnel(token,
            onReady  = { url -> viewModelScope.launch { _tunnelUrl.emit(url) } },
            onError  = { err -> VulcanLogger.e("Tunnel error: $err") }
        )
    }

    fun stopCloudflareTunnel() {
        vulcanConnect.stopCloudflareTunnel()
        viewModelScope.launch { _tunnelUrl.emit(null) }
    }
}
