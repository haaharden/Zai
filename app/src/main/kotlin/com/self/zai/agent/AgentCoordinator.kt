package com.self.zai.agent

import com.self.zai.data.CredentialVault
import com.self.zai.data.SessionRepository
import com.self.zai.mcp.McpToolClient
import com.self.zai.mcp.StreamableHttpMcpClient
import com.self.zai.model.ActionStep
import com.self.zai.model.ChatMessage
import com.self.zai.model.ChatSession
import com.self.zai.model.CompactionMode
import com.self.zai.model.CustomModelEntry
import com.self.zai.model.CustomProviderConfig
import com.self.zai.model.McpServerConfig
import com.self.zai.model.McpToolSummary
import com.self.zai.model.MessageRole
import com.self.zai.model.ModelOption
import com.self.zai.model.SessionContextUsage
import com.self.zai.model.SkillEntry
import com.self.zai.model.StepState
import com.self.zai.phone.PhoneController
import com.self.zai.pi.AgentRuntime
import com.self.zai.pi.PiSessionEvent
import com.self.zai.skills.SkillMarkdownParser
import kotlinx.coroutines.CoroutineDispatcher
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
    private val credentialVault: CredentialVault,
    private val runtime: AgentRuntime,
    val phoneController: PhoneController,
    val toolDispatcher: AgentToolDispatcher,
    val mcpClient: McpToolClient,
    val modelSelection: ModelSelectionManager,
    private val returnNavigator: AppReturnNavigator,
    private val chromeHost: RunChromeHost,
    private val onStartRuntimeExtra: () -> Unit = {},
    private val onRepairAccessibility: suspend () -> Result<Unit> = { Result.failure(UnsupportedOperationException()) },
    private val onDismissAssistantOverlay: () -> Unit = {},
    private val isOverlayVisible: () -> Boolean = { false },
    mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private val configuredSessions = ConcurrentHashMap<String, String>() // sessionId -> "provider/model/thinking"

    @Volatile
    private var foregroundServiceActive: Boolean = false

    private val _activeSessionId = MutableStateFlow(ensureInitialSession().id)
    val activeSessionId: StateFlow<String> = _activeSessionId.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _liveStepLabel = MutableStateFlow("")
    val liveStepLabel: StateFlow<String> = _liveStepLabel.asStateFlow()

    private val _sessionContextUsages = MutableStateFlow<Map<String, SessionContextUsage>>(emptyMap())
    val sessionContextUsages: StateFlow<Map<String, SessionContextUsage>> = _sessionContextUsages.asStateFlow()

    private val _compactionNotices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val compactionNotices: SharedFlow<String> = _compactionNotices.asSharedFlow()

    @Volatile
    var isAppInForeground: Boolean = true
        set(value) {
            field = value
            if (value && !_isRunning.value) {
                chromeHost.dismissOverlay()
            }
            if (!value) {
                repository.flushNow()
                onUiBackgroundedIfRunning()
            }
        }

    fun onUiBackgroundedIfRunning() {
        if (_isRunning.value && !isAppInForeground && !isOverlayVisible()) {
            ensureForegroundServiceRunning(_liveStepLabel.value.ifBlank { "正在后台处理请求" })
        }
    }

    private fun ensureForegroundServiceRunning(status: String) {
        foregroundServiceActive = true
        chromeHost.updateForegroundStatus(status)
    }

    private fun stopForegroundServiceIfActive() {
        if (foregroundServiceActive) {
            foregroundServiceActive = false
            chromeHost.stopForegroundService()
        }
    }

    init {
        runtime.toolHandler = toolDispatcher
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
        _sessionContextUsages.value = _sessionContextUsages.value - sessionId
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

    // --- User System Prompt, Skills & MCP management ---

    fun updateUserSystemPrompt(prompt: String) {
        repository.updateSettings { it.copy(userSystemPrompt = prompt.trim()) }
        configuredSessions.clear()
    }

    fun updateCompactionSettings(mode: CompactionMode, thresholdPercent: Int) {
        val clamped = thresholdPercent.coerceIn(40, 95)
        repository.updateSettings {
            it.copy(
                compactionMode = mode,
                compactionThresholdPercent = clamped,
            )
        }
        configuredSessions.clear()
    }

    fun compactActiveSession(): Result<Unit> {
        if (_isRunning.value) {
            return Result.failure(IllegalStateException("当前正在生成回复，请稍候再压缩"))
        }
        if (!runtime.isReady.value) {
            return Result.failure(IllegalStateException("Pi 引擎正在初始化"))
        }
        val session = currentSession()
        if (session.messages.size < 4) {
            return Result.failure(IllegalStateException("当前对话较短，暂无需压缩"))
        }
        val model = sessionModelOption(session)
            ?: return Result.failure(IllegalStateException("请先配置可用模型"))
        val thinking = sessionThinkingLevel(session, model)
        val currentSettings = repository.settings.value
        val capabilityFingerprint = SystemPromptBuilder.configFingerprint(currentSettings, model.supportsImages)
        val configKey = "${model.providerId}/${model.id}/$thinking/$capabilityFingerprint"
        if (configuredSessions[session.id] != configKey) {
            runtime.configureSession(
                sessionId = session.id,
                providerId = model.providerId,
                modelId = model.id,
                thinkingLevel = thinking,
                systemPrompt = SystemPromptBuilder.build(currentSettings, model.supportsImages),
                toolSpecsJson = toolDispatcher.buildToolSpecs(currentSettings, includeVisionTools = model.supportsImages),
                savedMessagesJson = session.piTranscriptJson,
                compactionMode = currentSettings.compactionMode.toWire(),
                compactionThresholdPercent = currentSettings.compactionThresholdPercent,
            )
            configuredSessions[session.id] = configKey
        }
        runtime.compactSession(session.id)
        return Result.success(Unit)
    }

    fun saveSkill(
        existingId: String?,
        name: String,
        description: String,
        instructions: String,
        enabled: Boolean = true,
    ): SkillEntry {
        val id = existingId?.takeIf { it.isNotBlank() } ?: "sk_${UUID.randomUUID().toString().take(8)}"
        val entry = SkillEntry(
            id = id,
            name = SkillMarkdownParser.normalizeSkillName(name),
            description = description.trim().ifBlank { "自定义技能" },
            instructions = instructions.trim(),
            enabled = enabled,
        )
        repository.updateSettings { cur ->
            val list = cur.skills.toMutableList()
            val idx = list.indexOfFirst { it.id == id }
            if (idx >= 0) list[idx] = entry else list.add(0, entry)
            cur.copy(skills = list)
        }
        configuredSessions.clear()
        return entry
    }

    fun toggleSkill(skillId: String, enabled: Boolean) {
        repository.updateSettings { cur ->
            cur.copy(skills = cur.skills.map { if (it.id == skillId) it.copy(enabled = enabled) else it })
        }
        configuredSessions.clear()
    }

    fun deleteSkill(skillId: String) {
        repository.updateSettings { cur ->
            cur.copy(skills = cur.skills.filterNot { it.id == skillId })
        }
        configuredSessions.clear()
    }

    fun saveMcpServer(
        existingId: String?,
        name: String,
        url: String,
        authToken: String?,
        tools: List<McpToolSummary>,
        enabled: Boolean = true,
    ): McpServerConfig {
        val id = existingId?.takeIf { it.isNotBlank() } ?: "mcp_${UUID.randomUUID().toString().take(8)}"
        val normalizedUrl = StreamableHttpMcpClient.normalizeUrl(url)
        val cfg = McpServerConfig(
            id = id,
            name = name.trim().ifBlank {
                normalizedUrl.substringAfter("://").substringBefore("/").ifBlank { "MCP 服务" }
            },
            url = normalizedUrl,
            enabled = enabled,
            tools = tools,
        )
        val trimmedToken = authToken?.trim()
        if (trimmedToken != null) {
            if (trimmedToken.isEmpty()) {
                credentialVault.remove(AgentToolDispatcher.mcpAuthKey(id))
            } else {
                credentialVault.putString(AgentToolDispatcher.mcpAuthKey(id), trimmedToken)
            }
        }
        mcpClient.invalidateSession(normalizedUrl)
        repository.updateSettings { cur ->
            val list = cur.mcpServers.toMutableList()
            val idx = list.indexOfFirst { it.id == id }
            if (idx >= 0) list[idx] = cfg else list.add(0, cfg)
            cur.copy(mcpServers = list)
        }
        configuredSessions.clear()
        return cfg
    }

    fun toggleMcpServer(serverId: String, enabled: Boolean) {
        repository.updateSettings { cur ->
            cur.copy(mcpServers = cur.mcpServers.map { if (it.id == serverId) it.copy(enabled = enabled) else it })
        }
        configuredSessions.clear()
    }

    fun deleteMcpServer(serverId: String) {
        val existing = repository.settings.value.mcpServers.firstOrNull { it.id == serverId }
        if (existing != null) mcpClient.invalidateSession(existing.url)
        credentialVault.remove(AgentToolDispatcher.mcpAuthKey(serverId))
        repository.updateSettings { cur ->
            cur.copy(mcpServers = cur.mcpServers.filterNot { it.id == serverId })
        }
        configuredSessions.clear()
    }

    fun getMcpAuthToken(serverId: String): String? =
        credentialVault.getString(AgentToolDispatcher.mcpAuthKey(serverId))

    suspend fun discoverMcpTools(url: String, authToken: String?): Result<List<McpToolSummary>> =
        mcpClient.discoverTools(url, authToken)

    fun onNonPhoneToolActivity(label: String) {
        _liveStepLabel.value = label
    }

    fun onPhoneOperationStep() {
        onDismissAssistantOverlay()
        _liveStepLabel.value = "正在操作手机"
        val s = repository.settings.value
        if (s.showFloatingCapsule || s.showEdgeGlow) {
            chromeHost.showOperatingOverlay(
                showCapsule = s.showFloatingCapsule,
                showEdgeGlow = s.showEdgeGlow,
            )
        }
        ensureForegroundServiceRunning("正在操作手机")
    }

    fun sendUserMessage(rawText: String, extraContextForModel: String? = null): Result<Unit> {
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
        val currentSettings = repository.settings.value

        // Check for explicit `/skill-name` invocation
        val slashMatch = SkillMarkdownParser.matchSlashInvocation(
            rawMessage = text,
            enabledSkills = currentSettings.skills.filter { it.enabled },
        )
        val basePrompt = if (slashMatch != null) {
            buildString {
                appendLine("请严格按照以下已激活的技能「${slashMatch.skill.name}」指引处理本次请求：")
                appendLine(slashMatch.skill.instructions)
                appendLine()
                append("用户请求：")
                append(slashMatch.remainingText.ifBlank { "请执行该技能。" })
            }
        } else {
            text
        }
        val promptForPi = if (!extraContextForModel.isNullOrBlank()) {
            buildString {
                appendLine("[用户附带的当前屏幕内容]")
                appendLine(extraContextForModel.trim())
                appendLine()
                append(basePrompt)
            }
        } else {
            basePrompt
        }

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
            val followUpPlaceholder = ChatMessage(
                id = "m_${UUID.randomUUID().toString().take(8)}",
                role = MessageRole.ASSISTANT,
                text = "",
            )
            updateSession(sessionId, flushImmediately = true) { s ->
                val baseMessages = s.messages.toMutableList()
                // Drop an untouched empty assistant placeholder if user followed up before any token arrived
                val trailing = baseMessages.lastOrNull()
                if (trailing != null &&
                    trailing.role == MessageRole.ASSISTANT &&
                    trailing.text.isBlank() &&
                    trailing.thinking.isBlank() &&
                    trailing.steps.isEmpty()
                ) {
                    baseMessages.removeAt(baseMessages.lastIndex)
                }
                baseMessages.add(userMsg)
                baseMessages.add(followUpPlaceholder)
                s.copy(
                    updatedAtMs = System.currentTimeMillis(),
                    messages = baseMessages,
                )
            }
            _liveStepLabel.value = "思考中…"
            runtime.prompt(sessionId, promptForPi)
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

        val capabilityFingerprint = SystemPromptBuilder.configFingerprint(currentSettings, model.supportsImages)
        val configKey = "${model.providerId}/${model.id}/$thinking/$capabilityFingerprint"
        if (configuredSessions[sessionId] != configKey) {
            runtime.configureSession(
                sessionId = sessionId,
                providerId = model.providerId,
                modelId = model.id,
                thinkingLevel = thinking,
                systemPrompt = SystemPromptBuilder.build(currentSettings, model.supportsImages),
                toolSpecsJson = toolDispatcher.buildToolSpecs(currentSettings, includeVisionTools = model.supportsImages),
                savedMessagesJson = session.piTranscriptJson,
                compactionMode = currentSettings.compactionMode.toWire(),
                compactionThresholdPercent = currentSettings.compactionThresholdPercent,
            )
            configuredSessions[sessionId] = configKey
        }

        _isRunning.value = true
        _liveStepLabel.value = "思考中…"
        // Only elevate to a foreground service on demand (phone automation or backgrounded run)
        if (!isAppInForeground && !isOverlayVisible()) {
            ensureForegroundServiceRunning("正在后台处理请求")
        }
        runtime.prompt(sessionId, promptForPi)
        return Result.success(Unit)
    }

    fun stopActiveRun() {
        val sessionId = _activeSessionId.value
        phoneController.clearPendingConfirmations()
        runtime.abort(sessionId)
        _isRunning.value = false
        _liveStepLabel.value = ""
        chromeHost.dismissOverlay()
        stopForegroundServiceIfActive()
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
            stopForegroundServiceIfActive()
            return
        }

        val summary = sessionId?.let { id ->
            repository.sessions.value.firstOrNull { it.id == id }
                ?.messages?.lastOrNull { it.role == MessageRole.ASSISTANT }
                ?.text?.lineSequence()?.firstOrNull()?.take(50)
        }.orEmpty()

        scope.launch {
            val returned = returnNavigator.attemptBringToFront { isAppInForeground }
            stopForegroundServiceIfActive()
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
                            stopForegroundServiceIfActive()
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
                        if (toolDispatcher.isScreenOperatingTool(event.tool)) {
                            val label = toolDispatcher.humanLabel(event.tool, event.args)
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
                        } else {
                            _liveStepLabel.value = when {
                                event.tool == AgentToolDispatcher.READ_SKILL_TOOL -> "正在读取技能…"
                                event.tool == "compress" || event.tool == "context_compress" -> "正在压缩上下文…"
                                event.tool == "decompress" || event.tool == "context_expand" -> "正在解压历史块…"
                                event.tool == "search_context" || event.tool == "context_search" -> "正在检索压缩历史…"
                                event.tool == "acp_retrieve" -> "正在读取归档原文…"
                                event.tool == "acp_status" -> "正在检查上下文状态…"
                                event.tool.startsWith(AgentToolDispatcher.MCP_PREFIX) -> "正在调用 MCP 工具…"
                                else -> "正在${toolDispatcher.humanLabel(event.tool, event.args)}…"
                            }
                        }
                    }

                    is PiSessionEvent.ToolEnd -> {
                        val firstLine = event.output.lineSequence().firstOrNull()?.take(80).orEmpty()
                        updateSession(event.sessionId, flushImmediately = false) { s ->
                            val list = s.messages.toMutableList()
                            val msgIdx = list.indexOfLast { m ->
                                m.role == MessageRole.ASSISTANT &&
                                    m.steps.any { it.tool == event.tool && it.state == StepState.RUNNING }
                            }
                            if (msgIdx < 0) {
                                s
                            } else {
                                val msg = list[msgIdx]
                                val stepIdx = msg.steps.indexOfLast { it.tool == event.tool && it.state == StepState.RUNNING }
                                val nextSteps = msg.steps.toMutableList()
                                nextSteps[stepIdx] = nextSteps[stepIdx].copy(
                                    state = if (event.isError) StepState.ERROR else StepState.DONE,
                                    detail = firstLine,
                                )
                                list[msgIdx] = msg.copy(steps = nextSteps)
                                s.copy(messages = list, updatedAtMs = System.currentTimeMillis())
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

                    is PiSessionEvent.ContextUsage -> {
                        _sessionContextUsages.value = _sessionContextUsages.value + (event.sessionId to event.usage)
                    }

                    is PiSessionEvent.ContextCompacted -> {
                        _compactionNotices.tryEmit(
                            "已压缩 ${event.foldedCount} 条早期消息 · 上下文降至 ${event.percent}%",
                        )
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
            val last = list.lastOrNull()
            if (last != null && last.role == MessageRole.ASSISTANT) {
                list[list.lastIndex] = transform(last)
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
