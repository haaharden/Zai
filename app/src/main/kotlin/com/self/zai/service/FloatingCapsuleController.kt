package com.self.zai.service

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.self.zai.MainActivity
import kotlin.math.abs

/**
 * Compact draggable floating control capsule visible above other apps while Zai operates the phone.
 * Temporarily hides itself during screenshots so the vision model never sees the capsule.
 */
class FloatingCapsuleController(
    private val context: Context,
    private val onStopRequested: () -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var rootView: LinearLayout? = null
    private var statusTextView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var active = false

    fun show(initialStatus: String) {
        mainHandler.post {
            active = true
            if (!Settings.canDrawOverlays(context)) return@post
            if (rootView != null) {
                statusTextView?.text = initialStatus
                rootView?.visibility = View.VISIBLE
                return@post
            }
            attachCapsule(initialStatus)
        }
    }

    fun updateStatus(text: String) {
        mainHandler.post {
            statusTextView?.text = text
        }
    }

    fun setTemporarilyHidden(hidden: Boolean) {
        mainHandler.post {
            rootView?.visibility = if (hidden || !active) View.INVISIBLE else View.VISIBLE
        }
    }

    fun dismiss() {
        mainHandler.post {
            active = false
            rootView?.let { v ->
                runCatching { wm.removeViewImmediate(v) }
            }
            rootView = null
            statusTextView = null
            layoutParams = null
        }
    }

    private fun attachCapsule(initialStatus: String) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(Color.argb(238, 17, 20, 25))
                setStroke(dp(1), Color.argb(110, 91, 139, 255))
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

        val label = TextView(context).apply {
            text = initialStatus
            setTextColor(Color.rgb(238, 241, 246))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            maxLines = 1
            maxWidth = dp(170)
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginEnd = dp(10)
            }
        }

        val stopBtn = TextView(context).apply {
            text = "停止"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(11), dp(5), dp(11), dp(5))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(Color.rgb(214, 59, 59))
            }
            setOnClickListener { onStopRequested() }
        }

        container.addView(pulseDot)
        container.addView(label)
        container.addView(stopBtn)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(48)
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
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        }
                        runCatching { context.startActivity(openIntent) }
                    }
                    true
                }
                else -> false
            }
        }

        runCatching {
            wm.addView(container, params)
            rootView = container
            statusTextView = label
            layoutParams = params
        }
    }
}
