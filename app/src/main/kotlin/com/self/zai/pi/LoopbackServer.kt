package com.self.zai.pi

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Localhost HTTP callback listener backing Pi's `node:http` shim for ChatGPT OAuth PKCE redirects.
 */
class LoopbackServer(
    private val scope: CoroutineScope,
    private val onRequest: (port: Int, reqId: String, url: String, method: String) -> Unit,
) {

    private data class PendingResponse(
        val status: Int,
        val headers: JSONObject,
        val body: String,
    )

    private val servers = ConcurrentHashMap<Int, ServerSocket>()
    private val openClients = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
    private val pendingResponses = ConcurrentHashMap<String, CompletableDeferred<PendingResponse>>()
    private val reqSeq = AtomicInteger(0)

    fun listen(port: Int, host: String): Result<Int> {
        close(port)
        return runCatching {
            val bindHost = host.ifBlank { "127.0.0.1" }
            val socket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName(bindHost), port), 16)
            }
            val actualPort = socket.localPort
            servers[actualPort] = socket
            scope.launch(Dispatchers.IO) {
                acceptLoop(actualPort, socket)
            }
            actualPort
        }
    }

    fun close(port: Int) {
        runCatching { servers.remove(port)?.close() }
        for (client in openClients.toList()) {
            openClients.remove(client)
            runCatching { client.close() }
        }
    }

    fun closeAll() {
        for (port in servers.keys.toList()) {
            close(port)
        }
    }

    fun respond(reqId: String, status: Int, headers: JSONObject, body: String) {
        pendingResponses.remove(reqId)?.complete(PendingResponse(status, headers, body))
    }

    private fun acceptLoop(port: Int, serverSocket: ServerSocket) {
        while (!serverSocket.isClosed) {
            val client = runCatching { serverSocket.accept() }.getOrNull() ?: break
            scope.launch(Dispatchers.IO) {
                handleClient(port, client)
            }
        }
    }

    private suspend fun handleClient(port: Int, socket: Socket) {
        openClients.add(socket)
        try {
            socket.use { s ->
                s.soTimeout = 10_000
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            // Drain headers
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: "GET"
            val url = parts.getOrNull(1) ?: "/"

            val reqId = "http_${reqSeq.incrementAndGet()}"
            val deferred = CompletableDeferred<PendingResponse>()
            pendingResponses[reqId] = deferred
            onRequest(port, reqId, url, method)

            val resp = withTimeoutOrNull(15_000) { deferred.await() }
                ?: PendingResponse(
                    status = 200,
                    headers = JSONObject().put("Content-Type", "text/html; charset=utf-8"),
                    body = "<html><body style='font-family:sans-serif;padding:32px'><h3>已收到回调</h3><p>请返回 Zai 应用。</p></body></html>",
                )
            pendingResponses.remove(reqId)

            val bodyBytes = resp.body.toByteArray(Charsets.UTF_8)
            val statusText = when (resp.status) {
                200 -> "OK"
                302 -> "Found"
                400 -> "Bad Request"
                404 -> "Not Found"
                else -> "OK"
            }
            val sb = StringBuilder()
            sb.append("HTTP/1.1 ${resp.status} $statusText\r\n")
            var hasContentType = false
            val keys = resp.headers.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k.equals("content-type", true)) hasContentType = true
                if (!k.equals("content-length", true) && !k.equals("connection", true)) {
                    sb.append("$k: ${resp.headers.optString(k)}\r\n")
                }
            }
            if (!hasContentType) {
                sb.append("Content-Type: text/html; charset=utf-8\r\n")
            }
            sb.append("Content-Length: ${bodyBytes.size}\r\n")
            sb.append("Connection: close\r\n\r\n")

                val out = s.getOutputStream()
                out.write(sb.toString().toByteArray(Charsets.UTF_8))
                out.write(bodyBytes)
                out.flush()
            }
        } finally {
            openClients.remove(socket)
        }
    }
}
