package com.vulcan.app.runtime

import android.content.Context
import com.vulcan.app.data.model.AppProcess
import com.vulcan.app.data.model.InstalledApp
import com.vulcan.app.security.SecretsVault
import com.vulcan.app.storage.StorageManager
import com.vulcan.app.util.VulcanLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * RUNTIME ENGINE — The heart of process execution.
 *
 * Starts apps via native ProcessBuilder (fast) or PRoot (compatible).
 * Manages PATH injection, env merging, log piping, health checks.
 * This is what makes "vulcan install librechat" actually run LibreChat.
 */
class RuntimeEngine(private val context: Context) {

    private val vault = SecretsVault(context)
    private val network = NetworkUtils(context)

    // ─── START ────────────────────────────────────────────────────────────────

    suspend fun start(app: InstalledApp): AppProcess = withContext(Dispatchers.IO) {
        VulcanLogger.i("Starting ${app.id} via ${app.runtimeType}/${app.runtimeEngine}", app.id)

        val process = when (app.runtimeType) {
            "proot" -> startViaProot(app)
            else    -> startNative(app)
        }

        VulcanLogger.i("${app.id} launched with PID ${process.pid()}", app.id)
        process
    }

    // ─── NATIVE RUNTIME ───────────────────────────────────────────────────────

    private fun startNative(app: InstalledApp): AppProcess {
        val runtimeBin = StorageManager.nativeRuntimeBin(
            context, app.runtimeEngine, app.runtimeVersion
        ).canonicalPath

        val sourceDir = StorageManager.appRealSourceDir(context, app.id)
        val dataDir   = StorageManager.appRealDataDir(context, app.id)
        val logsDir   = StorageManager.appLogsDir(context, app.id)
        logsDir.mkdirs()
        dataDir.mkdirs()

        // Build the full command
        val cmd = buildNativeCommand(app, runtimeBin, sourceDir)

        val pb = ProcessBuilder(cmd)
            .directory(sourceDir)
            .redirectErrorStream(true)

        // Inject full environment
        pb.environment().apply {
            putAll(buildEnvironment(app, runtimeBin, sourceDir, dataDir))
        }

        val process = pb.start()

        // Pipe stdout/stderr to log file asynchronously
        pipeToLog(process, app.id, logsDir)

        return AppProcess(
            appId   = app.id,
            pid     = process.pid().toInt(),
            process = process
        )
    }

    private fun buildNativeCommand(app: InstalledApp, runtimeBin: String, sourceDir: File): List<String> {
        val parts = app.startCommand.split(" ").filter { it.isNotBlank() }

        return when (app.runtimeEngine) {
            "node"   -> listOf("$runtimeBin/node") + parts.drop(1).ifEmpty { parts }
            "bun"    -> listOf("$runtimeBin/bun") + parts.drop(1).ifEmpty { parts }
            "deno"   -> listOf("$runtimeBin/deno") + parts.drop(1).ifEmpty { parts }
            "python" -> listOf("$runtimeBin/python3") + parts.drop(1).ifEmpty { parts }
            "go"     -> listOf(File(sourceDir, parts[0]).canonicalPath) + parts.drop(1)
            "java"   -> listOf("$runtimeBin/java", "-jar") + parts.drop(1).ifEmpty { parts }
            "binary" -> listOf(File(sourceDir, parts[0]).canonicalPath) + parts.drop(1)
            else     -> listOf("/system/bin/sh", "-c", app.startCommand)
        }
    }

    private fun buildEnvironment(
        app: InstalledApp,
        runtimeBin: String,
        sourceDir: File,
        dataDir: File
    ): Map<String, String> {
        val plainEnv  = StorageManager.readEnvFile(context, app.id)
        val secretEnv = vault.getAppSecrets(app.id)
        val meshEnv   = buildMeshEnv(app.id)

        val systemPath = System.getenv("PATH") ?: "/system/bin:/system/xbin"

        return buildMap {
            // System passthrough
            put("HOME", sourceDir.canonicalPath)
            put("TMPDIR", StorageManager.tempDir(context).canonicalPath)
            put("ANDROID_DATA", "/data")
            put("ANDROID_ROOT", "/system")

            // Runtime binary path — CRITICAL for node/npm/etc to be found
            put("PATH", "$runtimeBin:$systemPath")
            put("LD_LIBRARY_PATH", "$runtimeBin/../lib:${System.getenv("LD_LIBRARY_PATH") ?: ""}")

            // Vulcan-specific
            put("VULCAN_APP_ID",   app.id)
            put("VULCAN_PORT",     app.port.toString())
            put("DATA_DIR",        dataDir.canonicalPath)
            put("NODE_PATH",       "$runtimeBin/../lib/node_modules")

            // App-declared plain env (non-sensitive)
            putAll(plainEnv)

            // Vault secrets override plain env (sensitive values)
            putAll(secretEnv)

            // Mesh env vars so apps can discover each other
            putAll(meshEnv)
        }
    }

    // ─── PROOT RUNTIME ────────────────────────────────────────────────────────

    private fun startViaProot(app: InstalledApp): AppProcess {
        val prootBin  = StorageManager.prootBinary(context).canonicalPath
        val distroDir = StorageManager.distroDir(context, app.prootDistro ?: "ubuntu-22.04")
        val sourceDir = StorageManager.appRealSourceDir(context, app.id)
        val dataDir   = StorageManager.appRealDataDir(context, app.id)
        val logsDir   = StorageManager.appLogsDir(context, app.id)
        logsDir.mkdirs()
        dataDir.mkdirs()

        // Inject internal mesh DNS into distro's /etc/hosts
        injectMeshHosts(distroDir)

        val plainEnv  = StorageManager.readEnvFile(context, app.id)
        val secretEnv = vault.getAppSecrets(app.id)

        // Build env string for PRoot exec
        val envPairs = buildProotEnv(app, sourceDir, dataDir, plainEnv + secretEnv)

        val prootCmd = buildList {
            add(prootBin)
            add("--rootfs=${distroDir.canonicalPath}")
            add("--bind=/dev")
            add("--bind=/proc")
            add("--bind=/sys")
            // Bind app source into the PRoot environment
            add("--bind=${sourceDir.canonicalPath}:/app")
            // Bind app data directory
            add("--bind=${dataDir.canonicalPath}:/data/app")
            // Bind temp dir
            add("--bind=${StorageManager.tempDir(context).canonicalPath}:/tmp")
            add("--kill-on-exit")
            add("--change-id=0:0")          // Run as root inside PRoot
            add("/bin/sh")
            add("-c")

            // Export env vars then run the start command
            val envExports = envPairs.entries.joinToString("; ") { (k, v) ->
                "export $k='${v.replace("'", "\\'")}'"
            }
            add("cd /app && $envExports && ${app.startCommand}")
        }

        val pb = ProcessBuilder(prootCmd)
            .directory(distroDir)
            .redirectErrorStream(true)

        // PRoot reads env from parent process too
        pb.environment().apply {
            put("PROOT_NO_SECCOMP", "1")    // Required for Android
            put("PROOT_TMP_DIR", StorageManager.tempDir(context).canonicalPath)
        }

        val process = pb.start()
        pipeToLog(process, app.id, logsDir)

        return AppProcess(appId = app.id, pid = process.pid().toInt(), process = process)
    }

    private fun buildProotEnv(
        app: InstalledApp,
        sourceDir: File,
        dataDir: File,
        userEnv: Map<String, String>
    ): Map<String, String> = buildMap {
        put("HOME", "/root")
        put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/app/node_modules/.bin")
        put("TERM", "xterm-256color")
        put("LANG", "en_US.UTF-8")
        put("VULCAN_APP_ID", app.id)
        put("VULCAN_PORT", app.port.toString())
        put("DATA_DIR", "/data/app")
        putAll(userEnv)
    }

    private fun injectMeshHosts(distroDir: File) {
        val hostsFile = File(distroDir, "etc/hosts")
        // This will be updated by InternalMesh when apps start
        if (!hostsFile.exists()) {
            hostsFile.parentFile?.mkdirs()
            hostsFile.writeText(
                "127.0.0.1 localhost\n" +
                "::1 localhost\n" +
                "# Vulcan Internal Mesh — auto-updated\n"
            )
        }
    }

    // ─── STOP ─────────────────────────────────────────────────────────────────

    fun stop(appProcess: AppProcess) {
        VulcanLogger.i("Stopping ${appProcess.appId} (PID ${appProcess.pid})")
        try {
            appProcess.process.destroy()
            // Give it 5 seconds for graceful shutdown before force-killing
            Thread.sleep(5_000)
            if (appProcess.process.isAlive) {
                appProcess.process.destroyForcibly()
                VulcanLogger.w("Force-killed ${appProcess.appId}", appProcess.appId)
            }
        } catch (e: Exception) {
            VulcanLogger.e("Error stopping ${appProcess.appId}: ${e.message}", appProcess.appId)
        }
    }

    // ─── HEALTH CHECK ─────────────────────────────────────────────────────────

    suspend fun waitForReady(app: InstalledApp, timeoutMs: Long = 60_000): Boolean {
        val startTime = System.currentTimeMillis()
        val healthPath = app.manifest.healthCheck?.path ?: "/"
        val expectedStatus = app.manifest.healthCheck?.expectedStatus ?: 200

        VulcanLogger.i("Waiting for ${app.id} to be ready on port ${app.port}...", app.id)

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (checkHealth(app.port, healthPath, expectedStatus)) {
                VulcanLogger.i("${app.id} is ready ✓", app.id)
                return true
            }
            delay(1_500)
        }

        VulcanLogger.w("${app.id} health check timed out after ${timeoutMs}ms", app.id)
        return false
    }

    private fun checkHealth(port: Int, path: String, expectedStatus: Int): Boolean {
        return try {
            val url = URL("http://127.0.0.1:$port$path")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 1_000
            conn.readTimeout    = 2_000
            conn.requestMethod  = "GET"
            val status = conn.responseCode
            conn.disconnect()
            status == expectedStatus || status in 200..399
        } catch (e: Exception) {
            false
        }
    }

    // ─── LOG PIPING ───────────────────────────────────────────────────────────

    private fun pipeToLog(process: Process, appId: String, logsDir: File) {
        val dateStr  = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        val logFile  = File(logsDir, "$dateStr.log")

        Thread {
            try {
                process.inputStream.bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val entry = "[${System.currentTimeMillis()}] $line\n"
                        logFile.appendText(entry)
                        VulcanLogger.d("[$appId] $line")
                    }
                }
            } catch (e: Exception) {
                VulcanLogger.e("Log pipe closed for $appId: ${e.message}", appId)
            }
        }.apply { isDaemon = true; name = "LogPipe-$appId" }.start()
    }

    // ─── INSTALL ──────────────────────────────────────────────────────────────

    suspend fun install(
        app: com.vulcan.app.data.model.AppManifest,
        onProgress: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val appDir    = StorageManager.appDir(context, app.id)
        val sourceDir = StorageManager.appSourceDir(context, app.id)
        sourceDir.mkdirs()

        onProgress("Downloading ${app.label}...")
        // Download source
        val downloader = RuntimeDownloader(context)
        downloader.downloadSource(app, sourceDir, onProgress)

        onProgress("Running setup commands...")
        runSetupCommands(app, sourceDir, onProgress)

        // Write default .env from manifest
        onProgress("Writing configuration...")
        val defaultEnv = app.env.associate { it.key to it.defaultValue }
        StorageManager.writeEnvFile(context, app.id, defaultEnv)

        // Write manifest copy
        val manifestJson = com.google.gson.Gson().toJson(app)
        StorageManager.appManifestFile(context, app.id).writeText(manifestJson)

        onProgress("${app.label} installed successfully!")
    }

    private suspend fun runSetupCommands(
        app: com.vulcan.app.data.model.AppManifest,
        sourceDir: File,
        onProgress: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val runtimeBin = StorageManager.nativeRuntimeBin(
            context, app.runtime.engine, app.runtime.version
        ).canonicalPath

        app.install.setup.forEach { cmd ->
            onProgress("$ $cmd")
            VulcanLogger.i("Setup: $cmd", app.id)

            val process = ProcessBuilder("/system/bin/sh", "-c", cmd)
                .directory(sourceDir)
                .redirectErrorStream(true)
                .apply {
                    environment()["PATH"] = "$runtimeBin:${System.getenv("PATH")}"
                    environment()["HOME"] = sourceDir.canonicalPath
                }
                .start()

            // Stream output
            process.inputStream.bufferedReader().forEachLine { line ->
                VulcanLogger.d("[${app.id}] setup: $line", app.id)
                onProgress(line)
            }

            val exit = process.waitFor()
            if (exit != 0) {
                throw RuntimeException("Setup command failed with exit code $exit: $cmd")
            }
        }
    }

    // ─── MESH ENV ────────────────────────────────────────────────────────────

    private fun buildMeshEnv(requestingAppId: String): Map<String, String> {
        // InternalMesh provides these — populated at runtime
        return InternalMesh.getMeshEnvVars(requestingAppId)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// NETWORK UTILS — LAN IP detection
// ─────────────────────────────────────────────────────────────────────────────

class NetworkUtils(private val context: Context) {
    fun getLanIP(): String? {
        return try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val ipInt = wifiManager.connectionInfo.ipAddress
            if (ipInt == 0) return null
            String.format(
                "%d.%d.%d.%d",
                ipInt and 0xff,
                ipInt shr 8 and 0xff,
                ipInt shr 16 and 0xff,
                ipInt shr 24 and 0xff
            )
        } catch (e: Exception) { null }
    }
}
