package com.self.zai.mcp

import com.self.zai.BuildConfig
import com.self.zai.model.McpServerConfig
import com.self.zai.model.McpToolSummary
import com.self.zai.pi.ToolExecutionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Contract for remote MCP (Model Context Protocol) tool discovery and execution.
 */
interface McpToolClient {
    suspend fun discoverTools(url: String, authToken: String?): Result<List<McpToolSummary>>
    suspend fun callTool(
        server: McpServerConfig,
        authToken: String?,
        remoteToolName: String,
        arguments: JSONObject,
    ): ToolExecutionResult
    fun invalidateSession(serverUrl: String)
}

/**
 * Lightweight, standards-compliant MCP Streamable HTTP (JSON-RPC 2.0) client backed by OkHttp.
 *
 * Supports:
 * - `initialize` + `notifications/initialized` handshake with automatic `Mcp-Session-Id` tracking
 * - Both `application/json` and `text/event-stream` (SSE) JSON-RPC responses
 * - Paginated `tools/list` discovery with schema normalization for OpenAI/Anthropic compatibility
 * - `tools/call` returning text & optional image content blocks, plus auto-reinit on expired session
 */
class StreamableHttpMcpClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build(),
) : McpToolClient {

    private val reqSeq = AtomicLong(1L)
    private val sessionIds = ConcurrentHashMap<String, String>()
    private val initializedUrls = ConcurrentHashMap<String, Boolean>()

    override fun invalidateSession(serverUrl: String) {
        val normalized = normalizeUrl(serverUrl)
        sessionIds.remove(normalized)
        initializedUrls.remove(normalized)
    }

    override suspend fun discoverTools(url: String, authToken: String?): Result<List<McpToolSummary>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val endpoint = normalizeUrl(url)
                require(endpoint.startsWith("http://") || endpoint.startsWith("https://")) {
                    "请输入有效的 HTTP/HTTPS MCP 服务地址"
                }
                invalidateSession(endpoint)
                initializeHandshake(endpoint, authToken)

                val allTools = mutableListOf<McpToolSummary>()
                var cursor: String? = null
                repeat(10) {
                    val params = JSONObject().apply {
                        if (!cursor.isNullOrBlank()) put("cursor", cursor)
                    }
                    val resp = rpcCall(endpoint, authToken, "tools/list", params)
                    val result = resp.optJSONObject("result")
                        ?: throw IllegalStateException(extractRpcError(resp) ?: "MCP 未返回工具列表")
                    val arr = result.optJSONArray("tools") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val name = obj.optString("name").trim()
                        if (name.isEmpty()) continue
                        val desc = obj.optString("description").trim()
                        val schema = normalizeInputSchema(obj.optJSONObject("inputSchema"))
                        allTools += McpToolSummary(
                            name = name,
                            description = desc,
                            inputSchemaJson = schema.toString(),
                        )
                    }
                    cursor = result.optString("nextCursor").takeIf { it.isNotBlank() && it != "null" }
                    if (cursor == null) return@repeat
                }
                allTools
            }
        }

    override suspend fun callTool(
        server: McpServerConfig,
        authToken: String?,
        remoteToolName: String,
        arguments: JSONObject,
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        val endpoint = normalizeUrl(server.url)
        try {
            if (initializedUrls[endpoint] != true) {
                initializeHandshake(endpoint, authToken)
            }
            val params = JSONObject()
                .put("name", remoteToolName)
                .put("arguments", arguments)

            val resp = try {
                rpcCall(endpoint, authToken, "tools/call", params)
            } catch (sessionErr: McpSessionExpiredException) {
                // Retry once with fresh MCP session handshake
                invalidateSession(endpoint)
                initializeHandshake(endpoint, authToken)
                rpcCall(endpoint, authToken, "tools/call", params)
            }

            extractRpcError(resp)?.let { err ->
                return@withContext ToolExecutionResult(text = "MCP 错误 ($remoteToolName): $err", isError = true)
            }

            val result = resp.optJSONObject("result")
                ?: return@withContext ToolExecutionResult(text = "MCP 未返回结果", isError = true)
            parseToolCallResult(result)
        } catch (t: Throwable) {
            ToolExecutionResult(
                text = "调用 MCP 工具 ${server.name}/$remoteToolName 失败：${t.message ?: t.javaClass.simpleName}",
                isError = true,
            )
        }
    }

    private fun initializeHandshake(endpoint: String, authToken: String?) {
        val initParams = JSONObject()
            .put("protocolVersion", PROTOCOL_VERSION)
            .put("capabilities", JSONObject())
            .put(
                "clientInfo",
                JSONObject()
                    .put("name", "Zai")
                    .put("version", BuildConfig.VERSION_NAME),
            )
        val initResp = rpcCall(endpoint, authToken, "initialize", initParams)
        extractRpcError(initResp)?.let { err ->
            throw IllegalStateException("MCP 初始化失败：$err")
        }
        initializedUrls[endpoint] = true

        // Send notifications/initialized (best-effort per MCP spec; HTTP 202/204 expected)
        runCatching {
            val notifBody = JSONObject()
                .put("jsonrpc", "2.0")
                .put("method", "notifications/initialized")
                .put("params", JSONObject())
            sendRawPost(endpoint, authToken, notifBody.toString())
        }
    }

    private fun rpcCall(
        endpoint: String,
        authToken: String?,
        method: String,
        params: JSONObject,
    ): JSONObject {
        val id = reqSeq.getAndIncrement()
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .put("params", params)

        val (statusCode, contentType, body) = sendRawPost(endpoint, authToken, payload.toString())
        if (statusCode == 404 && sessionIds.containsKey(endpoint)) {
            throw McpSessionExpiredException("HTTP 404 session expired")
        }
        if (statusCode !in 200..299) {
            val preview = body.trim().take(240)
            throw IllegalStateException("HTTP $statusCode${if (preview.isNotEmpty()) ": $preview" else ""}")
        }
        return parseJsonRpcPayload(contentType, body, expectedId = id)
            ?: throw IllegalStateException("无法解析 MCP 响应（${contentType.ifBlank { "unknown" }}）")
    }

    private fun sendRawPost(
        endpoint: String,
        authToken: String?,
        jsonBody: String,
    ): Triple<Int, String, String> {
        val builder = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json")
            .header("MCP-Protocol-Version", PROTOCOL_VERSION)
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))

        val trimmedAuth = authToken?.trim().orEmpty()
        if (trimmedAuth.isNotEmpty()) {
            val headerVal = if (trimmedAuth.contains(' ')) trimmedAuth else "Bearer $trimmedAuth"
            builder.header("Authorization", headerVal)
        }
        sessionIds[endpoint]?.let { sid ->
            builder.header("Mcp-Session-Id", sid)
        }

        client.newCall(builder.build()).execute().use { resp ->
            resp.header("Mcp-Session-Id")?.takeIf { it.isNotBlank() }?.let { sid ->
                sessionIds[endpoint] = sid
            }
            val ct = resp.header("Content-Type").orEmpty()
            val text = resp.body?.string().orEmpty()
            return Triple(resp.code, ct, text)
        }
    }

    private class McpSessionExpiredException(message: String) : Exception(message)

    companion object {
        private const val PROTOCOL_VERSION = "2025-03-26"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun normalizeUrl(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return ""
            return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else {
                "https://$trimmed"
            }
        }

        /**
         * Parses either direct JSON-RPC body or an SSE stream (`data: {...}`) returning the matching response object.
         */
        fun parseJsonRpcPayload(contentType: String, body: String, expectedId: Long? = null): JSONObject? {
            val trimmed = body.trim()
            if (trimmed.isEmpty()) return null

            if (!contentType.contains("text/event-stream", ignoreCase = true) && trimmed.startsWith("{")) {
                return runCatching { JSONObject(trimmed) }.getOrNull()
            }

            // Parse SSE event blocks (`data:` lines separated by blank lines)
            val candidates = mutableListOf<JSONObject>()
            val dataBuf = StringBuilder()

            fun flushEvent() {
                val raw = dataBuf.toString().trim()
                dataBuf.clear()
                if (raw.startsWith("{")) {
                    runCatching { JSONObject(raw) }.getOrNull()?.let { candidates += it }
                }
            }

            for (line in trimmed.lineSequence()) {
                val l = line.trimEnd('\r')
                if (l.isBlank()) {
                    flushEvent()
                } else if (l.startsWith("data:")) {
                    if (dataBuf.isNotEmpty()) dataBuf.append('\n')
                    dataBuf.append(l.removePrefix("data:").trimStart())
                }
            }
            flushEvent()

            if (candidates.isEmpty()) return null
            if (expectedId != null) {
                candidates.firstOrNull { it.has("id") && it.optLong("id", Long.MIN_VALUE) == expectedId }
                    ?.let { return it }
            }
            return candidates.lastOrNull { it.has("result") || it.has("error") } ?: candidates.last()
        }

        /**
         * Ensures every MCP tool schema is a valid JSON Schema object accepted by strict OpenAI/Anthropic APIs.
         */
        fun normalizeInputSchema(raw: JSONObject?): JSONObject {
            val copy = if (raw != null) JSONObject(raw.toString()) else JSONObject()
            if (copy.optString("type").isBlank()) {
                copy.put("type", "object")
            }
            if (!copy.has("properties") || copy.isNull("properties")) {
                copy.put("properties", JSONObject())
            }
            return copy
        }

        fun parseToolCallResult(result: JSONObject): ToolExecutionResult {
            val isError = result.optBoolean("isError", false)
            val contentArr = result.optJSONArray("content")
            val textParts = mutableListOf<String>()
            var imageData: String? = null
            var imageMime = "image/png"

            if (contentArr != null) {
                for (i in 0 until contentArr.length()) {
                    val item = contentArr.optJSONObject(i) ?: continue
                    when (item.optString("type")) {
                        "text" -> item.optString("text").takeIf { it.isNotEmpty() }?.let { textParts += it }
                        "image" -> {
                            val data = item.optString("data")
                            if (data.isNotBlank() && imageData == null) {
                                imageData = data
                                imageMime = item.optString("mimeType", "image/png").ifBlank { "image/png" }
                            }
                        }
                        "resource" -> {
                            val resObj = item.optJSONObject("resource")
                            val txt = resObj?.optString("text").orEmpty()
                            if (txt.isNotBlank()) textParts += txt
                        }
                    }
                }
            }

            if (textParts.isEmpty() && result.has("structuredContent")) {
                textParts += result.opt("structuredContent")?.toString().orEmpty()
            }

            val combined = textParts.joinToString("\n").ifBlank {
                if (imageData != null) "(MCP 工具返回了图片)" else "(MCP 工具执行完成，无文本输出)"
            }
            return ToolExecutionResult(
                text = combined,
                isError = isError,
                imageBase64 = imageData,
                imageMimeType = imageMime,
            )
        }

        private fun extractRpcError(resp: JSONObject): String? {
            val err = resp.optJSONObject("error") ?: return null
            val msg = err.optString("message").ifBlank { "未知错误" }
            val code = if (err.has("code")) " (${err.optInt("code")})" else ""
            return "$msg$code"
        }
    }
}
