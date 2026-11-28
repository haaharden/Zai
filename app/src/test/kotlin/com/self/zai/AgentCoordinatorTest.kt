package com.self.zai

import android.graphics.Bitmap
import android.graphics.Rect
import com.self.zai.agent.AgentCoordinator
import com.self.zai.agent.AgentToolDispatcher
import com.self.zai.agent.AppReturnNavigator
import com.self.zai.agent.CapabilitiesManager
import com.self.zai.agent.ModelSelectionManager
import com.self.zai.agent.RunChromeHost
import com.self.zai.agent.RunEventBus
import com.self.zai.web.WebPageContent
import com.self.zai.web.WebSearchClient
import com.self.zai.web.WebSearchResponse
import com.self.zai.data.CredentialVault
import com.self.zai.data.SessionRepository
import com.self.zai.data.SettingsStore
import com.self.zai.mcp.McpToolClient
import com.self.zai.model.AppSettings
import com.self.zai.model.AttachmentKind
import com.self.zai.model.AuthUiState
import com.self.zai.model.ChatAttachment
import com.self.zai.model.ChatSession
import com.self.zai.model.CompactionMode
import com.self.zai.model.CustomProviderConfig
import com.self.zai.model.McpServerConfig
import com.self.zai.model.McpToolSummary
import com.self.zai.model.MessageRole
import com.self.zai.model.ModelOption
import com.self.zai.model.PendingAttachment
import com.self.zai.model.PromptImage
import com.self.zai.model.ProviderQuota
import com.self.zai.model.ProviderStatus
import com.self.zai.model.SessionContextUsage
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

            // Simulate corrupted primary per-session file after abrupt kill
            File(tempDir, "zai_sessions/${created.id}.json").writeText("{corrupted_json", Charsets.UTF_8)

            val store2 = SettingsStore(tempDir, ioDispatcher = Dispatchers.Unconfined, sessionDebounceMs = 0L)
            assertEquals("gpt-6.2-sol", store2.settings.value.activeModelId)
            assertTrue("Should recover session from .bak file", store2.sessions.value.isNotEmpty())
            assertEquals("测试对话", store2.sessions.value.first().title)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun settingsStore_persistsOver40SessionsWithoutEviction_andMigratesLegacyFile() = runBlocking {
        val tempDir = Files.createTempDirectory("zai_store_unlimited_test").toFile()
        try {
            // Seed a legacy monolithic zai_sessions.json with 5 conversations
            val legacySessions = (1..5).map { i ->
                ChatSession(id = "legacy_$i", title = "旧对话 $i", updatedAtMs = 1_000L + i)
            }
            val json = kotlinx.serialization.json.Json { encodeDefaults = true }
            File(tempDir, "zai_sessions.json").writeText(
                json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ChatSession.serializer()), legacySessions),
                Charsets.UTF_8,
            )

            val store1 = SettingsStore(tempDir, ioDispatcher = Dispatchers.Unconfined, sessionDebounceMs = 0L)
            assertEquals(5, store1.sessions.value.size)
            assertFalse("Legacy single file should be removed after migration", File(tempDir, "zai_sessions.json").exists())

            // Save 41 additional distinct sessions (total 46 > old 40 cap)
            for (i in 1..41) {
                store1.upsertSession(
                    ChatSession(id = "extra_$i", title = "对话 #$i", updatedAtMs = 10_000L + i),
                    flushImmediately = true,
                )
            }
            assertEquals(46, store1.sessions.value.size)
            assertTrue(store1.storageBytes.value > 0L)

            // Reload from disk and verify ZERO sessions were evicted
            val store2 = SettingsStore(tempDir, ioDispatcher = Dispatchers.Unconfined, sessionDebounceMs = 0L)
            assertEquals(46, store2.sessions.value.size)
            assertTrue(store2.sessions.value.any { it.id == "legacy_1" })
            assertTrue(store2.sessions.value.any { it.id == "extra_41" })
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun coordinator_stopActiveRun_abortsRunningSessionA_whenViewingSessionB() = runBlocking {
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

        val bus = RunEventBus()
        val phoneController = PhoneController(repo, StubDeviceChannel(), StubAppDirectory())
        val fakeMcp = StubMcpClient()
        val dispatcher = AgentToolDispatcher(repo, vault, phoneController, fakeMcp)
        val modelSelection = ModelSelectionManager(repo, vault, runtime) { bus.invalidateSessionConfig(it) }
        val coordinator = AgentCoordinator(
            repository = repo,
            runtime = runtime,
            phoneController = phoneController,
            toolDispatcher = dispatcher,
            modelSelection = modelSelection,
            returnNavigator = navigator,
            chromeHost = chrome,
            runEventBus = bus,
            mainDispatcher = Dispatchers.Unconfined,
        )

        // Start task in Session A
        val sessionAId = coordinator.activeSessionId.value
        assertTrue(coordinator.sendUserMessage("帮我订机票").isSuccess)
        assertEquals(sessionAId, coordinator.runningSessionId.value)

        // Capsule label reflects running session title + target app
        coordinator.onPhoneOperationStep("日历")
        assertTrue(chrome.lastOperatingStatusLabel.contains("帮我订机票"))
        assertTrue(chrome.lastOperatingStatusLabel.contains("日历"))

        // Switch UI to Session B while Session A is still running
        val sessionB = coordinator.startNewSession()
        assertEquals(sessionB.id, coordinator.activeSessionId.value)
        assertEquals(sessionAId, coordinator.runningSessionId.value)

        // Sending in Session B must not accidentally steer Session A
        assertTrue(coordinator.sendUserMessage("随便聊聊").isFailure)
        assertTrue(repo.sessions.value.first { it.id == sessionB.id }.messages.isEmpty())

        // Focus helper jumps back to Session A cleanly
        coordinator.focusRunningSession()
        assertEquals(sessionAId, coordinator.activeSessionId.value)

        // Switch back to Session B, then tap Stop -> MUST abort Session A, NEVER Session B!
        coordinator.selectSession(sessionB.id)
        coordinator.stopActiveRun()

        assertEquals(listOf(sessionAId), runtime.abortedSessionIds)
        assertFalse(coordinator.isRunning.value)
        assertEquals(null, coordinator.runningSessionId.value)
        val sessionAAfter = repo.sessions.value.first { it.id == sessionAId }
        assertTrue(sessionAAfter.messages.last().text.contains("已停止"))
        assertTrue(repo.sessions.value.first { it.id == sessionB.id }.messages.isEmpty())
    }

    @Test
    fun modelSelection_filtersSubscriptionModelsAndReassignsDefaultWhenUnchecked() = runBlocking {
        val repo = InMemorySessionRepository()
        val vault = InMemoryVault()
        val runtime = FakeAgentRuntime()
        val m1 = ModelOption("openai-codex", "ChatGPT Codex", "gpt-5.4", "GPT-5.4", 400_000, true, listOf("low", "high"))
        val m2 = ModelOption("openai-codex", "ChatGPT Codex", "gpt-5.3-codex", "GPT-5.3 Codex", 400_000, true, listOf("medium", "high"))
        val m3 = ModelOption("openai-codex", "ChatGPT Codex", "gpt-5-mini", "GPT-5 Mini", 200_000, true, listOf("low"))
        runtime.setProviders(
            listOf(
                ProviderStatus(
                    id = "openai-codex",
                    name = "ChatGPT Codex",
                    credentialType = "oauth",
                    isCustom = false,
                    supportsOAuth = true,
                    models = listOf(m1, m2, m3),
                ),
            ),
        )
        val mgr = ModelSelectionManager(repo, vault, runtime) {}
        mgr.ensureValidDefaultModel(runtime.providers.value)
        assertEquals(3, mgr.visibleProviders().first().models.size)
        assertEquals("gpt-5.4", mgr.defaultModelOption()?.id)

        // Enable only gpt-5.3-codex -> default automatically moves from unchecked gpt-5.4 to gpt-5.3-codex
        mgr.updateSubscriptionEnabledModels("openai-codex", listOf("gpt-5.3-codex"))
        assertEquals(listOf("gpt-5.3-codex"), mgr.visibleProviders().first().models.map { it.id })
        assertEquals("gpt-5.3-codex", mgr.defaultModelOption()?.id)
        // Raw provider list in runtime remains intact so user can re-enable hidden models anytime
        assertEquals(3, runtime.providers.value.first().models.size)
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

        val bus = RunEventBus()
        val modelSelection = ModelSelectionManager(
            repository = repo,
            credentialVault = vault,
            runtime = runtime,
            onSessionConfigInvalidated = { bus.invalidateSessionConfig(it) },
        )
        val coordinator = AgentCoordinator(
            repository = repo,
            runtime = runtime,
            phoneController = phoneController,
            toolDispatcher = dispatcher,
            modelSelection = modelSelection,
            returnNavigator = navigator,
            chromeHost = chrome,
            runEventBus = bus,
            mainDispatcher = Dispatchers.Unconfined,
        )

        // Default model auto-selected & thinking clamped from "off" -> "minimal"
        assertEquals("gpt-6.2-sol", modelSelection.defaultModelOption()?.id)
        assertEquals("minimal", modelSelection.defaultThinkingLevel())

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

        val bus = RunEventBus()
        val phoneController = PhoneController(repo, StubDeviceChannel(), StubAppDirectory())
        val fakeMcp = StubMcpClient()
        val dispatcher = AgentToolDispatcher(repo, vault, phoneController, fakeMcp)
        val modelSelection = ModelSelectionManager(repo, vault, runtime) { bus.invalidateSessionConfig(it) }
        val coordinator = AgentCoordinator(
            repository = repo,
            runtime = runtime,
            phoneController = phoneController,
            toolDispatcher = dispatcher,
            modelSelection = modelSelection,
            returnNavigator = navigator,
            chromeHost = chrome,
            runEventBus = bus,
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

        // Compaction settings update invalidates config & tracks ContextUsage events
        val capabilities = CapabilitiesManager(repo, vault, fakeMcp, StubWebSearchClient()) { bus.invalidateSessionConfig(null) }
        capabilities.updateCompactionSettings(CompactionMode.AUTO, 65)
        assertEquals(CompactionMode.AUTO, repo.settings.value.compactionMode)
        assertEquals(65, repo.settings.value.compactionThresholdPercent)

        runtime.emit(
            PiSessionEvent.ContextUsage(
                sessionId = sid,
                usage = SessionContextUsage(
                    estimatedTokens = 64_000,
                    contextWindow = 500_000,
                    percent = 13,
                    archivedBlocks = 1,
                    mode = CompactionMode.AUTO,
                    thresholdPercent = 65,
                ),
            ),
        )
        assertEquals(13, coordinator.sessionContextUsages.value[sid]?.percent)
        assertEquals(1, coordinator.sessionContextUsages.value[sid]?.archivedBlocks)

        // Manual compaction delegates cleanly to runtime once enough messages exist
        assertTrue(coordinator.compactActiveSession().isSuccess)
        assertEquals(1, runtime.compactCount)
        assertEquals("auto", runtime.lastCompactionMode)
        assertEquals(65, runtime.lastCompactionThreshold)
    }

    private class InMemorySessionRepository : SessionRepository {
        private var counter = 0
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
            ChatSession(id = "s_${++counter}", title = "新对话", updatedAtMs = System.currentTimeMillis()).also {
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

    @Test
    fun coordinator_sendUserMessage_forwardsImagesAndFiles_andGuardsTextOnlyModel() = runBlocking {
        val repo = InMemorySessionRepository()
        val vault = InMemoryVault()
        val runtime = FakeAgentRuntime()
        val chrome = FakeChromeHost()
        val navigator = FakeReturnNavigator(shouldSucceed = true)

        val visionModel = ModelOption(
            providerId = "custom_p",
            providerName = "Custom",
            id = "vision-1",
            name = "Vision 1",
            contextWindow = 128_000,
            supportsImages = true,
            thinkingLevels = listOf("off"),
        )
        val textOnlyModel = ModelOption(
            providerId = "custom_p",
            providerName = "Custom",
            id = "text-1",
            name = "Text Only",
            contextWindow = 128_000,
            supportsImages = false,
            thinkingLevels = listOf("off"),
        )
        runtime.setProviders(
            listOf(
                ProviderStatus(
                    id = "custom_p",
                    name = "Custom",
                    credentialType = "api_key",
                    isCustom = true,
                    supportsOAuth = false,
                    models = listOf(textOnlyModel, visionModel),
                ),
            ),
        )

        val bus = RunEventBus()
        val phoneController = PhoneController(repo, StubDeviceChannel(), StubAppDirectory())
        val dispatcher = AgentToolDispatcher(repo, vault, phoneController, StubMcpClient())
        val modelSelection = ModelSelectionManager(repo, vault, runtime) { bus.invalidateSessionConfig(it) }
        val coordinator = AgentCoordinator(
            repository = repo,
            runtime = runtime,
            phoneController = phoneController,
            toolDispatcher = dispatcher,
            modelSelection = modelSelection,
            returnNavigator = navigator,
            chromeHost = chrome,
            runEventBus = bus,
            mainDispatcher = Dispatchers.Unconfined,
        )

        val photo = PendingAttachment(
            attachment = ChatAttachment(
                id = "att_img",
                kind = AttachmentKind.IMAGE,
                displayName = "receipt.jpg",
                mimeType = "image/jpeg",
                sizeBytes = 512,
                localPath = "/tmp/receipt.jpg",
            ),
            promptImages = listOf(PromptImage("ZmFrZQ==", "image/jpeg")),
        )
        val txtFile = PendingAttachment(
            attachment = ChatAttachment(
                id = "att_txt",
                kind = AttachmentKind.FILE,
                displayName = "log.txt",
                mimeType = "text/plain",
                sizeBytes = 120,
            ),
            extractedText = "ERROR line 42",
        )

        // 1. Text-only model rejects image attachments cleanly without mutating session messages
        modelSelection.selectSessionModel(coordinator.currentSession(), "custom_p", "text-1")
        val blocked = coordinator.sendUserMessage("", attachments = listOf(photo))
        assertTrue(blocked.isFailure)
        assertTrue(coordinator.currentSession().messages.isEmpty())

        // 2. Text-only model DOES allow pure text file attachments
        val textFileOk = coordinator.sendUserMessage("看看这个报错", attachments = listOf(txtFile))
        assertTrue(textFileOk.isSuccess)
        assertTrue(runtime.lastPromptText.contains("[附件文件：log.txt"))
        assertTrue(runtime.lastPromptText.contains("ERROR line 42"))
        assertTrue(runtime.lastPromptImages.isEmpty())
        runtime.emit(PiSessionEvent.Idle(coordinator.currentSession().id))

        // 3. Vision model forwards image payload even when user text is empty
        val visionSession = coordinator.startNewSession()
        modelSelection.selectSessionModel(coordinator.currentSession(), "custom_p", "vision-1")
        val imgOk = coordinator.sendUserMessage("", attachments = listOf(photo))
        assertTrue(imgOk.isSuccess)
        assertEquals(1, runtime.lastPromptImages.size)
        assertEquals("ZmFrZQ==", runtime.lastPromptImages.first().dataBase64)
        val savedSession = repo.sessions.value.first { it.id == visionSession.id }
        assertEquals("图片分析", savedSession.title)
        assertEquals(1, savedSession.messages.first().attachments.size)
        assertEquals("receipt.jpg", savedSession.messages.first().attachments.first().displayName)
    }

    @Test
    fun coordinator_startNewSession_reusesCurrentOrExistingEmptySessionInsteadOfDuplicating() = runBlocking {
        val repo = InMemorySessionRepository()
        val vault = InMemoryVault()
        val runtime = FakeAgentRuntime()
        val model = ModelOption(
            providerId = "custom_p",
            providerName = "Custom",
            id = "m1",
            name = "M1",
            contextWindow = 128_000,
            supportsImages = true,
            thinkingLevels = listOf("off"),
        )
        runtime.setProviders(
            listOf(
                ProviderStatus(
                    id = "custom_p",
                    name = "Custom",
                    credentialType = "api_key",
                    isCustom = true,
                    supportsOAuth = false,
                    models = listOf(model),
                ),
            ),
        )
        val bus = RunEventBus()
        val phoneController = PhoneController(repo, StubDeviceChannel(), StubAppDirectory())
        val dispatcher = AgentToolDispatcher(repo, vault, phoneController, StubMcpClient())
        val modelSelection = ModelSelectionManager(repo, vault, runtime) { bus.invalidateSessionConfig(it) }
        val coordinator = AgentCoordinator(
            repository = repo,
            runtime = runtime,
            phoneController = phoneController,
            toolDispatcher = dispatcher,
            modelSelection = modelSelection,
            returnNavigator = FakeReturnNavigator(true),
            chromeHost = FakeChromeHost(),
            runEventBus = bus,
            mainDispatcher = Dispatchers.Unconfined,
        )

        // Calling startNewSession while already on an empty session is a no-op
        val firstEmpty = coordinator.currentSession()
        val repeated = coordinator.startNewSession()
        assertEquals(firstEmpty.id, repeated.id)
        assertEquals(1, repo.sessions.value.size)

        // After sending a message, startNewSession creates a second session
        assertTrue(coordinator.sendUserMessage("你好").isSuccess)
        runtime.emit(PiSessionEvent.Idle(firstEmpty.id))
        val secondEmpty = coordinator.startNewSession()
        assertTrue(secondEmpty.id != firstEmpty.id)
        assertEquals(2, repo.sessions.value.size)

        // Switching back to the non-empty first session and tapping "+" reuses the existing empty session
        coordinator.selectSession(firstEmpty.id)
        val reused = coordinator.startNewSession()
        assertEquals(secondEmpty.id, reused.id)
        assertEquals(2, repo.sessions.value.size)
    }

    private class FakeAgentRuntime : AgentRuntime {
        override val isReady = MutableStateFlow(true)
        override val piVersion = MutableStateFlow("1.6.0")
        override val hotUpdateTag = MutableStateFlow<String?>(null)
        private val _providers = MutableStateFlow<List<ProviderStatus>>(emptyList())
        override val providers: StateFlow<List<ProviderStatus>> = _providers
        override val providerQuotas: StateFlow<Map<String, ProviderQuota>> = MutableStateFlow(emptyMap())
        override val authState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
        private val _events = MutableSharedFlow<PiSessionEvent>(extraBufferCapacity = 64)
        override val events: SharedFlow<PiSessionEvent> = _events
        override var toolHandler: PiToolHandler? = null

        var configureCount = 0
        var compactCount = 0
        var lastCompactionMode = ""
        var lastCompactionThreshold = 0
        var lastPromptText = ""
        var lastPromptImages: List<PromptImage> = emptyList()
        val abortedSessionIds = mutableListOf<String>()

        fun setProviders(list: List<ProviderStatus>) { _providers.value = list }
        fun emit(ev: PiSessionEvent) { _events.tryEmit(ev) }

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
        ) {
            configureCount++
            lastCompactionMode = compactionMode
            lastCompactionThreshold = compactionThresholdPercent
        }
        override fun prompt(sessionId: String, text: String) {
            lastPromptText = text
            lastPromptImages = emptyList()
        }
        override fun prompt(sessionId: String, text: String, images: List<PromptImage>) {
            lastPromptText = text
            lastPromptImages = images
        }
        override fun compactSession(sessionId: String) { compactCount++ }
        override fun abort(sessionId: String) { abortedSessionIds += sessionId }
        override fun disposeSession(sessionId: String) {}
        override fun lookupModels(ids: List<String>, onResult: (Map<String, Triple<Int, Boolean, Boolean>>) -> Unit) {
            onResult(emptyMap())
        }
    }

    private class FakeChromeHost : RunChromeHost {
        var dismissedCount = 0
        var completedBannerShown = false
        var lastOperatingStatusLabel = ""
        var lastCompletedStatusLabel = ""
        var lastFallbackSummary = ""
        var fgsStartCount = 0
        var fgsStopCount = 0
        override fun showOperatingOverlay(showCapsule: Boolean, showEdgeGlow: Boolean, statusLabel: String) {
            lastOperatingStatusLabel = statusLabel
        }
        override fun showCompletedBanner(statusLabel: String) {
            completedBannerShown = true
            lastCompletedStatusLabel = statusLabel
        }
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

    private class StubWebSearchClient : WebSearchClient {
        override suspend fun search(
            query: String,
            maxResults: Int,
            priority: List<com.self.zai.model.WebSearchProvider>,
            apiKeys: Map<com.self.zai.model.WebSearchProvider, String>,
        ): Result<WebSearchResponse> = Result.failure(UnsupportedOperationException())

        override suspend fun fetchUrl(url: String, maxChars: Int): Result<WebPageContent> =
            Result.failure(UnsupportedOperationException())
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
