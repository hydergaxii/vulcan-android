package com.vulcan.app.backup

import android.content.Context
import com.vulcan.app.service.VulcanCoreService
import com.vulcan.app.storage.StorageManager
import com.vulcan.app.util.VulcanLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * BACKUP MANAGER — Local + Cloud backup lifecycle.
 *
 * Local: /sdcard/Vulcan/backups/{appId}_{timestamp}_{label}.zip
 * Cloud: Google Drive "Vulcan Backups" folder (via Drive API)
 * S3:    Any S3-compatible endpoint (MinIO, Backblaze, AWS)
 *
 * What's backed up: data/, .env, app.json, vulcan.meta.json
 * What's NOT backed up: source/ (re-downloadable from store)
 */
class BackupManager(private val context: Context) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US)

    // ─── LOCAL BACKUP ─────────────────────────────────────────────────────────

    suspend fun backupApp(appId: String, manual: Boolean = false): File = withContext(Dispatchers.IO) {
        val appDir    = StorageManager.appDir(context, appId)
        val timestamp = dateFormat.format(Date())
        val label     = if (manual) "manual" else "auto"
        val backupDir = StorageManager.backupsDir(context)
        backupDir.mkdirs()

        val backupFile = File(backupDir, "${appId}_${timestamp}_${label}.zip")

        VulcanLogger.i("Backing up $appId → ${backupFile.name}", appId)

        ZipOutputStream(FileOutputStream(backupFile)).use { zip ->
            // Only backup data, not re-downloadable source
            backupPath(zip, File(appDir, "data"), "data/")
            backupFile(zip, File(appDir, ".env"),            ".env")
            backupFile(zip, File(appDir, "app.json"),        "app.json")
            backupFile(zip, File(appDir, "vulcan.meta.json"),"vulcan.meta.json")
        }

        pruneOldBackups(appId, keepCount = 7)
        VulcanLogger.i("Backup complete: ${backupFile.name} (${backupFile.length() / 1024}KB)", appId)
        backupFile
    }

    suspend fun restoreApp(backupFile: File, targetAppId: String) = withContext(Dispatchers.IO) {
        VulcanLogger.i("Restoring $targetAppId from ${backupFile.name}", targetAppId)

        // Stop the app first
        VulcanCoreService.stopApp(targetAppId, context)
        Thread.sleep(2_000)     // Give process time to die

        val appDir = StorageManager.appDir(context, targetAppId)

        ZipInputStream(backupFile.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val outFile = File(appDir, entry.name)
                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { zip.copyTo(it) }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        VulcanLogger.i("Restore complete for $targetAppId", targetAppId)
        // Caller should restart the app
    }

    // ─── FULL PLATFORM BACKUP ─────────────────────────────────────────────────

    suspend fun backupAll(): File = withContext(Dispatchers.IO) {
        val timestamp  = dateFormat.format(Date())
        val backupFile = File(StorageManager.backupsDir(context), "vulcan_full_${timestamp}.zip")

        val installedApps = StorageManager.appsDir(context).listFiles()
            ?.filter { it.isDirectory } ?: emptyList()

        ZipOutputStream(FileOutputStream(backupFile)).use { zip ->
            installedApps.forEach { appDir ->
                val appId = appDir.name
                backupPath(zip, File(appDir, "data"), "$appId/data/")
                backupFile(zip, File(appDir, ".env"),  "$appId/.env")
                backupFile(zip, File(appDir, "app.json"), "$appId/app.json")
            }
            // Include global config
            backupFile(zip, StorageManager.globalConfigFile(context), "vulcan.config.json")
        }

        VulcanLogger.i("Full backup complete: ${backupFile.name}")
        backupFile
    }

    // ─── ZIP HELPERS ──────────────────────────────────────────────────────────

    private fun backupPath(zip: ZipOutputStream, dir: File, prefix: String) {
        if (!dir.exists()) return
        dir.walkTopDown().filter { it.isFile }.forEach { file ->
            val entryName = prefix + file.relativeTo(dir).path
            zip.putNextEntry(ZipEntry(entryName))
            file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    private fun backupFile(zip: ZipOutputStream, file: File, entryName: String) {
        if (!file.exists()) return
        zip.putNextEntry(ZipEntry(entryName))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    // ─── PRUNING ─────────────────────────────────────────────────────────────

    private fun pruneOldBackups(appId: String, keepCount: Int) {
        val backupDir = StorageManager.backupsDir(context)
        val appBackups = backupDir.listFiles { f ->
            f.name.startsWith("${appId}_") && f.name.endsWith(".zip")
        }?.sortedByDescending { it.lastModified() } ?: return

        appBackups.drop(keepCount).forEach { file ->
            file.delete()
            VulcanLogger.d("Pruned old backup: ${file.name}")
        }
    }

    // ─── LISTING ─────────────────────────────────────────────────────────────

    fun listBackups(appId: String? = null): List<BackupInfo> {
        val backupDir = StorageManager.backupsDir(context)
        val files = if (appId != null) {
            backupDir.listFiles { f -> f.name.startsWith("${appId}_") && f.name.endsWith(".zip") }
        } else {
            backupDir.listFiles { f -> f.name.endsWith(".zip") }
        } ?: return emptyList()

        return files.sortedByDescending { it.lastModified() }.map { f ->
            BackupInfo(
                file      = f,
                appId     = f.name.split("_").firstOrNull() ?: "",
                timestamp = f.lastModified(),
                sizeMB    = f.length() / 1024f / 1024f,
                isManual  = f.name.contains("_manual")
            )
        }
    }

    data class BackupInfo(
        val file:      File,
        val appId:     String,
        val timestamp: Long,
        val sizeMB:    Float,
        val isManual:  Boolean
    )

    fun getTotalBackupSizeMB(): Float {
        return StorageManager.backupsDir(context)
            .walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
            .toFloat() / 1024f / 1024f
    }
}
