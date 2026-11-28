package com.self.zai.assistant

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.view.View
import com.self.zai.R
import com.self.zai.ZaiApplication
import com.self.zai.phone.ZaiAccessibilityService

/**
 * System Digital Assistant entrypoint registered under `android.app.role.ASSISTANT`.
 * Invoked by Android / HyperOS when the user long-presses Power (0.5s) or triggers the system assistant.
 */
class ZaiVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        // Disable SystemUI AssistStructure / screenshot capture so Android does not flash
        // the white `AssistDisclosure` border window on every Power-button summon
        // (Zai already reads foreground screen context via ZaiAccessibilityService).
        setDisabledShowContext(
            VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT,
        )
    }
}

class ZaiVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        ZaiVoiceInteractionSession(this)
}

class ZaiVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    override fun onCreate() {
        super.onCreate()
        setDisabledShowContext(SHOW_WITH_ASSIST or SHOW_WITH_SCREENSHOT)
        // Fallback transparent theme in case any ROM creates the session Dialog despite setUiEnabled(false).
        setTheme(R.style.Theme_Zai_AssistOverlay)
        setUiEnabled(false)
    }

    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        // Android's VoiceInteractionSession.doShow() resets mUiEnabled=true before calling onPrepareShow()
        // and calls mWindow.show() immediately after if mUiEnabled is still true.
        // Calling setUiEnabled(false) HERE prevents the default white system dialog from ever showing!
        setUiEnabled(false)
    }

    override fun onCreateContentView(): View = View(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // Single-handed toggle: holding Power 0.5s while the overlay is already open dismisses it.
        if (AssistantContextBridge.shouldToggleDismissOnReinvoke()) {
            AssistantContextBridge.requestDismissOverlay()
            hide()
            return
        }
        // Always discard any previous app's snapshot before capturing the current foreground screen.
        AssistantContextBridge.clearScreenContext()
        if (!isKeyguardLocked()) {
            captureForegroundViaAccessibility()
        }

        val intent = Intent(context, ZaiAssistActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
            )
            putExtra(ZaiAssistActivity.EXTRA_FROM_ASSISTANT, true)
            putExtra(ZaiAssistActivity.EXTRA_SHOW_FLAGS, showFlags)
        }
        // Launch as a standard translucent Activity first so HyperOS never renders a white
        // ACTIVITY_TYPE_ASSISTANT starting frame or closes it on session hide().
        // Fall back to startAssistantActivity() only if context.startActivity() throws.
        val launched = runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
        if (!launched) {
            runCatching { startAssistantActivity(intent) }
        }
        hide()
    }

    override fun onHandleAssist(state: AssistState) {
        super.onHandleAssist(state)
        if (isKeyguardLocked()) return
        // Prefer richer Accessibility tree if already captured, otherwise fill from AssistStructure.
        val existing = AssistantContextBridge.currentValidScreenContext()
        if (existing != null) return
        val extracted = AssistantContextBridge.extractAssistStructureSummary(state.assistStructure) ?: return
        val app = context.applicationContext as? ZaiApplication
        val resolvedLabel = app?.container?.appDirectory?.resolveAppLabel(extracted.packageName)
            ?.takeIf { it.isNotBlank() }
            ?: extracted.appLabel
        AssistantContextBridge.updateScreenContext(extracted.copy(appLabel = resolvedLabel))
    }

    private fun isKeyguardLocked(): Boolean =
        (context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked == true

    private fun captureForegroundViaAccessibility() {
        val svc = ZaiAccessibilityService.instance ?: return
        val metrics = context.resources.displayMetrics
        val snap = runCatching { svc.captureScreenTree(metrics.widthPixels, metrics.heightPixels) }.getOrNull()
            ?: return
        if (snap.packageName.isBlank() || snap.packageName == context.packageName) return
        val app = context.applicationContext as? ZaiApplication
        val label = app?.container?.appDirectory?.resolveAppLabel(snap.packageName)
            ?.takeIf { it.isNotBlank() }
            ?: snap.packageName.substringAfterLast('.')
        AssistantContextBridge.updateScreenContext(
            CapturedScreenContext(
                packageName = snap.packageName,
                appLabel = label,
                summary = snap.formatForModel(label).take(3_600),
            ),
        )
    }
}

/**
 * Minimal same-package [RecognitionService] declared in `zai_voice_interaction.xml` so Android's
 * [android.service.voice.VoiceInteractionServiceInfo] validator accepts Zai as a full system assistant.
 * Zai's own [VoiceSpeechController] binds directly to the device's system ASR service (e.g., Xiaomi Speech).
 */
class ZaiRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        runCatching { listener?.error(SpeechRecognizer.ERROR_CLIENT) }
    }

    override fun onCancel(listener: Callback?) = Unit

    override fun onStopListening(listener: Callback?) = Unit
}
