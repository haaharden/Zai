package com.self.zai

import android.graphics.Bitmap
import android.graphics.Rect
import com.self.zai.agent.AgentCoordinator
import com.self.zai.agent.AgentToolDispatcher
import com.self.zai.agent.AppReturnNavigator
import com.self.zai.agent.ModelSelectionManager
import com.self.zai.agent.RunChromeHost
import com.self.zai.assistant.AssistantContextBridge
import com.self.zai.assistant.CapturedScreenContext
import com.self.zai.assistant.VoiceSpeechController
import com.self.zai.data.CredentialVault
import com.self.zai.data.SessionRepository
import com.self.zai.mcp.McpToolClient
import com.self.zai.model.AppSettings
import com.self.zai.model.AuthUiState
import com.self.zai.model.ChatSession
import com.self.zai.model.CustomProviderConfig
import com.self.zai.model.McpServerConfig
import com.self.zai.model.McpToolSummary
import com.self.zai.model.MessageRole
import com.self.zai.model.ModelOption
import com.self.zai.model.ProviderQuota
import com.self.zai.model.ProviderStatus
import com.self.zai.model.TtsReadoutMode
import com.self.zai.phone.AppDirectory
import com.self.zai.phone.PhoneController
import com.self.zai.phone.ScreenSnapshot
import com.self.zai.phone.channel.PhoneDeviceChannel
import com.self.zai.phone.tools.AndroidSystemAssistantBackend
import com.self.zai.phone.tools.PhoneToolRegistry
import com.self.zai.phone.tools.SystemAssistantBackend
import com.self.zai.pi.AgentRuntime
import com.self.zai.pi.PiSessionEvent
import com.self.zai.pi.PiToolHandler
import com.self.zai.pi.ToolExecutionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantArchitectureTest {

    @Test
    fun nativeAssistantTools_registeredAndDoNotMarkPhoneScreenOperated() = runBlocking {
        val backend = FakeAssistantBackend()
        val registry = PhoneToolRegistry.default(backend)
        val specs = registry.toolsJson(includeVisionTools = true)
        val names = (0 until specs.length()).map { specs.getJSONObject(it).getString("name") }.toSet()

        val expectedNative = setOf(
            "get_device_status",
            "set_alarm",
            "set_timer",
            "calendar_query",
            "calendar_create",
            "flashlight",
            "media_control",
            "find_contact",
            "read_notifications",
        )
        for (toolName in expectedNative) {
            assertTrue("Missing native tool $toolName", toolName in names)
            assertFalse("Tool $toolName should not mark interactsWithScreen", registry.get(toolName)!!.interactsWithScreen)
        }

        val repo = InMemoryRepo()
        val nativeLabels = mutableListOf<String>()
        var screenSteps = 0
        val controller = PhoneController(
            repository = repo,
            channel = FakeChannel(),
            appDirectory = FakeApps(),
            toolRegistry = registry,
            onStepProgress = { _, _ -> screenSteps++ },
            onNativeToolActivity = { label -> nativeLabels += label },
        )

        // Execute native tools — must succeed without marking phone screen operated
        val alarmRes = controller.executeTool(
            "s1",
            "c1",
            "set_alarm",
            JSONObject().put("hour", 7).put("minute", 30).put("message", "起床"),
        )
        assertFalse(alarmRes.isError)
        assertEquals("alarm:7:30:起床", alarmRes.text)

        val torchRes = controller.executeTool("s1", "c2", "flashlight", JSONObject().put("on", true))
        assertFalse(torchRes.isError)
        assertEquals("torch:true", torchRes.text)

        val badAlarm = controller.executeTool("s1", "c3", "set_alarm", JSONObject().put("hour", 25).put("minute", 0))
        assertTrue(badAlarm.isError)

        assertFalse("Native tools must not mark phone screen operated", controller.didOperatePhoneThisRun())
        assertEquals(0, screenSteps)
        assertTrue(nativeLabels.any { it.contains("07:30") })

        // Now execute a screen-operating tool — MUST mark operated and fire screen step callback
        val readRes = controller.executeTool("s1", "c4", "read_screen", JSONObject())
        assertFalse(readRes.isError)
        assertTrue(controller.didOperatePhoneThisRun())
        assertEquals(1, screenSteps)
    }

    @Test
    fun timestampParsing_andSpeechSanitization_workCleanly() {
        assertNotNull(AndroidSystemAssistantBackend.parseLocalTimestamp("2026-11-26 09:30"))
        assertNotNull(AndroidSystemAssistantBackend.parseLocalTimestamp("2026-11-26T09:30:00"))
        assertNotNull(AndroidSystemAssistantBackend.parseLocalTimestamp("1795570200000"))
        assertNull(AndroidSystemAssistantBackend.parseLocalTimestamp("明天早上"))

        val cleaned = VoiceSpeechController.sanitizeForSpeech(
            "## 总结\n已为你设置 **明天 07:30** 闹钟，详情见 [时钟](https://example.com) 和 `set_alarm`。\n```json\n{\"ok\":true}\n```",
        )
        assertFalse(cleaned.contains("**"))
        assertFalse(cleaned.contains("##"))
        assertFalse(cleaned.contains("```"))
        assertTrue(cleaned.contains("明天 07:30"))
        assertTrue(cleaned.contains("时钟"))
    }

    @Test
    fun settingsBackwardCompat_loadsPreV020JsonWithAssistantDefaults() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val legacyJson = """{"themeMode":"DARK","activeModelId":"gpt-6.2-sol","userSystemPrompt":"简洁"}"""
        val decoded = json.decodeFromString<AppSettings>(legacyJson)
        assertTrue(decoded.assistantAutoListen)
        assertFalse(decoded.assistantAttachScreenDefault)
        assertEquals(TtsReadoutMode.HEADSET_ONLY, decoded.assistantTtsMode)
    }

    @Test
    fun coordinator_sendsScreenContextToModelWithoutPollutingVisibleUserBubble_andDismissesOverlayOnScreenAction() {
        val repo = InMemoryRepo()
        val vault = FakeVault()
        val runtime = CapturingRuntime()
        val phoneController = PhoneController(repo, FakeChannel(), FakeApps(), PhoneToolRegistry.default(FakeAssistantBackend()))
        val mcp = FakeMcp()
        val dispatcher = AgentToolDispatcher(repo, vault, phoneController, mcp)
        var overlayDismissed = 0

        lateinit var coordinator: AgentCoordinator
        val modelSelection = ModelSelectionManager(repo, vault, runtime) { coordinator.invalidateSessionConfig(it) }
        coordinator = AgentCoordinator(
            repository = repo,
            credentialVault = vault,
            runtime = runtime,
            phoneController = phoneController,
            toolDispatcher = dispatcher,
            mcpClient = mcp,
            modelSelection = modelSelection,
            returnNavigator = object : AppReturnNavigator {
                override suspend fun attemptBringToFront(isForeground: () -> Boolean) = false
            },
            chromeHost = object : RunChromeHost {
                override fun showOperatingOverlay(showCapsule: Boolean, showEdgeGlow: Boolean) {}
                override fun showCompletedBanner() {}
                override fun dismissOverlay() {}
                override fun updateForegroundStatus(status: String) {}
                override fun stopForegroundService() {}
                override fun postFinishedFallbackNotification(summary: String) {}
            },
            onDismissAssistantOverlay = { overlayDismissed++ },
            mainDispatcher = Dispatchers.Unconfined,
        )

        val res = coordinator.sendUserMessage(
            rawText = "帮我总结当前页面",
            extraContextForModel = "app: 微信\n[1] TextView \"明天下午三点开产品评审\"",
        )
        assertTrue(res.isSuccess)

        val visibleUserMsg = coordinator.currentSession().messages.first { it.role == MessageRole.USER }
        assertEquals("帮我总结当前页面", visibleUserMsg.text)
        assertTrue(runtime.lastPromptText.contains("[用户附带的当前屏幕内容]"))
        assertTrue(runtime.lastPromptText.contains("明天下午三点开产品评审"))
        assertTrue(runtime.lastPromptText.contains("帮我总结当前页面"))

        coordinator.onPhoneOperationStep()
        assertEquals(1, overlayDismissed)
    }

    @Test
    fun contextBridge_clearsStaleScreenContext_andDebouncesReinvokeToggle() {
        AssistantContextBridge.updateScreenContext(
            CapturedScreenContext(
                packageName = "com.tencent.mm",
                appLabel = "微信",
                summary = "聊天记录",
                capturedAtMs = 1_000L,
            ),
        )
        assertNotNull(AssistantContextBridge.currentValidScreenContext(nowMs = 5_000L))
        AssistantContextBridge.clearScreenContext()
        assertNull(AssistantContextBridge.currentValidScreenContext(nowMs = 5_000L))

        AssistantContextBridge.isOverlayVisible = true
        AssistantContextBridge.overlayOpenedAtMs = 10_000L
        assertFalse(AssistantContextBridge.shouldToggleDismissOnReinvoke(nowMs = 10_300L))
        assertTrue(AssistantContextBridge.shouldToggleDismissOnReinvoke(nowMs = 10_800L))
        AssistantContextBridge.isOverlayVisible = false
        assertFalse(AssistantContextBridge.shouldToggleDismissOnReinvoke(nowMs = 12_000L))
    }

    private class FakeAssistantBackend : SystemAssistantBackend {
        override suspend fun getDeviceStatus() = ToolExecutionResult("time:ok")
        override suspend fun setAlarm(hour: Int, minute: Int, message: String?, days: List<Int>) =
            ToolExecutionResult("alarm:$hour:$minute:${message.orEmpty()}")
        override suspend fun setTimer(seconds: Int, message: String?) = ToolExecutionResult("timer:$seconds")
        override suspend fun queryCalendar(daysAhead: Int) = ToolExecutionResult("cal:$daysAhead")
        override suspend fun createCalendarEvent(
            title: String,
            startTimeRaw: String,
            durationMinutes: Int,
            location: String?,
            description: String?,
        ) = ToolExecutionResult("created:$title")
        override suspend fun setFlashlight(on: Boolean) = ToolExecutionResult("torch:$on")
        override suspend fun controlMedia(action: String) = ToolExecutionResult("media:$action")
        override suspend fun findContacts(query: String) = ToolExecutionResult("contact:$query")
        override suspend fun readNotifications(query: String?, limit: Int) = ToolExecutionResult("notif:$limit")
    }

    private class InMemoryRepo : SessionRepository {
        private val _settings = MutableStateFlow(AppSettings())
        override val settings: StateFlow<AppSettings> = _settings
        private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
        override val sessions: StateFlow<List<ChatSession>> = _sessions
        override fun updateSettings(transform: (AppSettings) -> AppSettings) =
            transform(_settings.value).also { _settings.value = it }
        override fun upsertSession(session: ChatSession, flushImmediately: Boolean) {
            _sessions.value = listOf(session) + _sessions.value.filterNot { it.id == session.id }
        }
        override fun deleteSession(sessionId: String) {
            _sessions.value = _sessions.value.filterNot { it.id == sessionId }
        }
        override fun createNewSession() = ChatSession("s1", "新对话", 1L).also { upsertSession(it, true) }
        override fun flushNow() {}
    }

    private class FakeVault : CredentialVault {
        override fun putString(key: String, value: String) {}
        override fun getString(key: String): String? = null
        override fun remove(key: String) {}
        override fun putProviderApiKey(providerId: String, apiKey: String) {}
        override fun getProviderApiKey(providerId: String): String? = null
        override fun removeProviderApiKey(providerId: String) {}
        override fun getAllProviderApiKeys(providerIds: Collection<String>) = emptyMap<String, String>()
        override fun savePiCredentialsJson(json: String) {}
        override fun loadPiCredentialsJson(): String? = null
    }

    private class FakeMcp : McpToolClient {
        override suspend fun discoverTools(url: String, authToken: String?) = Result.success(emptyList<McpToolSummary>())
        override suspend fun callTool(
            server: McpServerConfig,
            authToken: String?,
            remoteToolName: String,
            arguments: JSONObject,
        ) = ToolExecutionResult("ok")
        override fun invalidateSession(serverUrl: String) {}
    }

    private class FakeApps : AppDirectory {
        override fun resolveAppLabel(packageName: String) = "设置"
        override fun findLaunchableApp(query: String) = "com.android.settings" to "设置"
        override fun queryLaunchableApps(query: String) = listOf("com.android.settings" to "设置")
    }

    private class FakeChannel : PhoneDeviceChannel {
        override val ownPackageName = "com.self.zai"
        override fun screenDimensions() = 1080 to 2400
        override suspend fun ensureReady() {}
        override suspend fun captureTree(width: Int, height: Int) = ScreenSnapshot(
            packageName = "com.android.settings",
            screenWidth = width,
            screenHeight = height,
            elements = emptyList(),
            source = "fake",
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

    private class CapturingRuntime : AgentRuntime {
        override val isReady = MutableStateFlow(true)
        override val piVersion = MutableStateFlow("1.6.0")
        override val hotUpdateTag = MutableStateFlow<String?>(null)
        override val providers: StateFlow<List<ProviderStatus>> = MutableStateFlow(
            listOf(
                ProviderStatus(
                    id = "openai-codex",
                    name = "ChatGPT",
                    credentialType = "oauth",
                    isCustom = false,
                    supportsOAuth = true,
                    models = listOf(
                        ModelOption("openai-codex", "ChatGPT", "gpt-6.2-sol", "GPT-6.2 Sol", 500_000, true, listOf("minimal", "high")),
                    ),
                ),
            ),
        )
        override val authState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
        override val providerQuotas: StateFlow<Map<String, ProviderQuota>> = MutableStateFlow(emptyMap())
        override val events: SharedFlow<PiSessionEvent> = MutableSharedFlow(extraBufferCapacity = 16)
        override var toolHandler: PiToolHandler? = null
        var lastPromptText: String = ""

        override fun start(customProviders: List<CustomProviderConfig>) {}
        override fun hotReload() {}
        override fun resetToBundledPi() {}
        override suspend fun awaitReady() {}
        override fun syncCustomProviders(customProviders: List<CustomProviderConfig>) {}
        override fun refreshProviderQuota(providerId: String, force: Boolean) {}
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
            compactionMode: String,
            compactionThresholdPercent: Int,
        ) {}
        override fun prompt(sessionId: String, text: String) { lastPromptText = text }
        override fun compactSession(sessionId: String) {}
        override fun abort(sessionId: String) {}
        override fun disposeSession(sessionId: String) {}
        override fun lookupModels(ids: List<String>, onResult: (Map<String, Triple<Int, Boolean, Boolean>>) -> Unit) {}
    }
}
