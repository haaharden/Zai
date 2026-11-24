package com.self.zai.phone

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import com.self.zai.data.SettingsStore
import com.self.zai.model.ControlChannelMode
import com.self.zai.model.PendingConfirmation
import com.self.zai.model.PerceptionMode
import com.self.zai.permission.DevicePermissionHelper
import com.self.zai.pi.BeforeToolVerdict
import com.self.zai.pi.PiToolHandler
import com.self.zai.pi.ToolExecutionResult
import com.self.zai.shell.ShizukuBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unified phone automation executor combining Shizuku (priority) and AccessibilityService (fast UI tree & fallback).
 * Designed to avoid mid-run permission loss and permanent state-machine lockouts.
 */
class PhoneController(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val shizuku: ShizukuBridge,
    private val onCapsuleHideForCapture: (Boolean) -> Unit,
    private val onStepTextChanged: (String) -> Unit,
) : PiToolHandler {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val stepCount = AtomicInteger(0)

    @Volatile
    private var lastSnapshot: ScreenSnapshot? = null

    @Volatile
    var currentModelSupportsImages: Boolean = true

    private val _pendingConfirmation = MutableStateFlow<PendingConfirmation?>(null)
    val pendingConfirmation: StateFlow<PendingConfirmation?> = _pendingConfirmation.asStateFlow()

    private val confirmationWaiters = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    fun resetRunCounters() {
        stepCount.set(0)
        clearPendingConfirmations()
    }

    fun resolveConfirmation(callId: String, allow: Boolean) {
        confirmationWaiters.remove(callId)?.complete(allow)
        if (_pendingConfirmation.value?.callId == callId) {
            _pendingConfirmation.value = null
        }
    }

    fun clearPendingConfirmations() {
        for ((id, waiter) in confirmationWaiters) {
            confirmationWaiters.remove(id)
            waiter.complete(false)
        }
        _pendingConfirmation.value = null
    }

    override suspend fun beforeTool(
        sessionId: String,
        callId: String,
        tool: String,
        args: JSONObject,
    ): BeforeToolVerdict? {
        val settings = settingsStore.settings.value
        val step = stepCount.incrementAndGet()
        if (step > settings.maxStepsPerRun) {
            return BeforeToolVerdict(
                block = true,
                terminate = true,
                reason = "已达到单轮最大操作步数 (${settings.maxStepsPerRun} 步)，已自动停止以防死循环。",
            )
        }

        // Prevent self-tapping inside Zai itself
        val activePkg = currentForegroundPackage()
        if (activePkg == context.packageName && tool in setOf("tap", "tap_xy", "long_press", "swipe", "input_text")) {
            return BeforeToolVerdict(
                block = true,
                terminate = false,
                reason = "当前前台是 Zai 应用本身。请先调用 open_app 打开目标应用，或调用 press_key(home) 回到桌面。",
            )
        }

        // Optional sensitive action check
        if (settings.confirmSensitiveActions && tool in setOf("tap", "input_text", "long_press")) {
            val targetLabel = resolveTargetElement(args)?.displayLabel.orEmpty()
            val textArg = args.optString("text")
            val sensitiveHit = SENSITIVE_KEYWORDS.firstOrNull { kw ->
                targetLabel.contains(kw, ignoreCase = true) || textArg.contains(kw, ignoreCase = true)
            }
            val isPasswordField = resolveTargetElement(args)?.isPassword == true
            if (sensitiveHit != null || isPasswordField) {
                val title = if (isPasswordField) "涉及密码输入，需要你确认" else "检测到敏感操作「$sensitiveHit」"
                val detail = PhoneToolCatalog.humanLabel(tool, args) +
                    if (targetLabel.isNotBlank()) "（目标：$targetLabel）" else ""
                val allowed = askUserConfirmation(callId, title, detail)
                if (!allowed) {
                    return BeforeToolVerdict(
                        block = true,
                        terminate = false,
                        reason = "用户拒绝了该敏感操作（$detail），请停止敏感步骤或征询用户意见。",
                    )
                }
            }
        }
        return null
    }

    override suspend fun executeTool(
        sessionId: String,
        callId: String,
        tool: String,
        args: JSONObject,
    ): ToolExecutionResult {
        onStepTextChanged(PhoneToolCatalog.humanLabel(tool, args))

        return when (tool) {
            "read_screen" -> {
                val forceImg = args.optBoolean("with_image", false)
                observeScreenResult(prefix = null, forceImage = forceImg)
            }

            "screenshot" -> observeScreenResult(prefix = null, forceImage = true)

            "tap" -> {
                val id = args.optInt("id", -1)
                val el = lastSnapshot?.elements?.firstOrNull { it.index == id }
                    ?: return ToolExecutionResult(
                        text = "找不到控件编号 [$id]，请先调用 read_screen 获取最新控件列表。",
                        isError = true,
                    )
                val ok = performTap(el.centerX, el.centerY)
                if (!ok) {
                    return ToolExecutionResult(
                        text = "点击失败：Shizuku 与无障碍通道均不可用，请在 Zai 设置中检查权限。",
                        isError = true,
                    )
                }
                delay(550L)
                observeScreenResult(prefix = "已点击 [$id] ${el.displayLabel}", forceImage = false)
            }

            "tap_xy" -> {
                val (w, h) = screenDimensions()
                val nx = args.optInt("x", 500).coerceIn(0, 1000)
                val ny = args.optInt("y", 500).coerceIn(0, 1000)
                val px = (nx * w / 1000f).toInt().coerceIn(1, w - 1)
                val py = (ny * h / 1000f).toInt().coerceIn(1, h - 1)
                val ok = performTap(px, py)
                if (!ok) {
                    return ToolExecutionResult(
                        text = "坐标点击失败：请检查 Shizuku 或无障碍权限。",
                        isError = true,
                    )
                }
                delay(550L)
                observeScreenResult(prefix = "已点击坐标 ($nx, $ny)", forceImage = false)
            }

            "long_press" -> {
                val duration = args.optInt("duration_ms", 700).coerceIn(300, 3000)
                val (px, py, desc) = if (args.has("id")) {
                    val id = args.optInt("id", -1)
                    val el = lastSnapshot?.elements?.firstOrNull { it.index == id }
                        ?: return ToolExecutionResult("找不到控件编号 [$id]", isError = true)
                    Triple(el.centerX, el.centerY, "[$id] ${el.displayLabel}")
                } else {
                    val (w, h) = screenDimensions()
                    val nx = args.optInt("x", 500).coerceIn(0, 1000)
                    val ny = args.optInt("y", 500).coerceIn(0, 1000)
                    Triple((nx * w / 1000f).toInt(), (ny * h / 1000f).toInt(), "($nx, $ny)")
                }
                val ok = performLongPress(px, py, duration)
                if (!ok) return ToolExecutionResult("长按失败：请检查 Shizuku 或无障碍权限。", isError = true)
                delay(550L)
                observeScreenResult(prefix = "已长按 $desc", forceImage = false)
            }

            "swipe" -> {
                val (w, h) = screenDimensions()
                val duration = args.optInt("duration_ms", 360).coerceIn(150, 1500)
                val dir = args.optString("direction")
                val coords = when (dir) {
                    "up" -> listOf(w / 2, (h * 0.74f).toInt(), w / 2, (h * 0.26f).toInt())
                    "down" -> listOf(w / 2, (h * 0.28f).toInt(), w / 2, (h * 0.76f).toInt())
                    "left" -> listOf((w * 0.82f).toInt(), h / 2, (w * 0.18f).toInt(), h / 2)
                    "right" -> listOf((w * 0.18f).toInt(), h / 2, (w * 0.82f).toInt(), h / 2)
                    else -> listOf(
                        (args.optInt("x1", 500).coerceIn(0, 1000) * w / 1000f).toInt(),
                        (args.optInt("y1", 700).coerceIn(0, 1000) * h / 1000f).toInt(),
                        (args.optInt("x2", 500).coerceIn(0, 1000) * w / 1000f).toInt(),
                        (args.optInt("y2", 300).coerceIn(0, 1000) * h / 1000f).toInt(),
                    )
                }
                val ok = performSwipe(coords[0], coords[1], coords[2], coords[3], duration)
                if (!ok) return ToolExecutionResult("滑动失败：请检查 Shizuku 或无障碍权限。", isError = true)
                delay(650L)
                observeScreenResult(prefix = "已完成滑动 ${dir.ifBlank { "自定义坐标" }}", forceImage = false)
            }

            "input_text" -> {
                val text = args.optString("text")
                val targetEl = if (args.has("id")) {
                    val id = args.optInt("id", -1)
                    lastSnapshot?.elements?.firstOrNull { it.index == id }
                } else {
                    null
                }
                if (targetEl != null) {
                    performTap(targetEl.centerX, targetEl.centerY)
                    delay(350L)
                }
                val ok = performInputText(text, targetEl)
                if (!ok) {
                    return ToolExecutionResult(
                        text = "文字输入失败：请确认目标输入框已聚焦，且 Shizuku 或无障碍通道正常。",
                        isError = true,
                    )
                }
                if (args.optBoolean("press_enter", false)) {
                    delay(200L)
                    performKey("enter")
                }
                delay(550L)
                observeScreenResult(prefix = "已输入文本「${text.take(40)}」", forceImage = false)
            }

            "press_key" -> {
                val key = args.optString("key", "back")
                val ok = performKey(key)
                if (!ok) return ToolExecutionResult("按键失败：请检查 Shizuku 或无障碍权限。", isError = true)
                delay(600L)
                observeScreenResult(prefix = "已按下 $key", forceImage = false)
            }

            "open_app" -> {
                val query = args.optString("app_name").trim()
                if (query.isEmpty()) return ToolExecutionResult("请提供应用名称或包名", isError = true)
                val match = findLaunchableApp(query)
                    ?: return ToolExecutionResult("未找到名为「$query」的已安装应用，可先调用 list_apps 查看列表。", isError = true)
                val launched = launchApp(match.first)
                if (!launched) {
                    return ToolExecutionResult("启动 ${match.second} (${match.first}) 失败。", isError = true)
                }
                delay(1_100L)
                observeScreenResult(prefix = "已打开 ${match.second} (${match.first})", forceImage = false)
            }

            "list_apps" -> {
                val query = args.optString("query").trim()
                val apps = queryLaunchableApps(query).take(40)
                if (apps.isEmpty()) {
                    ToolExecutionResult("未找到匹配「$query」的可启动应用。")
                } else {
                    val text = apps.joinToString("\n") { (pkg, label) -> "$label ($pkg)" }
                    ToolExecutionResult("找到 ${apps.size} 个应用：\n$text")
                }
            }

            "wait" -> {
                val sec = args.optDouble("seconds", 1.5).coerceIn(0.5, 5.0)
                delay((sec * 1000).toLong())
                observeScreenResult(prefix = "已等待 ${sec}s", forceImage = false)
            }

            else -> ToolExecutionResult("未知工具: $tool", isError = true)
        }
    }

    override fun cancelTool(sessionId: String, callId: String) {
        resolveConfirmation(callId, allow = false)
    }

    /**
     * Captures screen tree and optional SoM-annotated JPEG screenshot according to [PerceptionMode].
     */
    private suspend fun observeScreenResult(prefix: String?, forceImage: Boolean): ToolExecutionResult {
        maybeAutoRestoreAccessibility()
        val (w, h) = screenDimensions()

        var snapshot = ZaiAccessibilityService.instance?.captureScreenTree(w, h)
        if (snapshot == null && shouldUseShizuku()) {
            val xmlFile = File(context.externalCacheDir ?: context.cacheDir, "zai_ui.xml")
            val xmlRes = shizuku.dumpUiXmlToFile(xmlFile)
            runCatching { xmlFile.delete() }
            xmlRes.onSuccess { xml ->
                snapshot = UiAutomatorXmlParser.parse(xml, w, h)
            }
        }

        if (snapshot == null) {
            val base = prefix?.let { "$it\n" }.orEmpty()
            return ToolExecutionResult(
                text = "${base}无法读取屏幕：无障碍服务未连接且 Shizuku 不可用。请在 Zai 设置中开启 Shizuku 或无障碍服务。",
                isError = true,
            )
        }

        val snap = snapshot
        lastSnapshot = snap
        val appLabel = resolveAppLabel(snap.packageName)
        val formattedTree = snap.formatForModel(appLabel)

        val perception = settingsStore.settings.value.perceptionMode
        val interactiveCount = snap.elements.count { it.clickable || it.editable }
        val wantImage = currentModelSupportsImages && when {
            forceImage -> true
            perception == PerceptionMode.TREE_ONLY -> false
            perception == PerceptionMode.ALWAYS_VISION -> true
            // HYBRID: attach annotated screenshot automatically when UI tree is sparse (e.g. WebView / mini-app / game)
            else -> interactiveCount <= 3
        }

        var imageBase64: String? = null
        if (wantImage) {
            val bmp = captureBitmapSafely()
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

    private suspend fun captureBitmapSafely(): Bitmap? {
        onCapsuleHideForCapture(true)
        delay(90L)
        return try {
            // 1. Prefer Shizuku screencap (immune to OEM accessibility kills & faster across all Android versions)
            if (shouldUseShizuku()) {
                val capFile = File(context.externalCacheDir ?: context.cacheDir, "zai_cap.png")
                val res = shizuku.screencapToFile(capFile)
                if (res.isSuccess) {
                    val bmp = BitmapFactory.decodeFile(capFile.absolutePath)
                    runCatching { capFile.delete() }
                    if (bmp != null) return bmp
                }
            }
            // 2. Fallback to Android 11+ Accessibility takeScreenshot
            ZaiAccessibilityService.instance?.captureBitmap()
        } finally {
            onCapsuleHideForCapture(false)
        }
    }

    private suspend fun performTap(x: Int, y: Int): Boolean {
        if (shouldUseShizuku() && shizuku.tap(x, y).isSuccess) return true
        return ZaiAccessibilityService.instance?.performTap(x, y) == true
    }

    private suspend fun performLongPress(x: Int, y: Int, durationMs: Int): Boolean {
        if (shouldUseShizuku() && shizuku.longPress(x, y, durationMs).isSuccess) return true
        return ZaiAccessibilityService.instance?.performTap(x, y, durationMs.toLong()) == true
    }

    private suspend fun performSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean {
        if (shouldUseShizuku() && shizuku.swipe(x1, y1, x2, y2, durationMs).isSuccess) return true
        return ZaiAccessibilityService.instance?.performSwipe(x1, y1, x2, y2, durationMs.toLong()) == true
    }

    private suspend fun performKey(key: String): Boolean {
        val (keyCode, globalAction) = when (key.lowercase()) {
            "back" -> KeyEvent.KEYCODE_BACK to AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> KeyEvent.KEYCODE_HOME to AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> KeyEvent.KEYCODE_APP_SWITCH to AccessibilityService.GLOBAL_ACTION_RECENTS
            "enter" -> KeyEvent.KEYCODE_ENTER to -1
            else -> return false
        }
        if (shouldUseShizuku() && shizuku.keyEvent(keyCode).isSuccess) return true
        if (globalAction >= 0) {
            return ZaiAccessibilityService.instance?.performGlobalAction(globalAction) == true
        }
        return false
    }

    private suspend fun performInputText(text: String, target: UiElement?): Boolean {
        // 1. Accessibility ACTION_SET_TEXT handles Chinese/Unicode reliably without keyboard quirks
        val a11yOk = ZaiAccessibilityService.instance?.setTextAtBounds(target?.bounds, text) == true
        if (a11yOk) return true

        // 2. Clipboard + Shizuku KEYCODE_PASTE (279) handles Chinese reliably via Shizuku
        if (shouldUseShizuku()) {
            val isPureAscii = text.all { it.code in 32..126 }
            if (isPureAscii && shizuku.inputTextAscii(text).isSuccess) {
                return true
            }
            setClipboardText(text)
            delay(120L)
            if (shizuku.keyEvent(279 /* KEYCODE_PASTE */).isSuccess) {
                return true
            }
        }
        return false
    }

    private suspend fun setClipboardText(text: String) {
        val done = CompletableDeferred<Unit>()
        mainHandler.post {
            runCatching {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Zai", text))
            }
            done.complete(Unit)
        }
        done.await()
    }

    private suspend fun maybeAutoRestoreAccessibility() {
        val settings = settingsStore.settings.value
        if (!settings.autoRestoreAccessibility) return
        if (ZaiAccessibilityService.isReallyConnected()) return

        // 1. Prefer native WRITE_SECURE_SETTINGS (works without Shizuku or Developer Options)
        if (DevicePermissionHelper.hasWriteSecureSettings(context)) {
            if (DevicePermissionHelper.restoreAccessibilityViaSecureSettings(context).isSuccess) {
                delay(450L)
                if (ZaiAccessibilityService.isReallyConnected()) return
            }
        }

        // 2. Fallback to Shizuku if connected
        if (!shizuku.status.value.isReady) return
        val component = ComponentName(context, ZaiAccessibilityService::class.java).flattenToString()
        if (shizuku.restoreAccessibilityService(component).isSuccess) {
            delay(450L)
        }
    }

    private fun shouldUseShizuku(): Boolean {
        if (settingsStore.settings.value.channelMode == ControlChannelMode.ACCESSIBILITY_ONLY) {
            return false
        }
        return shizuku.status.value.binderAlive && shizuku.status.value.permissionGranted
    }

    private suspend fun currentForegroundPackage(): String {
        val a11yPkg = ZaiAccessibilityService.instance?.rootInActiveWindow?.packageName?.toString()
        if (!a11yPkg.isNullOrBlank()) return a11yPkg
        if (shouldUseShizuku()) {
            shizuku.getTopPackageName()?.let { return it }
        }
        return ZaiAccessibilityService.lastEventPackage
    }

    private fun resolveTargetElement(args: JSONObject): UiElement? {
        if (!args.has("id")) return null
        val id = args.optInt("id", -1)
        return lastSnapshot?.elements?.firstOrNull { it.index == id }
    }

    private suspend fun askUserConfirmation(callId: String, title: String, detail: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        confirmationWaiters[callId] = deferred
        _pendingConfirmation.value = PendingConfirmation(callId = callId, title = title, detail = detail)
        val result = withTimeoutOrNull(45_000L) { deferred.await() } ?: false
        confirmationWaiters.remove(callId)
        if (_pendingConfirmation.value?.callId == callId) {
            _pendingConfirmation.value = null
        }
        return result
    }

    private fun screenDimensions(): Pair<Int, Int> {
        val dm = context.resources.displayMetrics
        return dm.widthPixels.coerceAtLeast(1) to dm.heightPixels.coerceAtLeast(1)
    }

    private fun resolveAppLabel(packageName: String): String {
        if (packageName.isBlank()) return "未知应用"
        return runCatching {
            val ai = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(ai).toString()
        }.getOrDefault(packageName)
    }

    private fun queryLaunchableApps(query: String): List<Pair<String, String>> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        val all = activities
            .map { info ->
                val pkg = info.activityInfo.packageName
                val label = info.loadLabel(pm).toString().trim()
                pkg to label
            }
            .distinctBy { it.first }
            .filterNot { it.first == context.packageName }

        if (query.isBlank()) return all.sortedBy { it.second.lowercase() }
        val q = query.lowercase()
        return all.filter { (pkg, label) ->
            label.lowercase().contains(q) || pkg.lowercase().contains(q)
        }.sortedBy { (pkg, label) ->
            when {
                label.equals(query, ignoreCase = true) || pkg.equals(query, ignoreCase = true) -> 0
                label.lowercase().startsWith(q) -> 1
                else -> 2
            }
        }
    }

    private fun findLaunchableApp(query: String): Pair<String, String>? {
        val aliasPkg = COMMON_APP_ALIASES[query.lowercase()]
        if (aliasPkg != null) {
            val installed = runCatching {
                val ai = context.packageManager.getApplicationInfo(aliasPkg, 0)
                aliasPkg to context.packageManager.getApplicationLabel(ai).toString()
            }.getOrNull()
            if (installed != null) return installed
        }
        return queryLaunchableApps(query).firstOrNull()
    }

    private suspend fun launchApp(packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            val launched = runCatching {
                context.startActivity(intent)
                true
            }.getOrDefault(false)
            if (launched) return true
        }
        if (shouldUseShizuku()) {
            return shizuku.launchPackage(packageName).isSuccess
        }
        return false
    }

    companion object {
        private val SENSITIVE_KEYWORDS = listOf(
            "确认支付", "立即支付", "付款", "转账", "确认转账", "支付密码",
            "卸载", "删除账号", "清除数据", "格式化", "pay now", "confirm payment",
        )

        private val COMMON_APP_ALIASES = mapOf(
            "微信" to "com.tencent.mm",
            "wechat" to "com.tencent.mm",
            "支付宝" to "com.eg.android.AlipayGphone",
            "alipay" to "com.eg.android.AlipayGphone",
            "设置" to "com.android.settings",
            "settings" to "com.android.settings",
            "抖音" to "com.ss.android.ugc.aweme",
            "淘宝" to "com.taobao.taobao",
            "京东" to "com.jingdong.app.mall",
            "美团" to "com.sankuai.meituan",
            "高德地图" to "com.autonavi.minimap",
            "高德" to "com.autonavi.minimap",
            "小红书" to "com.xingin.xhs",
            "哔哩哔哩" to "tv.danmaku.bili",
            "b站" to "tv.danmaku.bili",
            "bilibili" to "tv.danmaku.bili",
            "qq" to "com.tencent.mobileqq",
            "网易云音乐" to "com.netease.cloudmusic",
        )
    }
}
