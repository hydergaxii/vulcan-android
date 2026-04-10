package com.vulcan.app.storage

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * STORAGE MANAGER — Single source of truth for every file path in Vulcan.
 *
 * LAW 1: TRANSPARENCY — Everything lives in /sdcard/Vulcan/
 * User can open any file manager and see every log, config, database.
 * Nothing hidden in /data/data/. Nothing opaque. Total ownership.
 */
object StorageManager {

    // ─── ROOT ────────────────────────────────────────────────────────────────
    val DEFAULT_ROOT: File = File(
        Environment.getExternalStorageDirectory(),
        "Vulcan"
    )

    fun getRoot(context: Context): File {
        val prefs = context.getSharedPreferences("vulcan_storage", Context.MODE_PRIVATE)
        val custom = prefs.getString("custom_root", null)
        return if (custom != null) File(custom) else DEFAULT_ROOT
    }

    // Real path (resolves /sdcard symlink → /storage/emulated/0/)
    // Critical for ProcessBuilder — Android FUSE doesn't expose /sdcard to child processes
    fun getRealRoot(context: Context): File {
        return getRoot(context).canonicalFile
    }

    fun setCustomRoot(context: Context, path: String) {
        context.getSharedPreferences("vulcan_storage", Context.MODE_PRIVATE)
            .edit().putString("custom_root", path).apply()
    }

    // ─── APP PATHS ───────────────────────────────────────────────────────────
    fun appsDir(ctx: Context)                       = File(getRoot(ctx), "apps")
    fun appDir(ctx: Context, id: String)            = File(appsDir(ctx), id)
    fun appSourceDir(ctx: Context, id: String)      = File(appDir(ctx, id), "source")
    fun appDataDir(ctx: Context, id: String)        = File(appDir(ctx, id), "data")
    fun appLogsDir(ctx: Context, id: String)        = File(appDir(ctx, id), "logs")
    fun appBackupsDir(ctx: Context, id: String)     = File(appDir(ctx, id), "backups")
    fun appEnvFile(ctx: Context, id: String)        = File(appDir(ctx, id), ".env")
    fun appManifestFile(ctx: Context, id: String)   = File(appDir(ctx, id), "app.json")
    fun appMetaFile(ctx: Context, id: String)       = File(appDir(ctx, id), "vulcan.meta.json")

    // Real paths for process environments (resolves symlinks)
    fun appRealSourceDir(ctx: Context, id: String)  = appSourceDir(ctx, id).canonicalFile
    fun appRealDataDir(ctx: Context, id: String)    = appDataDir(ctx, id).canonicalFile

    // ─── RUNTIME PATHS ───────────────────────────────────────────────────────
    fun runtimesDir(ctx: Context)                   = File(getRoot(ctx), "runtimes")
    fun nativeRuntimesDir(ctx: Context)             = File(runtimesDir(ctx), "native")
    fun nativeRuntimeDir(ctx: Context, engine: String, version: String) =
        File(nativeRuntimesDir(ctx), "$engine/$version")
    fun nativeRuntimeBin(ctx: Context, engine: String, version: String) =
        File(nativeRuntimeDir(ctx, engine, version), "bin")

    fun prootDir(ctx: Context)                      = File(runtimesDir(ctx), "proot")
    fun prootBinary(ctx: Context)                   = File(prootDir(ctx), "proot")
    fun distrosDir(ctx: Context)                    = File(prootDir(ctx), "distros")
    fun distroDir(ctx: Context, distroId: String)   = File(distrosDir(ctx), distroId)

    // ─── VULCAN SYSTEM PATHS ─────────────────────────────────────────────────
    fun iconsDir(ctx: Context)                      = File(getRoot(ctx), "icons")
    fun storeDir(ctx: Context)                      = File(getRoot(ctx), "store")
    fun storeManifestsDir(ctx: Context)             = File(storeDir(ctx), "manifests")
    fun storeRegistryFile(ctx: Context)             = File(storeDir(ctx), "registry.json")
    fun daemonDir(ctx: Context)                     = File(getRoot(ctx), "daemon")
    fun daemonDexFile(ctx: Context)                 = File(daemonDir(ctx), "vulcan-daemon.dex")
    fun daemonPidFile(ctx: Context)                 = File(daemonDir(ctx), "daemon.pid")
    fun wireguardConfigFile(ctx: Context)           = File(daemonDir(ctx), "wg0.conf")
    fun backupsDir(ctx: Context)                    = File(getRoot(ctx), "backups")
    fun tempDir(ctx: Context)                       = File(getRoot(ctx), "temp")
    fun downloadsDir(ctx: Context)                  = File(tempDir(ctx), "downloads")
    fun logsDir(ctx: Context)                       = File(getRoot(ctx), "logs")
    fun vaultDir(ctx: Context)                      = File(getRoot(ctx), "vault")
    fun vaultFile(ctx: Context)                     = File(vaultDir(ctx), "secrets.enc")
    fun proxyDir(ctx: Context)                      = File(getRoot(ctx), "proxy")
    fun proxyRoutesFile(ctx: Context)               = File(proxyDir(ctx), "routes.json")
    fun metricsDir(ctx: Context)                    = File(getRoot(ctx), "metrics")
    fun globalConfigFile(ctx: Context)              = File(getRoot(ctx), "vulcan.config.json")

    // ─── INITIALIZATION ───────────────────────────────────────────────────────
    fun initialize(ctx: Context) {
        val root = getRoot(ctx)
        val dirsToCreate = listOf(
            root,
            appsDir(ctx),
            runtimesDir(ctx),
            nativeRuntimesDir(ctx),
            prootDir(ctx),
            distrosDir(ctx),
            iconsDir(ctx),
            storeDir(ctx),
            storeManifestsDir(ctx),
            daemonDir(ctx),
            backupsDir(ctx),
            tempDir(ctx),
            downloadsDir(ctx),
            logsDir(ctx),
            vaultDir(ctx),
            proxyDir(ctx),
            metricsDir(ctx)
        )
        dirsToCreate.forEach { it.mkdirs() }

        // Prevent Android gallery from indexing our app data
        File(root, ".nomedia").also { if (!it.exists()) it.createNewFile() }

        // Write global config template if first run
        val config = globalConfigFile(ctx)
        if (!config.exists()) config.writeText(defaultConfigJson())
    }

    // ─── STORAGE STATS ───────────────────────────────────────────────────────
    fun getVulcanUsageBytes(ctx: Context): Long = getRoot(ctx).walkTopDown()
        .filter { it.isFile }.sumOf { it.length() }

    fun getAppSizeBytes(ctx: Context, appId: String): Long = appDir(ctx, appId)
        .walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun getAvailableStorageBytes(): Long = Environment.getExternalStorageDirectory()
        .let { if (it.exists()) it.freeSpace else 0L }

    // ─── ENV FILE HELPERS ─────────────────────────────────────────────────────
    fun readEnvFile(ctx: Context, appId: String): Map<String, String> {
        val file = appEnvFile(ctx, appId)
        if (!file.exists()) return emptyMap()
        return file.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") && it.contains("=") }
            .associate { line ->
                val idx = line.indexOf('=')
                line.substring(0, idx).trim() to line.substring(idx + 1).trim()
                    .removeSurrounding("\"").removeSurrounding("'")
            }
    }

    fun writeEnvFile(ctx: Context, appId: String, env: Map<String, String>) {
        val file = appEnvFile(ctx, appId)
        file.parentFile?.mkdirs()
        val content = buildString {
            appendLine("# Vulcan — Environment config for $appId")
            appendLine("# Edit freely. Sensitive values use the Vulcan Vault.")
            appendLine()
            env.forEach { (k, v) -> appendLine("$k=$v") }
        }
        file.writeText(content)
    }

    // ─── DEFAULT CONFIG ───────────────────────────────────────────────────────
    private fun defaultConfigJson() = """
{
  "vulcanVersion": "2.0.0",
  "createdAt": "${System.currentTimeMillis()}",
  "launchMode": "SLOT",
  "permissionTier": "NORMAL",
  "theme": "SYSTEM",
  "language": "en",
  "autoBackup": true,
  "autoBackupIntervalHours": 24,
  "developerMode": false,
  "registryUrl": "https://store.vulcan.app/registry.json"
}
""".trimIndent()
}
