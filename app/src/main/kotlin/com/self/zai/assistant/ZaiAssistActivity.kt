package com.self.zai.assistant

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
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
import com.self.zai.MainActivity
import com.self.zai.ZaiApplication
import com.self.zai.model.MessageRole
import com.self.zai.permission.DevicePermissionHelper
import com.self.zai.phone.ZaiAccessibilityService
import com.self.zai.ui.theme.ZaiTheme
import kotlinx.coroutines.delay

/**
 * Translucent bottom-sheet assistant Activity invoked by long-pressing Power 0.5s,
 * Android's `ACTION_ASSIST` / `ACTION_VOICE_ASSIST`, or the Quick Settings tile.
 */
class ZaiAssistActivity : ComponentActivity() {

    private var speechController: VoiceSpeechController? = null
    private var inputTextState = mutableStateOf("")
    private var attachScreenState = mutableStateOf(false)
    private var visibleState = mutableStateOf(true)
    private var overlayTurnCount = mutableStateOf(0)
    private var invokedAtMs = mutableStateOf(System.currentTimeMillis())
    private var lastSpokenMessageId: String? = null

    private val audioPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
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

        val app = application as ZaiApplication
        val container = app.container
        val settings = container.sessionRepository.settings.value

        captureScreenIfMissing(app)
        attachScreenState.value = settings.assistantAttachScreenDefault &&
            AssistantContextBridge.currentValidScreenContext() != null
        prepareOverlaySession(app)

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

            // Show only turns from the current overlay session (or latest turn if user sent from here)
            val overlayMessages = remember(activeSession.messages, overlayTurnCount.value) {
                if (overlayTurnCount.value <= 0) emptyList() else activeSession.messages.takeLast(2)
            }

            // Auto-dismiss the overlay the instant Zai begins touching the underlying screen
            LaunchedEffect(Unit) {
                AssistantContextBridge.dismissOverlayRequests.collect {
                    dismissCleanly()
                }
            }

            // Trigger optional TTS readout once an overlay-initiated reply finishes cleanly
            var wasRunning by remember { mutableStateOf(false) }
            LaunchedEffect(isRunning, activeSession.messages.lastOrNull()?.id) {
                if (wasRunning && !isRunning && overlayTurnCount.value > 0) {
                    val lastReply = activeSession.messages.lastOrNull { it.role == MessageRole.ASSISTANT }
                    if (lastReply != null && !lastReply.isError && lastReply.text.isNotBlank() && lastReply.id != lastSpokenMessageId) {
                        lastSpokenMessageId = lastReply.id
                        speechController?.speakIfEligible(lastReply.text, liveSettings.assistantTtsMode)
                    }
                }
                wasRunning = isRunning
            }

            // Auto-start voice listening on open once the overlay is resumed and foreground
            LaunchedEffect(invokedAtMs.value) {
                if (liveSettings.assistantAutoListen &&
                    DevicePermissionHelper.hasAudioPermission(this@ZaiAssistActivity) &&
                    !container.agentCoordinator.isRunning.value
                ) {
                    delay(220L)
                    if (!isFinishing && !isDestroyed && AssistantContextBridge.isOverlayVisible) {
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
                    onInputChange = { inputTextState.value = it },
                    attachScreen = attachScreenState.value,
                    capturedScreen = capturedScreen,
                    onToggleAttachScreen = {
                        attachScreenState.value = !attachScreenState.value
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
                        container.agentCoordinator.startNewSession()
                        overlayTurnCount.value = 0
                        inputTextState.value = ""
                    },
                    onExpandFullApp = {
                        speechController?.stopSpeaking()
                        startActivity(
                            Intent(this@ZaiAssistActivity, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                            },
                        )
                        dismissCleanly()
                    },
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
        val app = application as ZaiApplication
        visibleState.value = true
        captureScreenIfMissing(app)
        invokedAtMs.value = System.currentTimeMillis()
    }

    override fun onResume() {
        super.onResume()
        AssistantContextBridge.isOverlayVisible = true
    }

    override fun onPause() {
        AssistantContextBridge.isOverlayVisible = false
        speechController?.cancelListening()
        super.onPause()
    }

    override fun onDestroy() {
        AssistantContextBridge.isOverlayVisible = false
        speechController?.release()
        speechController = null
        super.onDestroy()
    }

    private fun prepareOverlaySession(app: ZaiApplication) {
        val coordinator = app.container.agentCoordinator
        if (coordinator.isRunning.value) return
        val current = coordinator.currentSession()
        val staleMs = System.currentTimeMillis() - current.updatedAtMs
        if (current.messages.isNotEmpty() && staleMs > FRESH_SESSION_THRESHOLD_MS) {
            coordinator.startNewSession()
        }
    }

    private fun captureScreenIfMissing(app: ZaiApplication) {
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

        val screenContext = if (attachScreenState.value) {
            AssistantContextBridge.currentValidScreenContext()?.summary
        } else {
            null
        }
        val res = app.container.agentCoordinator.sendUserMessage(
            rawText = text,
            extraContextForModel = screenContext,
        )
        res.onSuccess {
            inputTextState.value = ""
            overlayTurnCount.value += 1
        }.onFailure { err ->
            Toast.makeText(this, err.message ?: "无法发送请求", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dismissCleanly() {
        speechController?.cancelListening()
        speechController?.stopSpeaking()
        visibleState.value = false
        finish()
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
    }
}
