package com.self.zai.assistant

import android.Manifest
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.self.zai.MainActivity
import com.self.zai.ZaiApplication
import com.self.zai.model.MessageRole
import com.self.zai.permission.DevicePermissionHelper
import com.self.zai.phone.ZaiAccessibilityService
import com.self.zai.ui.theme.ZaiTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Translucent bottom-sheet assistant Activity invoked by long-pressing Power 0.5s,
 * Android's `ACTION_ASSIST` / `ACTION_VOICE_ASSIST`, or the Quick Settings tile.
 */
class ZaiAssistActivity : ComponentActivity() {

    private var speechController: VoiceSpeechController? = null
    private var inputTextState = mutableStateOf("")
    private var attachScreenState = mutableStateOf(false)
    private var visibleState = mutableStateOf(true)
    private var overlaySessionId = mutableStateOf<String?>(null)
    private var overlayStartIndex = mutableStateOf<Int?>(null)
    private var invokedAtMs = mutableStateOf(System.currentTimeMillis())
    private var isResumedState = mutableStateOf(false)
    private var lastAutoListenedInvocationMs: Long = -1L
    private var lastSpokenMessageId: String? = null
    private var suppressAutoDismissUntilMs: Long = 0L
    private var systemReceiverRegistered: Boolean = false
    private var unlockRequesterRef: (suspend (String) -> Boolean)? = null

    private val systemDismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> dismissCleanly()
                @Suppress("DEPRECATION")
                Intent.ACTION_CLOSE_SYSTEM_DIALOGS -> {
                    val reason = intent.getStringExtra("reason").orEmpty()
                    if (reason in SYSTEM_DISMISS_REASONS) {
                        dismissCleanly()
                    }
                }
            }
        }
    }

    private val audioPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        suppressAutoDismissUntilMs = 0L
        if (granted) {
            speechController?.startListening()
        } else {
            Toast.makeText(this, "需要麦克风权限才能语音提问", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        disableTransitionAnimation()
        enableEdgeToEdge()
        registerSystemDismissReceiver()

        val app = application as ZaiApplication
        val container = app.container
        ZaiAccessibilityService.isReallyConnected()
        container.autoRestoreVoiceInteractionIfPossible()
        container.autoRestoreAccessibilityIfPossible()
        val settings = container.sessionRepository.settings.value

        val unlockHandler: suspend (String) -> Boolean = { reason -> requestUnlockForSensitiveTool(reason) }
        unlockRequesterRef = unlockHandler
        AssistantContextBridge.lockscreenUnlockRequester = unlockHandler

        if (isDeviceLocked()) {
            AssistantContextBridge.clearScreenContext()
            attachScreenState.value = false
        } else {
            captureScreenIfMissing(app)
            attachScreenState.value = settings.assistantAttachScreenDefault &&
                AssistantContextBridge.currentValidScreenContext() != null
        }
        prepareOverlaySession(app)
        AssistantContextBridge.overlayOpenedAtMs = System.currentTimeMillis()

        val controller = VoiceSpeechController(
            context = this,
            onPartialTranscript = { partial -> inputTextState.value = partial },
            onFinalTranscript = { finalText ->
                inputTextState.value = finalText
                dispatchCurrentPrompt(app)
            },
            onErrorMessage = { msg ->
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            },
        )
        speechController = controller

        setContent {
            val liveSettings by container.sessionRepository.settings.collectAsState()
            val sessions by container.sessionRepository.sessions.collectAsState()
            val activeSessionId by container.agentCoordinator.activeSessionId.collectAsState()
            val providers by container.agentRuntime.providers.collectAsState()
            val piReady by container.agentRuntime.isReady.collectAsState()
            val isRunning by container.agentCoordinator.isRunning.collectAsState()
            val liveStepLabel by container.agentCoordinator.liveStepLabel.collectAsState()
            val pendingConfirmation by container.agentCoordinator.phoneController.pendingConfirmation.collectAsState()
            val capturedScreen by AssistantContextBridge.screenContext.collectAsState()
            val listenState by controller.listenState.collectAsState()
            val isSpeaking by controller.isSpeaking.collectAsState()

            val activeSession = remember(sessions, activeSessionId) {
                sessions.firstOrNull { it.id == activeSessionId } ?: container.agentCoordinator.currentSession()
            }
            val sessionModel = remember(providers, activeSession.providerId, activeSession.modelId, liveSettings.activeModelId) {
                container.agentCoordinator.sessionModelOption(activeSession)
            }

            // Show all turns belonging to this overlay invocation (including active in-flight turn on reopen)
            val startIdx = if (overlaySessionId.value == activeSession.id) overlayStartIndex.value else null
            val overlayMessages = remember(activeSession.messages, startIdx) {
                if (startIdx == null || startIdx >= activeSession.messages.size) {
                    emptyList()
                } else {
                    activeSession.messages.subList(startIdx.coerceAtLeast(0), activeSession.messages.size)
                }
            }

            // Auto-dismiss when Zai starts touching the screen or user re-presses Power 0.5s to toggle off
            LaunchedEffect(Unit) {
                AssistantContextBridge.dismissOverlayRequests.collect {
                    dismissCleanly()
                }
            }

            // Trigger optional TTS readout once an overlay-initiated reply finishes cleanly
            var wasRunning by remember { mutableStateOf(false) }
            LaunchedEffect(isRunning, activeSession.messages.lastOrNull()?.id) {
                if (wasRunning && !isRunning && overlayMessages.isNotEmpty()) {
                    val lastReply = overlayMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
                    if (lastReply != null && !lastReply.isError && lastReply.text.isNotBlank() && lastReply.id != lastSpokenMessageId) {
                        lastSpokenMessageId = lastReply.id
                        speechController?.speakIfEligible(lastReply.text, liveSettings.assistantTtsMode)
                    }
                }
                wasRunning = isRunning
            }

            // Auto-start voice listening once the overlay is actually resumed (resilient to cold start)
            LaunchedEffect(invokedAtMs.value, isResumedState.value) {
                val invocationToken = invokedAtMs.value
                if (isResumedState.value &&
                    lastAutoListenedInvocationMs != invocationToken &&
                    liveSettings.assistantAutoListen &&
                    DevicePermissionHelper.hasAudioPermission(this@ZaiAssistActivity) &&
                    !container.agentCoordinator.isRunning.value
                ) {
                    delay(180L)
                    if (!isFinishing && !isDestroyed && isResumedState.value && lastAutoListenedInvocationMs != invocationToken) {
                        lastAutoListenedInvocationMs = invocationToken
                        speechController?.startListening()
                    }
                }
            }

            ZaiTheme(themeMode = liveSettings.themeMode) {
                AssistantOverlayScreen(
                    visible = visibleState.value,
                    model = sessionModel,
                    piReady = piReady,
                    isRunning = isRunning,
                    liveStepLabel = liveStepLabel,
                    listenState = listenState,
                    isSpeaking = isSpeaking,
                    inputText = inputTextState.value,
                    onInputChange = { next ->
                        if (controller.listenState.value == VoiceListenState.LISTENING) {
                            controller.cancelListening()
                        }
                        inputTextState.value = next
                    },
                    onCancelMic = { controller.cancelListening() },
                    attachScreen = attachScreenState.value,
                    capturedScreen = capturedScreen,
                    onToggleAttachScreen = {
                        if (isDeviceLocked()) {
                            attachScreenState.value = false
                            Toast.makeText(this@ZaiAssistActivity, "锁屏状态下无法附带屏幕，请先解锁", Toast.LENGTH_SHORT).show()
                        } else {
                            attachScreenState.value = !attachScreenState.value
                        }
                    },
                    overlayMessages = overlayMessages,
                    pendingConfirmation = pendingConfirmation,
                    onSend = { dispatchCurrentPrompt(app) },
                    onStopRun = {
                        speechController?.stopSpeaking()
                        container.agentCoordinator.stopActiveRun()
                    },
                    onToggleMic = {
                        val sc = speechController ?: return@AssistantOverlayScreen
                        if (sc.listenState.value == VoiceListenState.LISTENING) {
                            sc.stopListening()
                        } else if (DevicePermissionHelper.hasAudioPermission(this@ZaiAssistActivity)) {
                            sc.startListening()
                        } else {
                            suppressAutoDismissUntilMs = System.currentTimeMillis() + 10_000L
                            audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onToggleSpeakLatest = {
                        val sc = speechController ?: return@AssistantOverlayScreen
                        if (sc.isSpeaking.value) {
                            sc.stopSpeaking()
                        } else {
                            val latest = overlayMessages.lastOrNull { it.role == MessageRole.ASSISTANT }?.text.orEmpty()
                            if (latest.isNotBlank()) sc.speakNow(latest)
                        }
                    },
                    onNewOverlayChat = {
                        speechController?.stopSpeaking()
                        val created = container.agentCoordinator.startNewSession()
                        overlaySessionId.value = created.id
                        overlayStartIndex.value = 0
                        inputTextState.value = ""
                    },
                    onExpandFullApp = { expandToFullApp() },
                    onResolveConfirmation = { callId, allow ->
                        container.agentCoordinator.phoneController.resolveConfirmation(callId, allow)
                    },
                    onDismiss = { dismissCleanly() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Re-invoking assistant while overlay is already open acts as a clean single-handed toggle off.
        if (intent.getBooleanExtra(EXTRA_FROM_ASSISTANT, false) &&
            AssistantContextBridge.shouldToggleDismissOnReinvoke()
        ) {
            dismissCleanly()
            return
        }
        val app = application as ZaiApplication
        ZaiAccessibilityService.isReallyConnected()
        app.container.autoRestoreVoiceInteractionIfPossible()
        app.container.autoRestoreAccessibilityIfPossible()
        visibleState.value = true
        AssistantContextBridge.overlayOpenedAtMs = System.currentTimeMillis()
        if (isDeviceLocked()) {
            AssistantContextBridge.clearScreenContext()
            attachScreenState.value = false
        } else {
            captureScreenIfMissing(app)
        }
        prepareOverlaySession(app)
        invokedAtMs.value = System.currentTimeMillis()
    }

    override fun onResume() {
        super.onResume()
        isResumedState.value = true
        AssistantContextBridge.isOverlayVisible = true
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val now = System.currentTimeMillis()
        if (now > suppressAutoDismissUntilMs && now - AssistantContextBridge.overlayOpenedAtMs > 500L) {
            dismissCleanly()
        }
    }

    override fun onPause() {
        isResumedState.value = false
        AssistantContextBridge.isOverlayVisible = false
        speechController?.cancelListening()
        (application as? ZaiApplication)?.container?.agentCoordinator?.onUiBackgroundedIfRunning()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        // Redundant safety net alongside ACTION_SCREEN_OFF so locking the phone never leaves overlay alive
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null && !pm.isInteractive) {
            dismissCleanly()
        }
    }

    override fun onDestroy() {
        AssistantContextBridge.isOverlayVisible = false
        AssistantContextBridge.clearScreenContext()
        if (AssistantContextBridge.lockscreenUnlockRequester === unlockRequesterRef) {
            AssistantContextBridge.lockscreenUnlockRequester = null
        }
        unlockRequesterRef = null
        unregisterSystemDismissReceiver()
        speechController?.release()
        speechController = null
        super.onDestroy()
    }

    private fun registerSystemDismissReceiver() {
        if (systemReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            @Suppress("DEPRECATION")
            addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
        }
        runCatching {
            ContextCompat.registerReceiver(
                this,
                systemDismissReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            systemReceiverRegistered = true
        }
    }

    private fun unregisterSystemDismissReceiver() {
        if (!systemReceiverRegistered) return
        systemReceiverRegistered = false
        runCatching { unregisterReceiver(systemDismissReceiver) }
    }

    private fun expandToFullApp() {
        speechController?.stopSpeaking()
        speechController?.cancelListening()
        val launchMain = {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                },
            )
            dismissCleanly()
        }
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (km != null && km.isKeyguardLocked) {
            suppressAutoDismissUntilMs = System.currentTimeMillis() + 15_000L
            km.requestDismissKeyguard(
                this,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() {
                        suppressAutoDismissUntilMs = 0L
                        launchMain()
                    }

                    override fun onDismissCancelled() {
                        suppressAutoDismissUntilMs = 0L
                    }

                    override fun onDismissError() {
                        suppressAutoDismissUntilMs = 0L
                        if (!km.isKeyguardLocked) {
                            launchMain()
                        }
                    }
                },
            )
        } else {
            launchMain()
        }
    }

    private fun isDeviceLocked(): Boolean =
        (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked == true

    private suspend fun requestUnlockForSensitiveTool(reason: String): Boolean = withContext(Dispatchers.Main) {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return@withContext false
        if (!km.isKeyguardLocked) return@withContext true
        if (isFinishing || isDestroyed) return@withContext false

        Toast.makeText(this@ZaiAssistActivity, "「$reason」需要先解锁设备", Toast.LENGTH_SHORT).show()
        val deferred = CompletableDeferred<Boolean>()
        suppressAutoDismissUntilMs = System.currentTimeMillis() + 30_000L
        try {
            km.requestDismissKeyguard(
                this@ZaiAssistActivity,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() {
                        if (!deferred.isCompleted) deferred.complete(!km.isKeyguardLocked)
                    }

                    override fun onDismissCancelled() {
                        if (!deferred.isCompleted) deferred.complete(false)
                    }

                    override fun onDismissError() {
                        if (!deferred.isCompleted) deferred.complete(!km.isKeyguardLocked)
                    }
                },
            )
            withTimeoutOrNull(30_000L) { deferred.await() } ?: false
        } catch (_: Throwable) {
            false
        } finally {
            suppressAutoDismissUntilMs = 0L
        }
    }

    private fun prepareOverlaySession(app: ZaiApplication) {
        val coordinator = app.container.agentCoordinator
        if (coordinator.isRunning.value) {
            val current = coordinator.currentSession()
            overlaySessionId.value = current.id
            val lastUserIdx = current.messages.indexOfLast { it.role == MessageRole.USER }
            overlayStartIndex.value = if (lastUserIdx >= 0) lastUserIdx else 0
            return
        }
        var current = coordinator.currentSession()
        val staleMs = System.currentTimeMillis() - current.updatedAtMs
        // On lockscreen, always isolate into a fresh session so prior unlocked history is never exposed.
        if (current.messages.isNotEmpty() && (isDeviceLocked() || staleMs > FRESH_SESSION_THRESHOLD_MS)) {
            current = coordinator.startNewSession()
        }
        overlaySessionId.value = current.id
        overlayStartIndex.value = current.messages.size
    }

    private fun captureScreenIfMissing(app: ZaiApplication) {
        if (isDeviceLocked()) return
        if (AssistantContextBridge.currentValidScreenContext() != null) return
        val svc = ZaiAccessibilityService.instance ?: return
        val dm = resources.displayMetrics
        val snap = runCatching { svc.captureScreenTree(dm.widthPixels, dm.heightPixels) }.getOrNull() ?: return
        if (snap.packageName.isBlank() || snap.packageName == packageName) return
        val label = app.container.appDirectory.resolveAppLabel(snap.packageName)
            .takeIf { it.isNotBlank() }
            ?: snap.packageName.substringAfterLast('.')
        AssistantContextBridge.updateScreenContext(
            CapturedScreenContext(
                packageName = snap.packageName,
                appLabel = label,
                summary = snap.formatForModel(label).take(3_600),
            ),
        )
    }

    private fun dispatchCurrentPrompt(app: ZaiApplication) {
        val text = inputTextState.value.trim()
        if (text.isEmpty()) return
        speechController?.stopSpeaking()
        speechController?.cancelListening()

        val coordinator = app.container.agentCoordinator
        val current = coordinator.currentSession()
        if (overlaySessionId.value != current.id || overlayStartIndex.value == null) {
            overlaySessionId.value = current.id
            overlayStartIndex.value = current.messages.size
        }

        val screenContext = if (attachScreenState.value && !isDeviceLocked()) {
            AssistantContextBridge.currentValidScreenContext()?.summary
        } else {
            null
        }
        val res = coordinator.sendUserMessage(
            rawText = text,
            extraContextForModel = screenContext,
        )
        res.onSuccess {
            inputTextState.value = ""
        }.onFailure { err ->
            Toast.makeText(this, err.message ?: "无法发送请求", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dismissCleanly() {
        if (isFinishing || isDestroyed) return
        AssistantContextBridge.isOverlayVisible = false
        AssistantContextBridge.clearScreenContext()
        speechController?.cancelListening()
        speechController?.stopSpeaking()
        visibleState.value = false
        finishAndRemoveTask()
        disableTransitionAnimation()
    }

    private fun disableTransitionAnimation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        const val EXTRA_FROM_ASSISTANT = "extra_from_assistant"
        const val EXTRA_SHOW_FLAGS = "extra_show_flags"
        private const val FRESH_SESSION_THRESHOLD_MS = 10 * 60 * 1000L
        private val SYSTEM_DISMISS_REASONS = setOf(
            "homekey",
            "recentapps",
            "dream",
            "lock",
            "globalactions",
        )
    }
}
