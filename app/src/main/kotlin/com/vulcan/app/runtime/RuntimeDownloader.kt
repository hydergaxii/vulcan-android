package com.vulcan.app.runtime

import android.content.Context
import com.vulcan.app.data.model.AppManifest
import com.vulcan.app.storage.StorageManager
import com.vulcan.app.util.VulcanLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.tukaani.xz.XZInputStream

class RuntimeDownloader(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)     // 10 min for large runtime downloads
        .build()

    // ─── DOWNLOAD NATIVE RUNTIME ─────────────────────────────────────────────

    suspend fun downloadRuntime(
        runtime: RuntimeCatalog.NativeRuntime,
        version: String,
        onProgress: (String, Float) -> Unit
    ) {
        val destDir = StorageManager.nativeRuntimeDir(context, runtime.id, version)
        if (destDir.exists() && File(destDir, "bin/${runtime.binaryName}").exists()) {
            VulcanLogger.i("Runtime ${runtime.id}@$version already exists, skipping download")
            onProgress("${runtime.displayName} $version already installed", 1f)
            return
        }
        destDir.mkdirs()

        val url = runtime.downloadUrlTemplate.replace("{version}", version)
        val tempFile = File(StorageManager.downloadsDir(context), "${runtime.id}-$version.tmp")

        onProgress("Downloading ${runtime.displayName} $version (${runtime.sizeEstimateMB}MB)...", 0f)
        downloadFile(url, tempFile, onProgress)

        onProgress("Extracting ${runtime.displayName}...", 0.9f)
        extractArchive(tempFile, destDir, runtime.extractPath.replace("{version}", version))

        tempFile.delete()
        onProgress("${runtime.displayName} $version ready ✓", 1f)
        VulcanLogger.i("Runtime installed: ${runtime.id} @ $version → ${destDir.path}")
    }

    // ─── DOWNLOAD PROOT DISTRO ───────────────────────────────────────────────

    suspend fun downloadDistro(
        distro: RuntimeCatalog.ProotDistro,
        onProgress: (String, Float) -> Unit
    ) {
        val destDir = StorageManager.distroDir(context, distro.id)
        if (destDir.exists() && File(destDir, "bin/sh").exists()) {
            onProgress("${distro.displayName} already installed", 1f)
            return
        }
        destDir.mkdirs()

        val tempFile = File(StorageManager.downloadsDir(context), "${distro.id}.tmp")

        onProgress("Downloading ${distro.displayName} (${distro.sizeEstimateMB}MB)...", 0f)
        downloadFile(distro.downloadUrl, tempFile, onProgress)

        onProgress("Extracting ${distro.displayName}...", 0.9f)
        extractArchive(tempFile, destDir, null)

        tempFile.delete()

        // Extract bundled PRoot binary if not already there
        extractProotBinary()

        onProgress("${distro.displayName} ready ✓", 1f)
        VulcanLogger.i("Distro installed: ${distro.id}")
    }

    // ─── DOWNLOAD APP SOURCE ─────────────────────────────────────────────────

    suspend fun downloadSource(
        manifest: AppManifest,
        destDir: File,
        onProgress: (String) -> Unit
    ) {
        when (manifest.source.type) {
            "git"     -> cloneGit(manifest, destDir, onProgress)
            "archive" -> downloadAndExtract(manifest, destDir, onProgress)
            "binary"  -> downloadBinary(manifest, destDir, onProgress)
            else      -> throw IllegalArgumentException("Unknown source type: ${manifest.source.type}")
        }
    }

    private fun cloneGit(manifest: AppManifest, destDir: File, onProgress: (String) -> Unit) {
        onProgress("Cloning ${manifest.source.repo}...")
        val cmd = listOf(
            "/system/bin/sh", "-c",
            "git clone --depth=1 --branch ${manifest.source.branch} ${manifest.source.url} ${destDir.canonicalPath}"
        )
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().forEachLine { onProgress(it) }
        val exit = process.waitFor()
        if (exit != 0) throw RuntimeException("git clone failed with exit $exit")
    }

    private fun downloadAndExtract(manifest: AppManifest, destDir: File, onProgress: (String) -> Unit) {
        val tempFile = File(StorageManager.downloadsDir(context), "${manifest.id}-source.tmp")
        onProgress("Downloading ${manifest.label} source...")
        downloadFile(manifest.source.url, tempFile) { msg, _ -> onProgress(msg) }

        if (manifest.source.sha256.isNotBlank()) {
            onProgress("Verifying checksum...")
            if (!verifySha256(tempFile, manifest.source.sha256)) {
                tempFile.delete()
                throw SecurityException("Checksum mismatch for ${manifest.id}! Aborting.")
            }
        }

        onProgress("Extracting...")
        extractArchive(tempFile, destDir, null)
        tempFile.delete()
    }

    private fun downloadBinary(manifest: AppManifest, destDir: File, onProgress: (String) -> Unit) {
        val binaryFile = File(destDir, manifest.install.startCommand.split(" ").first())
        onProgress("Downloading ${manifest.label} binary...")
        downloadFile(manifest.source.url, binaryFile) { msg, _ -> onProgress(msg) }
        binaryFile.setExecutable(true)
    }

    // ─── CORE HTTP DOWNLOAD ───────────────────────────────────────────────────

    private fun downloadFile(url: String, dest: File, onProgress: (String, Float) -> Unit) {
        dest.parentFile?.mkdirs()
        val request = Request.Builder().url(url).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw RuntimeException("Download failed: HTTP ${response.code} for $url")

            val contentLength = response.body?.contentLength() ?: -1L
            var downloaded = 0L

            FileOutputStream(dest).use { out ->
                response.body?.byteStream()?.use { input ->
                    val buffer = ByteArray(8 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        out.write(buffer, 0, read)
                        downloaded += read
                        if (contentLength > 0) {
                            val progress = downloaded.toFloat() / contentLength
                            val dlMB = downloaded / 1024 / 1024
                            val totalMB = contentLength / 1024 / 1024
                            onProgress("${dlMB}MB / ${totalMB}MB", progress)
                        }
                    }
                }
            }
        }
    }

    // ─── ARCHIVE EXTRACTION ───────────────────────────────────────────────────

    private fun extractArchive(archive: File, destDir: File, stripPrefix: String?) {
        val name = archive.name.lowercase()
        when {
            name.endsWith(".zip")    -> extractZip(archive, destDir, stripPrefix)
            name.endsWith(".tar.gz") || name.endsWith(".tgz") -> extractTarGz(archive, destDir, stripPrefix)
            name.endsWith(".tar.xz") -> extractTarXz(archive, destDir, stripPrefix)
            else                     -> extractTarGz(archive, destDir, stripPrefix)  // Default
        }
    }

    private fun extractZip(zip: File, destDir: File, stripPrefix: String?) {
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = if (stripPrefix != null) {
                    entry.name.removePrefix("$stripPrefix/").trimStart('/')
                } else entry.name

                if (name.isNotBlank() && !entry.isDirectory) {
                    val outFile = File(destDir, name)
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { out -> zis.copyTo(out) }
                    if (name.contains("/bin/") || name.startsWith("bin/") || !name.contains(".")) {
                        outFile.setExecutable(true)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun extractTarGz(tar: File, destDir: File, stripPrefix: String?) {
        TarArchiveInputStream(GzipCompressorInputStream(tar.inputStream().buffered())).use { tis ->
            var entry = tis.nextTarEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val name = if (stripPrefix != null) {
                        entry.name.removePrefix("$stripPrefix/").trimStart('/')
                    } else entry.name

                    if (name.isNotBlank()) {
                        val outFile = File(destDir, name)
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { tis.copyTo(it) }
                        if (entry.mode and 0b001001001 != 0) outFile.setExecutable(true)
                    }
                }
                entry = tis.nextTarEntry
            }
        }
    }

    private fun extractTarXz(tar: File, destDir: File, stripPrefix: String?) {
        TarArchiveInputStream(XZInputStream(tar.inputStream().buffered())).use { tis ->
            var entry = tis.nextTarEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val name = if (stripPrefix != null) {
                        entry.name.removePrefix("$stripPrefix/").trimStart('/')
                    } else entry.name

                    if (name.isNotBlank()) {
                        val outFile = File(destDir, name)
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { tis.copyTo(it) }
                        if (entry.mode and 0b001001001 != 0) outFile.setExecutable(true)
                    }
                }
                entry = tis.nextTarEntry
            }
        }
    }

    // ─── PROOT BINARY EXTRACTION ──────────────────────────────────────────────

    private fun extractProotBinary() {
        val prootDest = StorageManager.prootBinary(context)
        if (prootDest.exists()) return
        prootDest.parentFile?.mkdirs()

        context.assets.open(RuntimeCatalog.PROOT_ASSET_NAME).use { input ->
            FileOutputStream(prootDest).use { input.copyTo(it) }
        }
        prootDest.setExecutable(true)
        VulcanLogger.i("PRoot binary extracted → ${prootDest.path}")
    }

    // ─── CHECKSUM ────────────────────────────────────────────────────────────

    fun verifySha256(file: File, expected: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        val actual = digest.digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        return actual.equals(expected, ignoreCase = true)
    }
}
