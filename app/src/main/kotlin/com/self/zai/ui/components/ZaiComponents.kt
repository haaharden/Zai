package com.self.zai.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Apple inset-grouped surface container with soft ambient elevation in light mode
 * and crisp optical hairline edge in dark / OLED modes.
 */
@Composable
fun ZaiPaperGroup(
    title: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.4f
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (title != null || trailing != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (title != null) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                trailing?.invoke()
            }
        }
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = if (isDark) 0.dp else 1.5.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                content()
            }
        }
    }
}

/**
 * Apple-style segmented control with a true spring-sliding elevated pill thumb and X-axis tick haptics.
 */
@Composable
fun <T> ZaiSegmentedControl(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (options.isEmpty()) return
    val view = LocalView.current
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.4f
    val selectedIndex = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(3.dp),
    ) {
        val segmentWidth = maxWidth / options.size
        val thumbOffset by animateDpAsState(
            targetValue = segmentWidth * selectedIndex,
            animationSpec = ZaiMotion.SpringDpSnappy,
            label = "segThumbOffset",
        )

        // Sliding spring thumb
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .width(segmentWidth)
                .fillMaxHeight()
                .then(
                    if (!isDark) {
                        Modifier.shadow(2.dp, RoundedCornerShape(9.5.dp), clip = false)
                    } else {
                        Modifier
                    },
                )
                .clip(RoundedCornerShape(9.5.dp))
                .background(if (isDark) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface)
                .border(
                    width = 0.5.dp,
                    color = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.04f),
                    shape = RoundedCornerShape(9.5.dp),
                ),
        )

        Row(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEach { (value, label) ->
                val isSelected = value == selected
                val textColor by animateColorAsState(
                    targetValue = if (isSelected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    animationSpec = tween(160),
                    label = "segTextColor",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(9.5.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            if (value != selected) {
                                ZaiHaptics.tick(view)
                                onSelect(value)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * Crisp tactile pill with spring press physics, animated fill, and mechanical tick haptic.
 */
@Composable
fun ZaiSelectPill(
    label: String,
    selected: Boolean,
    monospace: Boolean = false,
    onClick: () -> Unit,
) {
    val view = LocalView.current
    val bg by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        animationSpec = tween(180),
        label = "pillBg",
    )
    val fg by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        animationSpec = tween(180),
        label = "pillFg",
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) Color.Transparent else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = tween(180),
        label = "pillBorder",
    )

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .border(0.5.dp, borderColor, RoundedCornerShape(20.dp))
            .applePress(hapticTick = false) {
                ZaiHaptics.tick(view)
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.SansSerif,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * High-contrast Apple-style Switch palette tuned for Light, Dark, and Pure AMOLED OLED displays.
 */
@Composable
fun zaiSwitchColors(): SwitchColors {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.4f
    return SwitchDefaults.colors(
        checkedThumbColor = Color.White,
        checkedTrackColor = MaterialTheme.colorScheme.secondary,
        checkedBorderColor = Color.Transparent,
        uncheckedThumbColor = Color.White,
        uncheckedTrackColor = if (isDark) Color(0xFF39393D) else Color(0xFFD1D1D6),
        uncheckedBorderColor = Color.Transparent,
    )
}

/**
 * Soft tonal text input with smooth focus border transition.
 */
@Composable
fun ZaiTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String? = null,
    placeholder: String = "",
    monospace: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor by animateColorAsState(
        targetValue = if (focused) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
        } else {
            MaterialTheme.colorScheme.outlineVariant
        },
        animationSpec = tween(160),
        label = "tfBorder",
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(13.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .border(
                    width = if (focused) 1.2.dp else 0.5.dp,
                    color = borderColor,
                    shape = RoundedCornerShape(13.dp),
                )
                .padding(start = 14.dp, end = if (trailing != null) 4.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 12.dp),
            ) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.SansSerif,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    visualTransformation = visualTransformation,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = if (monospace) FontFamily.Monospace else FontFamily.SansSerif,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focused = it.isFocused },
                )
            }
            trailing?.invoke()
        }
    }
}

/**
 * Refined multiline tonal text area for custom system prompts and Skill Markdown bodies.
 */
@Composable
fun ZaiMultilineField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String? = null,
    placeholder: String = "",
    monospace: Boolean = false,
    minLines: Int = 4,
    maxLines: Int = 10,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor by animateColorAsState(
        targetValue = if (focused) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
        } else {
            MaterialTheme.colorScheme.outlineVariant
        },
        animationSpec = tween(160),
        label = "mlBorder",
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .border(
                    width = if (focused) 1.2.dp else 0.5.dp,
                    color = borderColor,
                    shape = RoundedCornerShape(14.dp),
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = if (monospace) FontFamily.Monospace else FontFamily.SansSerif,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                minLines = minLines,
                maxLines = maxLines,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = if (monospace) FontFamily.Monospace else FontFamily.SansSerif,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused },
            )
        }
    }
}

/**
 * Primary tactile Apple-grade pill button with spring press scale and linear-motor confirmation.
 */
@Composable
fun ZaiPrimaryButton(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val bg by animateColorAsState(
        targetValue = if (enabled) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        animationSpec = tween(160),
        label = "primaryBtnBg",
    )
    val fg = if (enabled) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .applePress(enabled = enabled, scaleDown = 0.965f, hapticTick = false) {
                ZaiHaptics.confirm(view)
                onClick()
            }
            .padding(horizontal = 18.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun ZaiSecondaryButton(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .applePress(enabled = enabled, scaleDown = 0.965f, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Signature minimalist stacked-cards + four-point sparkle mark tuned to Zai's optical palette.
 */
@Composable
fun ZaiMonogramMark(
    size: Dp = 20.dp,
    tint: Color = MaterialTheme.colorScheme.onBackground,
    dotColor: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
) {
    val sparkColor = MaterialTheme.colorScheme.background
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val cardW = w * 0.62f
        val cardH = h * 0.62f
        val radius = CornerRadius(w * 0.14f, w * 0.14f)

        rotate(degrees = -13f, pivot = Offset(w * 0.43f, h * 0.56f)) {
            drawRoundRect(
                color = dotColor,
                topLeft = Offset(w * 0.13f, h * 0.25f),
                size = Size(cardW, cardH),
                cornerRadius = radius,
            )
        }

        rotate(degrees = 11f, pivot = Offset(w * 0.57f, h * 0.48f)) {
            drawRoundRect(
                color = tint,
                topLeft = Offset(w * 0.26f, h * 0.16f),
                size = Size(cardW, cardH),
                cornerRadius = radius,
            )
            val cx = w * 0.57f
            val cy = h * 0.47f
            val rOuter = w * 0.175f
            val rCtrl = w * 0.032f
            val spark = Path().apply {
                moveTo(cx, cy - rOuter)
                quadraticTo(cx + rCtrl, cy - rCtrl, cx + rOuter, cy)
                quadraticTo(cx + rCtrl, cy + rCtrl, cx, cy + rOuter)
                quadraticTo(cx - rCtrl, cy + rCtrl, cx - rOuter, cy)
                quadraticTo(cx - rCtrl, cy - rCtrl, cx, cy - rOuter)
                close()
            }
            drawPath(path = spark, color = sparkColor)
        }
    }
}

/**
 * Frosted titanium/porcelain emblem tile for the empty chat canvas.
 */
@Composable
fun ZaiEmblemMark(
    size: Dp = 50.dp,
    modifier: Modifier = Modifier,
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.4f
    val tileBg = if (isDark) Color(0xFF1A1B1F) else Color(0xFFFFFFFF)
    val tileBorder = if (isDark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.08f)
    val frontCard = if (isDark) Color(0xFFF5F5F7) else Color(0xFF1C1C1E)
    val backCard = MaterialTheme.colorScheme.primary
    val sparkFill = if (isDark) Color(0xFF1A1B1F) else Color(0xFFFFFFFF)

    Box(
        modifier = modifier
            .size(size)
            .then(if (!isDark) Modifier.shadow(6.dp, RoundedCornerShape(size * 0.28f), clip = false) else Modifier)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(tileBg)
            .border(0.6.dp, tileBorder, RoundedCornerShape(size * 0.28f)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size * 0.66f)) {
            val w = this.size.width
            val h = this.size.height
            val cardW = w * 0.62f
            val cardH = h * 0.62f
            val radius = CornerRadius(w * 0.14f, w * 0.14f)

            rotate(degrees = -13f, pivot = Offset(w * 0.42f, h * 0.56f)) {
                drawRoundRect(
                    color = backCard,
                    topLeft = Offset(w * 0.12f, h * 0.25f),
                    size = Size(cardW, cardH),
                    cornerRadius = radius,
                )
            }
            rotate(degrees = 11f, pivot = Offset(w * 0.58f, h * 0.47f)) {
                drawRoundRect(
                    color = frontCard,
                    topLeft = Offset(w * 0.27f, h * 0.16f),
                    size = Size(cardW, cardH),
                    cornerRadius = radius,
                )
                val cx = w * 0.58f
                val cy = h * 0.47f
                val rOuter = w * 0.175f
                val rCtrl = w * 0.032f
                val spark = Path().apply {
                    moveTo(cx, cy - rOuter)
                    quadraticTo(cx + rCtrl, cy - rCtrl, cx + rOuter, cy)
                    quadraticTo(cx + rCtrl, cy + rCtrl, cx, cy + rOuter)
                    quadraticTo(cx - rCtrl, cy + rCtrl, cx - rOuter, cy)
                    quadraticTo(cx - rCtrl, cy - rCtrl, cx, cy - rOuter)
                    close()
                }
                drawPath(path = spark, color = sparkFill)
            }
        }
    }
}

@Composable
fun ZaiInsetDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}
