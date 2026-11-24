package com.self.zai.phone.channel

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import com.self.zai.data.SessionRepository
import com.self.zai.model.ControlChannelMode
import com.self.zai.permission.DevicePermissionHelper
import com.self.zai.phone.ScreenSnapshot
import com.self.zai.phone.UiAutomatorXmlParser
import com.self.zai.phone.ZaiAccessibilityService
import com.self.zai.shell.ShizukuBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import java.io.File

/**
 * Low-level hardware/OS automation channel abstraction.
 * Decouples tool execution from AccessibilityService, Shizuku, future VirtualDisplay channels, and test fakes.
 */
interface PhoneDeviceChannel {
    val ownPackageName: String
    fun screenDimensions(): Pair<Int, Int>
    suspend fun ensureReady()
    suspend fun captureTree(width: Int, height: Int): ScreenSnapshot?
    suspend fun captureBitmap(): Bitmap?
    suspend fun tap(x: Int, y: Int): Boolean
    suspend fun longPress(x: Int, y: Int, durationMs: Int): Boolean
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean
    suspend fun pressKey(key: String): Boolean
    suspend fun inputText(text: String, targetBounds: Rect?): Boolean
    suspend fun currentForegroundPackage(): String
    suspend fun launchPackage(packageName: String): Boolean
}

/**
 * Production composite device channel combining AccessibilityService (primary, zero-Shizuku)
 * with optional Shizuku fallback/priority according to [ControlChannelMode].
 */
class AndroidCompositeDeviceChannel(
    private val context: Context,
    private val repository: SessionRepository,
    private val shizuku: ShizukuBridge,
    private val onHideOverlayForCapture: (Boolean) -> Unit,
    private val onTapVisualMarker: (x: Int, y: Int) -> Unit,
) : PhoneDeviceChannel {

    private val mainHandler = Handler(Looper.getMainLooper())

    override val ownPackageName: String
        get() = context.packageName

    override fun screenDimensions(): Pair<Int, Int> {
        val dm = context.resources.displayMetrics
        return dm.widthPixels.coerceAtLeast(1) to dm.heightPixels.coerceAtLeast(1)
    }

    override suspend fun ensureReady() {
        val settings = repository.settings.value
        if (!settings.autoRestoreAccessibility) return
        if (ZaiAccessibilityService.isReallyConnected()) return

        if (DevicePermissionHelper.hasWriteSecureSettings(context)) {
            if (DevicePermissionHelper.restoreAccessibilityViaSecureSettings(context).isSuccess) {
                delay(450L)
                if (ZaiAccessibilityService.isReallyConnected()) return
            }
        }

        if (!shizuku.status.value.isReady) return
        val component = ComponentName(context, ZaiAccessibilityService::class.java).flattenToString()
        if (shizuku.restoreAccessibilityService(component).isSuccess) {
            delay(450L)
        }
    }

    override suspend fun captureTree(width: Int, height: Int): ScreenSnapshot? {
        ZaiAccessibilityService.instance?.captureScreenTree(width, height)?.let { return it }
        if (shouldUseShizuku()) {
            val xmlFile = File(context.externalCacheDir ?: context.cacheDir, "zai_ui.xml")
            val xmlRes = shizuku.dumpUiXmlToFile(xmlFile)
            runCatching { xmlFile.delete() }
            xmlRes.onSuccess { xml ->
                return UiAutomatorXmlParser.parse(xml, width, height)
            }
        }
        return null
    }

    override suspend fun captureBitmap(): Bitmap? {
        onHideOverlayForCapture(true)
        delay(90L)
        return try {
            if (shouldUseShizuku()) {
                val capFile = File(context.externalCacheDir ?: context.cacheDir, "zai_cap.png")
                val res = shizuku.screencapToFile(capFile)
                if (res.isSuccess) {
                    val bmp = BitmapFactory.decodeFile(capFile.absolutePath)
                    runCatching { capFile.delete() }
                    if (bmp != null) return bmp
                }
            }
            ZaiAccessibilityService.instance?.captureBitmap()
        } finally {
            onHideOverlayForCapture(false)
        }
    }

    override suspend fun tap(x: Int, y: Int): Boolean {
        onTapVisualMarker(x, y)
        if (shouldUseShizuku() && shizuku.tap(x, y).isSuccess) return true
        return ZaiAccessibilityService.instance?.performTap(x, y) == true
    }

    override suspend fun longPress(x: Int, y: Int, durationMs: Int): Boolean {
        onTapVisualMarker(x, y)
        if (shouldUseShizuku() && shizuku.longPress(x, y, durationMs).isSuccess) return true
        return ZaiAccessibilityService.instance?.performTap(x, y, durationMs.toLong()) == true
    }

    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean {
        if (shouldUseShizuku() && shizuku.swipe(x1, y1, x2, y2, durationMs).isSuccess) return true
        return ZaiAccessibilityService.instance?.performSwipe(x1, y1, x2, y2, durationMs.toLong()) == true
    }

    override suspend fun pressKey(key: String): Boolean {
        val (keyCode, globalAction) = when (key.lowercase()) {
            "back" -> KeyEvent.KEYCODE_BACK to AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> KeyEvent.KEYCODE_HOME to AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> KeyEvent.KEYCODE_APP_SWITCH to AccessibilityService.GLOBAL_ACTION_RECENTS
            "enter" -> KeyEvent.KEYCODE_ENTER to -1
            else -> return false
        }
        if (shouldUseShizuku() && shizuku.keyEvent(keyCode).isSuccess) return true
        if (key.equals("enter", ignoreCase = true)) {
            if (ZaiAccessibilityService.instance?.performImeEnter() == true) return true
        }
        if (globalAction >= 0) {
            return ZaiAccessibilityService.instance?.performGlobalAction(globalAction) == true
        }
        return false
    }

    override suspend fun inputText(text: String, targetBounds: Rect?): Boolean {
        if (ZaiAccessibilityService.instance?.setTextAtBounds(targetBounds, text) == true) {
            return true
        }
        if (shouldUseShizuku()) {
            val isPureAscii = text.all { it.code in 32..126 }
            if (isPureAscii && shizuku.inputTextAscii(text).isSuccess) {
                return true
            }
            setClipboardText(text)
            delay(120L)
            if (shizuku.keyEvent(KEYCODE_PASTE).isSuccess) {
                return true
            }
        }
        return false
    }

    override suspend fun currentForegroundPackage(): String {
        val a11yPkg = ZaiAccessibilityService.instance?.rootInActiveWindow?.packageName?.toString()
        if (!a11yPkg.isNullOrBlank()) return a11yPkg
        if (shouldUseShizuku()) {
            shizuku.getTopPackageName()?.let { return it }
        }
        return ZaiAccessibilityService.lastEventPackage
    }

    override suspend fun launchPackage(packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            val hostCtx = ZaiAccessibilityService.instance ?: context
            val launched = runCatching {
                hostCtx.startActivity(intent)
                true
            }.getOrDefault(false)
            if (launched) return true
        }
        if (shouldUseShizuku()) {
            return shizuku.launchPackage(packageName).isSuccess
        }
        return false
    }

    private fun shouldUseShizuku(): Boolean {
        if (repository.settings.value.channelMode == ControlChannelMode.ACCESSIBILITY_ONLY) {
            return false
        }
        return shizuku.status.value.binderAlive && shizuku.status.value.permissionGranted
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

    companion object {
        private const val KEYCODE_PASTE = 279
    }
}
