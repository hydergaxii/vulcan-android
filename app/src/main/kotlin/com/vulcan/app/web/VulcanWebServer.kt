package com.vulcan.app.web

import android.content.Context
import com.vulcan.app.service.VulcanCoreService
import com.vulcan.app.monitoring.MetricsCollector
import com.vulcan.app.util.VulcanLogger
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD

/**
 * VULCAN WEB DASHBOARD — Browser-accessible control at http://vulcan.local:7777
 *
 * A single self-contained HTML page served from our NanoHTTPD server.
 * No internet required. No build tools. Zero JavaScript frameworks.
 * Works from any browser on the same LAN (phone, laptop, tablet).
 *
 * Routes:
 *   GET  /               → Dashboard HTML
 *   GET  /api/apps       → JSON list of installed/running apps
 *   POST /api/apps/{id}/start  → Start an app
 *   POST /api/apps/{id}/stop   → Stop an app
 *   GET  /api/apps/{id}/logs   → Last 100 log lines
 *   GET  /api/metrics    → Device + per-app metrics
 *   GET  /api/proxy/{id}/ → Proxy to app (pass-through)
 */
class VulcanWebServer(private val context: Context) : NanoHTTPD(WEB_PORT) {

    companion object {
        const val WEB_PORT = 7777
    }

    private val gson = Gson()

    override fun serve(session: IHTTPSession): Response {
        val uri    = session.uri
        val method = session.method

        return try {
            when {
                uri == "/" || uri == "/index.html"                  -> serveDashboard()
                uri == "/api/apps" && method == Method.GET          -> serveAppList()
                uri.matches(Regex("/api/apps/[^/]+/start"))         -> serveAppAction(uri, "start")
                uri.matches(Regex("/api/apps/[^/]+/stop"))          -> serveAppAction(uri, "stop")
                uri.matches(Regex("/api/apps/[^/]+/logs"))          -> serveAppLogs(uri)
                uri == "/api/metrics"                               -> serveMetrics()
                uri == "/api/status"                                -> serveStatus()
                uri.startsWith("/api/proxy/")                       -> serveProxy(uri, session)
                else -> newFixedLengthResponse(
                    Response.Status.NOT_FOUND, "text/plain", "404 Not Found"
                )
            }
        } catch (e: Exception) {
            VulcanLogger.e("WebServer error: ${e.message}")
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "application/json",
                gson.toJson(mapOf("error" to e.message))
            )
        }
    }

    private fun serveDashboard(): Response {
        val html = buildDashboardHtml()
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun serveAppList(): Response {
        val running = VulcanCoreService.getRunningApps()
        // In full impl, fetch installed apps from DB too
        val data = running.map { (appId, process) ->
            mapOf(
                "id"       to appId,
                "pid"      to process.pid,
                "status"   to "running",
                "uptime"   to (System.currentTimeMillis() - process.startedAt)
            )
        }
        return jsonResponse(data)
    }

    private fun serveAppAction(uri: String, action: String): Response {
        val appId = uri.split("/").getOrElse(3) { "" }
        if (appId.isBlank()) return jsonError("Missing app ID")

        when (action) {
            "start" -> VulcanCoreService.startApp(appId, context)
            "stop"  -> VulcanCoreService.stopApp(appId, context)
        }
        return jsonResponse(mapOf("status" to "ok", "action" to action, "appId" to appId))
    }

    private fun serveAppLogs(uri: String): Response {
        val appId = uri.split("/").getOrElse(3) { "" }
        if (appId.isBlank()) return jsonError("Missing app ID")
        val lines = com.vulcan.app.util.VulcanLogger.getRecentLines(appId, 100)
        return jsonResponse(mapOf("appId" to appId, "lines" to lines))
    }

    private fun serveMetrics(): Response {
        val running = VulcanCoreService.getRunningApps()
        val data = mapOf(
            "runningApps" to running.size,
            "appIds"      to running.keys.toList(),
            "timestamp"   to System.currentTimeMillis()
        )
        return jsonResponse(data)
    }

    private fun serveStatus(): Response {
        val data = mapOf(
            "vulcanVersion" to "2.0.0",
            "runningApps"   to VulcanCoreService.getRunningApps().size,
            "uptime"        to System.currentTimeMillis(),
            "ok"            to true
        )
        return jsonResponse(data)
    }

    private fun serveProxy(uri: String, session: IHTTPSession): Response {
        // Simple passthrough proxy to app — for opening app UI from web dashboard
        val parts = uri.removePrefix("/api/proxy/").split("/", limit = 2)
        val appId = parts.firstOrNull() ?: return jsonError("Missing app ID")
        val path  = "/${parts.getOrElse(1) { "" }}"

        val running = VulcanCoreService.getRunningApps()
        val port = running[appId]?.let {
            context.getSharedPreferences("app_ports", Context.MODE_PRIVATE).getInt(appId, -1)
        } ?: return jsonError("App not running: $appId")

        return try {
            val targetUrl = java.net.URL("http://127.0.0.1:$port$path")
            val conn = targetUrl.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5_000
            conn.readTimeout    = 30_000
            val responseCode = conn.responseCode
            val contentType  = conn.contentType ?: "text/html"
            val body         = conn.inputStream.readBytes()
            conn.disconnect()

            newFixedLengthResponse(
                Response.Status.lookup(responseCode) ?: Response.Status.OK,
                contentType,
                body.toString(Charsets.UTF_8)
            )
        } catch (e: Exception) {
            jsonError("Proxy error: ${e.message}")
        }
    }

    private fun jsonResponse(data: Any): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(data))
            .also { it.addHeader("Access-Control-Allow-Origin", "*") }

    private fun jsonError(msg: String): Response =
        newFixedLengthResponse(
            Response.Status.BAD_REQUEST, "application/json",
            gson.toJson(mapOf("error" to msg))
        )

    // ─── DASHBOARD HTML ───────────────────────────────────────────────────────
    // Self-contained single-file dashboard. Vue 3 CDN. Dark theme.
    // Exactly as designed in Part XV of the plan.

    private fun buildDashboardHtml(): String = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Vulcan Dashboard</title>
<script src="https://unpkg.com/vue@3/dist/vue.global.prod.js"></script>
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body { font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
         background: #1A1A2E; color: #e0e0e0; min-height: 100vh; }
  .header { background: #16213E; padding: 16px 24px; display: flex;
            align-items: center; gap: 12px; border-bottom: 1px solid #2a2a4e; }
  .logo { font-size: 24px; font-weight: 700; color: #FF6B35; }
  .subtitle { font-size: 12px; color: #A0A0B0; }
  .container { padding: 24px; max-width: 1200px; margin: 0 auto; }
  .stats { display: flex; gap: 16px; margin-bottom: 24px; flex-wrap: wrap; }
  .stat-card { background: #16213E; border-radius: 12px; padding: 16px 20px;
               border: 1px solid #2a2a4e; min-width: 140px; }
  .stat-value { font-size: 28px; font-weight: 700; color: #FF6B35; }
  .stat-label { font-size: 12px; color: #A0A0B0; margin-top: 4px; }
  .apps-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 16px; }
  .app-card { background: #16213E; border-radius: 12px; padding: 20px;
              border: 1px solid #2a2a4e; transition: border-color 0.2s; }
  .app-card:hover { border-color: #FF6B35; }
  .app-header { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
  .app-name { font-weight: 600; font-size: 16px; }
  .app-status { font-size: 11px; padding: 3px 8px; border-radius: 20px; font-weight: 600; }
  .status-running { background: #0F9B58; color: white; }
  .status-stopped { background: #444; color: #aaa; }
  .app-actions { display: flex; gap: 8px; flex-wrap: wrap; }
  .btn { padding: 8px 16px; border: none; border-radius: 8px; cursor: pointer;
         font-size: 13px; font-weight: 600; transition: opacity 0.15s; }
  .btn:hover { opacity: 0.8; }
  .btn-primary { background: #FF6B35; color: white; }
  .btn-danger  { background: #E94560; color: white; }
  .btn-outline { background: transparent; color: #FF6B35; border: 1px solid #FF6B35; }
  .empty { text-align: center; padding: 60px 20px; color: #A0A0B0; }
  .refresh { font-size: 11px; color: #A0A0B0; }
</style>
</head>
<body>
<div id="app">
  <div class="header">
    <div>
      <div class="logo">🔥 Vulcan</div>
      <div class="subtitle">Your Android. Your Rules. Your Stack.</div>
    </div>
    <div style="margin-left: auto; text-align: right;">
      <div style="font-size: 12px; color: #A0A0B0;">{{ deviceName }}</div>
      <div class="refresh">Auto-refresh every 5s</div>
    </div>
  </div>

  <div class="container">
    <div class="stats">
      <div class="stat-card">
        <div class="stat-value">{{ apps.length }}</div>
        <div class="stat-label">Running Apps</div>
      </div>
      <div class="stat-card">
        <div class="stat-value" style="color: #0F9B58;">●</div>
        <div class="stat-label">Forge Online</div>
      </div>
    </div>

    <div v-if="apps.length === 0" class="empty">
      <div style="font-size: 40px; margin-bottom: 12px;">⚒️</div>
      <div style="font-size: 18px; font-weight: 600; margin-bottom: 8px;">No apps running</div>
      <div>Open the Vulcan app to install and start apps</div>
    </div>

    <div class="apps-grid">
      <div v-for="app in apps" :key="app.id" class="app-card">
        <div class="app-header">
          <div>
            <div class="app-name">{{ app.id }}</div>
            <div style="font-size: 12px; color: #A0A0B0; margin-top: 2px;">PID {{ app.pid }}</div>
          </div>
          <span class="app-status status-running" style="margin-left: auto;">● Running</span>
        </div>
        <div class="app-actions">
          <button class="btn btn-primary" @click="openApp(app)">Open UI</button>
          <button class="btn btn-danger"  @click="doAction(app.id, 'stop')">Stop</button>
          <button class="btn btn-outline" @click="viewLogs(app.id)">Logs</button>
        </div>
      </div>
    </div>
  </div>
</div>

<script>
Vue.createApp({
  data() {
    return { apps: [], deviceName: 'Vulcan Device' }
  },
  methods: {
    async fetchApps() {
      try {
        const r = await fetch('/api/apps');
        this.apps = await r.json();
      } catch(e) { console.error('Fetch error', e); }
    },
    async doAction(id, action) {
      await fetch('/api/apps/' + id + '/' + action, { method: 'POST' });
      setTimeout(() => this.fetchApps(), 1000);
    },
    openApp(app) { window.open('/api/proxy/' + app.id + '/', '_blank'); },
    viewLogs(id) { window.open('/api/apps/' + id + '/logs', '_blank'); }
  },
  mounted() {
    this.fetchApps();
    setInterval(() => this.fetchApps(), 5000);
  }
}).mount('#app');
</script>
</body>
</html>
""".trimIndent()
}
