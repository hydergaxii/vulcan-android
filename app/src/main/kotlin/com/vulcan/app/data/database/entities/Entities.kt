package com.vulcan.app.data.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.vulcan.app.data.model.InstalledApp
import com.vulcan.app.data.model.AppManifest
import com.google.gson.Gson

// ─────────────────────────────────────────────────────────────────────────────
// APP ENTITY — Persisted installed app record
// ─────────────────────────────────────────────────────────────────────────────

@Entity(tableName = "installed_apps")
data class AppEntity(
    @PrimaryKey val id: String,
    val label: String,
    val version: String,
    val port: Int,
    val runtimeType: String,        // "native" | "proot"
    val runtimeEngine: String,      // "node" | "python" | "go" | etc.
    val runtimeVersion: String,
    val prootDistro: String?,
    val startCommand: String,
    val installPath: String,
    val isAutoStart: Boolean,
    val slotIndex: Int,             // -1 if using shortcut mode
    val brandColor: Int,
    val installedAt: Long,
    val lastStartedAt: Long,
    val updateAvailable: Boolean,
    val latestVersion: String?,
    val manifestJson: String        // Full manifest serialized as JSON
) {
    fun toDomain(gson: Gson): InstalledApp {
        val manifest = gson.fromJson(manifestJson, AppManifest::class.java)
        return InstalledApp(
            id = id,
            label = label,
            version = version,
            port = port,
            runtimeType = runtimeType,
            runtimeEngine = runtimeEngine,
            runtimeVersion = runtimeVersion,
            prootDistro = prootDistro,
            startCommand = startCommand,
            installPath = installPath,
            isAutoStart = isAutoStart,
            slotIndex = slotIndex,
            brandColor = brandColor,
            installedAt = installedAt,
            lastStartedAt = lastStartedAt,
            updateAvailable = updateAvailable,
            latestVersion = latestVersion,
            manifest = manifest
        )
    }

    companion object {
        fun fromDomain(app: InstalledApp, gson: Gson): AppEntity = AppEntity(
            id = app.id,
            label = app.label,
            version = app.version,
            port = app.port,
            runtimeType = app.runtimeType,
            runtimeEngine = app.runtimeEngine,
            runtimeVersion = app.runtimeVersion,
            prootDistro = app.prootDistro,
            startCommand = app.startCommand,
            installPath = app.installPath,
            isAutoStart = app.isAutoStart,
            slotIndex = app.slotIndex,
            brandColor = app.brandColor,
            installedAt = app.installedAt,
            lastStartedAt = app.lastStartedAt,
            updateAvailable = app.updateAvailable,
            latestVersion = app.latestVersion,
            manifestJson = gson.toJson(app.manifest)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SLOT ENTITY — Home screen launcher slot assignments
// ─────────────────────────────────────────────────────────────────────────────

@Entity(tableName = "launcher_slots")
data class SlotEntity(
    @PrimaryKey val index: Int,     // 0-9
    val appId: String?,
    val appLabel: String?,
    val iconPath: String?,
    val isActive: Boolean
)

// ─────────────────────────────────────────────────────────────────────────────
// METRICS ENTRY — Time-series resource usage data (kept for 24h)
// ─────────────────────────────────────────────────────────────────────────────

@Entity(tableName = "metrics_history")
data class MetricsEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val appId: String,
    val timestamp: Long,
    val cpuPercent: Float,
    val ramMB: Float,
    val netRxBytes: Long,
    val netTxBytes: Long
)

// ─────────────────────────────────────────────────────────────────────────────
// AUDIT ENTRY — Immutable security event log
// ─────────────────────────────────────────────────────────────────────────────

@Entity(tableName = "audit_log")
data class AuditEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val event: String,
    val details: String,
    val severity: String    // "info" | "warn" | "critical"
)
