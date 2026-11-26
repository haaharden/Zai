package com.self.zai.pi

import com.self.zai.model.AuthUiState
import com.self.zai.model.CustomProviderConfig
import com.self.zai.model.PromptImage
import com.self.zai.model.ProviderQuota
import com.self.zai.model.ProviderStatus
import com.self.zai.model.SessionContextUsage
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
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
    data class ContextUsage(override val sessionId: String, val usage: SessionContextUsage) : PiSessionEvent
    data class ContextCompacted(
        override val sessionId: String,
        val blockId: String,
        val foldedCount: Int,
        val beforeTokens: Int,
        val afterTokens: Int,
        val percent: Int,
    ) : PiSessionEvent
    data class Idle(override val sessionId: String) : PiSessionEvent
    data class Error(override val sessionId: String, val message: String) : PiSessionEvent

    /**
     * Emitted when the underlying runtime restarts (e.g. WebView renderer killed by OS or hot-reloaded)
     * so coordinators can invalidate cached session configs and clean up any interrupted run state.
     */
    data class RuntimeReset(val reason: String) : PiSessionEvent {
        override val sessionId: String = ""
    }
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
 * Runtime contract implemented by [PiEngine] (and fake runtimes in JVM unit tests).
 */
interface AgentRuntime {
    val isReady: StateFlow<Boolean>
    val piVersion: StateFlow<String>
    val hotUpdateTag: StateFlow<String?>
    val providers: StateFlow<List<ProviderStatus>>
    val providerQuotas: StateFlow<Map<String, ProviderQuota>>
    val authState: StateFlow<AuthUiState>
    val events: SharedFlow<PiSessionEvent>

    var toolHandler: PiToolHandler?

    fun start(customProviders: List<CustomProviderConfig>)
    fun hotReload()
    fun resetToBundledPi()
    suspend fun awaitReady()
    fun syncCustomProviders(customProviders: List<CustomProviderConfig>)
    fun refreshProviderQuota(providerId: String = PiBridgeCodec.PRIMARY_OAUTH_PROVIDER_ID, force: Boolean = false)
    suspend fun consumeResetCredit(
        providerId: String = PiBridgeCodec.PRIMARY_OAUTH_PROVIDER_ID,
        creditId: String? = null,
    ): Result<String> = Result.failure(UnsupportedOperationException("当前环境不支持重置卡"))
    fun startChatGptLogin(method: String = "browser")
    fun cancelLogin()
    fun submitAuthPrompt(promptId: String, value: String)
    fun cancelAuthPrompt(promptId: String)
    fun dismissAuthError()
    fun logoutProvider(providerId: String)
    fun configureSession(
        sessionId: String,
        providerId: String,
        modelId: String,
        thinkingLevel: String,
        systemPrompt: String,
        toolSpecsJson: JSONArray,
        savedMessagesJson: String?,
        compactionMode: String = "acp",
        compactionThresholdPercent: Int = 75,
    )
    fun prompt(sessionId: String, text: String)
    fun prompt(sessionId: String, text: String, images: List<PromptImage>) {
        prompt(sessionId, text)
    }
    fun compactSession(sessionId: String)
    fun abort(sessionId: String)
    fun disposeSession(sessionId: String)
    fun lookupModels(ids: List<String>, onResult: (Map<String, Triple<Int, Boolean, Boolean>>) -> Unit)
}
