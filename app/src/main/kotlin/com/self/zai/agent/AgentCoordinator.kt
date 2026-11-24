package com.self.zai.agent

import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import com.self.zai.MainActivity
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
import com.self.zai.permission.DevicePermissionHelper
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
import kotlinx.coroutines.delay
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

    @Volatile
    var isAppInForeground: Boolean = true
        set(value) {
            field = value
            if (value && !_isRunning.value) {
                floatingCapsule.dismiss()
            }
        }

    val phoneController = PhoneController(
        context = context,
        settingsStore = settingsStore,
        shizuku = shizuku,
        onCapsuleHideForCapture = { hidden -> floatingCapsule.setTemporarilyHidden(hidden) },
        onStepProgress = { _, _ ->
            _liveStepLabel.value = "正在操作手机"
            val s = settingsStore.settings.value
            if (s.showFloatingCapsule || s.showEdgeGlow) {
                floatingCapsule.show(
                    showCapsule = s.showFloatingCapsule,
                    showEdgeGlow = s.showEdgeGlow,
                )
            }
            ZaiAgentService.startOrUpdate(context, "正在操作手机")
        },
        onTapMarker = { x, y ->
            if (settingsStore.settings.value.showEdgeGlow || settingsStore.settings.value.showFloatingCapsule) {
                floatingCapsule.showTapMarker(x, y)
            }
        },
    )

    private val _activeSessionId = MutableStateFlow(ensureInitialSession().id)
    val activeSessionId: StateFlow<String> = _activeSessionId.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _liveStepLabel = MutableStateFlow("")
    val liveStepLabel: StateFlow<String> = _liveStepLabel.asStateFlow()

    private val configuredSessions = HashMap<String, String>() // sessionId -> "provider/model/thinking"

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
        val defModel = defaultModelOption()
        val defThinking = defModel?.clampThinking(settingsStore.settings.value.thinkingLevel)
            ?: settingsStore.settings.value.thinkingLevel
        val created = settingsStore.createNewSession().copy(
            providerId = defModel?.providerId,
            modelId = defModel?.id,
            thinkingLevel = defThinking,
        )
        settingsStore.upsertSession(created)
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

    /** Global default model used when creating new conversations. */
    fun defaultModelOption(): ModelOption? {
        val s = settingsStore.settings.value
        val available = allAvailableModels()
        return available.firstOrNull {
            it.providerId == s.activeProviderId && it.id == s.activeModelId
        } ?: available.firstOrNull()
    }

    fun defaultThinkingLevel(): String {
        val model = defaultModelOption()
        val saved = settingsStore.settings.value.thinkingLevel
        return model?.clampThinking(saved) ?: saved
    }

    /** Active model for the specified conversation (falls back to global default). */
    fun sessionModelOption(session: ChatSession): ModelOption? {
        val available = allAvailableModels()
        if (!session.providerId.isNullOrBlank() && !session.modelId.isNullOrBlank()) {
            available.firstOrNull {
                it.providerId == session.providerId && it.id == session.modelId
            }?.let { return it }
        }
        return defaultModelOption()
    }

    /** Active thinking level for the specified conversation, clamped to the model's real supported levels. */
    fun sessionThinkingLevel(session: ChatSession, model: ModelOption? = sessionModelOption(session)): String {
        val raw = session.thinkingLevel ?: settingsStore.settings.value.thinkingLevel
        return model?.clampThinking(raw) ?: raw
    }

    /** Changes the global default model (Settings & Models page). */
    fun selectDefaultModel(providerId: String, modelId: String) {
        val picked = allAvailableModels().firstOrNull { it.providerId == providerId && it.id == modelId }
        val clampedThinking = picked?.clampThinking(settingsStore.settings.value.thinkingLevel)
            ?: settingsStore.settings.value.thinkingLevel
        settingsStore.updateSettings {
            it.copy(
                activeProviderId = providerId,
                activeModelId = modelId,
                thinkingLevel = clampedThinking,
            )
        }
        // If current session is still empty, reflect the new default immediately
        val cur = currentSession()
        if (cur.messages.isEmpty()) {
            updateSession(cur.id) {
                it.copy(providerId = providerId, modelId = modelId, thinkingLevel = clampedThinking)
            }
            configuredSessions.remove(cur.id)
        }
    }

    /** Changes the global default thinking level (Settings page). */
    fun selectDefaultThinkingLevel(level: String) {
        val model = defaultModelOption()
        val valid = model?.clampThinking(level) ?: level
        settingsStore.updateSettings { it.copy(thinkingLevel = valid) }
        val cur = currentSession()
        if (cur.messages.isEmpty()) {
            updateSession(cur.id) { it.copy(thinkingLevel = valid) }
            configuredSessions.remove(cur.id)
        }
    }

    /** Changes model for the current conversation only (chat bar above composer). */
    fun selectSessionModel(providerId: String, modelId: String) {
        val cur = currentSession()
        val picked = allAvailableModels().firstOrNull { it.providerId == providerId && it.id == modelId }
        val nextThinking = picked?.clampThinking(cur.thinkingLevel ?: settingsStore.settings.value.thinkingLevel)
            ?: cur.thinkingLevel
            ?: settingsStore.settings.value.thinkingLevel
        updateSession(cur.id) {
            it.copy(providerId = providerId, modelId = modelId, thinkingLevel = nextThinking)
        }
        configuredSessions.remove(cur.id)
    }

    /** Changes thinking effort for the current conversation only (chat bar above composer). */
    fun selectSessionThinkingLevel(level: String) {
        val cur = currentSession()
        val model = sessionModelOption(cur)
        val valid = model?.clampThinking(level) ?: level
        updateSession(cur.id) { it.copy(thinkingLevel = valid) }
        configuredSessions.remove(cur.id)
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
            piEngine.configureSession(
                sessionId = sessionId,
                providerId = model.providerId,
                modelId = model.id,
                thinkingLevel = thinking,
                systemPrompt = PhoneToolCatalog.buildSystemPrompt(model.supportsImages),
                toolSpecsJson = PhoneToolCatalog.toolsJson(includeScreenshot = model.supportsImages),
                savedMessagesJson = session.piTranscriptJson,
            )
            configuredSessions[sessionId] = configKey
        }

        _isRunning.value = true
        _liveStepLabel.value = "思考中…"
        ZaiAgentService.startOrUpdate(context, "正在处理请求")

        piEngine.prompt(sessionId, text)
        return Result.success(Unit)
    }

    fun stopActiveRun() {
        val sessionId = _activeSessionId.value
        phoneController.clearPendingConfirmations()
        piEngine.abort(sessionId)
        _isRunning.value = false
        _liveStepLabel.value = ""
        floatingCapsule.dismiss()
        ZaiAgentService.stop(context)
        updateLastAssistantMessage(sessionId) { msg ->
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
        if (resetToBundled) piEngine.resetToBundledPi() else piEngine.hotReload()
    }

    suspend fun repairAccessibilityWithShizuku(): Result<Unit> {
        if (DevicePermissionHelper.hasWriteSecureSettings(context)) {
            val nativeRes = DevicePermissionHelper.restoreAccessibilityViaSecureSettings(context)
            if (nativeRes.isSuccess) return nativeRes
        }
        val component = ComponentName(context, ZaiAccessibilityService::class.java).flattenToString()
        return shizuku.restoreAccessibilityService(component)
    }

    private fun finishRunUiState(allowAutoReturn: Boolean = false, sessionId: String? = null) {
        val operated = phoneController.didOperatePhoneThisRun()
        val shouldReturn = allowAutoReturn &&
            operated &&
            settingsStore.settings.value.returnToZaiOnFinish &&
            !isAppInForeground

        _isRunning.value = false
        _liveStepLabel.value = ""

        if (!shouldReturn) {
            floatingCapsule.dismiss()
            ZaiAgentService.stop(context)
            return
        }

        val summary = sessionId?.let { id ->
            settingsStore.sessions.value.firstOrNull { it.id == id }
                ?.messages?.lastOrNull { it.role == MessageRole.ASSISTANT }
                ?.text?.lineSequence()?.firstOrNull()?.take(50)
        }.orEmpty()

        // IMPORTANT: Trigger bring-to-front BEFORE dismissing the visible Accessibility overlay
        // and BEFORE stopping ZaiAgentService so Android 14+ BAL sees an active foreground process.
        returnToZaiBeforeTeardown(summary)
    }

    private fun returnToZaiBeforeTeardown(summary: String) {
        scope.launch {
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                )
            }
            val launcherIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                )
            } ?: launchIntent

            // Attempt 1: Move existing Zai task to front + launch via AccessibilityService / active FGS
            runCatching {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                am.appTasks.firstOrNull()?.moveToFront()
            }
            ZaiAccessibilityService.instance?.let { svc ->
                runCatching { svc.startActivity(launchIntent) }
            }
            ZaiAgentService.instance?.let { svc ->
                runCatching { svc.startActivity(launchIntent) }
            }
            runCatching { context.startActivity(launchIntent) }

            delay(360L)
            if (isAppInForeground) {
                floatingCapsule.dismiss()
                ZaiAgentService.stop(context)
                return@launch
            }

            // Attempt 2: PendingIntent with explicit Android 14+ BAL allow flag
            runCatching {
                val pi = PendingIntent.getActivity(
                    context,
                    902,
                    launcherIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val opts = ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                        )
                    pi.send(context, 0, null, null, null, null, opts.toBundle())
                } else {
                    pi.send()
                }
            }

            delay(360L)
            if (isAppInForeground) {
                floatingCapsule.dismiss()
                ZaiAgentService.stop(context)
                return@launch
            }

            // Attempt 3: Shizuku shell am start if connected
            if (shizuku.status.value.isReady) {
                shizuku.exec("am start -n ${context.packageName}/${MainActivity::class.java.name}", 4_000)
                delay(360L)
                if (isAppInForeground) {
                    floatingCapsule.dismiss()
                    ZaiAgentService.stop(context)
                    return@launch
                }
            }

            // Attempt 4 (OEM blocked background popup, e.g. Xiaomi "后台弹出界面" disabled):
            // Stop FGS, remove edge glow, keep a minimal clickable Accessibility pill + notification.
            ZaiAgentService.stop(context)
            floatingCapsule.showCompletedBanner()
            ZaiAgentService.postTaskFinishedFallback(context, summary)
        }
    }

    private fun observeProviders() {
        scope.launch {
            piEngine.providers.collect { list ->
                ensureValidDefaultModel(list)
            }
        }
    }

    private fun ensureValidDefaultModel(providers: List<ProviderStatus>) {
        val available = providers.filter { it.isConnected }.flatMap { it.models }
        if (available.isEmpty()) return
        val cur = settingsStore.settings.value
        val currentModel = available.firstOrNull {
            it.providerId == cur.activeProviderId && it.id == cur.activeModelId
        }
        val target = currentModel ?: available.first()
        val clampedThinking = target.clampThinking(cur.thinkingLevel)
        if (currentModel == null || clampedThinking != cur.thinkingLevel) {
            settingsStore.updateSettings {
                it.copy(
                    activeProviderId = target.providerId,
                    activeModelId = target.id,
                    thinkingLevel = clampedThinking,
                )
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
                        _liveStepLabel.value = "正在操作手机"
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
                            finishRunUiState(allowAutoReturn = true, sessionId = event.sessionId)
                        }
                    }

                    is PiSessionEvent.Error -> {
                        if (event.sessionId == _activeSessionId.value) {
                            finishRunUiState(allowAutoReturn = true, sessionId = event.sessionId)
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
