package com.self.zai.service

import android.animation.ValueAnimator
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
import com.self.zai.MainActivity
import com.self.zai.phone.ZaiAccessibilityService
import kotlin.math.abs

/**
 * Live visual indicator shown across apps while Zai operates the phone:
 * 1. Full-screen breathing cobalt edge frame (non-touchable, keeps screen awake during runs).
 * 2. Top draggable control capsule showing live step + count + one-tap Stop button.
 * 3. Brief tap-point ripple marker when Zai clicks a coordinate.
 *
 * Uses TYPE_ACCESSIBILITY_OVERLAY when AccessibilityService is bound (requires zero overlay permission),
 * falling back to TYPE_APPLICATION_OVERLAY when SYSTEM_ALERT_WINDOW is granted.
 * Automatically hides all layers during screenshots so the vision model never sees the chrome.
 */
class FloatingCapsuleController(
    private val context: Context,
    private val onStopRequested: () -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    private var activeWm: WindowManager? = null
    private var activeWindowType: Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private var edgeGlowView: EdgeGlowView? = null
    private var capsuleView: LinearLayout? = null
    private var stepCounterView: TextView? = null
    private var statusTextView: TextView? = null
    private var active = false
    private var showCapsuleEnabled = true
    private var showEdgeEnabled = true

    fun show(
        initialStatus: String,
        stepNumber: Int = 1,
        showCapsule: Boolean = true,
        showEdgeGlow: Boolean = true,
    ) {
        mainHandler.post {
            active = true
            showCapsuleEnabled = showCapsule
            showEdgeEnabled = showEdgeGlow

            val resolved = resolveWindowHost() ?: return@post
            val (wm, winType) = resolved

            // If window host/type changed (e.g. Accessibility just connected), recreate cleanly
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
                    attachCapsule(wm, winType, initialStatus, stepNumber)
                } else {
                    updateTextInternal(initialStatus, stepNumber)
                    capsuleView?.visibility = View.VISIBLE
                }
            }
            edgeGlowView?.visibility = if (showEdgeEnabled) View.VISIBLE else View.GONE
        }
    }

    fun updateStatus(text: String, stepNumber: Int) {
        mainHandler.post {
            if (!active) return@post
            updateTextInternal(text, stepNumber)
        }
    }

    fun showTapMarker(x: Int, y: Int) {
        mainHandler.post {
            if (!active) return@post
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
            active = false
            detachAllInternal()
        }
    }

    private fun detachAllInternal() {
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
        stepCounterView = null
        statusTextView = null
    }

    private fun resolveWindowHost(): Pair<WindowManager, Int>? {
        // 1. Prefer Accessibility overlay: works immediately with zero extra overlay permission!
        val a11y = ZaiAccessibilityService.instance
        if (a11y != null) {
            val wm = a11y.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (wm != null) return wm to WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        }
        // 2. Fallback to standard overlay permission when granted
        if (Settings.canDrawOverlays(context)) {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (wm != null) return wm to WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
        return null
    }

    private fun updateTextInternal(text: String, stepNumber: Int) {
        statusTextView?.text = text
        stepCounterView?.text = if (stepNumber > 0) "第 $stepNumber 步" else "操控中"
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

    private fun attachCapsule(wm: WindowManager, winType: Int, initialStatus: String, stepNumber: Int) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(7), dp(7), dp(7))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(Color.argb(242, 17, 20, 25))
                setStroke(dp(1), Color.argb(160, 91, 139, 255))
            }
            elevation = dp(10).toFloat()
        }

        val pulseDot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(91, 139, 255))
            }
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                marginEnd = dp(8)
            }
        }

        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginEnd = dp(10)
            }
        }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val brandTag = TextView(context).apply {
            text = "Zai 正在操控"
            setTextColor(Color.rgb(154, 186, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginEnd = dp(6)
            }
        }

        val stepBadge = TextView(context).apply {
            text = if (stepNumber > 0) "第 $stepNumber 步" else "操控中"
            setTextColor(Color.rgb(160, 170, 185))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
        }

        headerRow.addView(brandTag)
        headerRow.addView(stepBadge)

        val statusLabel = TextView(context).apply {
            text = initialStatus
            setTextColor(Color.rgb(242, 245, 250))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            maxLines = 1
            maxWidth = dp(185)
            ellipsize = TextUtils.TruncateAt.END
        }

        textColumn.addView(headerRow)
        textColumn.addView(statusLabel)

        val stopBtn = TextView(context).apply {
            text = "停止"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            paint.isFakeBoldText = true
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(Color.rgb(214, 59, 59))
            }
            setOnClickListener { onStopRequested() }
        }

        container.addView(pulseDot)
        container.addView(textColumn)
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
                        val openIntent = Intent(context, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        }
                        val hostCtx = ZaiAccessibilityService.instance ?: context
                        runCatching { hostCtx.startActivity(openIntent) }
                    }
                    true
                }
                else -> false
            }
        }

        runCatching {
            wm.addView(container, params)
            capsuleView = container
            stepCounterView = stepBadge
            statusTextView = statusLabel
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
        strokeWidth = 8f * density
        color = Color.argb(65, 91, 139, 255)
    }
    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * density
        color = Color.argb(225, 91, 139, 255)
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
        color = Color.rgb(91, 139, 255)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(170, 91, 139, 255)
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
