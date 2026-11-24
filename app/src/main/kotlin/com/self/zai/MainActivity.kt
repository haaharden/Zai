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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.self.zai.di.AppContainer
import com.self.zai.permission.DevicePermissionHelper
import com.self.zai.phone.ZaiAccessibilityService
import com.self.zai.ui.chat.ChatScreen
import com.self.zai.ui.models.ModelsAndAuthScreen
import com.self.zai.ui.settings.SettingsScreen
import com.self.zai.ui.theme.ZaiTheme
import com.self.zai.update.AppUpdater
import com.self.zai.update.ReleaseUpdateInfo

private enum class OverlayRoute { NONE, MODELS, SETTINGS }

class MainActivity : ComponentActivity() {

    private val notificationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

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
            ZaiTheme(themeMode = settings.themeMode) {
                ZaiRoot(
                    app = app,
                    container = app.container,
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
        val container = (application as ZaiApplication).container
        container.agentCoordinator.isAppInForeground = false
        super.onPause()
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
    onOpenBrowserUrl: (String) -> Unit,
    onShowToast: (String) -> Unit,
) {
    val coordinator = container.agentCoordinator
    val runtime = container.agentRuntime
    val repository = container.sessionRepository

    val settings by repository.settings.collectAsState()
    val sessions by repository.sessions.collectAsState()
    val activeSessionId by coordinator.activeSessionId.collectAsState()
    val providers by runtime.providers.collectAsState()
    val piReady by runtime.isReady.collectAsState()
    val piVersion by runtime.piVersion.collectAsState()
    val hotUpdateTag by runtime.hotUpdateTag.collectAsState()
    val authState by runtime.authState.collectAsState()
    val isRunning by coordinator.isRunning.collectAsState()
    val liveStepLabel by coordinator.liveStepLabel.collectAsState()
    val shizukuStatus by container.shizukuBridge.status.collectAsState()
    val accessibilityConnected by ZaiAccessibilityService.isConnected.collectAsState()
    val pendingConfirmation by coordinator.phoneController.pendingConfirmation.collectAsState()

    var route by remember { mutableStateOf(OverlayRoute.NONE) }
    var latestRelease by remember { mutableStateOf<ReleaseUpdateInfo?>(null) }

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
    val defaultModel = remember(providers, settings.activeProviderId, settings.activeModelId) {
        coordinator.defaultModelOption()
    }
    val defaultThinkingLevel = remember(defaultModel, settings.thinkingLevel) {
        coordinator.defaultThinkingLevel()
    }
    val sessionModel = remember(providers, activeSession.providerId, activeSession.modelId, defaultModel) {
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
        ChatScreen(
            session = activeSession,
            allSessions = sessions,
            providers = providers,
            sessionModel = sessionModel,
            sessionThinkingLevel = sessionThinkingLevel,
            piReady = piReady,
            isRunning = isRunning,
            liveStepLabel = liveStepLabel,
            shizukuStatus = shizukuStatus,
            accessibilityConnected = accessibilityConnected,
            hasNewUpdate = latestRelease?.isNewer == true,
            pendingConfirmation = pendingConfirmation,
            onSendMessage = { text ->
                coordinator.sendUserMessage(text).onFailure { err ->
                    onShowToast(err.message ?: "无法发送消息")
                }
            },
            onStopRun = { coordinator.stopActiveRun() },
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
            BackHandler { route = OverlayRoute.NONE }
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
                        activeModel = defaultModel,
                        authState = authState,
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
                        providers = providers,
                        defaultModel = defaultModel,
                        defaultThinkingLevel = defaultThinkingLevel,
                        piVersion = piVersion,
                        hotUpdateTag = hotUpdateTag,
                        latestRelease = latestRelease,
                        shizukuStatus = shizukuStatus,
                        accessibilityConnected = accessibilityConnected,
                        onReleaseChecked = { latestRelease = it },
                        onUpdateSettings = { repository.updateSettings(it) },
                        onSelectDefaultModel = { pid, mid -> coordinator.selectDefaultModel(pid, mid) },
                        onSelectDefaultThinking = { lvl -> coordinator.selectDefaultThinkingLevel(lvl) },
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
                        onBack = { route = OverlayRoute.NONE },
                    )

                    OverlayRoute.NONE -> Unit
                }
            }
        }
    }
}
