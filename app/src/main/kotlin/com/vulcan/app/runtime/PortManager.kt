package com.vulcan.app.runtime

import android.content.Context
import com.vulcan.app.util.VulcanLogger
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap

// ─────────────────────────────────────────────────────────────────────────────
// PORT MANAGER — Central port authority. No conflicts. Ever.
// ─────────────────────────────────────────────────────────────────────────────

class PortManager(private val context: Context) {

    private val RESERVED_PORTS = setOf(
        7777,               // Vulcan Web Dashboard
        8080,               // VulcanProxy
        53, 80, 443, 22, 21 // Standard reserved
    )

    private val DEFAULT_PORTS = mapOf(
        "librechat"         to 3080,
        "n8n"               to 5678,
        "uptime-kuma"       to 3001,
        "code-server"       to 8443,
        "gitea"             to 3000,
        "forgejo"           to 3002,
        "nextcloud"         to 8888,
        "jellyfin"          to 8096,
        "vaultwarden"       to 8000,
        "actual-budget"     to 5006,
        "freshrss"          to 8001,
        "home-assistant"    to 8123,
        "immich"            to 2283,
        "miniflux"          to 8002,
        "adguard-home"      to 3004,
        "open-webui"        to 3030,
        "anythingllm"       to 3001,
        "grafana"           to 3003,
        "prometheus"        to 9090,
        "mattermost"        to 8065,
        "ntfy"              to 8009,
        "minio"             to 9000,
        "filebrowser"       to 8010,
        "navidrome"         to 4533,
        "audiobookshelf"    to 13378
    )

    private val assignments = ConcurrentHashMap<String, Int>()

    fun assignPort(appId: String): Int {
        // Return existing assignment if already assigned
        assignments[appId]?.let { return it }

        // Try the preferred default port
        val preferred = DEFAULT_PORTS[appId]
        if (preferred != null && isPortFree(preferred)) {
            assignments[appId] = preferred
            return preferred
        }

        // Auto-assign from range 3000–9999, skipping reserved and taken
        val port = (3000..9999).firstOrNull { candidate ->
            candidate !in RESERVED_PORTS &&
            candidate !in assignments.values &&
            isPortFree(candidate)
        } ?: throw IllegalStateException("No free port available in range 3000–9999")

        assignments[appId] = port
        VulcanLogger.i("PortManager: assigned port $port to $appId")
        return port
    }

    fun releasePort(appId: String) {
        assignments.remove(appId)
        VulcanLogger.d("PortManager: released port for $appId")
    }

    fun getPort(appId: String): Int? = assignments[appId]

    private fun isPortFree(port: Int): Boolean {
        return try {
            ServerSocket(port).use { true }
        } catch (e: IOException) {
            false
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// INTERNAL MESH — Apps talk to each other via {appId}.vulcan.internal
// ─────────────────────────────────────────────────────────────────────────────

object InternalMesh {

    private val registry = ConcurrentHashMap<String, Int>()    // appId → port

    fun register(appId: String, port: Int) {
        registry[appId] = port
        VulcanLogger.d("InternalMesh: registered $appId → port $port")
    }

    fun unregister(appId: String) {
        registry.remove(appId)
    }

    fun generateHostsEntries(): String = buildString {
        appendLine("# Vulcan Internal Mesh — auto-generated, do not edit")
        registry.forEach { (appId, _) ->
            appendLine("127.0.0.1    $appId.vulcan.internal")
        }
    }

    /**
     * Called before starting any PRoot app.
     * Injects mesh DNS entries into the distro's /etc/hosts.
     */
    fun injectIntoProotDistro(distroPath: File) {
        val hostsFile = File(distroPath, "etc/hosts")
        val existing  = if (hostsFile.exists()) hostsFile.readText() else ""

        // Remove old Vulcan block
        val cleaned = existing.replace(
            Regex("# Vulcan Internal Mesh[\\s\\S]*?\\n(?=\\n|$)"), ""
        )

        val meshBlock = "\n${generateHostsEntries()}\n"
        hostsFile.writeText(cleaned + meshBlock)
    }

    /**
     * Returns env vars for native apps to discover their peers.
     * e.g. VULCAN_APP_N8N_URL=http://127.0.0.1:5678
     */
    fun getMeshEnvVars(requestingAppId: String): Map<String, String> =
        registry
            .filter { it.key != requestingAppId }
            .mapKeys { "VULCAN_APP_${it.key.uppercase().replace("-", "_")}_URL" }
            .mapValues { (_, port) -> "http://127.0.0.1:$port" }

    fun getAll(): Map<String, Int> = registry.toMap()
}
