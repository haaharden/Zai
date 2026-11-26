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
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.lifecycle.lifecycleScope
import com.self.zai.assistant.VoiceListenState
import com.self.zai.assistant.VoiceSpeechController
import com.self.zai.data.AttachmentManager
import com.self.zai.di.AppContainer
import com.self.zai.model.PendingAttachment
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

private enum class OverlayRoute { NONE, MODELS, SETTINGS, SKILLS, MCP }

class MainActivity : ComponentActivity() {

    private val chatInputText = mutableStateOf("")
    private val pendingAttachments = mutableStateOf<List<PendingAttachment>>(emptyList())
    private val importingAttachments = mutableStateOf(false)
    private val voiceControllerState = mutableStateOf<VoiceSpeechController?>(null)
    private var pendingCameraFile: File? = null

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

    private val pickImagesLauncher = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(AttachmentManager.MAX_ATTACHMENTS_PER_MESSAGE),
    ) { uris ->
        if (uris.isNotEmpty()) {
            importIncomingUris(uris, forcedMime = null)
        }
    }

    private val pickFilesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            importIncomingUris(uris, forcedMime = null)
        }
    }

    private val takePhotoLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { captured ->
        val file = pendingCameraFile
        pendingCameraFile = null
        if (!captured || file == null || !file.exists() || file.length() == 0L) {
            runCatching { file?.delete() }
            return@registerForActivityResult
        }
        importIncomingUris(
            uris = listOf(Uri.fromFile(file)),
            forcedMime = "image/jpeg",
            forcedName = "拍照_${System.currentTimeMillis() % 10000}.jpg",
            cleanupAfterImport = file,
        )
    }

    private fun launchImagePicker() {
        if (pendingAttachments.value.size >= AttachmentManager.MAX_ATTACHMENTS_PER_MESSAGE) {
            Toast.makeText(this, "单条消息最多附加 ${AttachmentManager.MAX_ATTACHMENTS_PER_MESSAGE} 个附件", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            pickImagesLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        }.onFailure {
            runCatching { pickFilesLauncher.launch(arrayOf("image/*")) }.onFailure { err ->
                Toast.makeText(this, "无法打开相册：${err.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun launchCameraCapture() {
        if (pendingAttachments.value.size >= AttachmentManager.MAX_ATTACHMENTS_PER_MESSAGE) {
            Toast.makeText(this, "单条消息最多附加 ${AttachmentManager.MAX_ATTACHMENTS_PER_MESSAGE} 个附件", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val (uri, file) = AttachmentManager.createCameraPhotoUri(this)
            pendingCameraFile = file
            takePhotoLauncher.launch(uri)
        }.onFailure { err ->
            pendingCameraFile?.let { runCatching { it.delete() } }
            pendingCameraFile = null
            Toast.makeText(this, "无法调用相机：${err.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchFilePicker() {
        if (pendingAttachments.value.size >= AttachmentManager.MAX_ATTACHMENTS_PER_MESSAGE) {
            Toast.makeText(this, "单条消息最多附加 ${AttachmentManager.MAX_ATTACHMENTS_PER_MESSAGE} 个附件", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            pickFilesLauncher.launch(arrayOf("*/*"))
        }.onFailure { err ->
            Toast.makeText(this, "无法打开文件选择器：${err.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pasteClipboardImage() {
        if (pendingAttachments.value.size >= AttachmentManager.MAX_ATTACHMENTS_PER_MESSAGE) {
            Toast.makeText(this, "单条消息最多附加 ${AttachmentManager.MAX_ATTACHMENTS_PER_MESSAGE} 个附件", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            importingAttachments.value = true
            val res = AttachmentManager.importFromClipboard(this@MainActivity)
            importingAttachments.value = false
            res.onSuccess { item ->
                pendingAttachments.value = pendingAttachments.value + item
            }.onFailure { err ->
                Toast.makeText(this@MainActivity, err.message ?: "剪贴板里没有图片", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun removePendingAttachment(attachmentId: String) {
        val current = pendingAttachments.value
        val target = current.firstOrNull { it.attachment.id == attachmentId } ?: return
        pendingAttachments.value = current.filterNot { it.attachment.id == attachmentId }
        lifecycleScope.launch(Dispatchers.IO) {
            AttachmentManager.deleteAttachmentFiles(applicationContext, listOf(target.attachment))
        }
    }

    private fun importIncomingUris(
        uris: List<Uri>,
        forcedMime: String? = null,
        forcedName: String? = null,
        cleanupAfterImport: File? = null,
        notifyAddedCount: Boolean = false,
    ) {
        val remaining = AttachmentManager.MAX_ATTACHMENTS_PER_MESSAGE - pendingAttachments.value.size
        if (remaining <= 0) {
            runCatching { cleanupAfterImport?.delete() }
            Toast.makeText(this, "单条消息最多附加 ${AttachmentManager.MAX_ATTACHMENTS_PER_MESSAGE} 个附件", Toast.LENGTH_SHORT).show()
            return
        }
        val toImport = uris.take(remaining)
        val skippedOverflow = uris.size - toImport.size
        lifecycleScope.launch {
            importingAttachments.value = true
            val imported = mutableListOf<PendingAttachment>()
            var firstError: String? = null
            try {
                for (uri in toImport) {
                    val res = AttachmentManager.importUri(
                        context = this@MainActivity,
                        uri = uri,
                        forcedName = forcedName,
                        forcedMime = forcedMime,
                    )
                    res.onSuccess { imported += it }
                        .onFailure { err -> if (firstError == null) firstError = err.message }
                }
            } finally {
                importingAttachments.value = false
                runCatching { cleanupAfterImport?.delete() }
            }
            if (imported.isNotEmpty()) {
                pendingAttachments.value = pendingAttachments.value + imported
                if (notifyAddedCount) {
                    Toast.makeText(this@MainActivity, "已导入 ${imported.size} 个附件", Toast.LENGTH_SHORT).show()
                }
            }
            if (firstError != null) {
                Toast.makeText(this@MainActivity, firstError, Toast.LENGTH_LONG).show()
            } else if (skippedOverflow > 0) {
                Toast.makeText(this@MainActivity, "超出数量上限，已忽略 $skippedOverflow 个附件", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return

        val sharedText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val uris = mutableListOf<Uri>()
        if (action == Intent.ACTION_SEND) {
            val singleUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            if (singleUri != null) uris += singleUri
        } else {
            val multiUris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            }
            if (!multiUris.isNullOrEmpty()) uris += multiUris
        }

        if (sharedText.isNotEmpty()) {
            chatInputText.value = if (chatInputText.value.isBlank()) {
                sharedText
            } else {
                "${chatInputText.value}\n$sharedText"
            }
        }
        if (uris.isNotEmpty()) {
            val mime = intent.type?.takeIf { !it.contains('*') }
            importIncomingUris(uris, forcedMime = mime, notifyAddedCount = true)
        }
        // Clear action so configuration changes / resume won't re-import the same share
        intent.action = Intent.ACTION_MAIN
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
        if (savedInstanceState == null) {
            handleShareIntent(intent)
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
                    pendingAttachments = pendingAttachments.value,
                    importingAttachments = importingAttachments.value,
                    onPickImages = { launchImagePicker() },
                    onTakePhoto = { launchCameraCapture() },
                    onPickFiles = { launchFilePicker() },
                    onPasteClipboardImage = { pasteClipboardImage() },
                    onRemoveAttachment = { removePendingAttachment(it) },
                    onClearPendingAttachments = { pendingAttachments.value = emptyList() },
                    voiceListenState = liveVoiceState,
                    onToggleVoiceInput = { toggleChatVoiceInput() },
                    onCancelVoiceInput = { cancelChatVoiceInput() },
                    onOpenBrowserUrl = { url -> openAuthUrl(url) },
                    onShowToast = { msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        val container = (application as ZaiApplication).container
        container.agentCoordinator.isAppInForeground = true
        ZaiAccessibilityService.isReallyConnected()
        container.shizukuBridge.refreshStatus()
        if (container.shizukuBridge.status.value.binderAlive &&
            container.shizukuBridge.status.value.permissionGranted
        ) {
            container.shizukuBridge.bindAsync()
        }
        container.autoRestoreAccessibilityIfPossible()
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
    pendingAttachments: List<PendingAttachment>,
    importingAttachments: Boolean,
    onPickImages: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickFiles: () -> Unit,
    onPasteClipboardImage: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onClearPendingAttachments: () -> Unit,
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
    var configuredWebSearchProviders by remember { mutableStateOf(coordinator.configuredWebSearchProviders()) }

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
            pendingAttachments = pendingAttachments,
            importingAttachments = importingAttachments,
            onPickImages = onPickImages,
            onTakePhoto = onTakePhoto,
            onPickFiles = onPickFiles,
            onPasteClipboardImage = onPasteClipboardImage,
            onRemoveAttachment = onRemoveAttachment,
            voiceListenState = voiceListenState,
            onToggleVoiceInput = onToggleVoiceInput,
            onCancelVoiceInput = onCancelVoiceInput,
            onRefreshQuota = { pid, force -> runtime.refreshProviderQuota(pid, force) },
            onCompactSession = {
                coordinator.compactActiveSession().onFailure { err ->
                    onShowToast(err.message ?: "暂时无法压缩")
                }
            },
            onSendMessage = { text, attachments ->
                val res = coordinator.sendUserMessage(text, attachments = attachments)
                res.onSuccess {
                    onClearPendingAttachments()
                }.onFailure { err ->
                    onShowToast(err.message ?: "无法发送消息")
                }
                res.isSuccess
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
            onRepairAccessibility = { coordinator.repairAccessibilityWithShizuku() },
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
                        configuredWebSearchProviders = configuredWebSearchProviders,
                        onUpdateWebSearchEnabled = { enabled -> coordinator.updateWebSearchEnabled(enabled) },
                        onUpdateWebSearchPriority = { list -> coordinator.updateWebSearchPriority(list) },
                        onGetWebSearchApiKey = { provider -> coordinator.getWebSearchApiKey(provider) },
                        onSaveWebSearchApiKey = { provider, apiKey ->
                            coordinator.saveWebSearchApiKey(provider, apiKey)
                            configuredWebSearchProviders = coordinator.configuredWebSearchProviders()
                        },
                        onTestWebSearch = { coordinator.testWebSearch() },
                        onRequestShizuku = {
                            if (!container.shizukuBridge.requestPermission()) {
                                onShowToast("请先打开 Shizuku 并启动服务")
                            }
                        },
                        onTestShizuku = { container.shizukuBridge.exec("id", 4_000) },
                        onRepairAccessibility = { coordinator.repairAccessibilityWithShizuku() },
                        onGrantWriteSecureViaShizuku = { container.shizukuBridge.grantWriteSecureSettings() },
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
