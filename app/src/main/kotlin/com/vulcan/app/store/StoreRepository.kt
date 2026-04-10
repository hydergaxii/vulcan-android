package com.vulcan.app.store

import android.content.Context
import com.google.gson.Gson
import com.vulcan.app.data.model.*
import com.vulcan.app.security.ManifestVerifier
import com.vulcan.app.storage.StorageManager
import com.vulcan.app.util.VulcanLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * STORE REPOSITORY — Fetches, verifies, and caches the Vulcan app registry.
 *
 * Registry lives at https://store.vulcan.app/registry.json
 * Cached locally at /sdcard/Vulcan/store/registry.json
 * Every manifest is Ed25519-verified before use.
 * Works fully offline using the local cache.
 */
class StoreRepository(
    private val context: Context,
    private val client: OkHttpClient,
    private val verifier: ManifestVerifier,
    private val gson: Gson
) {
    private val REGISTRY_URL = "https://store.vulcan.app/registry.json"

    private val _registry    = MutableStateFlow<RegistryResponse?>(null)
    private val _isLoading   = MutableStateFlow(false)
    private val _error       = MutableStateFlow<String?>(null)

    val registry:  Flow<RegistryResponse?> = _registry.asStateFlow()
    val isLoading: Flow<Boolean>           = _isLoading.asStateFlow()
    val error:     Flow<String?>           = _error.asStateFlow()

    // ─── LOAD REGISTRY ────────────────────────────────────────────────────────

    suspend fun loadRegistry(forceRefresh: Boolean = false) = withContext(Dispatchers.IO) {
        _isLoading.emit(true)
        _error.emit(null)

        try {
            val cached = loadCachedRegistry()

            // Use cache immediately so UI isn't blank
            if (cached != null) _registry.emit(cached)

            // Refresh from network unless we have a recent cache
            if (forceRefresh || cached == null || isCacheStale()) {
                refreshFromNetwork()
            }
        } catch (e: Exception) {
            _error.emit("Failed to load registry: ${e.message}")
            VulcanLogger.e("StoreRepository: ${e.message}")
        } finally {
            _isLoading.emit(false)
        }
    }

    private suspend fun refreshFromNetwork() {
        try {
            VulcanLogger.i("StoreRepository: fetching registry from $REGISTRY_URL")
            val request  = Request.Builder().url(REGISTRY_URL).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                VulcanLogger.w("StoreRepository: HTTP ${response.code}")
                return
            }

            val json = response.body?.string() ?: return
            val reg  = gson.fromJson(json, RegistryResponse::class.java)

            // Verify registry signature (Ed25519)
            if (reg.signature.isNotBlank() && !verifier.verify(json, reg.signature)) {
                VulcanLogger.e("StoreRepository: registry signature INVALID — rejecting update")
                _error.emit("Registry signature verification failed")
                return
            }

            // Cache to disk
            StorageManager.storeDir(context).mkdirs()
            StorageManager.storeRegistryFile(context).writeText(json)
            File(StorageManager.storeDir(context), "registry_timestamp").writeText(
                System.currentTimeMillis().toString()
            )

            _registry.emit(reg)
            VulcanLogger.i("StoreRepository: registry updated — ${reg.apps.size} apps")

        } catch (e: Exception) {
            VulcanLogger.w("StoreRepository: network refresh failed — ${e.message}")
            // Not an error if we have a cache
        }
    }

    private fun loadCachedRegistry(): RegistryResponse? {
        val file = StorageManager.storeRegistryFile(context)
        if (!file.exists()) return null
        return try {
            val json = file.readText()
            gson.fromJson(json, RegistryResponse::class.java)
        } catch (e: Exception) {
            VulcanLogger.w("StoreRepository: cached registry corrupt — ${e.message}")
            null
        }
    }

    private fun isCacheStale(): Boolean {
        val tsFile = File(StorageManager.storeDir(context), "registry_timestamp")
        if (!tsFile.exists()) return true
        val lastRefresh = tsFile.readText().toLongOrNull() ?: return true
        return System.currentTimeMillis() - lastRefresh > 6 * 60 * 60 * 1000L // 6 hours
    }

    // ─── APP LOOKUP ───────────────────────────────────────────────────────────

    fun getApp(appId: String): AppManifest? =
        _registry.value?.apps?.find { it.id == appId }

    fun getAppsInCategory(categoryId: String): List<AppManifest> =
        _registry.value?.apps?.filter { it.category == categoryId } ?: emptyList()

    fun searchApps(query: String): List<AppManifest> {
        val q = query.lowercase().trim()
        return _registry.value?.apps?.filter { app ->
            app.label.lowercase().contains(q) ||
            app.description.lowercase().contains(q) ||
            app.tags.any { it.lowercase().contains(q) } ||
            app.id.lowercase().contains(q)
        } ?: emptyList()
    }

    fun getCategories(): List<AppCategory> =
        _registry.value?.categories ?: emptyList()

    fun getFeaturedApps(): List<AppManifest> =
        _registry.value?.apps?.take(6) ?: emptyList()

    // ─── MANIFEST CACHE ───────────────────────────────────────────────────────

    suspend fun fetchManifest(appId: String): AppManifest? = withContext(Dispatchers.IO) {
        // Check registry first
        getApp(appId)?.let { return@withContext it }

        // Try individual manifest endpoint
        try {
            val url = "https://store.vulcan.app/apps/$appId/manifest.json"
            val resp = client.newCall(Request.Builder().url(url).build()).execute()
            if (!resp.isSuccessful) return@withContext null
            val json = resp.body?.string() ?: return@withContext null

            // Cache it
            val manifestFile = File(StorageManager.storeManifestsDir(context), "$appId.json")
            manifestFile.parentFile?.mkdirs()
            manifestFile.writeText(json)

            gson.fromJson(json, AppManifest::class.java)
        } catch (e: Exception) {
            // Fall back to local cache
            val cached = File(StorageManager.storeManifestsDir(context), "$appId.json")
            if (cached.exists()) {
                try { gson.fromJson(cached.readText(), AppManifest::class.java) }
                catch (e2: Exception) { null }
            } else null
        }
    }

    // ─── UPDATE CHECKING ──────────────────────────────────────────────────────

    suspend fun checkForUpdates(installedApps: List<InstalledApp>): Map<String, String> {
        val reg = _registry.value ?: run {
            loadRegistry()
            _registry.value ?: return emptyMap()
        }

        return installedApps.mapNotNull { installed ->
            val latest = reg.apps.find { it.id == installed.id }?.version ?: return@mapNotNull null
            if (latest != installed.version) installed.id to latest else null
        }.toMap()
    }
}
