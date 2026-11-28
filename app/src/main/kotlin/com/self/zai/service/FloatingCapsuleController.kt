package com.self.zai.service

import android.animation.ValueAnimator
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import com.self.zai.model.ZaiComponentNames
import com.self.zai.phone.ZaiAccessibilityService
import kotlin.math.abs

/**
 * Minimal, unobtrusive visual indicator shown across apps while Zai operates the phone:
 * 1. Full-screen breathing glacier-blue edge frame (non-touchable, keeps screen awake).
 * 2. Compact top draggable pill showing simply "正在操作手机" + one-tap Stop button.
 * 3. Brief tap-point ripple marker when Zai clicks a coordinate.
 *
 * Uses TYPE_ACCESSIBILITY_OVERLAY when AccessibilityService is bound (requires zero overlay permission),
 * falling back to TYPE_APPLICATION_OVERLAY when SYSTEM_ALERT_WINDOW is granted.
 * Automatically hides all layers during screenshots so the vision model never sees the chrome.
 */
class FloatingCapsuleController(
    private val context: Context,
    private val onStopRequested: () -> Unit,
    private val onOpenRunningSession: () -> Unit = {},
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    private var activeWm: WindowManager? = null
    private var activeWindowType: Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private var edgeGlowView: EdgeGlowView? = null
    private var capsuleView: LinearLayout? = null
    private var labelView: TextView? = null
    private var stopButtonView: TextView? = null
    private var dotView: View? = null
    private var dotAnimator: ValueAnimator? = null
    private var autoDismissRunnable: Runnable? = null
    private var active = false
    private var showingCompletedBanner = false
    private var showCapsuleEnabled = true
    private var showEdgeEnabled = true
    private var currentStatusText = "正在操作手机"

    fun show(
        showCapsule: Boolean = true,
        showEdgeGlow: Boolean = true,
        statusLabel: String = "正在操作手机",
    ) {
        mainHandler.post {
            cancelAutoDismiss()
            active = true
            showingCompletedBanner = false
            showCapsuleEnabled = showCapsule
            showEdgeEnabled = showEdgeGlow
            currentStatusText = statusLabel.ifBlank { "正在操作手机" }

            val resolved = resolveWindowHost() ?: return@post
            val (wm, winType) = resolved

            if (activeWm !== wm || activeWindowType != winType) {
                detachAllInternal()
                activeWm = wm
                activeWindowType = winType
            }

            if (showEdgeEnabled && edgeGlowView == null) {
                attachEdgeGlow(wm, winType)
            }
            if (showCapsuleEnabled) {
                if (capsuleView == null) {
                    attachCapsule(wm, winType)
                } else {
                    applyRunningChrome()
                    capsuleView?.visibility = View.VISIBLE
                }
            }
            edgeGlowView?.visibility = if (showEdgeEnabled) View.VISIBLE else View.GONE
        }
    }

    /**
     * When background auto-return is blocked by OEM rules (e.g. Xiaomi "后台弹出界面" disabled),
     * turns the top Accessibility pill into a calm clickable banner so one real tap returns to Zai.
     */
    fun showCompletedBanner(
        statusLabel: String = "已完成，点此返回",
        autoDismissMs: Long = 10_000L,
    ) {
        mainHandler.post {
            cancelAutoDismiss()
            val resolved = resolveWindowHost() ?: return@post
            val (wm, winType) = resolved
            active = true
            showingCompletedBanner = true
            showEdgeEnabled = false
            edgeGlowView?.let { v ->
                v.stopPulse()
                runCatching { activeWm?.removeViewImmediate(v) }
            }
            edgeGlowView = null

            if (activeWm !== wm || activeWindowType != winType) {
                detachAllInternal()
                activeWm = wm
                activeWindowType = winType
            }

            if (capsuleView == null) {
                attachCapsule(wm, winType)
            }
            stopDotPulse()
            dotView?.alpha = 1f
            dotView?.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(94, 179, 132))
            }
            labelView?.text = statusLabel.ifBlank { "已完成，点此返回" }
            stopButtonView?.visibility = View.GONE
            capsuleView?.visibility = View.VISIBLE

            val task = Runnable { dismiss() }
            autoDismissRunnable = task
            mainHandler.postDelayed(task, autoDismissMs)
        }
    }

    fun showTapMarker(x: Int, y: Int) {
        mainHandler.post {
            if (!active || showingCompletedBanner) return@post
            val (wm, winType) = resolveWindowHost() ?: return@post
            val density = context.resources.displayMetrics.density
            val sizePx = (72 * density).toInt()
            val marker = TapRippleView(context)
            val params = WindowManager.LayoutParams(
                sizePx,
                sizePx,
                winType,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                this.x = (x - sizePx / 2).coerceAtLeast(0)
                this.y = (y - sizePx / 2).coerceAtLeast(0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            val added = runCatching {
                wm.addView(marker, params)
                true
            }.getOrDefault(false)
            if (!added) return@post
            marker.startAnimation {
                runCatching { wm.removeViewImmediate(marker) }
            }
        }
    }

    fun setTemporarilyHidden(hidden: Boolean) {
        mainHandler.post {
            val vis = if (hidden || !active) View.INVISIBLE else View.VISIBLE
            if (showCapsuleEnabled) capsuleView?.visibility = vis
            if (showEdgeEnabled) edgeGlowView?.visibility = vis
        }
    }

    fun dismiss() {
        mainHandler.post {
            cancelAutoDismiss()
            active = false
            showingCompletedBanner = false
            detachAllInternal()
        }
    }

    private fun cancelAutoDismiss() {
        autoDismissRunnable?.let { mainHandler.removeCallbacks(it) }
        autoDismissRunnable = null
    }

    private fun applyRunningChrome() {
        dotView?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.rgb(217, 119, 87))
        }
        startDotPulse()
        labelView?.text = currentStatusText
        stopButtonView?.visibility = View.VISIBLE
    }

    private fun startDotPulse() {
        val target = dotView ?: return
        dotAnimator?.cancel()
        dotAnimator = ValueAnimator.ofFloat(1f, 0.28f).apply {
            duration = 820L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { target.alpha = it.animatedValue as Float }
            start()
        }
    }

    private fun stopDotPulse() {
        dotAnimator?.cancel()
        dotAnimator = null
    }

    private fun detachAllInternal() {
        stopDotPulse()
        val wm = activeWm
        edgeGlowView?.let { v ->
            v.stopPulse()
            runCatching { wm?.removeViewImmediate(v) }
        }
        capsuleView?.let { v ->
            runCatching { wm?.removeViewImmediate(v) }
        }
        edgeGlowView = null
        capsuleView = null
        labelView = null
        stopButtonView = null
        dotView = null
    }

    private fun resolveWindowHost(): Pair<WindowManager, Int>? {
        val a11y = ZaiAccessibilityService.instance
        if (a11y != null) {
            val wm = a11y.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (wm != null) return wm to WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        }
        if (Settings.canDrawOverlays(context)) {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (wm != null) return wm to WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
        return null
    }

    private fun attachEdgeGlow(wm: WindowManager, winType: Int) {
        val view = EdgeGlowView(context)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            winType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        runCatching {
            wm.addView(view, params)
            view.startPulse()
            edgeGlowView = view
        }
    }

    private fun attachCapsule(wm: WindowManager, winType: Int) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(Color.argb(240, 29, 28, 25))
                setStroke(dp(1), Color.argb(120, 217, 119, 87))
            }
            elevation = dp(10).toFloat()
        }

        val pulseDot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(217, 119, 87))
            }
            layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply {
                marginEnd = dp(9)
            }
        }

        val statusLabel = TextView(context).apply {
            text = currentStatusText
            setTextColor(Color.rgb(243, 241, 234))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            paint.isFakeBoldText = true
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(215)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginEnd = dp(12)
            }
        }

        val stopBtn = TextView(context).apply {
            text = "停止"
            setTextColor(Color.rgb(255, 246, 244))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            paint.isFakeBoldText = true
            setPadding(dp(11), dp(5), dp(11), dp(5))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(Color.argb(230, 192, 68, 56))
            }
            setOnClickListener { onStopRequested() }
        }

        container.addView(pulseDot)
        container.addView(statusLabel)
        container.addView(stopBtn)

        val statusBarHeight = getStatusBarHeightPx()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            winType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBarHeight + dp(10)
        }

        var startX = 0
        var startY = 0
        var downRawX = 0f
        var downRawY = 0f
        var moved = false

        container.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downRawX).toInt()
                    val dy = (ev.rawY - downRawY).toInt()
                    if (abs(dx) > dp(4) || abs(dy) > dp(4)) moved = true
                    params.x = startX + dx
                    params.y = startY + dy
                    runCatching { wm.updateViewLayout(container, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        onOpenRunningSession()
                        val openIntent = Intent().apply {
                            setClassName(context.packageName, ZaiComponentNames.MAIN_ACTIVITY)
                            addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                            )
                        }
                        runCatching {
                            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                            am.appTasks.firstOrNull()?.moveToFront()
                        }
                        val hostCtx = ZaiAccessibilityService.instance ?: context
                        runCatching { hostCtx.startActivity(openIntent) }
                        if (showingCompletedBanner) {
                            dismiss()
                        }
                    }
                    true
                }
                else -> false
            }
        }

        runCatching {
            wm.addView(container, params)
            capsuleView = container
            labelView = statusLabel
            stopButtonView = stopBtn
            dotView = pulseDot
            startDotPulse()
        }
    }

    private fun getStatusBarHeightPx(): Int {
        val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) context.resources.getDimensionPixelSize(resId) else (36 * context.resources.displayMetrics.density).toInt()
    }
}

private class EdgeGlowView(context: Context) : View(context) {
    private val density = context.resources.displayMetrics.density
    private val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7f * density
        color = Color.argb(56, 217, 119, 87)
    }
    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.argb(215, 217, 119, 87)
    }
    private val rect = RectF()
    private val cornerRadius = 34f * density
    private var pulseAlpha = 1f
    private var animator: ValueAnimator? = null

    fun startPulse() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0.48f, 1f).apply {
            duration = 1350L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                pulseAlpha = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stopPulse() {
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        val inset = 2f * density
        rect.set(inset, inset, width - inset, height - inset)
        outerPaint.alpha = (75 * pulseAlpha).toInt().coerceIn(20, 95)
        innerPaint.alpha = (225 * pulseAlpha).toInt().coerceIn(95, 240)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, outerPaint)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, innerPaint)
    }
}

private class TapRippleView(context: Context) : View(context) {
    private val density = context.resources.displayMetrics.density
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = Color.rgb(217, 119, 87)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(175, 217, 119, 87)
    }
    private var progress = 0f

    fun startAnimation(onEnd: () -> Unit) {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 380L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    onEnd()
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val minR = 6f * density
        val maxR = 30f * density
        val r = minR + (maxR - minR) * progress
        val alpha = ((1f - progress) * 235).toInt().coerceIn(0, 235)
        ringPaint.alpha = alpha
        dotPaint.alpha = ((1f - progress) * 140).toInt().coerceIn(0, 140)
        canvas.drawCircle(cx, cy, 4.5f * density, dotPaint)
        canvas.drawCircle(cx, cy, r, ringPaint)
    }
}
