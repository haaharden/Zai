package com.self.zai.di

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import com.self.zai.agent.AgentCoordinator
import com.self.zai.agent.AgentToolDispatcher
import com.self.zai.agent.AndroidAppReturnNavigator
import com.self.zai.agent.AppReturnNavigator
import com.self.zai.agent.ModelSelectionManager
import com.self.zai.agent.RunChromeHost
import com.self.zai.assistant.AssistantContextBridge
import com.self.zai.data.AttachmentManager
import com.self.zai.data.CredentialVault
import com.self.zai.data.SecureStore
import com.self.zai.data.SessionRepository
import com.self.zai.data.SettingsStore
import com.self.zai.mcp.McpToolClient
import com.self.zai.mcp.StreamableHttpMcpClient
import com.self.zai.permission.DevicePermissionHelper
import com.self.zai.phone.AndroidAppDirectory
import com.self.zai.phone.AppDirectory
import com.self.zai.phone.PhoneController
import com.self.zai.phone.SafetyGuard
import com.self.zai.phone.ZaiAccessibilityService
import com.self.zai.phone.channel.AndroidCompositeDeviceChannel
import com.self.zai.phone.channel.PhoneDeviceChannel
import com.self.zai.phone.tools.AndroidSystemAssistantBackend
import com.self.zai.phone.tools.PhoneToolRegistry
import com.self.zai.pi.AgentRuntime
import com.self.zai.pi.PiEngine
import com.self.zai.service.FloatingCapsuleController
import com.self.zai.service.ZaiAgentService
import com.self.zai.shell.ShizukuBridge
import com.self.zai.web.HttpWebSearchClient
import com.self.zai.web.WebSearchClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Explicit manual DI composition root for Zai.
 * Keeps every component wired through interfaces with zero reflection or annotation-processor overhead.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val accessibilityRepairMutex = Mutex()

    @Volatile
    private var lastFailedAutoRepairAtMs: Long = 0L

    val credentialVault: CredentialVault = SecureStore(appContext)
    val sessionRepository: SessionRepository = SettingsStore(appContext)
    val shizukuBridge: ShizukuBridge = ShizukuBridge(appContext)
    val agentRuntime: AgentRuntime = PiEngine(appContext, credentialVault)
    val appDirectory: AppDirectory = AndroidAppDirectory(appContext)
    val assistantBackend = AndroidSystemAssistantBackend(appContext, appDirectory, shizukuBridge)
    val toolRegistry: PhoneToolRegistry = PhoneToolRegistry.default(assistantBackend)
    val safetyGuard: SafetyGuard = SafetyGuard()
    val mcpClient: McpToolClient = StreamableHttpMcpClient()
    val webSearchClient: WebSearchClient = HttpWebSearchClient()
    val returnNavigator: AppReturnNavigator = AndroidAppReturnNavigator(appContext, shizukuBridge)

    @Volatile
    private var coordinatorRef: AgentCoordinator? = null

    private val floatingCapsule = FloatingCapsuleController(
        context = appContext,
        onStopRequested = { coordinatorRef?.stopActiveRun() },
        onOpenRunningSession = { coordinatorRef?.focusRunningSession() },
    )

    private val deviceChannel: PhoneDeviceChannel = AndroidCompositeDeviceChannel(
        context = appContext,
        repository = sessionRepository,
        shizuku = shizukuBridge,
        onHideOverlayForCapture = { hidden -> floatingCapsule.setTemporarilyHidden(hidden) },
        onTapVisualMarker = { x, y ->
            val s = sessionRepository.settings.value
            if (s.showEdgeGlow || s.showFloatingCapsule) {
                floatingCapsule.showTapMarker(x, y)
            }
        },
        onEnsureAccessibilityReady = { repairAccessibility() },
    )

    private val chromeHost = object : RunChromeHost {
        override fun showOperatingOverlay(showCapsule: Boolean, showEdgeGlow: Boolean, statusLabel: String) {
            floatingCapsule.show(showCapsule = showCapsule, showEdgeGlow = showEdgeGlow, statusLabel = statusLabel)
        }

        override fun showCompletedBanner(statusLabel: String) {
            floatingCapsule.showCompletedBanner(statusLabel = statusLabel)
        }

        override fun dismissOverlay() {
            floatingCapsule.dismiss()
        }

        override fun updateForegroundStatus(status: String) {
            ZaiAgentService.startOrUpdate(appContext, status)
        }

        override fun stopForegroundService() {
            ZaiAgentService.stop(appContext)
        }

        override fun postFinishedFallbackNotification(summary: String) {
            ZaiAgentService.postTaskFinishedFallback(appContext, summary)
        }
    }

    private val keyguardManager: KeyguardManager? =
        appContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager

    val phoneController: PhoneController = PhoneController(
        repository = sessionRepository,
        channel = deviceChannel,
        appDirectory = appDirectory,
        toolRegistry = toolRegistry,
        safetyGuard = safetyGuard,
        onStepProgressWithApp = { _, _, targetAppLabel ->
            coordinatorRef?.onPhoneOperationStep(targetAppLabel)
        },
        onNativeToolActivity = { label -> coordinatorRef?.onNonPhoneToolActivity(label) },
        isDeviceLocked = { keyguardManager?.isKeyguardLocked == true },
        requestDeviceUnlock = { reason ->
            AssistantContextBridge.lockscreenUnlockRequester?.invoke(reason) ?: false
        },
    )

    val toolDispatcher: AgentToolDispatcher = AgentToolDispatcher(
        repository = sessionRepository,
        credentialVault = credentialVault,
        phoneController = phoneController,
        mcpClient = mcpClient,
        webSearchClient = webSearchClient,
        onNonPhoneToolActivity = { label -> coordinatorRef?.onNonPhoneToolActivity(label) },
    )

    val modelSelectionManager: ModelSelectionManager = ModelSelectionManager(
        repository = sessionRepository,
        credentialVault = credentialVault,
        runtime = agentRuntime,
        onSessionConfigInvalidated = { sessionId -> coordinatorRef?.invalidateSessionConfig(sessionId) },
    )

    val agentCoordinator: AgentCoordinator = AgentCoordinator(
        repository = sessionRepository,
        credentialVault = credentialVault,
        runtime = agentRuntime,
        phoneController = phoneController,
        toolDispatcher = toolDispatcher,
        mcpClient = mcpClient,
        modelSelection = modelSelectionManager,
        returnNavigator = returnNavigator,
        chromeHost = chromeHost,
        onStartRuntimeExtra = { shizukuBridge.register() },
        onRepairAccessibility = { repairAccessibility() },
        onDismissAssistantOverlay = { AssistantContextBridge.requestDismissOverlay() },
        isOverlayVisible = { AssistantContextBridge.isOverlayVisible },
        onSessionDeleted = { deleted -> AttachmentManager.cleanupSessionAttachments(appContext, deleted) },
    ).also { coordinatorRef = it }

    suspend fun repairAccessibility(waitForBindTimeoutMs: Long = 1_500L): Result<Unit> =
        accessibilityRepairMutex.withLock {
            if (ZaiAccessibilityService.isReallyConnected()) {
                return@withLock Result.success(Unit)
            }
            var lastFailure: Throwable? = null
            if (DevicePermissionHelper.hasWriteSecureSettings(appContext)) {
                val nativeRes = DevicePermissionHelper.restoreAccessibilityViaSecureSettings(
                    context = appContext,
                    waitForBindTimeoutMs = waitForBindTimeoutMs,
                )
                if (nativeRes.isSuccess) return@withLock nativeRes
                lastFailure = nativeRes.exceptionOrNull()
            }
            val shizukuReady = shizukuBridge.status.value.isReady || shizukuBridge.ensureService() != null
            if (shizukuReady) {
                // Opportunistically grant permanent WRITE_SECURE_SETTINGS via Shizuku so future
                // self-healing survives reboots without requiring Shizuku to be active.
                if (!DevicePermissionHelper.hasWriteSecureSettings(appContext)) {
                    shizukuBridge.grantWriteSecureSettings()
                }
                val cn = ComponentName(appContext, ZaiAccessibilityService::class.java)
                val shizukuRes = shizukuBridge.restoreAccessibilityService(
                    fullComponent = cn.flattenToString(),
                    shortComponent = cn.flattenToShortString(),
                    forceRebind = true,
                )
                if (shizukuRes.isSuccess) {
                    val connected = DevicePermissionHelper.awaitAccessibilityConnected(waitForBindTimeoutMs)
                    if (connected) return@withLock Result.success(Unit)
                    lastFailure = IllegalStateException(
                        "已通过 Shizuku 写入无障碍配置，但系统未完成绑定，请在无障碍页面手动确认（如开关变灰请先允许受限制的设置）",
                    )
                } else {
                    lastFailure = shizukuRes.exceptionOrNull()
                }
            }
            Result.failure(
                lastFailure ?: IllegalStateException("未授予 ADB 永久自愈权限且 Shizuku 未就绪"),
            )
        }

    fun autoRestoreAccessibilityIfPossible(force: Boolean = false) {
        if (!sessionRepository.settings.value.autoRestoreAccessibility) return
        if (ZaiAccessibilityService.isReallyConnected()) return
        val status = shizukuBridge.status.value
        val canUseShizuku = status.isReady || (status.binderAlive && status.permissionGranted)
        if (!DevicePermissionHelper.hasWriteSecureSettings(appContext) && !canUseShizuku) return
        if (accessibilityRepairMutex.isLocked) return
        val now = System.currentTimeMillis()
        if (!force && now - lastFailedAutoRepairAtMs < AUTO_REPAIR_FAILURE_COOLDOWN_MS) return
        appScope.launch {
            val res = repairAccessibility()
            if (res.isFailure) {
                lastFailedAutoRepairAtMs = System.currentTimeMillis()
            }
        }
    }

    fun startRuntime() {
        agentCoordinator.startRuntime()
        autoRestoreAccessibilityIfPossible(force = true)
        appScope.launch {
            shizukuBridge.status.collect { st ->
                if (st.isReady && !ZaiAccessibilityService.isReallyConnected()) {
                    autoRestoreAccessibilityIfPossible()
                }
            }
        }
    }

    companion object {
        private const val AUTO_REPAIR_FAILURE_COOLDOWN_MS = 3_000L
    }
}
