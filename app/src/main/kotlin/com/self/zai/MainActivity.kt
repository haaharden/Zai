package com.self.zai

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import com.self.zai.assistant.VoiceListenState
import com.self.zai.assistant.VoiceSpeechController
import com.self.zai.di.AppContainer
import com.self.zai.model.ThemeMode
import com.self.zai.permission.DevicePermissionHelper
import com.self.zai.phone.ZaiAccessibilityService
import com.self.zai.ui.capabilities.McpServersScreen
import com.self.zai.ui.capabilities.SkillsScreen
import com.self.zai.ui.chat.ChatScreen
import com.self.zai.ui.models.ModelsAndAuthScreen
import com.self.zai.ui.settings.SettingsScreen
import com.self.zai.ui.theme.ZaiTheme
import com.self.zai.update.AppUpdater
import com.self.zai.update.ReleaseUpdateInfo

private enum class OverlayRoute { NONE, MODELS, SETTINGS, SKILLS, MCP }

class MainActivity : ComponentActivity() {

    private val chatInputText = mutableStateOf("")
    private val voiceControllerState = mutableStateOf<VoiceSpeechController?>(null)

    private val notificationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    private val audioPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            ensureVoiceController().startListening()
        } else {
            Toast.makeText(this, "需要麦克风权限才能语音输入", Toast.LENGTH_SHORT).show()
        }
    }

    private fun ensureVoiceController(): VoiceSpeechController {
        voiceControllerState.value?.let { return it }
        return VoiceSpeechController(
            context = this,
            enableTts = false,
            onPartialTranscript = { partial ->
                chatInputText.value = partial
            },
            onFinalTranscript = { finalText ->
                chatInputText.value = finalText
            },
            onErrorMessage = { msg ->
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            },
        ).also { created ->
            voiceControllerState.value = created
        }
    }

    private fun toggleChatVoiceInput() {
        val current = voiceControllerState.value
        if (current != null && current.listenState.value == VoiceListenState.LISTENING) {
            current.stopListening()
            return
        }
        if (DevicePermissionHelper.hasAudioPermission(this)) {
            ensureVoiceController().startListening()
        } else {
            audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun cancelChatVoiceInput() {
        voiceControllerState.value?.cancelListening()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !DevicePermissionHelper.hasNotificationPermission(this)
        ) {
            notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val app = application as ZaiApplication
        setContent {
            val settings by app.container.sessionRepository.settings.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val isDark = when (settings.themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK, ThemeMode.AMOLED -> true
                ThemeMode.SYSTEM -> systemDark
            }
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !isDark
                    isAppearanceLightNavigationBars = !isDark
                }
            }
            ZaiTheme(themeMode = settings.themeMode) {
                val activeVoiceController = voiceControllerState.value
                val liveVoiceState = activeVoiceController?.listenState?.collectAsState()?.value
                    ?: VoiceListenState.IDLE
                ZaiRoot(
                    app = app,
                    container = app.container,
                    chatInputText = chatInputText.value,
                    onChatInputTextChange = { chatInputText.value = it },
                    voiceListenState = liveVoiceState,
                    onToggleVoiceInput = { toggleChatVoiceInput() },
                    onCancelVoiceInput = { cancelChatVoiceInput() },
                    onOpenBrowserUrl = { url -> openAuthUrl(url) },
                    onShowToast = { msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val container = (application as ZaiApplication).container
        container.agentCoordinator.isAppInForeground = true
        container.shizukuBridge.refreshStatus()
        if (container.shizukuBridge.status.value.binderAlive &&
            container.shizukuBridge.status.value.permissionGranted
        ) {
            container.shizukuBridge.bindAsync()
        }
    }

    override fun onPause() {
        cancelChatVoiceInput()
        val container = (application as ZaiApplication).container
        container.agentCoordinator.isAppInForeground = false
        super.onPause()
    }

    override fun onDestroy() {
        voiceControllerState.value?.release()
        voiceControllerState.value = null
        super.onDestroy()
    }

    private fun openAuthUrl(url: String) {
        val uri = Uri.parse(url)
        val launchedCustomTab = runCatching {
            CustomTabsIntent.Builder().build().launchUrl(this, uri)
            true
        }.getOrDefault(false)
        if (!launchedCustomTab) {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            }
        }
    }
}

@Composable
private fun ZaiRoot(
    app: ZaiApplication,
    container: AppContainer,
    chatInputText: String,
    onChatInputTextChange: (String) -> Unit,
    voiceListenState: VoiceListenState,
    onToggleVoiceInput: () -> Unit,
    onCancelVoiceInput: () -> Unit,
    onOpenBrowserUrl: (String) -> Unit,
    onShowToast: (String) -> Unit,
) {
    val coordinator = container.agentCoordinator
    val runtime = container.agentRuntime
    val repository = container.sessionRepository

    val settings by repository.settings.collectAsState()
    val sessions by repository.sessions.collectAsState()
    val storageBytes by repository.storageBytes.collectAsState()
    val activeSessionId by coordinator.activeSessionId.collectAsState()
    val runningSessionId by coordinator.runningSessionId.collectAsState()
    val providers by runtime.providers.collectAsState()
    val providerQuotas by runtime.providerQuotas.collectAsState()
    val piReady by runtime.isReady.collectAsState()
    val piVersion by runtime.piVersion.collectAsState()
    val hotUpdateTag by runtime.hotUpdateTag.collectAsState()
    val authState by runtime.authState.collectAsState()
    val isRunning by coordinator.isRunning.collectAsState()
    val liveStepLabel by coordinator.liveStepLabel.collectAsState()
    val sessionContextUsages by coordinator.sessionContextUsages.collectAsState()
    val shizukuStatus by container.shizukuBridge.status.collectAsState()
    val accessibilityConnected by ZaiAccessibilityService.isConnected.collectAsState()
    val pendingConfirmation by coordinator.phoneController.pendingConfirmation.collectAsState()

    var route by remember { mutableStateOf(OverlayRoute.NONE) }
    var latestRelease by remember { mutableStateOf<ReleaseUpdateInfo?>(null) }

    LaunchedEffect(Unit) {
        coordinator.compactionNotices.collect { notice ->
            onShowToast(notice)
        }
    }

    LaunchedEffect(settings.githubUpdateRepo) {
        if (settings.githubUpdateRepo.isNotBlank()) {
            AppUpdater.checkLatestRelease(settings.githubUpdateRepo).onSuccess { info ->
                latestRelease = info
            }
        }
    }

    val activeSession = remember(sessions, activeSessionId) {
        sessions.firstOrNull { it.id == activeSessionId } ?: coordinator.currentSession()
    }
    val visibleProviders = remember(providers, settings.enabledSubscriptionModels) {
        coordinator.visibleProviders(providers)
    }
    val defaultModel = remember(visibleProviders, settings.activeProviderId, settings.activeModelId) {
        coordinator.defaultModelOption()
    }
    val defaultThinkingLevel = remember(defaultModel, settings.thinkingLevel) {
        coordinator.defaultThinkingLevel()
    }
    val sessionModel = remember(visibleProviders, activeSession.providerId, activeSession.modelId, defaultModel) {
        coordinator.sessionModelOption(activeSession)
    }
    val sessionThinkingLevel = remember(sessionModel, activeSession.thinkingLevel, settings.thinkingLevel) {
        coordinator.sessionThinkingLevel(activeSession, sessionModel)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        val enabledSkills = remember(settings.skills) { settings.skills.filter { it.enabled } }
        ChatScreen(
            session = activeSession,
            allSessions = sessions,
            storageBytes = storageBytes,
            providers = visibleProviders,
            providerQuotas = providerQuotas,
            enabledSkills = enabledSkills,
            sessionModel = sessionModel,
            sessionThinkingLevel = sessionThinkingLevel,
            contextUsage = sessionContextUsages[activeSession.id],
            piReady = piReady,
            isRunning = isRunning,
            runningSessionId = runningSessionId,
            liveStepLabel = liveStepLabel,
            shizukuStatus = shizukuStatus,
            accessibilityConnected = accessibilityConnected,
            hasNewUpdate = latestRelease?.isNewer == true,
            pendingConfirmation = pendingConfirmation,
            inputText = chatInputText,
            onInputTextChange = onChatInputTextChange,
            voiceListenState = voiceListenState,
            onToggleVoiceInput = onToggleVoiceInput,
            onCancelVoiceInput = onCancelVoiceInput,
            onRefreshQuota = { pid, force -> runtime.refreshProviderQuota(pid, force) },
            onCompactSession = {
                coordinator.compactActiveSession().onFailure { err ->
                    onShowToast(err.message ?: "暂时无法压缩")
                }
            },
            onSendMessage = { text ->
                coordinator.sendUserMessage(text).onFailure { err ->
                    onShowToast(err.message ?: "无法发送消息")
                }
            },
            onStopRun = { coordinator.stopActiveRun() },
            onFocusRunningSession = { coordinator.focusRunningSession() },
            onSelectSession = { coordinator.selectSession(it) },
            onNewSession = { coordinator.startNewSession() },
            onDeleteSession = { coordinator.deleteSession(it) },
            onSelectSessionModel = { pid, mid -> coordinator.selectSessionModel(pid, mid) },
            onSelectSessionThinking = { lvl -> coordinator.selectSessionThinkingLevel(lvl) },
            onOpenModels = { route = OverlayRoute.MODELS },
            onOpenSettings = { route = OverlayRoute.SETTINGS },
            onResolveConfirmation = { callId, allow ->
                coordinator.phoneController.resolveConfirmation(callId, allow)
            },
        )

        if (route != OverlayRoute.NONE) {
            BackHandler {
                route = when (route) {
                    OverlayRoute.SKILLS, OverlayRoute.MCP -> OverlayRoute.SETTINGS
                    else -> OverlayRoute.NONE
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            ) {
                when (route) {
                    OverlayRoute.MODELS -> ModelsAndAuthScreen(
                        settings = settings,
                        providers = providers,
                        providerQuotas = providerQuotas,
                        activeModel = defaultModel,
                        authState = authState,
                        onRefreshQuota = { pid, force -> runtime.refreshProviderQuota(pid, force) },
                        onConsumeResetCredit = { pid, cid -> runtime.consumeResetCredit(pid, cid) },
                        onUpdateSubscriptionModels = { pid, ids ->
                            coordinator.updateSubscriptionEnabledModels(pid, ids)
                        },
                        onSelectModel = { pid, mid -> coordinator.selectDefaultModel(pid, mid) },
                        onStartChatGptLogin = { method -> runtime.startChatGptLogin(method) },
                        onCancelLogin = { runtime.cancelLogin() },
                        onSubmitAuthPrompt = { id, value -> runtime.submitAuthPrompt(id, value) },
                        onLogoutChatGpt = { runtime.logoutProvider("openai-codex") },
                        onOpenBrowserUrl = onOpenBrowserUrl,
                        onLookupCatalogMetadata = { ids, cb -> runtime.lookupModels(ids, cb) },
                        onSaveCustomProvider = { existingId, name, baseUrl, apiKey, models, defaultImages ->
                            coordinator.saveCustomProvider(existingId, name, baseUrl, apiKey, models, defaultImages)
                            onShowToast("已保存渠道")
                        },
                        onDeleteCustomProvider = { id ->
                            coordinator.deleteCustomProvider(id)
                            onShowToast("已删除渠道")
                        },
                        onGetSavedApiKey = { id -> container.credentialVault.getProviderApiKey(id) },
                        onBack = { route = OverlayRoute.NONE },
                    )

                    OverlayRoute.SETTINGS -> SettingsScreen(
                        settings = settings,
                        providers = visibleProviders,
                        providerQuotas = providerQuotas,
                        defaultModel = defaultModel,
                        defaultThinkingLevel = defaultThinkingLevel,
                        piReady = piReady,
                        piVersion = piVersion,
                        hotUpdateTag = hotUpdateTag,
                        latestRelease = latestRelease,
                        shizukuStatus = shizukuStatus,
                        accessibilityConnected = accessibilityConnected,
                        onReleaseChecked = { latestRelease = it },
                        onUpdateSettings = { repository.updateSettings(it) },
                        onUpdateCompaction = { mode, threshold ->
                            coordinator.updateCompactionSettings(mode, threshold)
                        },
                        onRefreshQuota = { pid, force -> runtime.refreshProviderQuota(pid, force) },
                        onSelectDefaultModel = { pid, mid -> coordinator.selectDefaultModel(pid, mid) },
                        onSelectDefaultThinking = { lvl -> coordinator.selectDefaultThinkingLevel(lvl) },
                        onUpdateUserPrompt = { prompt ->
                            coordinator.updateUserSystemPrompt(prompt)
                            onShowToast("已更新用户提示词")
                        },
                        onOpenSkills = { route = OverlayRoute.SKILLS },
                        onOpenMcpServers = { route = OverlayRoute.MCP },
                        onOpenModels = { route = OverlayRoute.MODELS },
                        onRequestShizuku = {
                            if (!container.shizukuBridge.requestPermission()) {
                                onShowToast("请先打开 Shizuku 并启动服务")
                            }
                        },
                        onTestShizuku = { container.shizukuBridge.exec("id", 4_000) },
                        onRepairAccessibility = { coordinator.repairAccessibilityWithShizuku() },
                        onShizukuInstallApk = { file ->
                            AppUpdater.installApkViaShizuku(app, container.shizukuBridge, file)
                        },
                        onReloadPiHotUpdate = { coordinator.reloadPiEngine(resetToBundled = false) },
                        onResetPiHotUpdate = { coordinator.reloadPiEngine(resetToBundled = true) },
                        onRunSelfCheckPrompt = { prompt ->
                            route = OverlayRoute.NONE
                            if (!coordinator.isRunning.value) {
                                coordinator.startNewSession()
                            }
                            coordinator.sendUserMessage(prompt).onFailure { err ->
                                onShowToast(err.message ?: "无法发起试跑任务")
                            }
                        },
                        onBack = { route = OverlayRoute.NONE },
                    )

                    OverlayRoute.SKILLS -> SkillsScreen(
                        skills = settings.skills,
                        onSaveSkill = { existingId, name, desc, body, enabled ->
                            coordinator.saveSkill(existingId, name, desc, body, enabled)
                            onShowToast("已保存技能")
                        },
                        onToggleSkill = { id, enabled -> coordinator.toggleSkill(id, enabled) },
                        onDeleteSkill = { id ->
                            coordinator.deleteSkill(id)
                            onShowToast("已删除技能")
                        },
                        onBack = { route = OverlayRoute.SETTINGS },
                    )

                    OverlayRoute.MCP -> McpServersScreen(
                        servers = settings.mcpServers,
                        onDiscoverTools = { url, token -> coordinator.discoverMcpTools(url, token) },
                        onSaveServer = { existingId, name, url, token, tools, enabled ->
                            coordinator.saveMcpServer(existingId, name, url, token, tools, enabled)
                            onShowToast("已保存 MCP 服务")
                        },
                        onToggleServer = { id, enabled -> coordinator.toggleMcpServer(id, enabled) },
                        onDeleteServer = { id ->
                            coordinator.deleteMcpServer(id)
                            onShowToast("已删除 MCP 服务")
                        },
                        onGetAuthToken = { id -> coordinator.getMcpAuthToken(id) },
                        onBack = { route = OverlayRoute.SETTINGS },
                    )

                    OverlayRoute.NONE -> Unit
                }
            }
        }
    }
}
