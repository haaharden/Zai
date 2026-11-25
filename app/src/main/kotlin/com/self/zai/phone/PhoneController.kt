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
    private val onStepProgressWithApp: ((stepNumber: Int, label: String, targetAppLabel: String) -> Unit)? = null,
    private val onNativeToolActivity: (label: String) -> Unit = {},
    private val isDeviceLocked: () -> Boolean = { false },
    private val requestDeviceUnlock: suspend (reason: String) -> Boolean = { false },
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
        val plugin = toolRegistry.get(tool)
        val humanLabel = toolRegistry.humanLabel(tool, args)

        // Lockscreen privacy & safety gate: require system unlock before personal data or screen operations
        val requiresUnlock = (plugin?.interactsWithScreen != false) || tool in PRIVATE_LOCKSCREEN_TOOLS
        if (isDeviceLocked() && requiresUnlock) {
            val unlocked = runCatching { requestDeviceUnlock(humanLabel) }.getOrDefault(false)
            if (!unlocked || isDeviceLocked()) {
                return BeforeToolVerdict(
                    block = true,
                    terminate = false,
                    reason = "设备当前处于锁屏状态，为保护隐私与安全，请先解锁手机后再执行「$humanLabel」。",
                )
            }
        }

        val step = stepCount.incrementAndGet()
        val activePackage = channel.currentForegroundPackage()
        if (tool in TARGET_CHECKED_TOOLS) {
            refreshSnapshotIfStale(activePackage)
        }
        val targetElement = resolveTargetElement(tool, args)

        return safetyGuard.evaluate(
            callId = callId,
            toolName = tool,
            args = args,
            humanLabel = humanLabel,
            stepNumber = step,
            settings = repository.settings.value,
            activePackage = activePackage,
            ownPackage = channel.ownPackageName,
            targetElement = targetElement,
            currentSnapshot = lastSnapshot,
        )
    }

    private suspend fun refreshSnapshotIfStale(activePackage: String) {
        val current = lastSnapshot
        val packageChanged = activePackage.isNotBlank() &&
            activePackage != channel.ownPackageName &&
            current?.packageName != activePackage
        if (current == null || packageChanged) {
            val (w, h) = channel.screenDimensions()
            channel.captureTree(w, h)?.let { lastSnapshot = it }
        }
    }

    private fun resolveTargetElement(tool: String, args: JSONObject): UiElement? {
        if (args.has("id")) {
            return resolveElement(args.optInt("id", -1))
        }
        val isCoordTool = tool == "tap_xy" || (tool == "long_press" && args.has("x") && args.has("y"))
        if (!isCoordTool || !args.has("x") || !args.has("y")) return null

        val snap = lastSnapshot ?: return null
        val (fallbackW, fallbackH) = channel.screenDimensions()
        val w = if (snap.screenWidth > 0) snap.screenWidth else fallbackW
        val h = if (snap.screenHeight > 0) snap.screenHeight else fallbackH
        if (w <= 0 || h <= 0) return null

        val nx = args.optInt("x", 500).coerceIn(0, 1000)
        val ny = args.optInt("y", 500).coerceIn(0, 1000)
        val px = (nx / 1000f * w).toInt().coerceIn(0, w)
        val py = (ny / 1000f * h).toInt().coerceIn(0, h)

        val hits = snap.elements.filter { el ->
            val b = el.bounds
            val bw = b.right - b.left
            val bh = b.bottom - b.top
            bw > 0 && bh > 0 && px in b.left..b.right && py in b.top..b.bottom
        }
        if (hits.isEmpty()) return null
        // Prefer password fields or smallest labeled element enclosing (px, py)
        return hits.firstOrNull { it.isPassword }
            ?: hits.filter { it.text.isNotBlank() || it.contentDesc.isNotBlank() }
                .minByOrNull { (it.bounds.right - it.bounds.left).toLong() * (it.bounds.bottom - it.bounds.top).toLong() }
            ?: hits.minByOrNull { (it.bounds.right - it.bounds.left).toLong() * (it.bounds.bottom - it.bounds.top).toLong() }
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
            val step = stepCount.get().coerceAtLeast(1)
            val label = plugin.humanLabel(args)
            val targetAppLabel = resolveCurrentTargetAppLabel(tool, args)
            onStepProgress(step, label)
            onStepProgressWithApp?.invoke(step, label, targetAppLabel)
        } else {
            onNativeToolActivity("正在${plugin.humanLabel(args)}…")
        }
        return plugin.execute(this, args)
    }

    private suspend fun resolveCurrentTargetAppLabel(tool: String, args: JSONObject): String {
        if (tool == "open_app") {
            val query = args.optString("app_name").trim()
            if (query.isNotEmpty()) {
                return appDirectory.findLaunchableApp(query)?.second ?: query
            }
        }
        if (tool == "open_system_setting") return "系统设置"
        val pkg = channel.currentForegroundPackage().ifBlank { lastSnapshot?.packageName.orEmpty() }
        if (pkg.isBlank() || pkg == channel.ownPackageName) return ""
        return appDirectory.resolveAppLabel(pkg).takeIf { it.isNotBlank() } ?: pkg.substringAfterLast('.')
    }

    override suspend fun refreshTreeSnapshot(): ScreenSnapshot? {
        channel.ensureReady()
        val (w, h) = channel.screenDimensions()
        val snap = channel.captureTree(w, h) ?: return null
        lastSnapshot = snap
        return snap
    }

    override suspend fun checkSubActionSafety(
        callId: String,
        toolName: String,
        args: JSONObject,
        humanLabel: String,
        targetElement: UiElement?,
    ): BeforeToolVerdict? {
        val activePackage = channel.currentForegroundPackage()
        return safetyGuard.evaluate(
            callId = callId,
            toolName = toolName,
            args = args,
            humanLabel = humanLabel,
            stepNumber = stepCount.get().coerceAtLeast(1),
            settings = repository.settings.value,
            activePackage = activePackage,
            ownPackage = channel.ownPackageName,
            targetElement = targetElement,
            currentSnapshot = lastSnapshot,
        )
    }

    companion object {
        private val TARGET_CHECKED_TOOLS = setOf("tap", "tap_xy", "long_press", "input_text")
        val PRIVATE_LOCKSCREEN_TOOLS = setOf(
            "read_notifications",
            "find_contact",
            "calendar_query",
            "calendar_create",
        )
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
