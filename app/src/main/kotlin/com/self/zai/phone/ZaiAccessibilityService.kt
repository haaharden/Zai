package com.self.zai.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class UiElement(
    val index: Int,
    val text: String,
    val contentDesc: String,
    val viewId: String,
    val className: String,
    val bounds: Rect,
    val clickable: Boolean,
    val scrollable: Boolean,
    val editable: Boolean,
    val checked: Boolean?,
    val isPassword: Boolean,
) {
    val centerX: Int get() = bounds.centerX()
    val centerY: Int get() = bounds.centerY()

    val displayLabel: String
        get() = listOf(text, contentDesc)
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: viewId.substringAfterLast('/').ifBlank { className.substringAfterLast('.') }
}

data class ScreenSnapshot(
    val packageName: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val elements: List<UiElement>,
    val source: String, // "accessibility" or "shizuku_uiautomator"
) {
    fun formatForModel(appName: String? = null): String {
        val sb = StringBuilder()
        sb.append("app: ").append(appName?.ifBlank { packageName } ?: packageName)
        sb.append(" (").append(packageName).append(")\n")
        sb.append("screen: ").append(screenWidth).append("x").append(screenHeight)
            .append(" via ").append(source).append("\n")
        if (elements.isEmpty()) {
            sb.append("(当前页面未读取到可交互控件，建议调用 screenshot 查看画面并使用 tap_xy 坐标点击)\n")
            return sb.toString()
        }
        for (el in elements) {
            sb.append("[").append(el.index).append("] ")
            val shortCls = el.className.substringAfterLast('.')
            sb.append(shortCls)
            val tags = buildList {
                if (el.clickable) add("可点")
                if (el.editable) add("输入框")
                if (el.scrollable) add("可滚")
                if (el.checked == true) add("已选")
                if (el.isPassword) add("密码")
            }
            if (tags.isNotEmpty()) sb.append("{").append(tags.joinToString(",")).append("}")
            if (el.text.isNotBlank()) sb.append(" \"").append(el.text.replace("\n", " ").take(60)).append("\"")
            if (el.contentDesc.isNotBlank() && el.contentDesc != el.text) {
                sb.append(" desc=\"").append(el.contentDesc.replace("\n", " ").take(50)).append("\"")
            }
            val nx = if (screenWidth > 0) (el.centerX * 1000 / screenWidth) else 0
            val ny = if (screenHeight > 0) (el.centerY * 1000 / screenHeight) else 0
            sb.append(" @(").append(nx).append(",").append(ny).append(")\n")
        }
        return sb.toString()
    }
}

/**
 * Lightweight, resilient AccessibilityService for fast UI tree extraction, text entry, and fallback gestures.
 * Tracks real binder-level service liveness rather than relying on stale system Settings flags.
 */
class ZaiAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isConnected.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.packageName?.toString()?.takeIf { it.isNotBlank() }?.let {
            lastEventPackage = it
        }
    }

    override fun onInterrupt() {
        // Keep alive unless unbound
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        _isConnected.value = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        _isConnected.value = false
        super.onDestroy()
    }

    fun captureScreenTree(screenWidth: Int, screenHeight: Int): ScreenSnapshot? {
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString()?.ifBlank { lastEventPackage } ?: lastEventPackage
        val rawNodes = mutableListOf<UiElement>()
        val seenRects = HashSet<String>()

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 28 || rawNodes.size >= MAX_ELEMENTS) return
            if (!node.isVisibleToUser) return

            val rect = Rect()
            node.getBoundsInScreen(rect)
            val validRect = rect.width() > 4 && rect.height() > 4 &&
                rect.right > 0 && rect.bottom > 0 &&
                rect.left < screenWidth && rect.top < screenHeight

            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val clickable = node.isClickable
            val editable = node.isEditable
            val scrollable = node.isScrollable
            val meaningful = clickable || editable || scrollable || text.isNotEmpty() || desc.isNotEmpty()

            if (validRect && meaningful) {
                val dedupKey = "${rect.left},${rect.top},${rect.right},${rect.bottom}|$text|$desc"
                if (seenRects.add(dedupKey)) {
                    rawNodes += UiElement(
                        index = rawNodes.size + 1,
                        text = text,
                        contentDesc = desc,
                        viewId = node.viewIdResourceName.orEmpty(),
                        className = node.className?.toString().orEmpty(),
                        bounds = rect,
                        clickable = clickable,
                        scrollable = scrollable,
                        editable = editable,
                        checked = if (node.isCheckable) @Suppress("DEPRECATION") node.isChecked else null,
                        isPassword = node.isPassword,
                    )
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child, depth + 1)
            }
        }

        walk(root, 0)
        return ScreenSnapshot(
            packageName = pkg,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            elements = rawNodes,
            source = "accessibility",
        )
    }

    fun setTextAtBounds(targetBounds: Rect?, text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        var target: AccessibilityNodeInfo? = null

        fun find(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 28 || target != null) return
            if (node.isEditable) {
                if (targetBounds == null && node.isFocused) {
                    target = node
                    return
                }
                if (targetBounds != null) {
                    val r = Rect()
                    node.getBoundsInScreen(r)
                    if (Rect.intersects(r, targetBounds)) {
                        target = node
                        return
                    }
                }
            }
            for (i in 0 until node.childCount) {
                find(node.getChild(i), depth + 1)
            }
        }

        find(root, 0)
        val node = target ?: root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    suspend fun performTap(x: Int, y: Int, durationMs: Long = 80L): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        return dispatchGestureSuspend(GestureDescription.Builder().addStroke(stroke).build())
    }

    suspend fun performSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 350L): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        return dispatchGestureSuspend(GestureDescription.Builder().addStroke(stroke).build())
    }

    private suspend fun dispatchGestureSuspend(gesture: GestureDescription): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        val started = withContext(Dispatchers.Main) {
            dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        deferred.complete(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        deferred.complete(false)
                    }
                },
                null,
            )
        }
        if (!started) return false
        return withTimeoutOrNull(4_000L) { deferred.await() } ?: false
    }

    suspend fun captureBitmap(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val deferred = CompletableDeferred<Bitmap?>()
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val bmp = runCatching {
                        val hw = Bitmap.wrapHardwareBuffer(
                            screenshot.hardwareBuffer,
                            screenshot.colorSpace,
                        )
                        val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                        screenshot.hardwareBuffer.close()
                        soft
                    }.getOrNull()
                    deferred.complete(bmp)
                }

                override fun onFailure(errorCode: Int) {
                    deferred.complete(null)
                }
            },
        )
        return withTimeoutOrNull(5_000L) { deferred.await() }
    }

    companion object {
        private const val MAX_ELEMENTS = 100

        @Volatile
        var instance: ZaiAccessibilityService? = null
            private set

        @Volatile
        var lastEventPackage: String = ""
            private set

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        fun isReallyConnected(): Boolean {
            val svc = instance ?: return false
            return runCatching {
                svc.serviceInfo != null
            }.getOrDefault(false)
        }
    }
}
