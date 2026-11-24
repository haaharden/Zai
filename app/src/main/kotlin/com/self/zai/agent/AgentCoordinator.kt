package com.self.zai.agent

import com.self.zai.data.SessionRepository
import com.self.zai.model.ActionStep
import com.self.zai.model.ChatMessage
import com.self.zai.model.ChatSession
import com.self.zai.model.CustomModelEntry
import com.self.zai.model.CustomProviderConfig
import com.self.zai.model.ModelOption
import com.self.zai.model.MessageRole
import com.self.zai.model.StepState
import com.self.zai.phone.PhoneController
import com.self.zai.phone.PhoneToolCatalog
import com.self.zai.pi.AgentRuntime
import com.self.zai.pi.PiSessionEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Thin UI-chrome contracts injected into [AgentCoordinator] so orchestration stays decoupled from
 * Android WindowManager / ForegroundService details and remains directly unit-testable.
 */
interface RunChromeHost {
    fun showOperatingOverlay(showCapsule: Boolean, showEdgeGlow: Boolean)
    fun showCompletedBanner()
    fun dismissOverlay()
    fun updateForegroundStatus(status: String)
    fun stopForegroundService()
    fun postFinishedFallbackNotification(summary: String)
}

/**
 * Central session and run orchestrator coordinating [AgentRuntime], [PhoneController],
 * [ModelSelectionManager], [SessionRepository], [AppReturnNavigator], and [RunChromeHost].
 */
class AgentCoordinator(
    private val repository: SessionRepository,
    private val runtime: AgentRuntime,
    val phoneController: PhoneController,
    val modelSelection: ModelSelectionManager,
    private val returnNavigator: AppReturnNavigator,
    private val chromeHost: RunChromeHost,
    private val onStartRuntimeExtra: () -> Unit = {},
    private val onRepairAccessibility: suspend () -> Result<Unit> = { Result.failure(UnsupportedOperationException()) },
    mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private val configuredSessions = ConcurrentHashMap<String, String>() // sessionId -> "provider/model/thinking"

    private val _activeSessionId = MutableStateFlow(ensureInitialSession().id)
    val activeSessionId: StateFlow<String> = _activeSessionId.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _liveStepLabel = MutableStateFlow("")
    val liveStepLabel: StateFlow<String> = _liveStepLabel.asStateFlow()

    @Volatile
    var isAppInForeground: Boolean = true
        set(value) {
            field = value
            if (value && !_isRunning.value) {
                chromeHost.dismissOverlay()
            }
            if (!value) {
                repository.flushNow()
            }
        }

    init {
        runtime.toolHandler = phoneController
        observePiEvents()
        observeProviders()
    }

    fun startRuntime() {
        onStartRuntimeExtra()
        runtime.start(repository.settings.value.customProviders)
    }

    fun invalidateSessionConfig(sessionId: String?) {
        if (sessionId == null) configuredSessions.clear() else configuredSessions.remove(sessionId)
    }

    fun currentSession(): ChatSession {
        val id = _activeSessionId.value
        return repository.sessions.value.firstOrNull { it.id == id } ?: ensureInitialSession()
    }

    fun selectSession(sessionId: String) {
        if (repository.sessions.value.any { it.id == sessionId }) {
            _activeSessionId.value = sessionId
        }
    }

    fun startNewSession(): ChatSession {
        if (_isRunning.value) stopActiveRun()
        val defModel = modelSelection.defaultModelOption()
        val defThinking = defModel?.clampThinking(repository.settings.value.thinkingLevel)
            ?: repository.settings.value.thinkingLevel
        val created = repository.createNewSession().copy(
            providerId = defModel?.providerId,
            modelId = defModel?.id,
            thinkingLevel = defThinking,
        )
        repository.upsertSession(created, flushImmediately = true)
        _activeSessionId.value = created.id
        return created
    }

    fun deleteSession(sessionId: String) {
        if (_activeSessionId.value == sessionId && _isRunning.value) {
            stopActiveRun()
        }
        runtime.disposeSession(sessionId)
        configuredSessions.remove(sessionId)
        repository.deleteSession(sessionId)
        if (_activeSessionId.value == sessionId) {
            _activeSessionId.value = ensureInitialSession().id
        }
    }

    fun defaultModelOption(): ModelOption? = modelSelection.defaultModelOption()
    fun defaultThinkingLevel(): String = modelSelection.defaultThinkingLevel()
    fun sessionModelOption(session: ChatSession): ModelOption? = modelSelection.sessionModelOption(session)
    fun sessionThinkingLevel(
        session: ChatSession,
        model: ModelOption? = sessionModelOption(session),
    ): String = modelSelection.sessionThinkingLevel(session, model)

    fun selectDefaultModel(providerId: String, modelId: String) {
        modelSelection.selectDefaultModel(providerId, modelId, currentSession())
    }

    fun selectDefaultThinkingLevel(level: String) {
        modelSelection.selectDefaultThinkingLevel(level, currentSession())
    }

    fun selectSessionModel(providerId: String, modelId: String) {
        modelSelection.selectSessionModel(currentSession(), providerId, modelId)
    }

    fun selectSessionThinkingLevel(level: String) {
        modelSelection.selectSessionThinkingLevel(currentSession(), level)
    }

    fun saveCustomProvider(
        existingId: String?,
        name: String,
        baseUrl: String,
        apiKey: String,
        models: List<CustomModelEntry>,
        defaultImages: Boolean,
    ): CustomProviderConfig = modelSelection.saveCustomProvider(
        existingId = existingId,
        name = name,
        baseUrl = baseUrl,
        apiKey = apiKey,
        models = models,
        defaultImages = defaultImages,
    )

    fun deleteCustomProvider(providerId: String) = modelSelection.deleteCustomProvider(providerId)

    fun onPhoneOperationStep() {
        _liveStepLabel.value = "正在操作手机"
        val s = repository.settings.value
        if (s.showFloatingCapsule || s.showEdgeGlow) {
            chromeHost.showOperatingOverlay(
                showCapsule = s.showFloatingCapsule,
                showEdgeGlow = s.showEdgeGlow,
            )
        }
        chromeHost.updateForegroundStatus("正在操作手机")
    }

    fun sendUserMessage(rawText: String): Result<Unit> {
        val text = rawText.trim()
        if (text.isEmpty()) return Result.success(Unit)
        if (!runtime.isReady.value) {
            return Result.failure(IllegalStateException("Pi 引擎正在初始化，请稍候 1 秒再试"))
        }

        val session = currentSession()
        val model = sessionModelOption(session)
            ?: return Result.failure(IllegalStateException("请先登录 ChatGPT，或添加供应商密钥与模型"))
        val thinking = sessionThinkingLevel(session, model)
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
            updateSession(sessionId, flushImmediately = true) { s ->
                s.copy(
                    updatedAtMs = System.currentTimeMillis(),
                    messages = s.messages + userMsg,
                )
            }
            runtime.prompt(sessionId, text)
            return Result.success(Unit)
        }

        val assistantPlaceholder = ChatMessage(
            id = "m_${UUID.randomUUID().toString().take(8)}",
            role = MessageRole.ASSISTANT,
            text = "",
        )
        updateSession(sessionId, flushImmediately = true) { s ->
            s.copy(
                title = updatedTitle,
                providerId = model.providerId,
                modelId = model.id,
                thinkingLevel = thinking,
                updatedAtMs = System.currentTimeMillis(),
                messages = s.messages + userMsg + assistantPlaceholder,
            )
        }

        phoneController.resetRunCounters()
        phoneController.currentModelSupportsImages = model.supportsImages

        val configKey = "${model.providerId}/${model.id}/$thinking"
        if (configuredSessions[sessionId] != configKey) {
            runtime.configureSession(
                sessionId = sessionId,
                providerId = model.providerId,
                modelId = model.id,
                thinkingLevel = thinking,
                systemPrompt = PhoneToolCatalog.buildSystemPrompt(model.supportsImages),
                toolSpecsJson = phoneController.toolRegistry.toolsJson(includeVisionTools = model.supportsImages),
                savedMessagesJson = session.piTranscriptJson,
            )
            configuredSessions[sessionId] = configKey
        }

        _isRunning.value = true
        _liveStepLabel.value = "思考中…"
        chromeHost.updateForegroundStatus("正在处理请求")
        runtime.prompt(sessionId, text)
        return Result.success(Unit)
    }

    fun stopActiveRun() {
        val sessionId = _activeSessionId.value
        phoneController.clearPendingConfirmations()
        runtime.abort(sessionId)
        _isRunning.value = false
        _liveStepLabel.value = ""
        chromeHost.dismissOverlay()
        chromeHost.stopForegroundService()
        updateLastAssistantMessage(sessionId, flushImmediately = true) { msg ->
            val closedSteps = msg.steps.map {
                if (it.state == StepState.RUNNING) it.copy(state = StepState.ERROR, detail = "已停止") else it
            }
            val note = if (msg.text.isBlank()) "任务已停止。" else msg.text
            msg.copy(text = note, steps = closedSteps)
        }
    }

    fun reloadPiEngine(resetToBundled: Boolean = false) {
        if (_isRunning.value) stopActiveRun()
        configuredSessions.clear()
        if (resetToBundled) runtime.resetToBundledPi() else runtime.hotReload()
    }

    suspend fun repairAccessibilityWithShizuku(): Result<Unit> = onRepairAccessibility()

    private fun finishRunUiState(allowAutoReturn: Boolean = false, sessionId: String? = null) {
        val operated = phoneController.didOperatePhoneThisRun()
        val shouldReturn = allowAutoReturn &&
            operated &&
            repository.settings.value.returnToZaiOnFinish &&
            !isAppInForeground

        _isRunning.value = false
        _liveStepLabel.value = ""
        repository.flushNow()

        if (!shouldReturn) {
            chromeHost.dismissOverlay()
            chromeHost.stopForegroundService()
            return
        }

        val summary = sessionId?.let { id ->
            repository.sessions.value.firstOrNull { it.id == id }
                ?.messages?.lastOrNull { it.role == MessageRole.ASSISTANT }
                ?.text?.lineSequence()?.firstOrNull()?.take(50)
        }.orEmpty()

        scope.launch {
            val returned = returnNavigator.attemptBringToFront { isAppInForeground }
            chromeHost.stopForegroundService()
            if (returned) {
                chromeHost.dismissOverlay()
            } else {
                chromeHost.showCompletedBanner()
                chromeHost.postFinishedFallbackNotification(summary)
            }
        }
    }

    private fun observeProviders() {
        scope.launch {
            runtime.providers.collect { list ->
                modelSelection.ensureValidDefaultModel(list)
            }
        }
    }

    private fun observePiEvents() {
        scope.launch {
            runtime.events.collect { event ->
                when (event) {
                    is PiSessionEvent.RuntimeReset -> {
                        configuredSessions.clear()
                        if (_isRunning.value) {
                            val activeId = _activeSessionId.value
                            phoneController.clearPendingConfirmations()
                            _isRunning.value = false
                            _liveStepLabel.value = ""
                            chromeHost.dismissOverlay()
                            chromeHost.stopForegroundService()
                            updateLastAssistantMessage(activeId, flushImmediately = true) { msg ->
                                val closed = msg.steps.map {
                                    if (it.state == StepState.RUNNING) it.copy(state = StepState.ERROR) else it
                                }
                                val note = if (msg.text.isBlank()) "${event.reason}，请重新发送消息。" else msg.text
                                msg.copy(text = note, steps = closed, isError = msg.text.isBlank())
                            }
                        }
                    }

                    is PiSessionEvent.TextDelta -> {
                        updateLastAssistantMessage(event.sessionId, flushImmediately = false) { msg ->
                            msg.copy(text = msg.text + event.delta)
                        }
                    }

                    is PiSessionEvent.ThinkingDelta -> {
                        updateLastAssistantMessage(event.sessionId, flushImmediately = false) { msg ->
                            msg.copy(thinking = msg.thinking + event.delta)
                        }
                    }

                    is PiSessionEvent.ThinkingEnd -> {
                        if (event.text.isNotBlank()) {
                            updateLastAssistantMessage(event.sessionId, flushImmediately = false) { msg ->
                                msg.copy(thinking = event.text)
                            }
                        }
                    }

                    is PiSessionEvent.ToolStart -> {
                        val label = phoneController.toolRegistry.humanLabel(event.tool, event.args)
                        _liveStepLabel.value = "正在操作手机"
                        val step = ActionStep(
                            id = "st_${UUID.randomUUID().toString().take(6)}",
                            tool = event.tool,
                            label = label,
                            state = StepState.RUNNING,
                        )
                        updateLastAssistantMessage(event.sessionId, flushImmediately = false) { msg ->
                            msg.copy(steps = msg.steps + step)
                        }
                    }

                    is PiSessionEvent.ToolEnd -> {
                        val firstLine = event.output.lineSequence().firstOrNull()?.take(80).orEmpty()
                        updateLastAssistantMessage(event.sessionId, flushImmediately = false) { msg ->
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
                            updateLastAssistantMessage(event.sessionId, flushImmediately = true) { msg ->
                                msg.copy(
                                    text = if (msg.text.isBlank()) event.errorMessage else "${msg.text}\n\n${event.errorMessage}",
                                    isError = true,
                                )
                            }
                        } else if (event.text.isNotBlank()) {
                            updateLastAssistantMessage(event.sessionId, flushImmediately = true) { msg ->
                                if (msg.text.isBlank()) msg.copy(text = event.text) else msg
                            }
                        } else {
                            repository.flushNow()
                        }
                    }

                    is PiSessionEvent.Transcript -> {
                        updateSession(event.sessionId, flushImmediately = true) { s ->
                            s.copy(
                                piTranscriptJson = event.messagesJson,
                                updatedAtMs = System.currentTimeMillis(),
                            )
                        }
                    }

                    is PiSessionEvent.Idle -> {
                        if (event.sessionId == _activeSessionId.value) {
                            finishRunUiState(allowAutoReturn = true, sessionId = event.sessionId)
                        }
                    }

                    is PiSessionEvent.Error -> {
                        if (event.sessionId == _activeSessionId.value) {
                            finishRunUiState(allowAutoReturn = true, sessionId = event.sessionId)
                        }
                        updateLastAssistantMessage(event.sessionId, flushImmediately = true) { msg ->
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

    private fun updateSession(
        sessionId: String,
        flushImmediately: Boolean,
        transform: (ChatSession) -> ChatSession,
    ) {
        val current = repository.sessions.value.firstOrNull { it.id == sessionId } ?: return
        repository.upsertSession(transform(current), flushImmediately = flushImmediately)
    }

    private fun updateLastAssistantMessage(
        sessionId: String,
        flushImmediately: Boolean,
        transform: (ChatMessage) -> ChatMessage,
    ) {
        updateSession(sessionId, flushImmediately = flushImmediately) { s ->
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

    private fun ensureInitialSession(): ChatSession =
        repository.sessions.value.firstOrNull() ?: repository.createNewSession()
}
