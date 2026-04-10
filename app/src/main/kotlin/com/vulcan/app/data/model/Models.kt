package com.vulcan.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ─────────────────────────────────────────────────────────────────────────────
// APP MANIFEST — What the Vulcan Store knows about an app
// Parsed from JSON registry. This is the source of truth for installation.
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class AppManifest(
    val id: String,                         // e.g. "librechat"
    val label: String,                      // e.g. "LibreChat"
    val version: String,                    // e.g. "0.7.4"
    val description: String,
    val category: String,                   // e.g. "ai", "automation"
    val website: String,
    val source: AppSource,
    val runtime: RuntimeSpec,
    val install: InstallSpec,
    val ports: List<PortSpec>,
    val env: List<EnvSpec>,
    val secrets: List<SecretSpec> = emptyList(),
    val healthCheck: HealthCheckSpec? = null,
    val icon: IconSpec? = null,
    val tags: List<String> = emptyList(),
    val minRamMB: Int = 256,
    val minStorageMB: Int = 500,
    val nativeAddons: Boolean = false,      // If true, force PRoot
    val signature: String = "",             // Ed25519 signature of this manifest JSON
    @SerialName("updatedAt") val updatedAt: String = ""
)

@Serializable
data class AppSource(
    val type: String,           // "git" | "archive" | "binary"
    val url: String,
    val repo: String = "",      // "owner/repo" for GitHub
    val branch: String = "main",
    val sha256: String = ""     // Checksum for downloaded archives
)

@Serializable
data class RuntimeSpec(
    val type: String,           // "native" | "proot"
    val engine: String,         // "node" | "bun" | "python" | "go" | "java" | "php" | "binary"
    val version: String,
    val distro: String = "",    // PRoot distro: "ubuntu-22.04" | "debian-12" | "alpine-3.18"
    val arch: String = "arm64"
)

@Serializable
data class InstallSpec(
    val setup: List<String>,            // Shell commands to run on install
    val startCommand: String,           // Command to start the app
    val workingDir: String = "source",  // Relative to /sdcard/Vulcan/apps/{id}/
    val stopSignal: String = "SIGTERM"
)

@Serializable
data class PortSpec(
    val port: Int,
    val label: String,          // e.g. "Web UI"
    val protocol: String = "http"
)

@Serializable
data class EnvSpec(
    val key: String,
    val label: String,
    val defaultValue: String = "",
    val required: Boolean = false,
    val description: String = "",
    val isSecret: Boolean = false       // If true → goes to SecretsVault
)

@Serializable
data class SecretSpec(
    val key: String,
    val label: String,
    val description: String = "",
    val example: String = ""
)

@Serializable
data class HealthCheckSpec(
    val path: String = "/",             // HTTP path to check
    val expectedStatus: Int = 200,
    val timeoutMs: Long = 5_000,
    val intervalMs: Long = 30_000
)

@Serializable
data class IconSpec(
    val url: String = "",               // Direct icon URL
    val type: String = "auto"           // "auto" | "url" | "bundled"
)

// ─────────────────────────────────────────────────────────────────────────────
// INSTALLED APP — The runtime state of an installed app
// This is the live domain model (not the DB entity).
// ─────────────────────────────────────────────────────────────────────────────

data class InstalledApp(
    val id: String,
    val label: String,
    val version: String,
    val port: Int,
    val runtimeType: String,
    val runtimeEngine: String,
    val runtimeVersion: String,
    val prootDistro: String?,
    val startCommand: String,
    val installPath: String,
    val isAutoStart: Boolean,
    val slotIndex: Int,             // -1 if using shortcut mode
    val brandColor: Int,            // Extracted from icon
    val installedAt: Long,
    val lastStartedAt: Long,
    val updateAvailable: Boolean,
    val latestVersion: String?,
    val manifest: AppManifest       // The full manifest it was installed from
)

// ─────────────────────────────────────────────────────────────────────────────
// APP STATUS — Real-time lifecycle states
// ─────────────────────────────────────────────────────────────────────────────

enum class AppStatus {
    STOPPED,        // Not running
    STARTING,       // Process launched, waiting for health check
    RUNNING,        // Health check passed, serving traffic
    STOPPING,       // Graceful shutdown in progress
    ERROR,          // Crashed or failed to start
    UPDATING,       // Update in progress
    INSTALLING,     // First-time install in progress
    BACKING_UP      // Backup in progress
}

// ─────────────────────────────────────────────────────────────────────────────
// PERMISSION TIER — The three rings of Vulcan power
// ─────────────────────────────────────────────────────────────────────────────

enum class PermissionTier {
    NORMAL,     // Standard app permissions — 80% of features
    ADB,        // One-time ADB pairing — 95% of features (our privileged daemon)
    ROOT        // Full root access — 100% of features
}

// ─────────────────────────────────────────────────────────────────────────────
// LAUNCH MODE — How apps appear on the home screen
// ─────────────────────────────────────────────────────────────────────────────

enum class LaunchMode {
    SLOT,       // ActivityAlias — real launcher icon (max 10, daemon required for silent)
    SHORTCUT    // ShortcutManager — pinned shortcut (unlimited, works everywhere)
}

// ─────────────────────────────────────────────────────────────────────────────
// APP PROCESS — Running process handle
// ─────────────────────────────────────────────────────────────────────────────

data class AppProcess(
    val appId: String,
    val pid: Int,
    val process: Process,
    val startedAt: Long = System.currentTimeMillis()
)

// ─────────────────────────────────────────────────────────────────────────────
// BOOTSTRAP RESULT — What happens when we try to start the daemon
// ─────────────────────────────────────────────────────────────────────────────

sealed class BootstrapResult {
    data class Success(val tier: PermissionTier) : BootstrapResult()
    data class Failure(val reason: String) : BootstrapResult()
    object NormalTier : BootstrapResult()
}

// ─────────────────────────────────────────────────────────────────────────────
// REGISTRY RESPONSE — From the Vulcan Store registry endpoint
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class RegistryResponse(
    val schemaVersion: Int,
    val registryVersion: String,
    val updatedAt: String,
    val signature: String,
    val categories: List<AppCategory>,
    val apps: List<AppManifest>
)

@Serializable
data class AppCategory(
    val id: String,
    val label: String,
    val icon: String
)

// ─────────────────────────────────────────────────────────────────────────────
// METRICS SNAPSHOT — Real-time resource usage for one app
// ─────────────────────────────────────────────────────────────────────────────

data class AppMetrics(
    val appId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val cpuPercent: Float,
    val ramMB: Float,
    val netRxBytes: Long,
    val netTxBytes: Long,
    val uptimeMs: Long
)

data class DeviceMetrics(
    val timestamp: Long = System.currentTimeMillis(),
    val totalRamMB: Long,
    val availableRamMB: Long,
    val cpuPercent: Float,
    val storageTotalMB: Long,
    val storageUsedMB: Long,
    val batteryPercent: Int,
    val isCharging: Boolean
)

// ─────────────────────────────────────────────────────────────────────────────
// NETWORK CONFIG — VulcanConnect settings
// ─────────────────────────────────────────────────────────────────────────────

data class NetworkConfig(
    val cloudflareEnabled: Boolean = false,
    val cloudflareToken: String = "",
    val tailscaleEnabled: Boolean = false,
    val tailscaleAuthKey: String = "",
    val wireguardEnabled: Boolean = false,
    val wireguardConfig: String = "",
    val lanAccessEnabled: Boolean = true,
    val proxyPort: Int = 8080,
    val dashboardPort: Int = 7777
)
