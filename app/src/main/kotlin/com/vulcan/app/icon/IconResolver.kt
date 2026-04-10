package com.vulcan.app.icon

import android.content.Context
import android.graphics.*
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.util.LruCache
import com.vulcan.app.data.model.AppManifest
import com.vulcan.app.storage.StorageManager
import com.vulcan.app.util.VulcanLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URL
import java.util.concurrent.TimeUnit

// ─────────────────────────────────────────────────────────────────────────────
// ICON RESOLVER — 7-tier icon resolution pipeline
//
// P1: Declared in manifest    (most trusted, developer-specified)
// P2: GitHub repo avatar      (fetched from GitHub org/repo)
// P3: Best favicon            (PWA icons, apple-touch-icon, OG image)
// P4: Clearbit Logo API       (logo.clearbit.com/domain.com)
// P5: DuckDuckGo favicon      (icons.duckduckgo.com)
// P6: DiceBear avatar         (deterministic, always works)
// P7: Bundled fallback        (our curated icons for popular apps)
// ─────────────────────────────────────────────────────────────────────────────

class IconResolver(private val context: Context) {

    private val cache     = IconCache(context)
    private val processor = IconProcessor(context)
    private val client    = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun resolveIcon(manifest: AppManifest): Bitmap {
        // Return cached if available
        cache.get(manifest.id)?.let { return it }

        val raw = tryInOrder(
            { fetchDeclaredIcon(manifest.icon?.url) },
            { GitHubIconFetcher(client).fetchRepoAvatar(manifest.source.repo) },
            { FaviconFetcher(client).fetchBestFavicon(manifest.website) },
            { ClearbitFetcher(client).fetch(extractDomain(manifest.website)) },
            { DDGFaviconFetcher(client).fetch(extractDomain(manifest.website)) },
            { DiceBearFetcher(client).generate(manifest.id, manifest.label) },
            { BundledIcons.getFor(manifest.id, context) }
        ) ?: BundledIcons.getGenericIcon(context)

        val processed = processor.process(raw)
        cache.put(manifest.id, processed)

        // Persist to disk for next launch
        val iconFile = File(StorageManager.iconsDir(context), "${manifest.id}.webp")
        iconFile.outputStream().use { processed.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 90, it) }

        return processed
    }

    private fun fetchDeclaredIcon(url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        return try {
            val request  = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            response.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        } catch (e: Exception) { null }
    }

    private fun extractDomain(website: String): String {
        return try { URL(website).host.removePrefix("www.") } catch (e: Exception) { website }
    }

    private inline fun tryInOrder(vararg fetchers: () -> Bitmap?): Bitmap? {
        for (fetcher in fetchers) {
            try {
                val result = fetcher()
                if (result != null) return result
            } catch (e: Exception) {
                VulcanLogger.d("IconResolver: fetcher failed — ${e.message}")
            }
        }
        return null
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ICON PROCESSOR — 5-step processing pipeline
// ─────────────────────────────────────────────────────────────────────────────

class IconProcessor(private val context: Context) {

    fun process(raw: Bitmap): Bitmap = raw
        .let { makeSquare(it) }
        .let { ensureMinSize(it, 512) }
        .let { roundCorners(it, 0.2275f) }       // Material adaptive icon radius
        .let { addSubtleShadow(it) }
        .let { resize(it, 192) }                  // Standard launcher icon size

    fun makeAdaptive(icon: Bitmap, brandColor: Int): AdaptiveIconDrawable? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val foreground = BitmapDrawable(context.resources, icon)
        val background = ColorDrawable(brandColor)
        return AdaptiveIconDrawable(background, foreground)
    }

    private fun makeSquare(bmp: Bitmap): Bitmap {
        val size = minOf(bmp.width, bmp.height)
        val x    = (bmp.width - size) / 2
        val y    = (bmp.height - size) / 2
        return Bitmap.createBitmap(bmp, x, y, size, size)
    }

    private fun ensureMinSize(bmp: Bitmap, minSize: Int): Bitmap {
        if (bmp.width >= minSize && bmp.height >= minSize) return bmp
        return Bitmap.createScaledBitmap(bmp, minSize, minSize, true)
    }

    private fun roundCorners(bmp: Bitmap, radiusFraction: Float): Bitmap {
        val output = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint  = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect   = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        val radius  = bmp.width * radiusFraction
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(bmp, 0f, 0f, paint)
        return output
    }

    private fun addSubtleShadow(bmp: Bitmap): Bitmap {
        val padded = Bitmap.createBitmap(bmp.width + 8, bmp.height + 8, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(padded)
        val paint  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            maskFilter = BlurMaskFilter(4f, BlurMaskFilter.Blur.NORMAL)
            color = 0x40000000
        }
        canvas.drawBitmap(bmp, 4f, 4f, paint)
        paint.maskFilter = null
        canvas.drawBitmap(bmp, 0f, 0f, Paint())
        return padded
    }

    private fun resize(bmp: Bitmap, size: Int): Bitmap =
        Bitmap.createScaledBitmap(bmp, size, size, true)
}

// ─────────────────────────────────────────────────────────────────────────────
// ICON CACHE — LRU (memory) + Disk cache
// ─────────────────────────────────────────────────────────────────────────────

class IconCache(private val context: Context) {

    private val memCache = object : LruCache<String, Bitmap>(20) {
        override fun sizeOf(key: String, value: Bitmap) = 1
    }

    fun get(appId: String): Bitmap? {
        memCache.get(appId)?.let { return it }
        val file = File(StorageManager.iconsDir(context), "$appId.webp")
        if (file.exists()) {
            val bmp = BitmapFactory.decodeFile(file.path) ?: return null
            memCache.put(appId, bmp)
            return bmp
        }
        return null
    }

    fun put(appId: String, bmp: Bitmap) {
        memCache.put(appId, bmp)
    }

    fun clear(appId: String) {
        memCache.remove(appId)
        File(StorageManager.iconsDir(context), "$appId.webp").delete()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// FETCHERS
// ─────────────────────────────────────────────────────────────────────────────

class GitHubIconFetcher(private val client: OkHttpClient) {
    fun fetchRepoAvatar(repo: String): Bitmap? {
        if (repo.isBlank() || !repo.contains("/")) return null
        val owner = repo.split("/").first()
        val url = "https://avatars.githubusercontent.com/$owner?size=192"
        return fetchBitmap(url)
    }
    private fun fetchBitmap(url: String): Bitmap? {
        val resp = client.newCall(Request.Builder().url(url).build()).execute()
        return resp.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }
}

class FaviconFetcher(private val client: OkHttpClient) {
    fun fetchBestFavicon(website: String): Bitmap? {
        val domain = try { URL(website).let { "${it.protocol}://${it.host}" } } catch (e: Exception) { return null }
        val candidates = listOf(
            "$domain/apple-touch-icon.png",
            "$domain/apple-touch-icon-precomposed.png",
            "$domain/favicon-192x192.png",
            "$domain/favicon-96x96.png",
            "$domain/favicon.png",
            "$domain/favicon.ico"
        )
        for (url in candidates) {
            try {
                val resp = client.newCall(Request.Builder().url(url).build()).execute()
                val bmp = resp.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                if (bmp != null && bmp.width >= 48) return bmp
            } catch (e: Exception) { continue }
        }
        return null
    }
}

class ClearbitFetcher(private val client: OkHttpClient) {
    fun fetch(domain: String): Bitmap? {
        if (domain.isBlank()) return null
        val url  = "https://logo.clearbit.com/$domain?size=192"
        val resp = client.newCall(Request.Builder().url(url).build()).execute()
        if (!resp.isSuccessful) return null
        return resp.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }
}

class DDGFaviconFetcher(private val client: OkHttpClient) {
    fun fetch(domain: String): Bitmap? {
        if (domain.isBlank()) return null
        val url  = "https://icons.duckduckgo.com/ip3/$domain.ico"
        val resp = client.newCall(Request.Builder().url(url).build()).execute()
        return resp.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }
}

class DiceBearFetcher(private val client: OkHttpClient) {
    // DiceBear generates a deterministic SVG avatar from a seed.
    // We use the "shapes" style — clean, geometric, works at any size.
    fun generate(appId: String, label: String): Bitmap? {
        val url = "https://api.dicebear.com/7.x/shapes/png?seed=$appId&size=192"
        val resp = client.newCall(Request.Builder().url(url).build()).execute()
        return resp.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }
}

object BundledIcons {
    // Map of known app IDs → bundled drawable resource names
    // These are high-quality curated icons for the most popular apps
    private val ICON_MAP = mapOf(
        "librechat"      to "icon_librechat",
        "n8n"            to "icon_n8n",
        "gitea"          to "icon_gitea",
        "vaultwarden"    to "icon_vaultwarden",
        "code-server"    to "icon_code_server",
        "immich"         to "icon_immich",
        "jellyfin"       to "icon_jellyfin",
        "nextcloud"      to "icon_nextcloud",
        "home-assistant" to "icon_home_assistant",
        "uptime-kuma"    to "icon_uptime_kuma"
    )

    fun getFor(appId: String, context: Context): Bitmap? {
        val resName = ICON_MAP[appId] ?: return null
        val resId   = context.resources.getIdentifier(resName, "drawable", context.packageName)
        if (resId == 0) return null
        return BitmapFactory.decodeResource(context.resources, resId)
    }

    fun getGenericIcon(context: Context): Bitmap {
        // Returns a forge-themed placeholder: orange circle with "V"
        val size = 192
        val bmp  = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = 0xFFFF6B35.toInt()  // Vulcan Orange
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)

        paint.color = Color.WHITE
        paint.textSize = size * 0.45f
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        val textY = size / 2f - (paint.descent() + paint.ascent()) / 2
        canvas.drawText("V", size / 2f, textY, paint)

        return bmp
    }
}
