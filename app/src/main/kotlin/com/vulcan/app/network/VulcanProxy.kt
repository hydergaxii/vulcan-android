package com.vulcan.app.network

import com.vulcan.app.data.model.InstalledApp
import com.vulcan.app.util.VulcanLogger
import java.io.*
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * VULCAN PROXY — Built-in reverse proxy.
 *
 * Routes traffic to running apps by hostname OR path prefix.
 *
 * Routing table examples:
 *   librechat.vulcan.local  → localhost:3080
 *   n8n.vulcan.local        → localhost:5678
 *   localhost:8080/app/n8n  → localhost:5678   (path-based, for WebView)
 *
 * KEY CAPABILITY: Full WebSocket upgrade handling (Plan gap filled here).
 * Apps like n8n and LibreChat use WebSockets. Standard HTTP proxying drops
 * the Upgrade header. We detect it and create a raw TCP tunnel instead.
 */
class VulcanProxy {

    companion object {
        const val PROXY_PORT = 8080
    }

    private val routingTable = ConcurrentHashMap<String, ProxyRoute>()
    private val executor     = Executors.newCachedThreadPool()
    private val running      = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null

    data class ProxyRoute(
        val appId:      String,
        val hostname:   String,         // e.g. librechat.vulcan.local
        val pathPrefix: String?,        // e.g. /app/librechat
        val targetPort: Int,
        val stripPath:  Boolean = true
    )

    // ─── LIFECYCLE ────────────────────────────────────────────────────────────

    fun start() {
        if (running.compareAndSet(false, true)) {
            executor.submit { acceptLoop() }
            VulcanLogger.i("VulcanProxy started on port $PROXY_PORT")
        }
    }

    fun stop() {
        running.set(false)
        serverSocket?.close()
        executor.shutdown()
        VulcanLogger.i("VulcanProxy stopped")
    }

    // ─── ROUTING ─────────────────────────────────────────────────────────────

    fun registerApp(app: InstalledApp) {
        routingTable[app.id] = ProxyRoute(
            appId      = app.id,
            hostname   = "${app.id}.vulcan.local",
            pathPrefix = "/app/${app.id}",
            targetPort = app.port
        )
        VulcanLogger.d("Proxy: registered ${app.id} → port ${app.port}")
    }

    fun unregisterApp(appId: String) {
        routingTable.remove(appId)
        VulcanLogger.d("Proxy: unregistered $appId")
    }

    fun getRoutes(): Map<String, ProxyRoute> = routingTable.toMap()

    // ─── ACCEPT LOOP ─────────────────────────────────────────────────────────

    private fun acceptLoop() {
        try {
            serverSocket = ServerSocket(PROXY_PORT, 100, InetAddress.getByName("0.0.0.0"))
            while (running.get()) {
                try {
                    val client = serverSocket!!.accept()
                    executor.submit { handleConnection(client) }
                } catch (e: SocketException) {
                    if (running.get()) VulcanLogger.e("Proxy accept error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            if (running.get()) VulcanLogger.e("Proxy server error: ${e.message}")
        }
    }

    // ─── CONNECTION HANDLING ──────────────────────────────────────────────────

    private fun handleConnection(client: Socket) {
        try {
            client.soTimeout = 30_000
            val input = client.getInputStream().buffered()

            // Read request line + headers (peek without consuming the body)
            val (requestLine, headers, rawHeaders) = readHeaders(input)
            if (requestLine == null) { client.close(); return }

            val route = resolveRoute(requestLine, headers) ?: run {
                send404(client)
                return
            }

            // ── WebSocket Upgrade — raw TCP tunnel (fills plan gap #9) ──────
            val upgradeHeader = headers["upgrade"]?.lowercase()
            if (upgradeHeader == "websocket") {
                handleWebSocketTunnel(client, route, requestLine, rawHeaders, input)
                return
            }

            // ── Standard HTTP proxy ──────────────────────────────────────────
            handleHttpProxy(client, route, requestLine, headers, rawHeaders, input)

        } catch (e: Exception) {
            VulcanLogger.d("Proxy connection error: ${e.message}")
        } finally {
            runCatching { client.close() }
        }
    }

    // ─── HTTP PROXY ───────────────────────────────────────────────────────────

    private fun handleHttpProxy(
        client: Socket,
        route: ProxyRoute,
        requestLine: String,
        headers: Map<String, String>,
        rawHeaders: ByteArray,
        clientInput: InputStream
    ) {
        try {
            Socket("127.0.0.1", route.targetPort).use { target ->
                target.soTimeout = 60_000

                // Rewrite request path if needed
                val rewritten = rewriteRequestLine(requestLine, route)

                // Forward headers + body to target
                target.outputStream.apply {
                    write("$rewritten\r\n".toByteArray())
                    // Forward Host header pointing to the app's port
                    write("Host: 127.0.0.1:${route.targetPort}\r\n".toByteArray())
                    // Forward all original headers except Host
                    headers.filter { it.key.lowercase() != "host" }
                        .forEach { (k, v) -> write("$k: $v\r\n".toByteArray()) }
                    write("\r\n".toByteArray())
                    // Stream body from client to target
                    val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L
                    if (contentLength > 0) {
                        val bodyBuf = ByteArray(8192)
                        var remaining = contentLength
                        while (remaining > 0) {
                            val read = clientInput.read(bodyBuf, 0, minOf(bodyBuf.size.toLong(), remaining).toInt())
                            if (read == -1) break
                            write(bodyBuf, 0, read)
                            remaining -= read
                        }
                    }
                    flush()
                }

                // Stream response back to client
                target.inputStream.copyTo(client.outputStream)
                client.outputStream.flush()
            }
        } catch (e: Exception) {
            VulcanLogger.d("HTTP proxy error for ${route.appId}: ${e.message}")
        }
    }

    // ─── WEBSOCKET TUNNEL ────────────────────────────────────────────────────
    // Creates a transparent raw TCP tunnel — client and app talk directly.
    // The proxy just splices the two sockets together bidirectionally.

    private fun handleWebSocketTunnel(
        client: Socket,
        route: ProxyRoute,
        requestLine: String,
        rawHeaders: ByteArray,
        clientInput: InputStream
    ) {
        VulcanLogger.d("WebSocket tunnel: ${route.appId} (port ${route.targetPort})")
        try {
            val target = Socket("127.0.0.1", route.targetPort)
            target.soTimeout = 0   // No timeout on WebSocket connections

            // Forward the upgrade request to the target app
            val rewritten = rewriteRequestLine(requestLine, route)
            target.outputStream.apply {
                write("$rewritten\r\n".toByteArray())
                write(rawHeaders)
                flush()
            }

            // Bidirectional splice: client ↔ target
            val clientToTarget = Thread {
                try { clientInput.copyTo(target.outputStream); target.shutdownOutput() }
                catch (e: Exception) { /* Connection closed */ }
            }.apply { isDaemon = true; start() }

            try { target.inputStream.copyTo(client.outputStream); client.shutdownOutput() }
            catch (e: Exception) { /* Connection closed */ }

            clientToTarget.join(5_000)
            target.close()
        } catch (e: Exception) {
            VulcanLogger.d("WebSocket tunnel error for ${route.appId}: ${e.message}")
        }
    }

    // ─── ROUTE RESOLUTION ────────────────────────────────────────────────────

    private fun resolveRoute(requestLine: String, headers: Map<String, String>): ProxyRoute? {
        val path       = requestLine.split(" ").getOrElse(1) { "/" }
        val hostHeader = headers["host"]?.substringBefore(":")?.trim()

        // 1. Hostname-based routing (e.g. librechat.vulcan.local)
        routingTable.values.find { it.hostname == hostHeader }?.let { return it }

        // 2. Path-prefix routing (e.g. /app/librechat/...)
        return routingTable.values.find {
            it.pathPrefix != null && path.startsWith(it.pathPrefix)
        }
    }

    private fun rewriteRequestLine(requestLine: String, route: ProxyRoute): String {
        val parts = requestLine.split(" ")
        if (parts.size < 3) return requestLine
        val method  = parts[0]
        val path    = parts[1]
        val version = parts[2]

        val newPath = if (route.stripPath && route.pathPrefix != null) {
            path.removePrefix(route.pathPrefix).ifEmpty { "/" }
        } else path

        return "$method $newPath $version"
    }

    // ─── HEADER PARSING ───────────────────────────────────────────────────────

    private data class HeaderParseResult(
        val requestLine: String?,
        val headers: Map<String, String>,
        val rawHeaders: ByteArray
    )

    private fun readHeaders(input: InputStream): HeaderParseResult {
        val headerBytes = ByteArrayOutputStream()
        val reader = input.bufferedReader(Charsets.ISO_8859_1)

        val requestLine = reader.readLine() ?: return HeaderParseResult(null, emptyMap(), ByteArray(0))
        headerBytes.write((requestLine + "\r\n").toByteArray(Charsets.ISO_8859_1))

        val headers = mutableMapOf<String, String>()
        var line = reader.readLine()
        while (line != null && line.isNotBlank()) {
            headerBytes.write((line + "\r\n").toByteArray(Charsets.ISO_8859_1))
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0) {
                headers[line.substring(0, colonIdx).trim().lowercase()] =
                    line.substring(colonIdx + 1).trim()
            }
            line = reader.readLine()
        }
        headerBytes.write("\r\n".toByteArray())

        return HeaderParseResult(requestLine, headers, headerBytes.toByteArray())
    }

    private fun send404(client: Socket) {
        val body = "Vulcan Proxy: No route found"
        val response = "HTTP/1.1 404 Not Found\r\n" +
                       "Content-Length: ${body.length}\r\n" +
                       "Connection: close\r\n\r\n$body"
        runCatching { client.outputStream.write(response.toByteArray()); client.close() }
    }
}
