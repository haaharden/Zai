package com.self.zai

import android.graphics.Bitmap
import android.graphics.Rect
import com.self.zai.data.SessionRepository
import com.self.zai.model.AppSettings
import com.self.zai.model.ChatSession
import com.self.zai.phone.AppDirectory
import com.self.zai.phone.PhoneController
import com.self.zai.phone.SafetyGuard
import com.self.zai.phone.ScreenSnapshot
import com.self.zai.phone.UiElement
import com.self.zai.phone.channel.PhoneDeviceChannel
import com.self.zai.phone.tools.PhoneTool
import com.self.zai.phone.tools.PhoneToolContext
import com.self.zai.phone.tools.PhoneToolRegistry
import com.self.zai.phone.tools.SystemAssistantBackend
import com.self.zai.pi.ToolExecutionResult
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneArchitectureTest {

    @Test
    fun toolRegistry_filtersVisionToolsAndSupportsCustomPluginRegistration() = runBlocking {
        val registry = PhoneToolRegistry.default()
        val withVision = registry.toolsJson(includeVisionTools = true)
        val withoutVision = registry.toolsJson(includeVisionTools = false)

        val namesWithVision = (0 until withVision.length()).map { withVision.getJSONObject(it).getString("name") }
        val namesWithoutVision = (0 until withoutVision.length()).map { withoutVision.getJSONObject(it).getString("name") }

        assertTrue("screenshot" in namesWithVision)
        assertFalse("screenshot" in namesWithoutVision)

        // Dynamically register a new custom tool plugin without changing PhoneController
        registry.register(
            object : PhoneTool {
                override val name = "custom_ping"
                override val label = "测试插件"
                override val description = "Custom extensible plugin tool"
                override suspend fun execute(ctx: PhoneToolContext, args: JSONObject): ToolExecutionResult =
                    ToolExecutionResult("pong:${args.optString("msg")}")
            },
        )
        assertNotNull(registry.get("custom_ping"))

        val repo = InMemoryRepo()
        val channel = FakeDeviceChannel()
        val controller = PhoneController(repo, channel, FakeAppDirectory(), toolRegistry = registry)
        val res = controller.executeTool("s1", "c1", "custom_ping", JSONObject().put("msg", "ok"))
        assertFalse(res.isError)
        assertEquals("pong:ok", res.text)
        assertTrue(controller.didOperatePhoneThisRun())
    }

    @Test
    fun safetyGuard_enforcesStepLimitAndSelfTapProtection() = runBlocking {
        val guard = SafetyGuard(confirmationTimeoutMs = 500L)
        val settings = AppSettings(maxStepsPerRun = 3, confirmSensitiveActions = true)

        // Exceeding max steps terminates run
        val overLimit = guard.evaluate(
            callId = "c1",
            toolName = "tap",
            args = JSONObject(),
            humanLabel = "点击",
            stepNumber = 4,
            settings = settings,
            activePackage = "com.android.settings",
            ownPackage = "com.self.zai",
            targetElement = null,
        )
        assertNotNull(overLimit)
        assertTrue(overLimit!!.block && overLimit.terminate)

        // Tapping inside Zai itself is blocked without terminating
        val selfTap = guard.evaluate(
            callId = "c2",
            toolName = "tap_xy",
            args = JSONObject(),
            humanLabel = "坐标点击",
            stepNumber = 1,
            settings = settings,
            activePackage = "com.self.zai",
            ownPackage = "com.self.zai",
            targetElement = null,
        )
        assertNotNull(selfTap)
        assertTrue(selfTap!!.block)
        assertFalse(selfTap.terminate)

        // Reading screen inside Zai is allowed
        val selfRead = guard.evaluate(
            callId = "c3",
            toolName = "read_screen",
            args = JSONObject(),
            humanLabel = "读屏",
            stepNumber = 1,
            settings = settings,
            activePackage = "com.self.zai",
            ownPackage = "com.self.zai",
            targetElement = null,
        )
        assertNull(selfRead)
    }

    @Test
    fun safetyGuard_pausesForSensitiveActionAndRespectsUserChoice() = runBlocking {
        val guard = SafetyGuard(confirmationTimeoutMs = 5_000L)
        val settings = AppSettings(confirmSensitiveActions = true)
        val payButton = UiElement(
            index = 1,
            text = "确认支付 ¥99",
            contentDesc = "",
            viewId = "",
            className = "android.widget.Button",
            bounds = Rect(),
            clickable = true,
            scrollable = false,
            editable = false,
            checked = null,
            isPassword = false,
        )

        val deferredVerdict = async {
            guard.evaluate(
                callId = "pay_1",
                toolName = "tap",
                args = JSONObject().put("id", 1),
                humanLabel = "点击控件 #1",
                stepNumber = 1,
                settings = settings,
                activePackage = "com.eg.android.AlipayGphone",
                ownPackage = "com.self.zai",
                targetElement = payButton,
            )
        }

        // Wait briefly for confirmation prompt to appear, then deny
        repeat(20) {
            if (guard.pendingConfirmation.value != null) return@repeat
            delay(10L)
        }
        assertEquals("pay_1", guard.pendingConfirmation.value?.callId)
        guard.resolveConfirmation("pay_1", allow = false)

        val verdict = deferredVerdict.await()
        assertNotNull(verdict)
        assertTrue(verdict!!.block)
        assertNull(guard.pendingConfirmation.value)
    }

    @Test
    fun phoneController_tapXy_guardsSensitiveBounds_staleRefresh_andSensitiveScreenFallback() = runBlocking {
        val repo = InMemoryRepo()
        val guard = SafetyGuard(confirmationTimeoutMs = 5_000L)

        fun rectOf(l: Int, t: Int, r: Int, b: Int) = Rect().apply {
            left = l; top = t; right = r; bottom = b
        }

        val payBtn = UiElement(
            index = 1,
            text = "确认支付 ¥199",
            contentDesc = "",
            viewId = "btn_pay",
            className = "android.widget.Button",
            bounds = rectOf(200, 1800, 880, 2000),
            clickable = true,
            scrollable = false,
            editable = false,
            checked = null,
            isPassword = false,
        )
        var currentPkg = "com.android.settings"
        var currentElements = emptyList<UiElement>()

        val channel = object : PhoneDeviceChannel {
            override val ownPackageName = "com.self.zai"
            override fun screenDimensions() = 1080 to 2400
            override suspend fun ensureReady() {}
            override suspend fun captureTree(width: Int, height: Int) = ScreenSnapshot(
                packageName = currentPkg,
                screenWidth = width,
                screenHeight = height,
                elements = currentElements,
                source = "fake",
            )
            override suspend fun captureBitmap(): Bitmap? = null
            override suspend fun tap(x: Int, y: Int) = true
            override suspend fun longPress(x: Int, y: Int, durationMs: Int) = true
            override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) = true
            override suspend fun pressKey(key: String) = true
            override suspend fun inputText(text: String, targetBounds: Rect?) = true
            override suspend fun currentForegroundPackage() = currentPkg
            override suspend fun launchPackage(packageName: String) = true
        }

        val controller = PhoneController(repo, channel, FakeAppDirectory(), safetyGuard = guard)

        // 1. Benign page coordinate tap passes directly
        assertNull(controller.beforeTool("s1", "c_safe", "tap_xy", JSONObject().put("x", 500).put("y", 500)))

        // 2. Page transitions to payment screen -> stale snapshot refreshes & tap_xy inside pay button prompts
        currentPkg = "com.eg.android.AlipayGphone"
        currentElements = listOf(payBtn)

        val payTapDeferred = async {
            controller.beforeTool("s1", "c_pay_xy", "tap_xy", JSONObject().put("x", 500).put("y", 790))
        }
        repeat(30) {
            if (guard.pendingConfirmation.value != null) return@repeat
            delay(10L)
        }
        assertEquals("c_pay_xy", guard.pendingConfirmation.value?.callId)
        assertTrue(guard.pendingConfirmation.value?.title.orEmpty().contains("确认支付"))
        guard.resolveConfirmation("c_pay_xy", allow = false)
        val payVerdict = payTapDeferred.await()
        assertNotNull(payVerdict)
        assertTrue(payVerdict!!.block)

        // 3. Blind tap_xy outside known bounds on a page containing a sensitive payment button still asks confirmation
        val blindTapDeferred = async {
            controller.beforeTool("s1", "c_blind_xy", "tap_xy", JSONObject().put("x", 100).put("y", 100))
        }
        repeat(30) {
            if (guard.pendingConfirmation.value != null) return@repeat
            delay(10L)
        }
        assertEquals("c_blind_xy", guard.pendingConfirmation.value?.callId)
        guard.resolveConfirmation("c_blind_xy", allow = false)
        val blindVerdict = blindTapDeferred.await()
        assertNotNull(blindVerdict)
        assertTrue(blindVerdict!!.block)
    }

    @Test
    fun phoneController_lockscreenGate_blocksPrivateAndScreenToolsUntilUnlocked() = runBlocking {
        val repo = InMemoryRepo()
        var locked = true
        var unlockSucceeds = false
        val unlockReasons = mutableListOf<String>()

        val controller = PhoneController(
            repository = repo,
            channel = FakeDeviceChannel(),
            appDirectory = FakeAppDirectory(),
            isDeviceLocked = { locked },
            requestDeviceUnlock = { reason ->
                unlockReasons += reason
                if (unlockSucceeds) {
                    locked = false
                    true
                } else {
                    false
                }
            },
        )

        // Safe non-private voice utilities work directly while locked
        assertNull(controller.beforeTool("s1", "c1", "flashlight", JSONObject().put("on", true)))
        assertNull(controller.beforeTool("s1", "c2", "set_alarm", JSONObject().put("hour", 7).put("minute", 0)))
        assertNull(controller.beforeTool("s1", "c3", "get_device_status", JSONObject()))
        assertTrue(unlockReasons.isEmpty())

        // Private native tools & screen operations require unlock and block if user cancels unlock
        val notifBlocked = controller.beforeTool("s1", "c4", "read_notifications", JSONObject())
        assertNotNull(notifBlocked)
        assertTrue(notifBlocked!!.block && !notifBlocked.terminate)

        val contactBlocked = controller.beforeTool("s1", "c5", "find_contact", JSONObject().put("query", "张"))
        assertNotNull(contactBlocked)
        assertTrue(contactBlocked!!.block)

        val screenBlocked = controller.beforeTool("s1", "c6", "read_screen", JSONObject())
        assertNotNull(screenBlocked)
        assertTrue(screenBlocked!!.block)
        assertEquals(3, unlockReasons.size)

        // Once unlock succeeds, private tool is allowed
        unlockSucceeds = true
        assertNull(controller.beforeTool("s1", "c7", "read_notifications", JSONObject()))
        assertFalse(locked)
    }

    @Test
    fun scrollFindTap_scrollsLocallyFindsTargetAndRespectsSafetyGuard() = runBlocking {
        val repo = InMemoryRepo()
        val guard = SafetyGuard(confirmationTimeoutMs = 5_000L)

        fun el(idx: Int, text: String, top: Int) = UiElement(
            index = idx,
            text = text,
            contentDesc = "",
            viewId = "",
            className = "android.widget.TextView",
            bounds = Rect().apply { left = 40; this.top = top; right = 1000; bottom = top + 120 },
            clickable = true,
            scrollable = false,
            editable = false,
            checked = null,
            isPassword = false,
        )

        var scrollCount = 0
        var nodeTapCount = 0
        val pages = listOf(
            listOf(el(1, "WLAN", 200), el(2, "蓝牙", 360)),
            listOf(el(1, "电池", 200), el(2, "存储空间", 360)),
            listOf(el(1, "开发者选项", 200), el(2, "关于手机", 360)),
        )

        val channel = object : PhoneDeviceChannel {
            override val ownPackageName = "com.self.zai"
            override fun screenDimensions() = 1080 to 2400
            override suspend fun ensureReady() {}
            override suspend fun captureTree(width: Int, height: Int) = ScreenSnapshot(
                packageName = "com.android.settings",
                screenWidth = width,
                screenHeight = height,
                elements = pages[scrollCount.coerceAtMost(pages.lastIndex)],
                source = "fake",
            )
            override suspend fun captureBitmap(): Bitmap? = null
            override suspend fun tap(x: Int, y: Int) = true
            override suspend fun tapNode(bounds: Rect, centerX: Int, centerY: Int): Boolean {
                nodeTapCount++
                return true
            }
            override suspend fun longPress(x: Int, y: Int, durationMs: Int) = true
            override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean {
                scrollCount++
                return true
            }
            override suspend fun pressKey(key: String) = true
            override suspend fun inputText(text: String, targetBounds: Rect?) = true
            override suspend fun currentForegroundPackage() = "com.android.settings"
            override suspend fun launchPackage(packageName: String) = true
        }

        val controller = PhoneController(repo, channel, FakeAppDirectory(), safetyGuard = guard)
        val res = controller.executeTool(
            "s1",
            "c_sft",
            "scroll_find_tap",
            JSONObject().put("query", "关于手机").put("max_scrolls", 5),
        )
        assertFalse(res.isError)
        assertEquals(2, scrollCount)
        assertEquals(1, nodeTapCount)
        assertTrue(res.text.contains("滚动 2 次找到并点击「关于手机」"))

        // Searching for non-existent item stops early at bottom of list without exhausting all 6 scrolls
        scrollCount = 0
        val miss = controller.executeTool(
            "s1",
            "c_miss",
            "scroll_find_tap",
            JSONObject().put("query", "不存在的设置项").put("max_scrolls", 6),
        )
        assertFalse(miss.isError)
        assertTrue("Should stop once list stops changing", scrollCount <= 3)
        assertTrue(miss.text.contains("已到底部"))
    }

    @Test
    fun runActions_batchesMultipleStepsLocally_andStopsOnUnexpectedAppSwitchOrSafetyBlock() = runBlocking {
        val repo = InMemoryRepo()
        val guard = SafetyGuard(confirmationTimeoutMs = 5_000L)

        fun el(idx: Int, text: String, editable: Boolean = false) = UiElement(
            index = idx,
            text = text,
            contentDesc = "",
            viewId = "",
            className = if (editable) "android.widget.EditText" else "android.widget.Button",
            bounds = Rect().apply { left = 50; top = 100 * idx; right = 900; bottom = 100 * idx + 80 },
            clickable = true,
            scrollable = false,
            editable = editable,
            checked = null,
            isPassword = false,
        )

        var stepStage = 0
        var typedText = ""
        var entered = false
        var activePkg = "com.shop.app"

        val channel = object : PhoneDeviceChannel {
            override val ownPackageName = "com.self.zai"
            override fun screenDimensions() = 1080 to 2400
            override suspend fun ensureReady() {}
            override suspend fun captureTree(width: Int, height: Int) = ScreenSnapshot(
                packageName = activePkg,
                screenWidth = width,
                screenHeight = height,
                elements = when (stepStage) {
                    0 -> listOf(el(1, "搜索商品", editable = true), el(2, "确认支付"))
                    else -> listOf(el(1, "蓝牙耳机 热卖"), el(2, "综合排序"))
                },
                source = "fake",
            )
            override suspend fun captureBitmap(): Bitmap? = null
            override suspend fun tap(x: Int, y: Int) = true
            override suspend fun longPress(x: Int, y: Int, durationMs: Int) = true
            override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) = true
            override suspend fun pressKey(key: String): Boolean {
                if (key == "enter") {
                    entered = true
                    stepStage = 1
                }
                return true
            }
            override suspend fun inputText(text: String, targetBounds: Rect?): Boolean {
                typedText = text
                return true
            }
            override suspend fun currentForegroundPackage() = activePkg
            override suspend fun launchPackage(packageName: String) = true
        }

        val controller = PhoneController(repo, channel, FakeAppDirectory(), safetyGuard = guard)
        val batch = JSONArray()
            .put(JSONObject().put("type", "tap").put("text", "搜索商品"))
            .put(JSONObject().put("type", "input_text").put("target_text", "搜索商品").put("text", "蓝牙耳机").put("press_enter", true))
            .put(JSONObject().put("type", "wait_for_text").put("text", "综合排序").put("timeout_ms", 500))

        val res = controller.executeTool("s1", "c_batch", "run_actions", JSONObject().put("actions", batch))
        assertFalse(res.isError)
        assertEquals("蓝牙耳机", typedText)
        assertTrue(entered)
        assertTrue(res.text.contains("已连续执行 3 步"))

        // Sensitive sub-step inside run_actions must still invoke SafetyGuard confirmation
        stepStage = 0
        val payBatch = JSONArray().put(JSONObject().put("type", "tap").put("text", "确认支付"))
        val payDeferred = async {
            controller.executeTool("s1", "c_pay_batch", "run_actions", JSONObject().put("actions", payBatch))
        }
        repeat(30) {
            if (guard.pendingConfirmation.value != null) return@repeat
            delay(10L)
        }
        assertNotNull(guard.pendingConfirmation.value)
        guard.resolveConfirmation(guard.pendingConfirmation.value!!.callId, allow = false)
        val payRes = payDeferred.await()
        assertTrue(payRes.text.contains("安全拦截"))

        // Unexpected foreground app switch mid-batch aborts remaining steps safely
        var switched = false
        val switchChannel = object : PhoneDeviceChannel {
            override val ownPackageName = "com.self.zai"
            override fun screenDimensions() = 1080 to 2400
            override suspend fun ensureReady() {}
            override suspend fun captureTree(width: Int, height: Int) = ScreenSnapshot(
                packageName = if (switched) "com.other.ad" else "com.shop.app",
                screenWidth = width,
                screenHeight = height,
                elements = listOf(el(1, "打开详情")),
                source = "fake",
            )
            override suspend fun captureBitmap(): Bitmap? = null
            override suspend fun tap(x: Int, y: Int): Boolean {
                switched = true
                return true
            }
            override suspend fun longPress(x: Int, y: Int, durationMs: Int) = true
            override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) = true
            override suspend fun pressKey(key: String) = true
            override suspend fun inputText(text: String, targetBounds: Rect?) = true
            override suspend fun currentForegroundPackage() = if (switched) "com.other.ad" else "com.shop.app"
            override suspend fun launchPackage(packageName: String) = true
        }
        val switchController = PhoneController(repo, switchChannel, FakeAppDirectory())
        val twoSteps = JSONArray()
            .put(JSONObject().put("type", "tap").put("text", "打开详情"))
            .put(JSONObject().put("type", "tap").put("text", "打开详情"))
        val switchRes = switchController.executeTool("s1", "c_sw", "run_actions", JSONObject().put("actions", twoSteps))
        assertTrue(switchRes.text.contains("检测到页面跳转到 com.other.ad"))
    }

    @Test
    fun openSystemSetting_andOpenUri_validateSchemesAndGuardSensitiveLinks() = runBlocking {
        val repo = InMemoryRepo()
        val guard = SafetyGuard(confirmationTimeoutMs = 5_000L)
        val openedSettings = mutableListOf<Pair<String, String?>>()
        val openedUris = mutableListOf<String>()

        val backend = object : SystemAssistantBackend {
            override suspend fun getDeviceStatus() = ToolExecutionResult("ok")
            override suspend fun setAlarm(hour: Int, minute: Int, message: String?, days: List<Int>) = ToolExecutionResult("ok")
            override suspend fun setTimer(seconds: Int, message: String?) = ToolExecutionResult("ok")
            override suspend fun queryCalendar(daysAhead: Int) = ToolExecutionResult("ok")
            override suspend fun createCalendarEvent(
                title: String, startTimeRaw: String, durationMinutes: Int, location: String?, description: String?,
            ) = ToolExecutionResult("ok")
            override suspend fun setFlashlight(on: Boolean) = ToolExecutionResult("ok")
            override suspend fun controlMedia(action: String) = ToolExecutionResult("ok")
            override suspend fun findContacts(query: String) = ToolExecutionResult("ok")
            override suspend fun readNotifications(query: String?, limit: Int) = ToolExecutionResult("ok")
            override suspend fun openSystemSetting(target: String, state: String?): ToolExecutionResult {
                openedSettings += target to state
                return if (state != null) ToolExecutionResult("DIRECT_DONE:已直接切换 $target=$state")
                else ToolExecutionResult("已打开设置 $target")
            }
            override suspend fun openUri(uri: String, packageName: String?): ToolExecutionResult {
                openedUris += uri
                return ToolExecutionResult("已打开 $uri")
            }
        }

        val registry = PhoneToolRegistry.default(backend)
        val controller = PhoneController(repo, FakeDeviceChannel(), FakeAppDirectory(), toolRegistry = registry, safetyGuard = guard)

        // Direct toggle returns without screen dump
        val directRes = controller.executeTool(
            "s1", "c_wifi", "open_system_setting",
            JSONObject().put("target", "wifi").put("state", "on"),
        )
        assertFalse(directRes.isError)
        assertEquals("已直接切换 wifi=on", directRes.text)
        assertEquals("wifi" to "on", openedSettings.first())

        // Safe geo URI allowed
        val geoRes = controller.executeTool("s1", "c_geo", "open_uri", JSONObject().put("uri", "geo:0,0?q=故宫"))
        assertFalse(geoRes.isError)
        assertEquals(listOf("geo:0,0?q=故宫"), openedUris)

        // Unsafe file:// scheme rejected
        val badUri = controller.executeTool("s1", "c_bad", "open_uri", JSONObject().put("uri", "file:///etc/hosts"))
        assertTrue(badUri.isError)

        // Sensitive alipays:// URI triggers confirmation in beforeTool
        val payUriDeferred = async {
            controller.beforeTool("s1", "c_pay_uri", "open_uri", JSONObject().put("uri", "alipays://platformapi/startapp?appId=20000056"))
        }
        repeat(30) {
            if (guard.pendingConfirmation.value != null) return@repeat
            delay(10L)
        }
        assertEquals("c_pay_uri", guard.pendingConfirmation.value?.callId)
        guard.resolveConfirmation("c_pay_uri", allow = false)
        assertTrue(payUriDeferred.await()!!.block)
    }

    private class InMemoryRepo : SessionRepository {
        private val _settings = MutableStateFlow(AppSettings())
        override val settings: StateFlow<AppSettings> = _settings
        private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
        override val sessions: StateFlow<List<ChatSession>> = _sessions
        override fun updateSettings(transform: (AppSettings) -> AppSettings): AppSettings =
            transform(_settings.value).also { _settings.value = it }
        override fun upsertSession(session: ChatSession, flushImmediately: Boolean) {
            _sessions.value = listOf(session) + _sessions.value.filterNot { it.id == session.id }
        }
        override fun deleteSession(sessionId: String) {
            _sessions.value = _sessions.value.filterNot { it.id == sessionId }
        }
        override fun createNewSession(): ChatSession =
            ChatSession(id = "s_test", title = "新对话", updatedAtMs = 1L).also { upsertSession(it, true) }
        override fun flushNow() {}
    }

    private class FakeAppDirectory : AppDirectory {
        override fun resolveAppLabel(packageName: String) = "设置"
        override fun findLaunchableApp(query: String) = "com.android.settings" to "设置"
        override fun queryLaunchableApps(query: String) = listOf("com.android.settings" to "设置")
    }

    private class FakeDeviceChannel : PhoneDeviceChannel {
        override val ownPackageName = "com.self.zai"
        override fun screenDimensions() = 1080 to 2400
        override suspend fun ensureReady() {}
        override suspend fun captureTree(width: Int, height: Int) = ScreenSnapshot(
            packageName = "com.android.settings",
            screenWidth = width,
            screenHeight = height,
            elements = emptyList(),
            source = "fake",
        )
        override suspend fun captureBitmap(): Bitmap? = null
        override suspend fun tap(x: Int, y: Int) = true
        override suspend fun longPress(x: Int, y: Int, durationMs: Int) = true
        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) = true
        override suspend fun pressKey(key: String) = true
        override suspend fun inputText(text: String, targetBounds: Rect?) = true
        override suspend fun currentForegroundPackage() = "com.android.settings"
        override suspend fun launchPackage(packageName: String) = true
    }
}
