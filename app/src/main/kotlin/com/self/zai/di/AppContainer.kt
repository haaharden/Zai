package com.self.zai.di

import android.content.ComponentName
import android.content.Context
import com.self.zai.agent.AgentCoordinator
import com.self.zai.agent.AndroidAppReturnNavigator
import com.self.zai.agent.AppReturnNavigator
import com.self.zai.agent.ModelSelectionManager
import com.self.zai.agent.RunChromeHost
import com.self.zai.data.CredentialVault
import com.self.zai.data.SecureStore
import com.self.zai.data.SessionRepository
import com.self.zai.data.SettingsStore
import com.self.zai.permission.DevicePermissionHelper
import com.self.zai.phone.AndroidAppDirectory
import com.self.zai.phone.AppDirectory
import com.self.zai.phone.PhoneController
import com.self.zai.phone.SafetyGuard
import com.self.zai.phone.ZaiAccessibilityService
import com.self.zai.phone.channel.AndroidCompositeDeviceChannel
import com.self.zai.phone.channel.PhoneDeviceChannel
import com.self.zai.phone.tools.PhoneToolRegistry
import com.self.zai.pi.AgentRuntime
import com.self.zai.pi.PiEngine
import com.self.zai.service.FloatingCapsuleController
import com.self.zai.service.ZaiAgentService
import com.self.zai.shell.ShizukuBridge

/**
 * Explicit manual DI composition root for Zai.
 * Keeps every component wired through interfaces with zero reflection or annotation-processor overhead.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val credentialVault: CredentialVault = SecureStore(appContext)
    val sessionRepository: SessionRepository = SettingsStore(appContext)
    val shizukuBridge: ShizukuBridge = ShizukuBridge(appContext)
    val agentRuntime: AgentRuntime = PiEngine(appContext, credentialVault)
    val appDirectory: AppDirectory = AndroidAppDirectory(appContext)
    val toolRegistry: PhoneToolRegistry = PhoneToolRegistry.default()
    val safetyGuard: SafetyGuard = SafetyGuard()
    val returnNavigator: AppReturnNavigator = AndroidAppReturnNavigator(appContext, shizukuBridge)

    lateinit var agentCoordinator: AgentCoordinator
        private set

    private val floatingCapsule = FloatingCapsuleController(
        context = appContext,
        onStopRequested = {
            if (::agentCoordinator.isInitialized) {
                agentCoordinator.stopActiveRun()
            }
        },
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
    )

    private val chromeHost = object : RunChromeHost {
        override fun showOperatingOverlay(showCapsule: Boolean, showEdgeGlow: Boolean) {
            floatingCapsule.show(showCapsule = showCapsule, showEdgeGlow = showEdgeGlow)
        }

        override fun showCompletedBanner() {
            floatingCapsule.showCompletedBanner()
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

    val phoneController: PhoneController = PhoneController(
        repository = sessionRepository,
        channel = deviceChannel,
        appDirectory = appDirectory,
        toolRegistry = toolRegistry,
        safetyGuard = safetyGuard,
        onStepProgress = { _, _ ->
            if (::agentCoordinator.isInitialized) {
                agentCoordinator.onPhoneOperationStep()
            }
        },
    )

    val modelSelectionManager: ModelSelectionManager = ModelSelectionManager(
        repository = sessionRepository,
        credentialVault = credentialVault,
        runtime = agentRuntime,
        onSessionConfigInvalidated = { sessionId ->
            if (::agentCoordinator.isInitialized) {
                agentCoordinator.invalidateSessionConfig(sessionId)
            }
        },
    )

    init {
        agentCoordinator = AgentCoordinator(
            repository = sessionRepository,
            runtime = agentRuntime,
            phoneController = phoneController,
            modelSelection = modelSelectionManager,
            returnNavigator = returnNavigator,
            chromeHost = chromeHost,
            onStartRuntimeExtra = { shizukuBridge.register() },
            onRepairAccessibility = {
                if (DevicePermissionHelper.hasWriteSecureSettings(appContext)) {
                    val nativeRes = DevicePermissionHelper.restoreAccessibilityViaSecureSettings(appContext)
                    if (nativeRes.isSuccess) return@AgentCoordinator nativeRes
                }
                val component = ComponentName(appContext, ZaiAccessibilityService::class.java).flattenToString()
                shizukuBridge.restoreAccessibilityService(component)
            },
        )
    }

    fun startRuntime() {
        agentCoordinator.startRuntime()
    }
}
