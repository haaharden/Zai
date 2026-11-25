package com.self.zai.phone

import com.self.zai.data.SessionRepository
import com.self.zai.model.PendingConfirmation
import com.self.zai.model.PerceptionMode
import com.self.zai.phone.channel.PhoneDeviceChannel
import com.self.zai.phone.tools.PhoneToolContext
import com.self.zai.phone.tools.PhoneToolRegistry
import com.self.zai.pi.BeforeToolVerdict
import com.self.zai.pi.PiToolHandler
import com.self.zai.pi.ToolExecutionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * Slim, modular phone automation coordinator composing:
 * - [PhoneDeviceChannel]: hardware/OS execution backend (Accessibility / Shizuku / future VirtualDisplay)
 * - [PhoneToolRegistry]: extensible plugin registry of phone tools
 * - [SafetyGuard]: step budget, self-tap protection, and sensitive-action confirmation policy
 * - [AppDirectory]: cached launchable app resolver
 */
class PhoneController(
    private val repository: SessionRepository,
    override val channel: PhoneDeviceChannel,
    override val appDirectory: AppDirectory,
    val toolRegistry: PhoneToolRegistry = PhoneToolRegistry.default(),
    private val safetyGuard: SafetyGuard = SafetyGuard(),
    private val onStepProgress: (stepNumber: Int, label: String) -> Unit = { _, _ -> },
    private val onNativeToolActivity: (label: String) -> Unit = {},
) : PiToolHandler, PhoneToolContext {

    private val stepCount = AtomicInteger(0)

    @Volatile
    private var operatedPhoneThisRun: Boolean = false

    @Volatile
    override var lastSnapshot: ScreenSnapshot? = null
        private set

    @Volatile
    var currentModelSupportsImages: Boolean = true

    val pendingConfirmation: StateFlow<PendingConfirmation?> = safetyGuard.pendingConfirmation

    fun resetRunCounters() {
        stepCount.set(0)
        operatedPhoneThisRun = false
        safetyGuard.clearPendingConfirmations()
    }

    fun didOperatePhoneThisRun(): Boolean = operatedPhoneThisRun

    fun resolveConfirmation(callId: String, allow: Boolean) {
        safetyGuard.resolveConfirmation(callId, allow)
    }

    fun clearPendingConfirmations() {
        safetyGuard.clearPendingConfirmations()
    }

    override suspend fun beforeTool(
        sessionId: String,
        callId: String,
        tool: String,
        args: JSONObject,
    ): BeforeToolVerdict? {
        val step = stepCount.incrementAndGet()
        val targetElement = if (args.has("id")) resolveElement(args.optInt("id", -1)) else null
        return safetyGuard.evaluate(
            callId = callId,
            toolName = tool,
            args = args,
            humanLabel = toolRegistry.humanLabel(tool, args),
            stepNumber = step,
            settings = repository.settings.value,
            activePackage = channel.currentForegroundPackage(),
            ownPackage = channel.ownPackageName,
            targetElement = targetElement,
        )
    }

    override suspend fun executeTool(
        sessionId: String,
        callId: String,
        tool: String,
        args: JSONObject,
    ): ToolExecutionResult {
        val plugin = toolRegistry.get(tool)
            ?: return ToolExecutionResult("未知工具: $tool", isError = true)
        if (plugin.interactsWithScreen) {
            operatedPhoneThisRun = true
            onStepProgress(stepCount.get().coerceAtLeast(1), plugin.humanLabel(args))
        } else {
            onNativeToolActivity("正在${plugin.humanLabel(args)}…")
        }
        return plugin.execute(this, args)
    }

    override fun cancelTool(sessionId: String, callId: String) {
        safetyGuard.resolveConfirmation(callId, allow = false)
    }

    override suspend fun observeScreen(prefix: String?, forceImage: Boolean): ToolExecutionResult {
        channel.ensureReady()
        val (w, h) = channel.screenDimensions()
        val snap = channel.captureTree(w, h)
            ?: return ToolExecutionResult(
                text = "${prefix?.let { "$it\n" }.orEmpty()}无法读取屏幕：无障碍服务未连接且 Shizuku 不可用。请在 Zai 设置中开启无障碍服务或 Shizuku。",
                isError = true,
            )

        lastSnapshot = snap
        val appLabel = appDirectory.resolveAppLabel(snap.packageName)
        val formattedTree = snap.formatForModel(appLabel)

        val perception = repository.settings.value.perceptionMode
        val interactiveCount = snap.elements.count { it.clickable || it.editable }
        val wantImage = currentModelSupportsImages && when {
            forceImage -> true
            perception == PerceptionMode.TREE_ONLY -> false
            perception == PerceptionMode.ALWAYS_VISION -> true
            else -> interactiveCount <= 3
        }

        var imageBase64: String? = null
        if (wantImage) {
            val bmp = channel.captureBitmap()
            if (bmp != null) {
                imageBase64 = withContext(Dispatchers.Default) {
                    ScreenAnnotator.annotateAndEncodeJpeg(bmp, snap.elements)
                }
                bmp.recycle()
            }
        }

        val textOut = buildString {
            if (!prefix.isNullOrBlank()) {
                append(prefix)
                append(PhoneToolCatalog.SCREEN_NOW)
            }
            append(formattedTree)
            if (imageBase64 != null) {
                append("\n(已附带当前画面标号截图，优先按编号 tap(id) 点击，无编号区域可用 tap_xy)")
            }
        }

        return ToolExecutionResult(
            text = textOut,
            isError = false,
            imageBase64 = imageBase64,
            imageMimeType = "image/jpeg",
        )
    }
}
