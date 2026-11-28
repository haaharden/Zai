package com.self.zai.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.self.zai.ui.theme.HaloBlue
import com.self.zai.ui.theme.HaloCyan
import com.self.zai.ui.theme.HaloIndigo
import kotlin.math.PI
import kotlin.math.sin

/**
 * Unified Apple-inspired spring motion specs for Zai.
 */
object ZaiMotion {
    val SpringSnappy = spring<Float>(dampingRatio = 0.84f, stiffness = 540f)
    val SpringSmooth = spring<Float>(dampingRatio = 0.78f, stiffness = 360f)
    val SpringBouncy = spring<Float>(dampingRatio = 0.66f, stiffness = 420f)
    val SpringDpSnappy = spring<Dp>(dampingRatio = 0.82f, stiffness = 480f)
    val SpringOffsetPage = spring<IntOffset>(dampingRatio = 0.88f, stiffness = 420f)
    val SpringSizeSmooth = spring<androidx.compose.ui.unit.IntSize>(dampingRatio = 0.82f, stiffness = 380f)
}

/**
 * Tactile X-axis linear motor feedback tailored for flagship Android devices (e.g. Xiaomi 13 Ultra).
 */
object ZaiHaptics {
    fun tick(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    fun tap(view: View) {
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            HapticFeedbackConstants.TEXT_HANDLE_MOVE
        } else {
            HapticFeedbackConstants.KEYBOARD_TAP
        }
        view.performHapticFeedback(code)
    }

    fun confirm(view: View) {
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.CONTEXT_CLICK
        }
        view.performHapticFeedback(code)
    }

    fun reject(view: View) {
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.REJECT
        } else {
            HapticFeedbackConstants.LONG_PRESS
        }
        view.performHapticFeedback(code)
    }
}

/**
 * Tactile spring press modifier replacing harsh gray Android ripples with fluid physical scale feedback.
 */
fun Modifier.applePress(
    enabled: Boolean = true,
    scaleDown: Float = 0.965f,
    hapticTick: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    val view = LocalView.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) scaleDown else 1f,
        animationSpec = ZaiMotion.SpringSnappy,
        label = "applePressScale",
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClick = {
                if (hapticTick) ZaiHaptics.tap(view)
                onClick()
            },
        )
}

/**
 * Signature acoustic voice waveform reacting in real time to live microphone [rmsDb].
 * Renders smooth 5-bar optical-blue/indigo/cyan fluid resonance when listening.
 */
@Composable
fun LiveVoiceWaveform(
    rmsDb: Float,
    listening: Boolean,
    modifier: Modifier = Modifier,
) {
    // Normalize Android SpeechRecognizer rmsDb (typically -2..12 dB) into 0.15..1.0 energy
    val normalizedEnergy = remember(rmsDb, listening) {
        if (!listening) 0.15f else ((rmsDb + 2f) / 12f).coerceIn(0.18f, 1f)
    }
    val smoothEnergy by animateFloatAsState(
        targetValue = normalizedEnergy,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 320f),
        label = "voiceEnergy",
    )
    val transition = rememberInfiniteTransition(label = "voiceWave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200),
            repeatMode = RepeatMode.Restart,
        ),
        label = "voicePhase",
    )

    Canvas(modifier = modifier) {
        val barCount = 5
        val gap = size.width * 0.08f
        val barWidth = (size.width - gap * (barCount - 1)) / barCount
        val centerY = size.height / 2f
        val weights = floatArrayOf(0.55f, 0.85f, 1.0f, 0.85f, 0.55f)
        val brush = Brush.verticalGradient(listOf(HaloCyan, HaloBlue, HaloIndigo))

        for (i in 0 until barCount) {
            val wave = 0.65f + 0.35f * sin(phase + i * 1.15f)
            val h = (size.height * (0.24f + 0.72f * smoothEnergy * weights[i] * wave))
                .coerceIn(barWidth, size.height)
            val x = i * (barWidth + gap)
            drawRoundRect(
                brush = brush,
                topLeft = Offset(x, centerY - h / 2f),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

/**
 * Soft luminous breathing halo used behind the Zai emblem on empty canvas & active voice listening.
 */
@Composable
fun OpticalAuraRing(
    energy: Float,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val smoothEnergy by animateFloatAsState(
        targetValue = if (active) energy.coerceIn(0.25f, 1f) else 0.15f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 240f),
        label = "auraEnergy",
    )
    val transition = rememberInfiniteTransition(label = "auraBreath")
    val breath by transition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (active) 1100 else 2600),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "auraScale",
    )

    Canvas(modifier = modifier) {
        val radius = (size.minDimension / 2f) * breath * (0.85f + 0.15f * smoothEnergy)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    HaloBlue.copy(alpha = if (active) 0.28f else 0.12f),
                    HaloIndigo.copy(alpha = if (active) 0.14f else 0.05f),
                    Color.Transparent,
                ),
                center = center,
                radius = radius,
            ),
            radius = radius,
            center = center,
        )
        drawCircle(
            brush = Brush.sweepGradient(listOf(HaloBlue, HaloIndigo, HaloCyan, HaloBlue)),
            radius = radius * 0.72f,
            center = center,
            alpha = if (active) 0.55f else 0.22f,
            style = Stroke(width = if (active) 2.2.dp.toPx() else 1.2.dp.toPx()),
        )
    }
}
