package com.self.zai.pi

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import com.self.zai.data.SecureStore
import com.self.zai.model.AuthUiState
import com.self.zai.model.CustomProviderConfig
import com.self.zai.model.ModelOption
import com.self.zai.model.ProviderStatus
import com.self.zai.update.AppUpdater
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

sealed interface PiSessionEvent {
    val sessionId: String

    data class Configured(
        override val sessionId: String,
        val provider: String,
        val model: String,
        val supportsImages: Boolean,
    ) : PiSessionEvent

    data class TextDelta(override val sessionId: String, val delta: String) : PiSessionEvent
    data class ThinkingStart(override val sessionId: String) : PiSessionEvent
    data class ThinkingDelta(override val sessionId: String, val delta: String) : PiSessionEvent
    data class ThinkingEnd(override val sessionId: String, val text: String) : PiSessionEvent

    data class AssistantEnd(
        override val sessionId: String,
        val text: String,
        val stopReason: String,
        val errorMessage: String?,
    ) : PiSessionEvent

    data class ToolStart(
        override val sessionId: String,
        val tool: String,
        val args: JSONObject,
    ) : PiSessionEvent

    data class ToolEnd(
        override val sessionId: String,
        val tool: String,
        val isError: Boolean,
        val output: String,
    ) : PiSessionEvent

    data class Steered(override val sessionId: String, val text: String) : PiSessionEvent
    data class Transcript(override val sessionId: String, val messagesJson: String) : PiSessionEvent
    data class Idle(override val sessionId: String) : PiSessionEvent
    data class Error(override val sessionId: String, val message: String) : PiSessionEvent
}

data class ToolExecutionResult(
    val text: String,
    val isError: Boolean = false,
    val imageBase64: String? = null,
    val imageMimeType: String = "image/jpeg",
)

data class BeforeToolVerdict(
    val block: Boolean,
    val terminate: Boolean = true,
    val reason: String = "",
)

interface PiToolHandler {
    suspend fun beforeTool(sessionId: String, callId: String, tool: String, args: JSONObject): BeforeToolVerdict?
    suspend fun executeTool(sessionId: String, callId: String, tool: String, args: JSONObject): ToolExecutionResult
    fun cancelTool(sessionId: String, callId: String)
}

/**
 * Hosts unmodified `@earendil-works/pi-ai` and `@earendil-works/pi-agent-core` inside a hidden Android WebView.
 * Provides ChatGPT OAuth sign-in, custom OpenAI/Anthropic provider support, and streaming agent execution
 * without PRoot, Linux, or Node.js.
 */
class PiEngine(
    private val context: Context,
    private val secureStore: SecureStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _piVersion = MutableStateFlow("")
    val piVersion: StateFlow<String> = _piVersion.asStateFlow()

    private val _hotUpdateTag = MutableStateFlow(AppUpdater.getActivePiHotTag(context))
    val hotUpdateTag: StateFlow<String?> = _hotUpdateTag.asStateFlow()

    private val _providers = MutableStateFlow<List<ProviderStatus>>(emptyList())
    val providers: StateFlow<List<ProviderStatus>> = _providers.asStateFlow()

    private val _authState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val authState: StateFlow<AuthUiState> = _authState.asStateFlow()

    private val _events = MutableSharedFlow<PiSessionEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<PiSessionEvent> = _events.asSharedFlow()

    @Volatile
    var toolHandler: PiToolHandler? = null

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var latestCustomProviders: List<CustomProviderConfig> = emptyList()

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

    fun start(customProviders: List<CustomProviderConfig>) {
        latestCustomProviders = customProviders
        mainHandler.post {
            if (webView == null) {
                createAndLoadWebView()
            }
        }
    }

    /**
     * Hot-reloads the hidden Pi WebView immediately after a JS hot-update or reset without restarting the app.
     */
    fun hotReload() {
        mainHandler.post {
            _isReady.value = false
            readySignal = CompletableDeferred()
            nativeHttp.cancelAll()
            loopbackServer.closeAll()
            webView?.let { old ->
                runCatching { old.destroy() }
            }
            webView = null
            _hotUpdateTag.value = AppUpdater.getActivePiHotTag(context)
            createAndLoadWebView()
        }
    }

    fun resetToBundledPi() {
        AppUpdater.clearPiHotUpdate(context)
        hotReload()
    }

    suspend fun awaitReady() {
        readySignal.await()
    }

    fun syncCustomProviders(customProviders: List<CustomProviderConfig>) {
        latestCustomProviders = customProviders
        if (!_isReady.value) return
        val payload = JSONObject()
            .put("providers", encodeCustomProviders(customProviders))
            .put("keys", encodeCustomKeys(customProviders))
        callJs("syncCustomProviders", payload.toString())
    }

    fun startChatGptLogin(method: String = "browser") {
        _authState.value = AuthUiState.InProgress(
            providerId = "openai-codex",
            statusMessage = if (method == "device_code") {
                "正在获取 Codex 设备授权码…"
            } else {
                "正在准备 ChatGPT Codex 登录通道…"
            },
        )
        val payload = JSONObject()
            .put("provider", "openai-codex")
            .put("type", "oauth")
            .put("method", method)
        callJs("login", payload.toString())
    }

    fun cancelLogin() {
        callJsNoArg("cancelLogin")
        loopbackServer.closeAll()
        _authState.value = AuthUiState.Idle
    }

    fun submitAuthPrompt(promptId: String, value: String) {
        val payload = JSONObject().put("id", promptId).put("value", value)
        callJs("answer", payload.toString())
    }

    fun cancelAuthPrompt(promptId: String) {
        val payload = JSONObject().put("id", promptId).put("cancel", true)
        callJs("answer", payload.toString())
    }

    fun dismissAuthError() {
        if (_authState.value is AuthUiState.Failed) {
            _authState.value = AuthUiState.Idle
        }
    }

    fun logoutProvider(providerId: String) {
        val payload = JSONObject().put("provider", providerId)
        callJs("logout", payload.toString())
    }

    fun configureSession(
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

    fun prompt(sessionId: String, text: String) {
        val payload = JSONObject().put("session", sessionId).put("text", text)
        callJs("prompt", payload.toString())
    }

    fun abort(sessionId: String) {
        val payload = JSONObject().put("session", sessionId)
        callJs("abort", payload.toString())
    }

    fun disposeSession(sessionId: String) {
        val payload = JSONObject().put("session", sessionId)
        callJs("dispose", payload.toString())
    }

    fun lookupModels(ids: List<String>, onResult: (Map<String, Triple<Int, Boolean, Boolean>>) -> Unit) {
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
                val map = runCatching {
                    val unquoted = JSONObject("{\"v\":$raw}").optString("v", "{}")
                    val obj = JSONObject(unquoted)
                    buildMap {
                        val keys = obj.keys()
                        while (keys.hasNext()) {
                            val id = keys.next()
                            val entry = obj.optJSONObject(id) ?: continue
                            put(
                                id,
                                Triple(
                                    entry.optInt("contextWindow", 128_000),
                                    entry.optBoolean("images", true),
                                    entry.optBoolean("reasoning", false),
                                ),
                            )
                        }
                    }
                }.getOrDefault(emptyMap())
                onResult(map)
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
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

                override fun onRenderProcessGone(
                    view: WebView,
                    detail: RenderProcessGoneDetail,
                ): Boolean {
                    _isReady.value = false
                    readySignal = CompletableDeferred()
                    nativeHttp.cancelAll()
                    loopbackServer.closeAll()
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
            scope.launch { handleJsMessage(data) }
        }

        webView = wv
        val entryUrl = if (hotTag != null) {
            "https://appassets.androidplatform.net/pi-hot/${AppUpdater.PI_HOT_HTML_NAME}"
        } else {
            "https://appassets.androidplatform.net/assets/pi/index.html"
        }
        wv.loadUrl(entryUrl)
    }

    private suspend fun handleJsMessage(raw: String) {
        val msg = runCatching { JSONObject(raw) }.getOrNull() ?: return
        when (msg.optString("type")) {
            "ready" -> {
                _piVersion.value = msg.optString("pi")
                val initPayload = JSONObject()
                    .put("deviceId", getStableDeviceId())
                    .put("customProviders", encodeCustomProviders(latestCustomProviders))
                    .put("customKeys", encodeCustomKeys(latestCustomProviders))
                secureStore.loadPiCredentialsJson()?.let { initPayload.put("credentials", it) }
                callJs("init", initPayload.toString())
                _isReady.value = true
                if (!readySignal.isCompleted) readySignal.complete(Unit)
            }

            "credentials_changed" -> {
                val creds = msg.optString("json")
                if (creds.isNotEmpty()) {
                    secureStore.savePiCredentialsJson(creds)
                }
            }

            "status" -> {
                val arr = msg.optJSONArray("providers") ?: JSONArray()
                val parsed = buildList {
                    for (i in 0 until arr.length()) {
                        val p = arr.optJSONObject(i) ?: continue
                        val pId = p.optString("id")
                        val pName = p.optString("name", pId)
                        val cred = p.optString("credential").takeIf { it.isNotBlank() && it != "null" }
                        val modelsArr = p.optJSONArray("models") ?: JSONArray()
                        val modelList = buildList {
                            for (j in 0 until modelsArr.length()) {
                                val m = modelsArr.optJSONObject(j) ?: continue
                                val inputArr = m.optJSONArray("input") ?: JSONArray()
                                var hasImg = false
                                for (k in 0 until inputArr.length()) {
                                    if (inputArr.optString(k) == "image") hasImg = true
                                }
                                val thinkArr = m.optJSONArray("thinkingLevels") ?: JSONArray()
                                val thinks = buildList {
                                    for (k in 0 until thinkArr.length()) {
                                        add(thinkArr.optString(k))
                                    }
                                }
                                add(
                                    ModelOption(
                                        providerId = pId,
                                        providerName = pName,
                                        id = m.optString("id"),
                                        name = m.optString("name", m.optString("id")),
                                        contextWindow = m.optInt("contextWindow", 128_000),
                                        supportsImages = hasImg,
                                        thinkingLevels = thinks,
                                    ),
                                )
                            }
                        }
                        add(
                            ProviderStatus(
                                id = pId,
                                name = pName,
                                credentialType = cred,
                                isCustom = p.optBoolean("custom", false),
                                supportsOAuth = p.optBoolean("supportsOAuth", false),
                                models = modelList,
                            ),
                        )
                    }
                }
                _providers.value = parsed
            }

            "auth_event" -> handleAuthEvent(msg.optJSONObject("event"))

            "auth_prompt" -> {
                val promptId = msg.optString("id")
                val prompt = msg.optJSONObject("prompt")
                val current = _authState.value as? AuthUiState.InProgress
                    ?: AuthUiState.InProgress(providerId = "openai-codex", statusMessage = "请完成登录")
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
                _authState.value = if (err == "已取消登录") {
                    AuthUiState.Idle
                } else {
                    AuthUiState.Failed(err)
                }
            }

            "fetch" -> {
                nativeHttp.execute(
                    id = msg.optString("id"),
                    method = msg.optString("method", "GET"),
                    url = msg.optString("url"),
                    headersJson = msg.optJSONObject("headers"),
                    bodyBase64 = if (msg.isNull("body")) null else msg.optString("body"),
                )
            }

            "fetch_abort" -> nativeHttp.abort(msg.optString("id"))

            "http_listen" -> {
                val callId = msg.optString("id")
                val port = msg.optInt("port", 1455)
                val host = msg.optString("host", "127.0.0.1")
                val res = loopbackServer.listen(port, host)
                if (res.isSuccess) {
                    replyJs(callId, true, JSONObject().put("port", res.getOrThrow()))
                } else {
                    val err = res.exceptionOrNull()
                    replyJs(
                        callId,
                        false,
                        JSONObject()
                            .put("message", err?.message ?: "port bind failed")
                            .put("code", "EADDRINUSE"),
                    )
                }
            }

            "http_close" -> loopbackServer.close(msg.optInt("port"))

            "http_response" -> {
                loopbackServer.respond(
                    reqId = msg.optString("id"),
                    status = msg.optInt("status", 200),
                    headers = msg.optJSONObject("headers") ?: JSONObject(),
                    body = msg.optString("body", ""),
                )
            }

            "before_tool" -> {
                val bridgeId = msg.optString("id")
                val sessionId = msg.optString("session")
                val callId = msg.optString("call")
                val tool = msg.optString("tool")
                val args = msg.optJSONObject("args") ?: JSONObject()
                val verdict = runCatching {
                    toolHandler?.beforeTool(sessionId, callId, tool, args)
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
                val sessionId = msg.optString("session")
                val callId = msg.optString("call")
                val tool = msg.optString("tool", msg.optString("name"))
                val args = msg.optJSONObject("args") ?: JSONObject()
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
                    handler.executeTool(sessionId, callId, tool, args)
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

            "configured" -> {
                _events.tryEmit(
                    PiSessionEvent.Configured(
                        sessionId = msg.optString("session"),
                        provider = msg.optString("provider"),
                        model = msg.optString("model"),
                        supportsImages = msg.optBoolean("supportsImages", false),
                    ),
                )
            }

            "text_delta" -> _events.tryEmit(
                PiSessionEvent.TextDelta(msg.optString("session"), msg.optString("delta")),
            )

            "thinking_start" -> _events.tryEmit(PiSessionEvent.ThinkingStart(msg.optString("session")))
            "thinking_delta" -> _events.tryEmit(
                PiSessionEvent.ThinkingDelta(msg.optString("session"), msg.optString("delta")),
            )

            "thinking_end" -> _events.tryEmit(
                PiSessionEvent.ThinkingEnd(msg.optString("session"), msg.optString("text")),
            )

            "assistant_end" -> _events.tryEmit(
                PiSessionEvent.AssistantEnd(
                    sessionId = msg.optString("session"),
                    text = msg.optString("text"),
                    stopReason = msg.optString("stopReason"),
                    errorMessage = msg.optString("errorMessage").takeIf { it.isNotBlank() && it != "null" },
                ),
            )

            "tool_start" -> _events.tryEmit(
                PiSessionEvent.ToolStart(
                    sessionId = msg.optString("session"),
                    tool = msg.optString("tool"),
                    args = msg.optJSONObject("args") ?: JSONObject(),
                ),
            )

            "tool_end" -> _events.tryEmit(
                PiSessionEvent.ToolEnd(
                    sessionId = msg.optString("session"),
                    tool = msg.optString("tool"),
                    isError = msg.optBoolean("isError"),
                    output = msg.optString("output"),
                ),
            )

            "steered" -> _events.tryEmit(
                PiSessionEvent.Steered(msg.optString("session"), msg.optString("text")),
            )

            "transcript" -> {
                val arr = msg.optJSONArray("messages")
                if (arr != null) {
                    _events.tryEmit(PiSessionEvent.Transcript(msg.optString("session"), arr.toString()))
                }
            }

            "idle" -> _events.tryEmit(PiSessionEvent.Idle(msg.optString("session")))

            "error" -> {
                val sessionId = msg.optString("session")
                val message = msg.optString("message", "Pi 运行时错误")
                if (sessionId.isNotEmpty()) {
                    _events.tryEmit(PiSessionEvent.Error(sessionId, message))
                } else if (msg.optString("op") == "login") {
                    _authState.value = AuthUiState.Failed(message)
                }
            }
        }
    }

    private fun handleAuthEvent(event: JSONObject?) {
        if (event == null) return
        val current = _authState.value as? AuthUiState.InProgress
            ?: AuthUiState.InProgress(providerId = "openai-codex", statusMessage = "正在登录 ChatGPT Codex…")
        val type = event.optString("type")
        val url = event.optString("url").takeIf { it.isNotBlank() }
            ?: event.optString("verificationUri").takeIf { it.isNotBlank() }
        val userCode = event.optString("userCode").takeIf { it.isNotBlank() }
            ?: event.optString("code").takeIf { it.isNotBlank() }
        val instructions = event.optString("instructions").takeIf { it.isNotBlank() }
            ?: event.optString("message").takeIf { it.isNotBlank() }

        _authState.value = current.copy(
            statusMessage = instructions ?: when (type) {
                "auth_url", "open_url", "url" -> "请在浏览器中完成 ChatGPT 授权登录"
                "device_code" -> "请在打开的页面中输入下方授权码完成登录"
                else -> current.statusMessage
            },
            openUrl = url ?: current.openUrl,
            userCode = userCode ?: current.userCode,
        )
    }

    private fun encodeCustomProviders(list: List<CustomProviderConfig>): JSONArray {
        val arr = JSONArray()
        for (p in list) {
            val modelsArr = JSONArray()
            for (m in p.models) {
                val modelObj = JSONObject()
                    .put("id", m.id)
                    .put("contextWindow", m.contextWindow)
                    .put("images", m.supportsImages)
                    .put("reasoning", m.reasoning)
                    .put("api", m.api)
                if (m.efforts.isNotEmpty()) {
                    modelObj.put("efforts", JSONArray(m.efforts))
                }
                modelsArr.put(modelObj)
            }
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("baseUrl", p.baseUrl)
                    .put("images", p.defaultImages)
                    .put("models", modelsArr),
            )
        }
        return arr
    }

    private fun encodeCustomKeys(list: List<CustomProviderConfig>): JSONObject {
        val keys = secureStore.getAllProviderApiKeys(list.map { it.id })
        val obj = JSONObject()
        for ((k, v) in keys) obj.put(k, v)
        return obj
    }

    private fun getStableDeviceId(): String {
        val existing = secureStore.getString(KEY_DEVICE_UUID)
        if (!existing.isNullOrBlank() && UUID_REGEX.matches(existing)) {
            return existing
        }
        val created = java.util.UUID.randomUUID().toString()
        secureStore.putString(KEY_DEVICE_UUID, created)
        return created
    }

    companion object {
        private const val KEY_DEVICE_UUID = "zai_device_uuid"
        private val UUID_REGEX = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
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
}
