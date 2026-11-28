package com.self.zai.di

import android.app.KeyguardManager
import android.content.Context
import com.self.zai.agent.AgentCoordinator
import com.self.zai.agent.AgentToolDispatcher
import com.self.zai.agent.AndroidAppReturnNavigator
import com.self.zai.agent.AppReturnNavigator
import com.self.zai.agent.AssistantSessionBridge
import com.self.zai.agent.CapabilitiesManager
import com.self.zai.agent.DeviceHealthCoordinator
import com.self.zai.agent.ModelSelectionManager
import com.self.zai.agent.RunChromeHost
import com.self.zai.agent.RunEventBus
import com.self.zai.data.AttachmentManager
import com.self.zai.data.CredentialVault
import com.self.zai.data.SecureStore
import com.self.zai.data.SessionRepository
import com.self.zai.data.SettingsStore
import com.self.zai.mcp.McpToolClient
import com.self.zai.mcp.StreamableHttpMcpClient
import com.self.zai.model.LiveNotificationSnapshotRegistry
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

/** Implemented by Application so Services/Activities/Receivers can retrieve [AppContainer] without importing root Application. */
interface ZaiContainerProvider {
    val container: AppContainer
}

/**
 * Pure manual DI composition root for Zai.
 * Strict acyclic DAG construction with zero reflection, zero back-references, and zero embedded business logic.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val credentialVault: CredentialVault = SecureStore(appContext)
    val sessionRepository: SessionRepository = SettingsStore(appContext)
    val shizukuBridge: ShizukuBridge = ShizukuBridge(appContext)
    val agentRuntime: AgentRuntime = PiEngine(appContext, credentialVault)
    val appDirectory: AppDirectory = AndroidAppDirectory(appContext)
    val assistantBackend = AndroidSystemAssistantBackend(
        context = appContext,
        appDirectory = appDirectory,
        shizuku = shizukuBridge,
        notificationSource = LiveNotificationSnapshotRegistry,
    )
    val toolRegistry: PhoneToolRegistry = PhoneToolRegistry.default(assistantBackend)
    val safetyGuard: SafetyGuard = SafetyGuard()
    val mcpClient: McpToolClient = StreamableHttpMcpClient()
    val webSearchClient: WebSearchClient = HttpWebSearchClient()
    val returnNavigator: AppReturnNavigator = AndroidAppReturnNavigator(appContext, shizukuBridge)
    val runEventBus: RunEventBus = RunEventBus()
    val assistantSessionBridge: AssistantSessionBridge = AssistantSessionBridge()

    val deviceHealthCoordinator: DeviceHealthCoordinator = DeviceHealthCoordinator(
        context = appContext,
        sessionRepository = sessionRepository,
        shizukuBridge = shizukuBridge,
        isAccessibilityConnected = { ZaiAccessibilityService.isReallyConnected() },
    )

    private val floatingCapsule = FloatingCapsuleController(
        context = appContext,
        onStopRequested = { runEventBus.requestStopActiveRun() },
        onOpenRunningSession = { runEventBus.requestFocusRunningSession() },
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
        onEnsureAccessibilityReady = { deviceHealthCoordinator.repairAccessibility() },
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
            runEventBus.emitPhoneOperationStep(targetAppLabel)
        },
        onNativeToolActivity = { label -> runEventBus.emitNonPhoneToolActivity(label) },
        isDeviceLocked = { keyguardManager?.isKeyguardLocked == true },
        requestDeviceUnlock = { reason ->
            assistantSessionBridge.lockscreenUnlockRequester?.invoke(reason) ?: false
        },
    )

    val toolDispatcher: AgentToolDispatcher = AgentToolDispatcher(
        repository = sessionRepository,
        credentialVault = credentialVault,
        phoneController = phoneController,
        mcpClient = mcpClient,
        webSearchClient = webSearchClient,
        onNonPhoneToolActivity = { label -> runEventBus.emitNonPhoneToolActivity(label) },
    )

    val modelSelectionManager: ModelSelectionManager = ModelSelectionManager(
        repository = sessionRepository,
        credentialVault = credentialVault,
        runtime = agentRuntime,
        onSessionConfigInvalidated = { sessionId -> runEventBus.invalidateSessionConfig(sessionId) },
    )

    val capabilitiesManager: CapabilitiesManager = CapabilitiesManager(
        repository = sessionRepository,
        credentialVault = credentialVault,
        mcpClient = mcpClient,
        webSearchClient = webSearchClient,
        onConfigInvalidated = { runEventBus.invalidateSessionConfig(null) },
    )

    val agentCoordinator: AgentCoordinator = AgentCoordinator(
        repository = sessionRepository,
        runtime = agentRuntime,
        phoneController = phoneController,
        toolDispatcher = toolDispatcher,
        modelSelection = modelSelectionManager,
        returnNavigator = returnNavigator,
        chromeHost = chromeHost,
        runEventBus = runEventBus,
        onStartRuntimeExtra = { shizukuBridge.register() },
        onDismissAssistantOverlay = { assistantSessionBridge.requestDismissOverlay() },
        isOverlayVisible = { assistantSessionBridge.isOverlayVisible },
        onSessionDeleted = { deleted -> AttachmentManager.cleanupSessionAttachments(appContext, deleted) },
    )

    fun startRuntime() {
        agentCoordinator.startRuntime()
        deviceHealthCoordinator.startMonitoring()
    }
}
