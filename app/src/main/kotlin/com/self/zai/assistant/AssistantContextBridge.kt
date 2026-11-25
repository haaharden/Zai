package com.self.zai.assistant

import android.app.assist.AssistStructure
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

data class CapturedScreenContext(
    val packageName: String,
    val appLabel: String,
    val summary: String,
    val capturedAtMs: Long = System.currentTimeMillis(),
)

/**
 * Lightweight in-process bridge connecting [ZaiVoiceInteractionSession], [ZaiAssistActivity],
 * and [com.self.zai.agent.AgentCoordinator]:
 * - Stores the pre-captured foreground screen snapshot taken immediately before the assistant overlay appears.
 * - Broadcasts overlay dismissal requests the moment Zai begins a physical screen-touching phone action.
 */
object AssistantContextBridge {

    private const val MAX_AGE_MS = 90_000L
    private const val MAX_ASSIST_NODES = 80
    private const val MAX_SUMMARY_CHARS = 3_200

    private val _screenContext = MutableStateFlow<CapturedScreenContext?>(null)
    val screenContext: StateFlow<CapturedScreenContext?> = _screenContext.asStateFlow()

    private val _dismissOverlayRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val dismissOverlayRequests: SharedFlow<Unit> = _dismissOverlayRequests.asSharedFlow()

    @Volatile
    var isOverlayVisible: Boolean = false

    @Volatile
    var overlayOpenedAtMs: Long = 0L

    fun shouldToggleDismissOnReinvoke(nowMs: Long = System.currentTimeMillis()): Boolean =
        isOverlayVisible && (nowMs - overlayOpenedAtMs > REVOKE_TOGGLE_DEBOUNCE_MS)

    private const val REVOKE_TOGGLE_DEBOUNCE_MS = 600L

    fun updateScreenContext(context: CapturedScreenContext?) {
        _screenContext.value = context
    }

    fun currentValidScreenContext(nowMs: Long = System.currentTimeMillis()): CapturedScreenContext? {
        val ctx = _screenContext.value ?: return null
        return if (nowMs - ctx.capturedAtMs <= MAX_AGE_MS && ctx.summary.isNotBlank()) ctx else null
    }

    fun clearScreenContext() {
        _screenContext.value = null
    }

    fun requestDismissOverlay() {
        _dismissOverlayRequests.tryEmit(Unit)
    }

    /**
     * Extracts compact readable text lines from Android's [AssistStructure] when invoked via
     * [android.service.voice.VoiceInteractionSession.onHandleAssist]. Serves as an automatic
     * fallback even when Accessibility is temporarily unavailable.
     */
    fun extractAssistStructureSummary(structure: AssistStructure?): CapturedScreenContext? {
        if (structure == null || structure.windowNodeCount <= 0) return null
        val pkg = structure.activityComponent?.packageName.orEmpty()
        if (pkg.isBlank() || pkg == "com.self.zai") return null

        val lines = LinkedHashSet<String>()
        fun walk(node: AssistStructure.ViewNode?, depth: Int) {
            if (node == null || depth > 24 || lines.size >= MAX_ASSIST_NODES) return
            if (node.visibility != android.view.View.VISIBLE) return
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) lines.add(text.replace("\n", " ").take(120))
            if (desc.isNotEmpty() && desc != text) lines.add(desc.replace("\n", " ").take(100))
            for (i in 0 until node.childCount) {
                walk(node.getChildAt(i), depth + 1)
            }
        }

        for (w in 0 until structure.windowNodeCount) {
            val win = structure.getWindowNodeAt(w)
            walk(win.rootViewNode, 0)
        }
        if (lines.isEmpty()) return null

        val summary = buildString {
            appendLine("app: $pkg (via assist_structure)")
            for (line in lines) {
                if (length + line.length + 2 > MAX_SUMMARY_CHARS) break
                appendLine(line)
            }
        }.trim()

        return CapturedScreenContext(
            packageName = pkg,
            appLabel = pkg.substringAfterLast('.'),
            summary = summary,
        )
    }
}
