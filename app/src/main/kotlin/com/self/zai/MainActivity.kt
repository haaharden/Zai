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
import com.self.zai.di.AppContainer
import com.self.zai.model.ThemeMode
import com.self.zai.model.VoiceListenState
import com.self.zai.permission.DevicePermissionHelper
import com.self.zai.phone.ZaiAccessibilityService
import com.self.zai.ui.capabilities.McpServersScreen
import com.self.zai.ui.capabilities.SkillsScreen
import com.self.zai.ui.chat.ChatActions
import com.self.zai.ui.chat.ChatScreen
import com.self.zai.ui.chat.ChatUiState
import com.self.zai.ui.models.ModelsAndAuthScreen
import com.self.zai.ui.settings.SettingsActions
import com.self.zai.ui.settings.SettingsScreen
import com.self.zai.ui.settings.SettingsUiState
import com.self.zai.ui.theme.ZaiTheme
import com.self.zai.update.AppUpdater
import com.self.zai.update.ReleaseUpdateInfo

private enum class OverlayRoute { NONE, MODELS, SETTINGS, SKILLS, MCP }

class MainActivity : ComponentActivity() {

    private lateinit var inputHost: MainComposerInputHost

    private val notificationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        inputHost = MainComposerInputHost(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !DevicePermissionHelper.hasNotificationPermission(this)
        ) {
            notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (savedInstanceState == null) {
            inputHost.handleShareIntent(intent)
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
                ZaiRoot(
                    app = app,
                    container = app.container,
                    inputHost = inputHost,
                    onOpenBrowserUrl = ::openAuthUrl,
                    onShowToast = { msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        inputHost.handleShareIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        val container = (application as ZaiApplication).container
        container.agentCoordinator.isAppInForeground = true
        container.deviceHealthCoordinator.ensureHealthyOnForeground(refreshShizuku = true)
    }

    override fun onPause() {
        inputHost.cancelChatVoiceInput()
        (application as ZaiApplication).container.agentCoordinator.isAppInForeground = false
        super.onPause()
    }

    override fun onDestroy() {
        inputHost.release()
        super.onDestroy()
    }

    private fun openAuthUrl(url: String) {
        val uri = Uri.parse(url)
        val launchedCustomTab = runCatching {
            CustomTabsIntent.Builder().build().launchUrl(this, uri)
            true
        }.getOrDefault(false)
        if (!launchedCustomTab) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        }
    }
}

@Composable
private fun ZaiRoot(
    app: ZaiApplication,
    container: AppContainer,
    inputHost: MainComposerInputHost,
    onOpenBrowserUrl: (String) -> Unit,
    onShowToast: (String) -> Unit,
) {
    val settings by container.sessionRepository.settings.collectAsState()
    var route by remember { mutableStateOf(OverlayRoute.NONE) }
    var latestRelease by remember { mutableStateOf<ReleaseUpdateInfo?>(null) }

    LaunchedEffect(Unit) {
        container.agentCoordinator.compactionNotices.collect(onShowToast)
    }

    LaunchedEffect(settings.githubUpdateRepo) {
        if (settings.githubUpdateRepo.isNotBlank()) {
            AppUpdater.checkLatestRelease(settings.githubUpdateRepo).onSuccess { latestRelease = it }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        ChatRoute(
            container = container,
            inputHost = inputHost,
            hasNewUpdate = latestRelease?.isNewer == true,
            onOpenModels = { route = OverlayRoute.MODELS },
            onOpenSettings = { route = OverlayRoute.SETTINGS },
            onShowToast = onShowToast,
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
                    OverlayRoute.MODELS -> ModelsRoute(
                        container = container,
                        onOpenBrowserUrl = onOpenBrowserUrl,
                        onShowToast = onShowToast,
                        onBack = { route = OverlayRoute.NONE },
                    )

                    OverlayRoute.SETTINGS -> SettingsRoute(
                        app = app,
                        container = container,
                        latestRelease = latestRelease,
                        onReleaseChecked = { latestRelease = it },
                        onNavigate = { route = it },
                        onShowToast = onShowToast,
                    )

                    OverlayRoute.SKILLS -> SkillsScreen(
                        skills = settings.skills,
                        onSaveSkill = { existingId, name, desc, body, enabled ->
                            container.capabilitiesManager.saveSkill(existingId, name, desc, body, enabled)
                            onShowToast("已保存技能")
                        },
                        onToggleSkill = { id, enabled -> container.capabilitiesManager.toggleSkill(id, enabled) },
                        onDeleteSkill = { id ->
                            container.capabilitiesManager.deleteSkill(id)
                            onShowToast("已删除技能")
                        },
                        onBack = { route = OverlayRoute.SETTINGS },
                    )

                    OverlayRoute.MCP -> McpServersScreen(
                        servers = settings.mcpServers,
                        onDiscoverTools = { url, token -> container.capabilitiesManager.discoverMcpTools(url, token) },
                        onSaveServer = { existingId, name, url, token, tools, enabled ->
                            container.capabilitiesManager.saveMcpServer(existingId, name, url, token, tools, enabled)
                            onShowToast("已保存 MCP 服务")
                        },
                        onToggleServer = { id, enabled -> container.capabilitiesManager.toggleMcpServer(id, enabled) },
                        onDeleteServer = { id ->
                            container.capabilitiesManager.deleteMcpServer(id)
                            onShowToast("已删除 MCP 服务")
                        },
                        onGetAuthToken = { id -> container.capabilitiesManager.getMcpAuthToken(id) },
                        onBack = { route = OverlayRoute.SETTINGS },
                    )

                    OverlayRoute.NONE -> Unit
                }
            }
        }
    }
}

@Composable
private fun ChatRoute(
    container: AppContainer,
    inputHost: MainComposerInputHost,
    hasNewUpdate: Boolean,
    onOpenModels: () -> Unit,
    onOpenSettings: () -> Unit,
    onShowToast: (String) -> Unit,
) {
    val coordinator = container.agentCoordinator
    val modelSelection = container.modelSelectionManager
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
    val isRunning by coordinator.isRunning.collectAsState()
    val liveStepLabel by coordinator.liveStepLabel.collectAsState()
    val sessionContextUsages by coordinator.sessionContextUsages.collectAsState()
    val shizukuStatus by container.shizukuBridge.status.collectAsState()
    val accessibilityConnected by ZaiAccessibilityService.isConnected.collectAsState()
    val pendingConfirmation by coordinator.phoneController.pendingConfirmation.collectAsState()
    val liveVoiceState = inputHost.voiceControllerState.value?.listenState?.collectAsState()?.value
        ?: VoiceListenState.IDLE

    val activeSession = remember(sessions, activeSessionId) {
        sessions.firstOrNull { it.id == activeSessionId } ?: coordinator.currentSession()
    }
    val visibleProviders = remember(providers, settings.enabledSubscriptionModels) {
        modelSelection.visibleProviders(providers)
    }
    val defaultModel = remember(visibleProviders, settings.activeProviderId, settings.activeModelId) {
        modelSelection.defaultModelOption()
    }
    val sessionModel = remember(visibleProviders, activeSession.providerId, activeSession.modelId, defaultModel) {
        modelSelection.sessionModelOption(activeSession)
    }
    val sessionThinkingLevel = remember(sessionModel, activeSession.thinkingLevel, settings.thinkingLevel) {
        modelSelection.sessionThinkingLevel(activeSession, sessionModel)
    }
    val enabledSkills = remember(settings.skills) { settings.skills.filter { it.enabled } }

    ChatScreen(
        state = ChatUiState(
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
            hasNewUpdate = hasNewUpdate,
            pendingConfirmation = pendingConfirmation,
            inputText = inputHost.chatInputText.value,
            pendingAttachments = inputHost.pendingAttachments.value,
            importingAttachments = inputHost.importingAttachments.value,
            voiceListenState = liveVoiceState,
        ),
        actions = ChatActions(
            onInputTextChange = { inputHost.chatInputText.value = it },
            onPickImages = inputHost::launchImagePicker,
            onTakePhoto = inputHost::launchCameraCapture,
            onPickFiles = inputHost::launchFilePicker,
            onPasteClipboardImage = inputHost::pasteClipboardImage,
            onRemoveAttachment = inputHost::removePendingAttachment,
            onToggleVoiceInput = inputHost::toggleChatVoiceInput,
            onCancelVoiceInput = inputHost::cancelChatVoiceInput,
            onRefreshQuota = { pid, force -> runtime.refreshProviderQuota(pid, force) },
            onCompactSession = {
                coordinator.compactActiveSession().onFailure { err ->
                    onShowToast(err.message ?: "暂时无法压缩")
                }
            },
            onSendMessage = { text, attachments ->
                val res = coordinator.sendUserMessage(text, attachments = attachments)
                res.onSuccess {
                    inputHost.clearPendingAttachments()
                }.onFailure { err ->
                    onShowToast(err.message ?: "无法发送消息")
                }
                res.isSuccess
            },
            onStopRun = coordinator::stopActiveRun,
            onFocusRunningSession = coordinator::focusRunningSession,
            onSelectSession = coordinator::selectSession,
            onNewSession = { coordinator.startNewSession() },
            onDeleteSession = coordinator::deleteSession,
            onSelectSessionModel = { pid, mid -> modelSelection.selectSessionModel(coordinator.currentSession(), pid, mid) },
            onSelectSessionThinking = { lvl -> modelSelection.selectSessionThinkingLevel(coordinator.currentSession(), lvl) },
            onOpenModels = onOpenModels,
            onOpenSettings = onOpenSettings,
            onRepairAccessibility = { container.deviceHealthCoordinator.repairAccessibility() },
            onResolveConfirmation = { callId, allow ->
                coordinator.phoneController.resolveConfirmation(callId, allow)
            },
        ),
    )
}

@Composable
private fun ModelsRoute(
    container: AppContainer,
    onOpenBrowserUrl: (String) -> Unit,
    onShowToast: (String) -> Unit,
    onBack: () -> Unit,
) {
    val runtime = container.agentRuntime
    val modelSelection = container.modelSelectionManager
    val coordinator = container.agentCoordinator
    val settings by container.sessionRepository.settings.collectAsState()
    val providers by runtime.providers.collectAsState()
    val providerQuotas by runtime.providerQuotas.collectAsState()
    val authState by runtime.authState.collectAsState()
    val visibleProviders = remember(providers, settings.enabledSubscriptionModels) {
        modelSelection.visibleProviders(providers)
    }
    val defaultModel = remember(visibleProviders, settings.activeProviderId, settings.activeModelId) {
        modelSelection.defaultModelOption()
    }

    ModelsAndAuthScreen(
        settings = settings,
        providers = providers,
        providerQuotas = providerQuotas,
        activeModel = defaultModel,
        authState = authState,
        onRefreshQuota = { pid, force -> runtime.refreshProviderQuota(pid, force) },
        onConsumeResetCredit = { pid, cid -> runtime.consumeResetCredit(pid, cid) },
        onUpdateSubscriptionModels = { pid, ids -> modelSelection.updateSubscriptionEnabledModels(pid, ids) },
        onSelectModel = { pid, mid -> modelSelection.selectDefaultModel(pid, mid, coordinator.currentSession()) },
        onStartChatGptLogin = { method -> runtime.startChatGptLogin(method) },
        onCancelLogin = { runtime.cancelLogin() },
        onSubmitAuthPrompt = { id, value -> runtime.submitAuthPrompt(id, value) },
        onLogoutChatGpt = { runtime.logoutProvider("openai-codex") },
        onOpenBrowserUrl = onOpenBrowserUrl,
        onLookupCatalogMetadata = { ids, cb -> runtime.lookupModels(ids, cb) },
        onSaveCustomProvider = { existingId, name, baseUrl, apiKey, models, defaultImages ->
            modelSelection.saveCustomProvider(existingId, name, baseUrl, apiKey, models, defaultImages)
            onShowToast("已保存渠道")
        },
        onDeleteCustomProvider = { id ->
            modelSelection.deleteCustomProvider(id)
            onShowToast("已删除渠道")
        },
        onGetSavedApiKey = { id -> container.credentialVault.getProviderApiKey(id) },
        onBack = onBack,
    )
}

@Composable
private fun SettingsRoute(
    app: ZaiApplication,
    container: AppContainer,
    latestRelease: ReleaseUpdateInfo?,
    onReleaseChecked: (ReleaseUpdateInfo?) -> Unit,
    onNavigate: (OverlayRoute) -> Unit,
    onShowToast: (String) -> Unit,
) {
    val runtime = container.agentRuntime
    val coordinator = container.agentCoordinator
    val modelSelection = container.modelSelectionManager
    val capabilities = container.capabilitiesManager
    val repository = container.sessionRepository

    val settings by repository.settings.collectAsState()
    val providers by runtime.providers.collectAsState()
    val providerQuotas by runtime.providerQuotas.collectAsState()
    val piReady by runtime.isReady.collectAsState()
    val piVersion by runtime.piVersion.collectAsState()
    val hotUpdateTag by runtime.hotUpdateTag.collectAsState()
    val shizukuStatus by container.shizukuBridge.status.collectAsState()
    val accessibilityConnected by ZaiAccessibilityService.isConnected.collectAsState()

    var configuredWebSearchProviders by remember { mutableStateOf(capabilities.configuredWebSearchProviders()) }
    val visibleProviders = remember(providers, settings.enabledSubscriptionModels) {
        modelSelection.visibleProviders(providers)
    }
    val defaultModel = remember(visibleProviders, settings.activeProviderId, settings.activeModelId) {
        modelSelection.defaultModelOption()
    }
    val defaultThinkingLevel = remember(defaultModel, settings.thinkingLevel) {
        modelSelection.defaultThinkingLevel()
    }

    SettingsScreen(
        state = SettingsUiState(
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
            configuredWebSearchProviders = configuredWebSearchProviders,
        ),
        actions = SettingsActions(
            onReleaseChecked = onReleaseChecked,
            onUpdateSettings = { repository.updateSettings(it) },
            onUpdateCompaction = { mode, threshold -> capabilities.updateCompactionSettings(mode, threshold) },
            onRefreshQuota = { pid, force -> runtime.refreshProviderQuota(pid, force) },
            onSelectDefaultModel = { pid, mid -> modelSelection.selectDefaultModel(pid, mid, coordinator.currentSession()) },
            onSelectDefaultThinking = { lvl -> modelSelection.selectDefaultThinkingLevel(lvl, coordinator.currentSession()) },
            onUpdateUserPrompt = { prompt ->
                capabilities.updateUserSystemPrompt(prompt)
                onShowToast("已更新用户提示词")
            },
            onOpenSkills = { onNavigate(OverlayRoute.SKILLS) },
            onOpenMcpServers = { onNavigate(OverlayRoute.MCP) },
            onOpenModels = { onNavigate(OverlayRoute.MODELS) },
            onUpdateWebSearchEnabled = capabilities::updateWebSearchEnabled,
            onUpdateWebSearchPriority = capabilities::updateWebSearchPriority,
            onGetWebSearchApiKey = capabilities::getWebSearchApiKey,
            onSaveWebSearchApiKey = { provider, apiKey ->
                capabilities.saveWebSearchApiKey(provider, apiKey)
                configuredWebSearchProviders = capabilities.configuredWebSearchProviders()
            },
            onTestWebSearch = { capabilities.testWebSearch() },
            onRequestShizuku = {
                if (!container.shizukuBridge.requestPermission()) {
                    onShowToast("请先打开 Shizuku 并启动服务")
                }
            },
            onTestShizuku = { container.shizukuBridge.exec("id", 4_000) },
            onRepairAccessibility = { container.deviceHealthCoordinator.repairAccessibility() },
            onGrantWriteSecureViaShizuku = { container.shizukuBridge.grantWriteSecureSettings() },
            onShizukuInstallApk = { file ->
                AppUpdater.installApkViaShizuku(app, container.shizukuBridge, file)
            },
            onReloadPiHotUpdate = { coordinator.reloadPiEngine(resetToBundled = false) },
            onResetPiHotUpdate = { coordinator.reloadPiEngine(resetToBundled = true) },
            onRunSelfCheckPrompt = { prompt ->
                onNavigate(OverlayRoute.NONE)
                if (!coordinator.isRunning.value) {
                    coordinator.startNewSession()
                }
                coordinator.sendUserMessage(prompt).onFailure { err ->
                    onShowToast(err.message ?: "无法发起试跑任务")
                }
            },
            onBack = { onNavigate(OverlayRoute.NONE) },
        ),
    )
}
