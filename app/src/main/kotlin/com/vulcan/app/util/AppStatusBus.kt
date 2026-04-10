package com.vulcan.app.util

import com.vulcan.app.data.model.AppStatus
import kotlinx.coroutines.flow.*

/**
 * APP STATUS BUS — Reactive state broadcast for app lifecycle.
 *
 * Every component observes this. VulcanCoreService emits here.
 * UI, watchdog, metrics — all subscribe.
 */
object AppStatusBus {

    private val _events = MutableSharedFlow<AppStatusEvent>(
        replay = 0,
        extraBufferCapacity = 64
    )

    private val _currentStatus = MutableStateFlow<Map<String, AppStatus>>(emptyMap())

    // Observe all events
    val events: SharedFlow<AppStatusEvent> = _events.asSharedFlow()

    // Observe current status map (always has latest state per app)
    val statusMap: StateFlow<Map<String, AppStatus>> = _currentStatus.asStateFlow()

    data class AppStatusEvent(
        val appId: String,
        val status: AppStatus,
        val message: String = "",
        val timestamp: Long = System.currentTimeMillis()
    )

    suspend fun emit(appId: String, status: AppStatus, message: String = "") {
        _currentStatus.update { current ->
            current.toMutableMap().also { it[appId] = status }
        }
        _events.emit(AppStatusEvent(appId, status, message))
        VulcanLogger.i("[$appId] → $status${if (message.isNotBlank()) ": $message" else ""}")
    }

    fun getStatus(appId: String): AppStatus =
        _currentStatus.value[appId] ?: AppStatus.STOPPED

    fun observeApp(appId: String): Flow<AppStatus> =
        statusMap.map { it[appId] ?: AppStatus.STOPPED }.distinctUntilChanged()

    fun clearApp(appId: String) {
        _currentStatus.update { current ->
            current.toMutableMap().also { it.remove(appId) }
        }
    }
}
