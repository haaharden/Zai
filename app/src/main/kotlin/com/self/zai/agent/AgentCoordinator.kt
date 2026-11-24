package com.self.zai.agent

import android.content.ComponentName
import android.content.Context
import com.self.zai.data.SecureStore
import com.self.zai.data.SettingsStore
import com.self.zai.model.ActionStep
import com.self.zai.model.ChatMessage
import com.self.zai.model.ChatSession
import com.self.zai.model.CustomModelEntry
import com.self.zai.model.CustomProviderConfig
import com.self.zai.model.MessageRole
import com.self.zai.model.ModelOption
import com.self.zai.model.ProviderStatus
import com.self.zai.model.StepState
import com.self.zai.phone.PhoneController
import com.self.zai.phone.PhoneToolCatalog
import com.self.zai.phone.ZaiAccessibilityService
import com.self.zai.pi.PiEngine
import com.self.zai.pi.PiSessionEvent
import com.self.zai.service.FloatingCapsuleController
import com.self.zai.service.ZaiAgentService
import com.self.zai.shell.ShizukuBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class AgentCoordinator(
    private val context: Context,
    val settingsStore: SettingsStore,
    val secureStore: SecureStore,
    val shizuku: ShizukuBridge,
    val piEngine: PiEngine,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val floatingCapsule = FloatingCapsuleController(
        context = context,
        onStopRequested = { stopActiveRun() },
    )

    val phoneController = PhoneController(
        context = context,
        settingsStore = settingsStore,
        shizuku = shizuku,
        onCapsuleHideForCapture = { hidden -> floatingCapsule.setTemporarilyHidden(hidden) },
        onStepTextChanged = { stepLabel ->
            _liveStepLabel.value = stepLabel
            floatingCapsule.updateStatus(stepLabel)
            ZaiAgentService.startOrUpdate(context, stepLabel)
        },
    )

    private val _activeSessionId = MutableStateFlow(ensureInitialSession().id)
    val activeSessionId: StateFlow<String> = _activeSessionId.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _liveStepLabel = MutableStateFlow("")
    val liveStepLabel: StateFlow<String> = _liveStepLabel.asStateFlow()

    private val configuredSessions = HashMap<String, String>() // sessionId -> "provider/model"

    init {
        piEngine.toolHandler = phoneController
        observePiEvents()
        observeProviders()
    }

    fun startRuntime() {
        shizuku.register()
        piEngine.start(settingsStore.settings.value.customProviders)
    }

    fun currentSession(): ChatSession {
        val id = _activeSessionId.value
        return settingsStore.sessions.value.firstOrNull { it.id == id } ?: ensureInitialSession()
    }

    fun selectSession(sessionId: String) {
        if (settingsStore.sessions.value.any { it.id == sessionId }) {
            _activeSessionId.value = sessionId
        }
    }

    fun startNewSession(): ChatSession {
        if (_isRunning.value) stopActiveRun()
        val created = settingsStore.createNewSession()
        _activeSessionId.value = created.id
        return created
    }

    fun deleteSession(sessionId: String) {
        if (_activeSessionId.value == sessionId && _isRunning.value) {
            stopActiveRun()
        }
        piEngine.disposeSession(sessionId)
        configuredSessions.remove(sessionId)
        settingsStore.deleteSession(sessionId)
        if (_activeSessionId.value == sessionId) {
            _activeSessionId.value = ensureInitialSession().id
        }
    }

    fun allAvailableModels(): List<ModelOption> =
        piEngine.providers.value
            .filter { it.isConnected }
            .flatMap { it.models }

    fun activeModelOption(): ModelOption? {
        val s = settingsStore.settings.value
        val available = allAvailableModels()
        return available.firstOrNull {
            it.providerId == s.activeProviderId && it.id == s.activeModelId
        } ?: available.firstOrNull()
    }

    fun selectModel(providerId: String, modelId: String) {
        settingsStore.updateSettings {
            it.copy(activeProviderId = providerId, activeModelId = modelId)
        }
        configuredSessions.clear()
    }

    fun saveCustomProvider(
        existingId: String?,
        name: String,
        baseUrl: String,
        apiKey: String,
        models: List<CustomModelEntry>,
        defaultImages: Boolean,
    ): CustomProviderConfig {
        val id = existingId?.takeIf { it.isNotBlank() } ?: "custom_${UUID.randomUUID().toString().take(8)}"
        val cfg = CustomProviderConfig(
            id = id,
            name = name.trim().ifBlank { "自定义渠道" },
            baseUrl = baseUrl.trim().trimEnd('/'),
            models = models,
            defaultImages = defaultImages,
        )
        if (apiKey.isNotBlank()) {
            secureStore.putProviderApiKey(id, apiKey.trim())
        }
        val nextSettings = settingsStore.updateSettings { current ->
            val list = current.customProviders.toMutableList()
            val idx = list.indexOfFirst { it.id == id }
            if (idx >= 0) list[idx] = cfg else list.add(cfg)
            val firstModel = cfg.models.firstOrNull()?.id.orEmpty()
            val shouldActivate = current.activeProviderId.isBlank() || current.activeProviderId == id
            current.copy(
                customProviders = list,
                activeProviderId = if (shouldActivate && firstModel.isNotEmpty()) id else current.activeProviderId,
                activeModelId = if (shouldActivate && firstModel.isNotEmpty()) firstModel else current.activeModelId,
            )
        }
        configuredSessions.clear()
        piEngine.syncCustomProviders(nextSettings.customProviders)
        return cfg
    }

    fun deleteCustomProvider(providerId: String) {
        secureStore.removeProviderApiKey(providerId)
        val next = settingsStore.updateSettings { current ->
            val remaining = current.customProviders.filterNot { it.id == providerId }
            val resetActive = current.activeProviderId == providerId
            current.copy(
                customProviders = remaining,
                activeProviderId = if (resetActive) "" else current.activeProviderId,
                activeModelId = if (resetActive) "" else current.activeModelId,
            )
        }
        configuredSessions.clear()
        piEngine.syncCustomProviders(next.customProviders)
    }

    fun sendUserMessage(rawText: String): Result<Unit> {
        val text = rawText.trim()
        if (text.isEmpty()) return Result.success(Unit)
        if (!piEngine.isReady.value) {
            return Result.failure(IllegalStateException("Pi 引擎正在初始化，请稍候 1 秒再试"))
        }

        val model = activeModelOption()
            ?: return Result.failure(IllegalStateException("请先登录 ChatGPT，或添加自定义渠道与模型"))

        val session = currentSession()
        val sessionId = session.id

        val userMsg = ChatMessage(
            id = "m_${UUID.randomUUID().toString().take(8)}",
            role = MessageRole.USER,
            text = text,
        )

        val updatedTitle = if (session.messages.none { it.role == MessageRole.USER }) {
            text.replace("\n", " ").take(20)
        } else {
            session.title
        }

        if (_isRunning.value) {
            // Mid-run steering: Pi injects this user message cleanly after the active tool step
            updateSession(sessionId) { s ->
                s.copy(
                    updatedAtMs = System.currentTimeMillis(),
                    messages = s.messages + userMsg,
                )
            }
            piEngine.prompt(sessionId, text)
            return Result.success(Unit)
        }

        val assistantPlaceholder = ChatMessage(
            id = "m_${UUID.randomUUID().toString().take(8)}",
            role = MessageRole.ASSISTANT,
            text = "",
        )
        updateSession(sessionId) { s ->
            s.copy(
                title = updatedTitle,
                updatedAtMs = System.currentTimeMillis(),
                messages = s.messages + userMsg + assistantPlaceholder,
            )
        }

        phoneController.resetRunCounters()
        phoneController.currentModelSupportsImages = model.supportsImages

        val configKey = "${model.providerId}/${model.id}/${settingsStore.settings.value.thinkingLevel}"
        if (configuredSessions[sessionId] != configKey) {
            piEngine.configureSession(
                sessionId = sessionId,
                providerId = model.providerId,
                modelId = model.id,
                thinkingLevel = settingsStore.settings.value.thinkingLevel,
                systemPrompt = PhoneToolCatalog.buildSystemPrompt(model.supportsImages),
                toolSpecsJson = PhoneToolCatalog.toolsJson(includeScreenshot = model.supportsImages),
                savedMessagesJson = session.piTranscriptJson,
            )
            configuredSessions[sessionId] = configKey
        }

        _isRunning.value = true
        _liveStepLabel.value = "思考中…"
        ZaiAgentService.startOrUpdate(context, "正在处理：$updatedTitle")
        if (settingsStore.settings.value.showFloatingCapsule) {
            floatingCapsule.show("Zai 思考中…")
        }

        piEngine.prompt(sessionId, text)
        return Result.success(Unit)
    }

    fun stopActiveRun() {
        val sessionId = _activeSessionId.value
        phoneController.clearPendingConfirmations()
        piEngine.abort(sessionId)
        finishRunUiState()
        updateLastAssistantMessage(sessionId) { msg ->
            val closedSteps = msg.steps.map {
                if (it.state == StepState.RUNNING) it.copy(state = StepState.ERROR, detail = "已停止") else it
            }
            val note = if (msg.text.isBlank()) "任务已停止。" else msg.text
            msg.copy(text = note, steps = closedSteps)
        }
    }

    suspend fun repairAccessibilityWithShizuku(): Result<Unit> {
        val component = ComponentName(context, ZaiAccessibilityService::class.java).flattenToString()
        return shizuku.restoreAccessibilityService(component)
    }

    private fun finishRunUiState() {
        _isRunning.value = false
        _liveStepLabel.value = ""
        floatingCapsule.dismiss()
        ZaiAgentService.stop(context)
    }

    private fun observeProviders() {
        scope.launch {
            piEngine.providers.collect { list ->
                ensureValidActiveModel(list)
            }
        }
    }

    private fun ensureValidActiveModel(providers: List<ProviderStatus>) {
        val available = providers.filter { it.isConnected }.flatMap { it.models }
        if (available.isEmpty()) return
        val cur = settingsStore.settings.value
        val exists = available.any { it.providerId == cur.activeProviderId && it.id == cur.activeModelId }
        if (!exists) {
            val pick = available.first()
            settingsStore.updateSettings {
                it.copy(activeProviderId = pick.providerId, activeModelId = pick.id)
            }
        }
    }

    private fun observePiEvents() {
        scope.launch {
            piEngine.events.collect { event ->
                when (event) {
                    is PiSessionEvent.TextDelta -> {
                        updateLastAssistantMessage(event.sessionId) { msg ->
                            msg.copy(text = msg.text + event.delta)
                        }
                    }

                    is PiSessionEvent.ThinkingDelta -> {
                        updateLastAssistantMessage(event.sessionId) { msg ->
                            msg.copy(thinking = msg.thinking + event.delta)
                        }
                    }

                    is PiSessionEvent.ThinkingEnd -> {
                        if (event.text.isNotBlank()) {
                            updateLastAssistantMessage(event.sessionId) { msg ->
                                msg.copy(thinking = event.text)
                            }
                        }
                    }

                    is PiSessionEvent.ToolStart -> {
                        val label = PhoneToolCatalog.humanLabel(event.tool, event.args)
                        _liveStepLabel.value = label
                        floatingCapsule.updateStatus(label)
                        val step = ActionStep(
                            id = "st_${UUID.randomUUID().toString().take(6)}",
                            tool = event.tool,
                            label = label,
                            state = StepState.RUNNING,
                        )
                        updateLastAssistantMessage(event.sessionId) { msg ->
                            msg.copy(steps = msg.steps + step)
                        }
                    }

                    is PiSessionEvent.ToolEnd -> {
                        val firstLine = event.output.lineSequence().firstOrNull()?.take(80).orEmpty()
                        updateLastAssistantMessage(event.sessionId) { msg ->
                            val idx = msg.steps.indexOfLast { it.tool == event.tool && it.state == StepState.RUNNING }
                            if (idx < 0) {
                                msg
                            } else {
                                val nextSteps = msg.steps.toMutableList()
                                nextSteps[idx] = nextSteps[idx].copy(
                                    state = if (event.isError) StepState.ERROR else StepState.DONE,
                                    detail = firstLine,
                                )
                                msg.copy(steps = nextSteps)
                            }
                        }
                    }

                    is PiSessionEvent.AssistantEnd -> {
                        if (event.stopReason == "error" && !event.errorMessage.isNullOrBlank()) {
                            updateLastAssistantMessage(event.sessionId) { msg ->
                                msg.copy(
                                    text = if (msg.text.isBlank()) event.errorMessage else "${msg.text}\n\n${event.errorMessage}",
                                    isError = true,
                                )
                            }
                        } else if (event.text.isNotBlank()) {
                            updateLastAssistantMessage(event.sessionId) { msg ->
                                if (msg.text.isBlank()) msg.copy(text = event.text) else msg
                            }
                        }
                    }

                    is PiSessionEvent.Transcript -> {
                        updateSession(event.sessionId) { s ->
                            s.copy(
                                piTranscriptJson = event.messagesJson,
                                updatedAtMs = System.currentTimeMillis(),
                            )
                        }
                    }

                    is PiSessionEvent.Idle -> {
                        if (event.sessionId == _activeSessionId.value) {
                            finishRunUiState()
                        }
                    }

                    is PiSessionEvent.Error -> {
                        if (event.sessionId == _activeSessionId.value) {
                            finishRunUiState()
                        }
                        updateLastAssistantMessage(event.sessionId) { msg ->
                            msg.copy(
                                text = if (msg.text.isBlank()) event.message else "${msg.text}\n\n${event.message}",
                                isError = true,
                            )
                        }
                    }

                    is PiSessionEvent.Configured,
                    is PiSessionEvent.ThinkingStart,
                    is PiSessionEvent.Steered,
                    -> Unit
                }
            }
        }
    }

    private fun updateSession(sessionId: String, transform: (ChatSession) -> ChatSession) {
        val current = settingsStore.sessions.value.firstOrNull { it.id == sessionId } ?: return
        settingsStore.upsertSession(transform(current))
    }

    private fun updateLastAssistantMessage(sessionId: String, transform: (ChatMessage) -> ChatMessage) {
        updateSession(sessionId) { s ->
            val list = s.messages.toMutableList()
            val idx = list.indexOfLast { it.role == MessageRole.ASSISTANT }
            if (idx >= 0) {
                list[idx] = transform(list[idx])
            } else {
                list.add(
                    transform(
                        ChatMessage(
                            id = "m_${UUID.randomUUID().toString().take(8)}",
                            role = MessageRole.ASSISTANT,
                        ),
                    ),
                )
            }
            s.copy(messages = list, updatedAtMs = System.currentTimeMillis())
        }
    }

    private fun ensureInitialSession(): ChatSession {
        return settingsStore.sessions.value.firstOrNull() ?: settingsStore.createNewSession()
    }
}
