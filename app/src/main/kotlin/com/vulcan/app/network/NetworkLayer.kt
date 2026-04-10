package com.vulcan.app.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.vulcan.app.util.VulcanLogger
import java.util.concurrent.ConcurrentHashMap

// ─────────────────────────────────────────────────────────────────────────────
// mDNS ADVERTISER — Discover Vulcan on your LAN (no IP needed)
//
// Uses Android's built-in NsdManager — zero extra libraries.
// Users access Vulcan at http://vulcan.local:7777 from any device on LAN.
// Each running app also gets its own mDNS entry.
// ─────────────────────────────────────────────────────────────────────────────

class MDNSAdvertiser(private val context: Context) {

    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    private val registeredServices = ConcurrentHashMap<String, NsdManager.RegistrationListener>()

    // Advertise the main Vulcan dashboard
    fun advertiseDashboard(port: Int) {
        advertise("vulcan-dashboard", "_http._tcp", "vulcan", port)
    }

    // Advertise an individual app
    fun advertise(appId: String, port: Int) {
        advertise("vulcan-app-$appId", "_http._tcp", appId, port)
    }

    // Stop advertising an app (when it stops)
    fun stopApp(appId: String) {
        val key = "vulcan-app-$appId"
        registeredServices.remove(key)?.let { listener ->
            try { nsdManager.unregisterService(listener) }
            catch (e: Exception) { VulcanLogger.d("mDNS: unregister error for $appId: ${e.message}") }
        }
    }

    fun stopAll() {
        registeredServices.values.forEach { listener ->
            try { nsdManager.unregisterService(listener) }
            catch (e: Exception) { /* ignore */ }
        }
        registeredServices.clear()
    }

    private fun advertise(serviceKey: String, serviceType: String, name: String, port: Int) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = name
            this.serviceType = serviceType
            this.port = port
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                VulcanLogger.i("mDNS: ${info.serviceName}.local:$port advertised")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                VulcanLogger.w("mDNS: registration failed for $name (code $code)")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                VulcanLogger.d("mDNS: ${info.serviceName} unregistered")
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {
                VulcanLogger.w("mDNS: unregistration failed for $name (code $code)")
            }
        }

        registeredServices[serviceKey] = listener
        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            VulcanLogger.e("mDNS: failed to register $name: ${e.message}")
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// VULCAN CONNECT — External access via Cloudflare Tunnel, Tailscale, WireGuard
// ─────────────────────────────────────────────────────────────────────────────

class VulcanConnect(private val context: Context) {

    private var cloudflaredProcess: Process? = null
    private var wireguardProcess: Process? = null

    // ─── CLOUDFLARE TUNNEL ────────────────────────────────────────────────────

    fun startCloudflareTunnel(token: String, onReady: (String) -> Unit, onError: (String) -> Unit) {
        stopCloudflareTunnel()

        val cloudflaredBin = extractCloudflaredBinary()
        if (cloudflaredBin == null) {
            onError("cloudflared binary not found")
            return
        }

        val cmd = listOf(
            cloudflaredBin.canonicalPath,
            "tunnel",
            "--no-autoupdate",
            "run",
            "--token", token
        )

        try {
            cloudflaredProcess = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()

            // Parse output for tunnel URL
            Thread {
                cloudflaredProcess!!.inputStream.bufferedReader().forEachLine { line ->
                    VulcanLogger.d("cloudflared: $line")
                    if (line.contains("https://") && line.contains(".trycloudflare.com")) {
                        val url = Regex("https://[a-z0-9-]+\\.trycloudflare\\.com").find(line)?.value
                        if (url != null) onReady(url)
                    }
                }
            }.apply { isDaemon = true }.start()

            VulcanLogger.i("Cloudflare tunnel started")
        } catch (e: Exception) {
            onError("Failed to start cloudflared: ${e.message}")
        }
    }

    fun stopCloudflareTunnel() {
        cloudflaredProcess?.destroy()
        cloudflaredProcess = null
        VulcanLogger.i("Cloudflare tunnel stopped")
    }

    fun isCloudflareTunnelRunning(): Boolean =
        cloudflaredProcess?.isAlive == true

    // ─── WIREGUARD ────────────────────────────────────────────────────────────

    fun startWireGuard(config: String, onError: (String) -> Unit) {
        val configFile = com.vulcan.app.storage.StorageManager.wireguardConfigFile(context)
        configFile.writeText(config)

        val wgBin = extractWireGuardBinary()
        if (wgBin == null) {
            onError("wireguard-go binary not found")
            return
        }

        try {
            wireguardProcess = ProcessBuilder(wgBin.canonicalPath, "utun")
                .redirectErrorStream(true)
                .start()

            VulcanLogger.i("WireGuard started")
        } catch (e: Exception) {
            onError("Failed to start WireGuard: ${e.message}")
        }
    }

    fun stopWireGuard() {
        wireguardProcess?.destroy()
        wireguardProcess = null
    }

    fun isWireGuardRunning(): Boolean = wireguardProcess?.isAlive == true

    // ─── WIREGUARD CONFIG GENERATOR ───────────────────────────────────────────

    fun generateWireGuardConfig(
        clientPrivKey: String,
        serverPubKey: String,
        clientIP: String,
        serverIP: String,
        port: Int = 51820
    ): String = """
[Interface]
PrivateKey = $clientPrivKey
Address = $clientIP/24
DNS = 1.1.1.1

[Peer]
PublicKey = $serverPubKey
Endpoint = $serverIP:$port
AllowedIPs = 0.0.0.0/0, ::/0
PersistentKeepalive = 25
""".trimIndent()

    // ─── BINARY EXTRACTION ────────────────────────────────────────────────────

    private fun extractCloudflaredBinary(): java.io.File? {
        val dest = java.io.File(
            com.vulcan.app.storage.StorageManager.daemonDir(context),
            "cloudflared"
        )
        if (!dest.exists()) {
            return try {
                context.assets.open(RuntimeCatalog.CLOUDFLARED_ASSET_NAME)
                    .use { it.copyTo(dest.outputStream()) }
                dest.setExecutable(true)
                dest
            } catch (e: Exception) {
                VulcanLogger.e("Failed to extract cloudflared: ${e.message}")
                null
            }
        }
        return dest
    }

    private fun extractWireGuardBinary(): java.io.File? {
        val dest = java.io.File(
            com.vulcan.app.storage.StorageManager.daemonDir(context),
            "wireguard-go"
        )
        if (!dest.exists()) {
            return try {
                context.assets.open(RuntimeCatalog.WIREGUARD_ASSET_NAME)
                    .use { it.copyTo(dest.outputStream()) }
                dest.setExecutable(true)
                dest
            } catch (e: Exception) {
                VulcanLogger.e("Failed to extract wireguard-go: ${e.message}")
                null
            }
        }
        return dest
    }

    fun stopAll() {
        stopCloudflareTunnel()
        stopWireGuard()
    }
}
