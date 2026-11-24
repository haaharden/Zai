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
import com.self.zai.pi.ToolExecutionResult
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
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
