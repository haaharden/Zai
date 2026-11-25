package com.self.zai.pi

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import com.self.zai.BuildConfig
import com.self.zai.data.CredentialVault
import com.self.zai.model.AuthUiState
import com.self.zai.model.CustomProviderConfig
import com.self.zai.model.ProviderStatus
import com.self.zai.update.AppUpdater
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Hosts unmodified `@earendil-works/pi-ai` and `@earendil-works/pi-agent-core` inside a hidden Android WebView.
 *
 * Concurrency & resilience guarantees:
 * - Ordered protocol events (`text_delta`, `assistant_end`, `transcript`, `idle`, `status`) flow through
 *   an unbounded single-consumer [Channel] so token chunks and lifecycle signals can NEVER reorder.
 * - Blocking host RPCs (`fetch`, `http_listen`, `before_tool`, `tool`) dispatch onto parallel IO workers so
 *   a long-running phone tool or HTTP stream never stalls cancellation or incoming events.
 * - WebView renderer crashes (`onRenderProcessGone`) and hot-reloads emit [PiSessionEvent.RuntimeReset]
 *   so coordinators clear stale JS session handles and abort stuck UI states cleanly.
 */
class PiEngine(
    private val context: Context,
    private val credentialVault: CredentialVault,
) : AgentRuntime {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val orderedEventChannel = Channel<JSONObject>(Channel.UNLIMITED)

    private val _isReady = MutableStateFlow(false)
    override val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _piVersion = MutableStateFlow("")
    override val piVersion: StateFlow<String> = _piVersion.asStateFlow()

    private val _hotUpdateTag = MutableStateFlow(AppUpdater.getActivePiHotTag(context))
    override val hotUpdateTag: StateFlow<String?> = _hotUpdateTag.asStateFlow()

    private val _providers = MutableStateFlow<List<ProviderStatus>>(emptyList())
    override val providers: StateFlow<List<ProviderStatus>> = _providers.asStateFlow()

    private val _authState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    override val authState: StateFlow<AuthUiState> = _authState.asStateFlow()

    private val _events = MutableSharedFlow<PiSessionEvent>(extraBufferCapacity = 512)
    override val events: SharedFlow<PiSessionEvent> = _events.asSharedFlow()

    @Volatile
    override var toolHandler: PiToolHandler? = null

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var latestCustomProviders: List<CustomProviderConfig> = emptyList()

    @Volatile
    private var readySignal = CompletableDeferred<Unit>()

    private val nativeHttp = NativeHttp(
        scope = scope,
        onHead = { id, status, statusText, headers ->
            val payload = JSONObject()
                .put("id", id)
                .put("status", status)
                .put("statusText", statusText)
                .put("headers", headers)
            callJs("fetchHead", payload.toString())
        },
        onChunk = { id, b64 ->
            evalJs("window.ZaiHost && window.ZaiHost.fetchChunk(${jsQuote(id)}, ${jsQuote(b64)})")
        },
        onEnd = { id ->
            evalJs("window.ZaiHost && window.ZaiHost.fetchEnd(${jsQuote(id)})")
        },
        onError = { id, msg ->
            evalJs("window.ZaiHost && window.ZaiHost.fetchError(${jsQuote(id)}, ${jsQuote(msg)})")
        },
    )

    private val loopbackServer = LoopbackServer(
        scope = scope,
        onRequest = { port, reqId, url, method ->
            val payload = JSONObject()
                .put("port", port)
                .put("id", reqId)
                .put("url", url)
                .put("method", method)
            callJs("http", payload.toString())
        },
    )

    init {
        scope.launch {
            for (msg in orderedEventChannel) {
                handleOrderedEvent(msg)
            }
        }
    }

    override fun start(customProviders: List<CustomProviderConfig>) {
        latestCustomProviders = customProviders
        mainHandler.post {
            if (webView == null) {
                createAndLoadWebView()
            }
        }
    }

    override fun hotReload() {
        mainHandler.post {
            resetRuntimeState(reason = "Pi 内核已热重载")
            webView?.let { old -> runCatching { old.destroy() } }
            webView = null
            _hotUpdateTag.value = AppUpdater.getActivePiHotTag(context)
            createAndLoadWebView()
        }
    }

    override fun resetToBundledPi() {
        AppUpdater.clearPiHotUpdate(context)
        hotReload()
    }

    override suspend fun awaitReady() {
        readySignal.await()
    }

    override fun syncCustomProviders(customProviders: List<CustomProviderConfig>) {
        latestCustomProviders = customProviders
        if (!_isReady.value) return
        val payload = JSONObject()
            .put("providers", PiBridgeCodec.encodeCustomProviders(customProviders))
            .put("keys", encodeCustomKeys(customProviders))
        callJs("syncCustomProviders", payload.toString())
    }

    override fun startChatGptLogin(method: String) {
        _authState.value = AuthUiState.InProgress(
            providerId = PiBridgeCodec.PRIMARY_OAUTH_PROVIDER_ID,
            statusMessage = if (method == "device_code") {
                "正在获取 Codex 设备授权码…"
            } else {
                "正在准备 ChatGPT Codex 登录通道…"
            },
        )
        val payload = JSONObject()
            .put("provider", PiBridgeCodec.PRIMARY_OAUTH_PROVIDER_ID)
            .put("type", "oauth")
            .put("method", method)
        callJs("login", payload.toString())
    }

    override fun cancelLogin() {
        callJsNoArg("cancelLogin")
        loopbackServer.closeAll()
        _authState.value = AuthUiState.Idle
    }

    override fun submitAuthPrompt(promptId: String, value: String) {
        callJs("answer", JSONObject().put("id", promptId).put("value", value).toString())
    }

    override fun cancelAuthPrompt(promptId: String) {
        callJs("answer", JSONObject().put("id", promptId).put("cancel", true).toString())
    }

    override fun dismissAuthError() {
        if (_authState.value is AuthUiState.Failed) {
            _authState.value = AuthUiState.Idle
        }
    }

    override fun logoutProvider(providerId: String) {
        callJs("logout", JSONObject().put("provider", providerId).toString())
    }

    override fun configureSession(
        sessionId: String,
        providerId: String,
        modelId: String,
        thinkingLevel: String,
        systemPrompt: String,
        toolSpecsJson: JSONArray,
        savedMessagesJson: String?,
    ) {
        val payload = JSONObject()
            .put("session", sessionId)
            .put("provider", providerId)
            .put("modelId", modelId)
            .put("thinkingLevel", thinkingLevel)
            .put("systemPrompt", systemPrompt)
            .put("toolSpecs", toolSpecsJson)
        if (!savedMessagesJson.isNullOrBlank()) {
            runCatching { JSONArray(savedMessagesJson) }.onSuccess {
                payload.put("messages", it)
            }
        }
        callJs("configure", payload.toString())
    }

    override fun prompt(sessionId: String, text: String) {
        callJs("prompt", JSONObject().put("session", sessionId).put("text", text).toString())
    }

    override fun abort(sessionId: String) {
        callJs("abort", JSONObject().put("session", sessionId).toString())
    }

    override fun disposeSession(sessionId: String) {
        callJs("dispose", JSONObject().put("session", sessionId).toString())
    }

    override fun lookupModels(
        ids: List<String>,
        onResult: (Map<String, Triple<Int, Boolean, Boolean>>) -> Unit,
    ) {
        if (!_isReady.value || ids.isEmpty()) {
            onResult(emptyMap())
            return
        }
        val arg = JSONObject().put("ids", JSONArray(ids)).toString()
        mainHandler.post {
            val wv = webView
            if (wv == null) {
                onResult(emptyMap())
                return@post
            }
            wv.evaluateJavascript("window.ZaiHost ? window.ZaiHost.lookupModels(${jsQuote(arg)}) : '{}'") { raw ->
                onResult(PiBridgeCodec.parseLookupModelsResult(raw))
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createAndLoadWebView() {
        val hotDir = File(context.filesDir, AppUpdater.PI_HOT_DIR_NAME).apply { mkdirs() }
        val hotTag = AppUpdater.getActivePiHotTag(context)
        _hotUpdateTag.value = hotTag

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/pi-hot/", WebViewAssetLoader.InternalStoragePathHandler(context, hotDir))
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()

        val wv = WebView(context.applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            clearCache(true)
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)?.apply {
                    val headers = HashMap(responseHeaders ?: emptyMap())
                    headers["Cache-Control"] = "no-store, no-cache, must-revalidate"
                    headers["Pragma"] = "no-cache"
                    responseHeaders = headers
                }

                override fun onRenderProcessGone(
                    view: WebView,
                    detail: RenderProcessGoneDetail,
                ): Boolean {
                    resetRuntimeState(reason = "WebView 渲染进程被系统回收，已自动重建")
                    webView = null
                    runCatching { view.destroy() }
                    mainHandler.postDelayed({ createAndLoadWebView() }, 300L)
                    return true
                }
            }
        }

        WebViewCompat.addWebMessageListener(
            wv,
            "ZaiBridge",
            setOf("https://appassets.androidplatform.net"),
        ) { _, message, _, _, _ ->
            val data = message.data ?: return@addWebMessageListener
            routeIncomingMessage(data)
        }

        webView = wv
        val bust = "${BuildConfig.VERSION_CODE}_${hotTag ?: "bundled"}"
        val entryUrl = if (hotTag != null) {
            "https://appassets.androidplatform.net/pi-hot/${AppUpdater.PI_HOT_HTML_NAME}?v=$bust"
        } else {
            "https://appassets.androidplatform.net/assets/pi/index.html?v=$bust"
        }
        wv.loadUrl(entryUrl)
    }

    private fun resetRuntimeState(reason: String) {
        _isReady.value = false
        readySignal = CompletableDeferred()
        nativeHttp.cancelAll()
        loopbackServer.closeAll()
        _events.tryEmit(PiSessionEvent.RuntimeReset(reason))
    }

    private fun routeIncomingMessage(raw: String) {
        val msg = runCatching { JSONObject(raw) }.getOrNull() ?: return
        when (msg.optString("type")) {
            // Concurrent RPCs that may block on network, sockets, or user confirmation
            "fetch", "fetch_abort", "http_listen", "http_close", "http_response",
            "before_tool", "tool", "tool_cancel",
            -> scope.launch(Dispatchers.IO) { handleRpcMessage(msg) }

            // Strictly ordered state & streaming events
            else -> orderedEventChannel.trySend(msg)
        }
    }

    private fun handleOrderedEvent(msg: JSONObject) {
        when (msg.optString("type")) {
            "ready" -> {
                _piVersion.value = msg.optString("pi")
                val initPayload = JSONObject()
                    .put("deviceId", getStableDeviceId())
                    .put("customProviders", PiBridgeCodec.encodeCustomProviders(latestCustomProviders))
                    .put("customKeys", encodeCustomKeys(latestCustomProviders))
                credentialVault.loadPiCredentialsJson()?.let { initPayload.put("credentials", it) }
                callJs("init", initPayload.toString())
                _isReady.value = true
                if (!readySignal.isCompleted) readySignal.complete(Unit)
            }

            "credentials_changed" -> {
                val creds = msg.optString("json")
                if (creds.isNotEmpty()) {
                    credentialVault.savePiCredentialsJson(creds)
                }
            }

            "status" -> {
                _providers.value = PiBridgeCodec.parseProvidersStatus(msg.optJSONArray("providers"))
            }

            "auth_event" -> {
                PiBridgeCodec.parseAuthEvent(_authState.value, msg.optJSONObject("event"))?.let {
                    _authState.value = it
                }
            }

            "auth_prompt" -> {
                val promptId = msg.optString("id")
                val prompt = msg.optJSONObject("prompt")
                val current = _authState.value as? AuthUiState.InProgress
                    ?: AuthUiState.InProgress(
                        providerId = PiBridgeCodec.PRIMARY_OAUTH_PROVIDER_ID,
                        statusMessage = "请完成登录",
                    )
                _authState.value = current.copy(
                    promptId = promptId,
                    promptMessage = prompt?.optString("message").orEmpty().ifBlank {
                        "如果浏览器没有自动跳回，请把登录后的完整跳转链接粘贴到下方"
                    },
                    promptPlaceholder = prompt?.optString("placeholder").orEmpty().ifBlank {
                        "http://localhost:1455/auth/callback?code=..."
                    },
                )
            }

            "auth_prompt_closed" -> {
                val current = _authState.value as? AuthUiState.InProgress ?: return
                if (current.promptId == msg.optString("id")) {
                    _authState.value = current.copy(promptId = null, promptMessage = null)
                }
            }

            "login_done" -> {
                loopbackServer.closeAll()
                _authState.value = AuthUiState.Idle
            }

            "login_failed" -> {
                loopbackServer.closeAll()
                val err = msg.optString("message", "登录失败")
                _authState.value = if (err == "已取消登录") AuthUiState.Idle else AuthUiState.Failed(err)
            }

            "error" -> {
                val sessionEvent = PiBridgeCodec.parseSessionEvent(msg)
                if (sessionEvent != null) {
                    _events.tryEmit(sessionEvent)
                } else if (msg.optString("op") == "login") {
                    _authState.value = AuthUiState.Failed(msg.optString("message", "登录失败"))
                }
            }

            else -> {
                PiBridgeCodec.parseSessionEvent(msg)?.let { _events.tryEmit(it) }
            }
        }
    }

    private suspend fun handleRpcMessage(msg: JSONObject) {
        when (msg.optString("type")) {
            "fetch" -> nativeHttp.execute(
                id = msg.optString("id"),
                method = msg.optString("method", "GET"),
                url = msg.optString("url"),
                headersJson = msg.optJSONObject("headers"),
                bodyBase64 = if (msg.isNull("body")) null else msg.optString("body"),
            )

            "fetch_abort" -> nativeHttp.abort(msg.optString("id"))

            "http_listen" -> {
                val callId = msg.optString("id")
                val port = msg.optInt("port", 1455)
                val host = msg.optString("host", "127.0.0.1")
                val res = loopbackServer.listen(port, host)
                if (res.isSuccess) {
                    replyJs(callId, true, JSONObject().put("port", res.getOrThrow()))
                } else {
                    replyJs(
                        callId,
                        false,
                        JSONObject()
                            .put("message", res.exceptionOrNull()?.message ?: "port bind failed")
                            .put("code", "EADDRINUSE"),
                    )
                }
            }

            "http_close" -> loopbackServer.close(msg.optInt("port"))

            "http_response" -> loopbackServer.respond(
                reqId = msg.optString("id"),
                status = msg.optInt("status", 200),
                headers = msg.optJSONObject("headers") ?: JSONObject(),
                body = msg.optString("body", ""),
            )

            "before_tool" -> {
                val bridgeId = msg.optString("id")
                val verdict = runCatching {
                    toolHandler?.beforeTool(
                        sessionId = msg.optString("session"),
                        callId = msg.optString("call"),
                        tool = msg.optString("tool"),
                        args = msg.optJSONObject("args") ?: JSONObject(),
                    )
                }.getOrNull()
                if (verdict != null && verdict.block) {
                    replyJs(
                        bridgeId,
                        true,
                        JSONObject()
                            .put("block", true)
                            .put("terminate", verdict.terminate)
                            .put("reason", verdict.reason),
                    )
                } else {
                    replyJs(bridgeId, true, JSONObject.NULL)
                }
            }

            "tool" -> {
                val bridgeId = msg.optString("id")
                val handler = toolHandler
                if (handler == null) {
                    replyJs(
                        bridgeId,
                        true,
                        JSONObject().put("text", "手机操控执行器未就绪").put("isError", true),
                    )
                    return
                }
                val result = runCatching {
                    handler.executeTool(
                        sessionId = msg.optString("session"),
                        callId = msg.optString("call"),
                        tool = msg.optString("tool", msg.optString("name")),
                        args = msg.optJSONObject("args") ?: JSONObject(),
                    )
                }.getOrElse {
                    ToolExecutionResult(text = "工具执行异常: ${it.message}", isError = true)
                }
                val out = JSONObject()
                    .put("text", result.text)
                    .put("isError", result.isError)
                if (!result.imageBase64.isNullOrBlank()) {
                    out.put(
                        "image",
                        JSONObject()
                            .put("data", result.imageBase64)
                            .put("mimeType", result.imageMimeType),
                    )
                }
                replyJs(bridgeId, true, out)
            }

            "tool_cancel" -> {
                val bridgeId = msg.optString("id")
                toolHandler?.cancelTool(msg.optString("session"), msg.optString("call"))
                if (bridgeId.isNotEmpty()) replyJs(bridgeId, true, JSONObject.NULL)
            }
        }
    }

    private fun encodeCustomKeys(list: List<CustomProviderConfig>): JSONObject {
        val keys = credentialVault.getAllProviderApiKeys(list.map { it.id })
        val obj = JSONObject()
        for ((k, v) in keys) obj.put(k, v)
        return obj
    }

    private fun getStableDeviceId(): String {
        val existing = credentialVault.getString(KEY_DEVICE_UUID)
        if (!existing.isNullOrBlank() && UUID_REGEX.matches(existing)) {
            return existing
        }
        val created = UUID.randomUUID().toString()
        credentialVault.putString(KEY_DEVICE_UUID, created)
        return created
    }

    private fun replyJs(id: String, ok: Boolean, value: Any?) {
        val payload = JSONObject()
            .put("id", id)
            .put("ok", ok)
            .put("value", value ?: JSONObject.NULL)
        callJs("reply", payload.toString())
    }

    private fun callJs(fn: String, jsonArg: String) {
        evalJs("window.ZaiHost && window.ZaiHost.$fn(${jsQuote(jsonArg)})")
    }

    private fun callJsNoArg(fn: String) {
        evalJs("window.ZaiHost && window.ZaiHost.$fn()")
    }

    private fun evalJs(script: String) {
        mainHandler.post {
            webView?.evaluateJavascript(script, null)
        }
    }

    private fun jsQuote(value: String): String =
        JSONObject.quote(value)
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029")

    companion object {
        private const val KEY_DEVICE_UUID = "zai_device_uuid"
        private val UUID_REGEX =
            Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}
