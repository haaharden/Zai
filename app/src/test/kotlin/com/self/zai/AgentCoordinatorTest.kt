package com.self.zai

import android.graphics.Bitmap
import android.graphics.Rect
import com.self.zai.agent.AgentCoordinator
import com.self.zai.agent.AgentToolDispatcher
import com.self.zai.agent.AppReturnNavigator
import com.self.zai.agent.ModelSelectionManager
import com.self.zai.agent.RunChromeHost
import com.self.zai.data.CredentialVault
import com.self.zai.data.SessionRepository
import com.self.zai.data.SettingsStore
import com.self.zai.mcp.McpToolClient
import com.self.zai.model.AppSettings
import com.self.zai.model.AuthUiState
import com.self.zai.model.ChatSession
import com.self.zai.model.CustomProviderConfig
import com.self.zai.model.McpServerConfig
import com.self.zai.model.McpToolSummary
import com.self.zai.model.MessageRole
import com.self.zai.model.ModelOption
import com.self.zai.model.ProviderStatus
import com.self.zai.phone.AppDirectory
import com.self.zai.phone.PhoneController
import com.self.zai.phone.ScreenSnapshot
import com.self.zai.phone.channel.PhoneDeviceChannel
import com.self.zai.pi.AgentRuntime
import com.self.zai.pi.PiSessionEvent
import com.self.zai.pi.PiToolHandler
import com.self.zai.pi.ToolExecutionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AgentCoordinatorTest {

    @Test
    fun settingsStore_persistsAtomicallyAndRecoversFromBackup() = runBlocking {
        val tempDir = Files.createTempDirectory("zai_store_test").toFile()
        try {
            val store1 = SettingsStore(tempDir, ioDispatcher = Dispatchers.Unconfined, sessionDebounceMs = 0L)
            store1.updateSettings { it.copy(activeProviderId = "openai-codex", activeModelId = "gpt-6.2-sol") }
            val created = store1.createNewSession().copy(title = "测试对话")
            store1.upsertSession(created, flushImmediately = true)
            // Trigger second write so .bak is populated
            store1.upsertSession(created.copy(title = "最新标题"), flushImmediately = true)

            // Simulate corrupted primary sessions file after abrupt kill
            File(tempDir, "zai_sessions.json").writeText("{corrupted_json", Charsets.UTF_8)

            val store2 = SettingsStore(tempDir, ioDispatcher = Dispatchers.Unconfined, sessionDebounceMs = 0L)
            assertEquals("gpt-6.2-sol", store2.settings.value.activeModelId)
            assertTrue("Should recover session from .bak file", store2.sessions.value.isNotEmpty())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun coordinator_handlesStreaming_runtimeResetRecovery_andAutoReturnFallback() = runBlocking {
        val repo = InMemorySessionRepository()
        val vault = InMemoryVault()
        val runtime = FakeAgentRuntime()
        val chrome = FakeChromeHost()
        val navigator = FakeReturnNavigator(shouldSucceed = false)

        val solModel = ModelOption(
            providerId = "openai-codex",
            providerName = "ChatGPT Codex",
            id = "gpt-6.2-sol",
            name = "GPT-6.2 Sol",
            contextWindow = 500_000,
            supportsImages = true,
            thinkingLevels = listOf("minimal", "low", "medium", "high", "xhigh", "max"),
        )
        runtime.setProviders(
            listOf(
                ProviderStatus(
                    id = "openai-codex",
                    name = "ChatGPT Codex",
                    credentialType = "oauth",
                    isCustom = false,
                    supportsOAuth = true,
                    models = listOf(solModel),
                ),
            ),
        )

        val phoneController = PhoneController(
            repository = repo,
            channel = StubDeviceChannel(),
            appDirectory = StubAppDirectory(),
        )
        val fakeMcp = StubMcpClient()
        val dispatcher = AgentToolDispatcher(
            repository = repo,
            credentialVault = vault,
            phoneController = phoneController,
            mcpClient = fakeMcp,
        )

        lateinit var coordinator: AgentCoordinator
        val modelSelection = ModelSelectionManager(
            repository = repo,
            credentialVault = vault,
            runtime = runtime,
            onSessionConfigInvalidated = { coordinator.invalidateSessionConfig(it) },
        )
        coordinator = AgentCoordinator(
            repository = repo,
            credentialVault = vault,
            runtime = runtime,
            phoneController = phoneController,
            toolDispatcher = dispatcher,
            mcpClient = fakeMcp,
            modelSelection = modelSelection,
            returnNavigator = navigator,
            chromeHost = chrome,
            mainDispatcher = Dispatchers.Unconfined,
        )

        // Default model auto-selected & thinking clamped from "off" -> "minimal"
        assertEquals("gpt-6.2-sol", coordinator.defaultModelOption()?.id)
        assertEquals("minimal", coordinator.defaultThinkingLevel())

        // Send user prompt and verify ordered streaming
        val sendRes = coordinator.sendUserMessage("帮我打开设置")
        assertTrue(sendRes.isSuccess)
        assertTrue(coordinator.isRunning.value)
        assertEquals(1, runtime.configureCount)

        val sid = coordinator.activeSessionId.value
        runtime.emit(PiSessionEvent.TextDelta(sid, "好的，"))
        runtime.emit(PiSessionEvent.TextDelta(sid, "已打开设置。"))

        // Simulate WebView renderer crash mid-run: coordinator must auto-reset running state & config cache
        runtime.emit(PiSessionEvent.RuntimeReset("WebView 崩溃"))
        assertFalse(coordinator.isRunning.value)
        assertTrue(chrome.dismissedCount >= 1)

        // Next prompt cleanly re-configures session (configureCount becomes 2)
        assertTrue(coordinator.sendUserMessage("再查一下存储空间").isSuccess)
        assertEquals(2, runtime.configureCount)

        // Simulate phone operation while user is in another app, then Idle with OEM blocking return
        coordinator.isAppInForeground = false
        phoneController.executeTool(sid, "call_1", "read_screen", JSONObject())
        runtime.emit(PiSessionEvent.AssistantEnd(sid, "剩余 128 GB", "stop", null))
        runtime.emit(PiSessionEvent.Idle(sid))
        delay(50L)

        assertFalse(coordinator.isRunning.value)
        assertTrue(navigator.attempted)
        assertTrue(chrome.completedBannerShown)
        assertEquals("剩余 128 GB", chrome.lastFallbackSummary)

        val lastReply = coordinator.currentSession().messages.last { it.role == MessageRole.ASSISTANT }
        assertEquals("剩余 128 GB", lastReply.text)
    }

    @Test
    fun coordinator_midRunFollowUp_preservesOrderAndOnDemandForegroundService() = runBlocking {
        val repo = InMemorySessionRepository()
        val vault = InMemoryVault()
        val runtime = FakeAgentRuntime()
        val chrome = FakeChromeHost()
        val navigator = FakeReturnNavigator(shouldSucceed = true)

        val solModel = ModelOption(
            providerId = "openai-codex",
            providerName = "ChatGPT Codex",
            id = "gpt-6.2-sol",
            name = "GPT-6.2 Sol",
            contextWindow = 500_000,
            supportsImages = true,
            thinkingLevels = listOf("minimal", "high"),
        )
        runtime.setProviders(
            listOf(
                ProviderStatus(
                    id = "openai-codex",
                    name = "ChatGPT Codex",
                    credentialType = "oauth",
                    isCustom = false,
                    supportsOAuth = true,
                    models = listOf(solModel),
                ),
            ),
        )

        val phoneController = PhoneController(repo, StubDeviceChannel(), StubAppDirectory())
        val fakeMcp = StubMcpClient()
        val dispatcher = AgentToolDispatcher(repo, vault, phoneController, fakeMcp)
        lateinit var coordinator: AgentCoordinator
        val modelSelection = ModelSelectionManager(repo, vault, runtime) { coordinator.invalidateSessionConfig(it) }
        coordinator = AgentCoordinator(
            repository = repo,
            credentialVault = vault,
            runtime = runtime,
            phoneController = phoneController,
            toolDispatcher = dispatcher,
            mcpClient = fakeMcp,
            modelSelection = modelSelection,
            returnNavigator = navigator,
            chromeHost = chrome,
            mainDispatcher = Dispatchers.Unconfined,
        )

        // Foreground plain chat must NOT spin up foreground service
        assertTrue(coordinator.sendUserMessage("第一句提问").isSuccess)
        assertEquals(0, chrome.fgsStartCount)

        val sid = coordinator.activeSessionId.value
        runtime.emit(PiSessionEvent.TextDelta(sid, "第一段回答"))

        // User steers mid-run -> new assistant turn must appear BELOW the follow-up user message
        assertTrue(coordinator.sendUserMessage("补充一下要求").isSuccess)
        runtime.emit(PiSessionEvent.TextDelta(sid, "收到，按补充要求回答"))
        runtime.emit(PiSessionEvent.Idle(sid))

        val msgs = coordinator.currentSession().messages
        assertEquals(4, msgs.size)
        assertEquals(MessageRole.USER, msgs[0].role)
        assertEquals("第一句提问", msgs[0].text)
        assertEquals(MessageRole.ASSISTANT, msgs[1].role)
        assertEquals("第一段回答", msgs[1].text)
        assertEquals(MessageRole.USER, msgs[2].role)
        assertEquals("补充一下要求", msgs[2].text)
        assertEquals(MessageRole.ASSISTANT, msgs[3].role)
        assertEquals("收到，按补充要求回答", msgs[3].text)
        assertEquals(0, chrome.fgsStartCount)
        assertEquals(0, chrome.fgsStopCount)

        // Start a run, then background the app -> FGS must start and later stop on Idle
        assertTrue(coordinator.sendUserMessage("后台长任务").isSuccess)
        assertEquals(0, chrome.fgsStartCount)
        coordinator.isAppInForeground = false
        assertEquals(1, chrome.fgsStartCount)
        runtime.emit(PiSessionEvent.Idle(sid))
        assertEquals(1, chrome.fgsStopCount)
    }

    private class InMemorySessionRepository : SessionRepository {
        private val _settings = MutableStateFlow(AppSettings())
        override val settings: StateFlow<AppSettings> = _settings
        private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
        override val sessions: StateFlow<List<ChatSession>> = _sessions
        override fun updateSettings(transform: (AppSettings) -> AppSettings): AppSettings =
            transform(_settings.value).also { _settings.value = it }
        override fun upsertSession(session: ChatSession, flushImmediately: Boolean) {
            _sessions.value = (listOf(session) + _sessions.value.filterNot { it.id == session.id })
                .sortedByDescending { it.updatedAtMs }
        }
        override fun deleteSession(sessionId: String) {
            _sessions.value = _sessions.value.filterNot { it.id == sessionId }
        }
        override fun createNewSession(): ChatSession =
            ChatSession(id = "s_1", title = "新对话", updatedAtMs = System.currentTimeMillis()).also {
                upsertSession(it, true)
            }
        override fun flushNow() {}
    }

    private class InMemoryVault : CredentialVault {
        private val map = mutableMapOf<String, String>()
        override fun putString(key: String, value: String) { map[key] = value }
        override fun getString(key: String): String? = map[key]
        override fun remove(key: String) { map.remove(key) }
        override fun putProviderApiKey(providerId: String, apiKey: String) { map["k_$providerId"] = apiKey }
        override fun getProviderApiKey(providerId: String): String? = map["k_$providerId"]
        override fun removeProviderApiKey(providerId: String) { map.remove("k_$providerId") }
        override fun getAllProviderApiKeys(providerIds: Collection<String>): Map<String, String> =
            providerIds.mapNotNull { id -> map["k_$id"]?.let { id to it } }.toMap()
        override fun savePiCredentialsJson(json: String) { map["creds"] = json }
        override fun loadPiCredentialsJson(): String? = map["creds"]
    }

    private class FakeAgentRuntime : AgentRuntime {
        override val isReady = MutableStateFlow(true)
        override val piVersion = MutableStateFlow("1.6.0")
        override val hotUpdateTag = MutableStateFlow<String?>(null)
        private val _providers = MutableStateFlow<List<ProviderStatus>>(emptyList())
        override val providers: StateFlow<List<ProviderStatus>> = _providers
        override val authState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
        private val _events = MutableSharedFlow<PiSessionEvent>(extraBufferCapacity = 64)
        override val events: SharedFlow<PiSessionEvent> = _events
        override var toolHandler: PiToolHandler? = null

        var configureCount = 0

        fun setProviders(list: List<ProviderStatus>) { _providers.value = list }
        fun emit(ev: PiSessionEvent) { _events.tryEmit(ev) }

        override fun start(customProviders: List<CustomProviderConfig>) {}
        override fun hotReload() {}
        override fun resetToBundledPi() {}
        override suspend fun awaitReady() {}
        override fun syncCustomProviders(customProviders: List<CustomProviderConfig>) {}
        override fun startChatGptLogin(method: String) {}
        override fun cancelLogin() {}
        override fun submitAuthPrompt(promptId: String, value: String) {}
        override fun cancelAuthPrompt(promptId: String) {}
        override fun dismissAuthError() {}
        override fun logoutProvider(providerId: String) {}
        override fun configureSession(
            sessionId: String,
            providerId: String,
            modelId: String,
            thinkingLevel: String,
            systemPrompt: String,
            toolSpecsJson: JSONArray,
            savedMessagesJson: String?,
        ) {
            configureCount++
        }
        override fun prompt(sessionId: String, text: String) {}
        override fun abort(sessionId: String) {}
        override fun disposeSession(sessionId: String) {}
        override fun lookupModels(ids: List<String>, onResult: (Map<String, Triple<Int, Boolean, Boolean>>) -> Unit) {
            onResult(emptyMap())
        }
    }

    private class FakeChromeHost : RunChromeHost {
        var dismissedCount = 0
        var completedBannerShown = false
        var lastFallbackSummary = ""
        var fgsStartCount = 0
        var fgsStopCount = 0
        override fun showOperatingOverlay(showCapsule: Boolean, showEdgeGlow: Boolean) {}
        override fun showCompletedBanner() { completedBannerShown = true }
        override fun dismissOverlay() { dismissedCount++ }
        override fun updateForegroundStatus(status: String) { fgsStartCount++ }
        override fun stopForegroundService() { fgsStopCount++ }
        override fun postFinishedFallbackNotification(summary: String) { lastFallbackSummary = summary }
    }

    private class FakeReturnNavigator(private val shouldSucceed: Boolean) : AppReturnNavigator {
        var attempted = false
        override suspend fun attemptBringToFront(isForeground: () -> Boolean): Boolean {
            attempted = true
            return shouldSucceed
        }
    }

    private class StubAppDirectory : AppDirectory {
        override fun resolveAppLabel(packageName: String) = "设置"
        override fun findLaunchableApp(query: String) = "com.android.settings" to "设置"
        override fun queryLaunchableApps(query: String) = listOf("com.android.settings" to "设置")
    }

    private class StubMcpClient : McpToolClient {
        override suspend fun discoverTools(url: String, authToken: String?): Result<List<McpToolSummary>> =
            Result.success(emptyList())
        override suspend fun callTool(
            server: McpServerConfig,
            authToken: String?,
            remoteToolName: String,
            arguments: JSONObject,
        ): ToolExecutionResult = ToolExecutionResult("ok")
        override fun invalidateSession(serverUrl: String) {}
    }

    private class StubDeviceChannel : PhoneDeviceChannel {
        override val ownPackageName = "com.self.zai"
        override fun screenDimensions() = 1080 to 2400
        override suspend fun ensureReady() {}
        override suspend fun captureTree(width: Int, height: Int) = ScreenSnapshot(
            packageName = "com.android.settings",
            screenWidth = width,
            screenHeight = height,
            elements = emptyList(),
            source = "stub",
        )
        override suspend fun captureBitmap(): Bitmap? = null
        override suspend fun tap(x: Int, y: Int) = true
        override suspend fun longPress(x: Int, y: Int, durationMs: Int) = true
        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) = true
        override suspend fun pressKey(key: String) = true
        override suspend fun inputText(text: String, targetBounds: Rect?) = true
        override suspend fun currentForegroundPackage() = "com.android.settings"
        override suspend fun launchPackage(packageName: String) = true
    }
}
