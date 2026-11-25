package com.self.zai.assistant

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.self.zai.model.ChatMessage
import com.self.zai.model.MessageRole
import com.self.zai.model.ModelOption
import com.self.zai.model.PendingConfirmation
import com.self.zai.ui.components.ZaiMonogramMark

/**
 * Ultra-clean Inkstone & Glacier Silk floating personal assistant bottom card.
 * Appears smoothly over any foreground app without leaving it; tap scrim to dismiss.
 */
@Composable
fun AssistantOverlayScreen(
    visible: Boolean,
    model: ModelOption?,
    piReady: Boolean,
    isRunning: Boolean,
    liveStepLabel: String,
    listenState: VoiceListenState,
    isSpeaking: Boolean,
    inputText: String,
    onInputChange: (String) -> Unit,
    attachScreen: Boolean,
    capturedScreen: CapturedScreenContext?,
    onToggleAttachScreen: () -> Unit,
    overlayMessages: List<ChatMessage>,
    pendingConfirmation: PendingConfirmation?,
    onSend: () -> Unit,
    onStopRun: () -> Unit,
    onToggleMic: () -> Unit,
    onToggleSpeakLatest: () -> Unit,
    onNewOverlayChat: () -> Unit,
    onExpandFullApp: () -> Unit,
    onResolveConfirmation: (callId: String, allow: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val lastAssistant = overlayMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
    val lastUser = overlayMessages.lastOrNull { it.role == MessageRole.USER }

    LaunchedEffect(overlayMessages.size, lastAssistant?.text?.length, isRunning) {
        if (overlayMessages.isNotEmpty()) {
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Subtle dimmed scrim — tapping outside immediately dismisses back to foreground app
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(140)),
            exit = fadeOut(tween(120)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.36f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }

        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(tween(180)) { it / 2 } + fadeIn(tween(160)),
            exit = slideOutVertically(tween(140)) { it / 2 } + fadeOut(tween(120)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                tonalElevation = 0.dp,
                shadowElevation = 14.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Header row: Sculpted Z + model pill + "带屏幕" + speaker + new + expand
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ZaiMonogramMark(size = 18.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = model?.name ?: if (piReady) "未配置模型" else "准备中…",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )

                        // Attach screen context toggle pill
                        val screenLabel = if (attachScreen && capturedScreen != null) {
                            "带屏幕·${capturedScreen.appLabel.take(4)}"
                        } else {
                            "带屏幕"
                        }
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    if (attachScreen) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerLow
                                    },
                                )
                                .border(
                                    width = 0.5.dp,
                                    color = if (attachScreen) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    },
                                    shape = RoundedCornerShape(16.dp),
                                )
                                .clickable(onClick = onToggleAttachScreen)
                                .padding(horizontal = 9.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                Icons.Rounded.Smartphone,
                                contentDescription = "附带当前屏幕",
                                tint = if (attachScreen) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(13.dp),
                            )
                            Text(
                                text = screenLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (attachScreen) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                            )
                        }

                        if (!lastAssistant?.text.isNullOrBlank()) {
                            Spacer(Modifier.width(6.dp))
                            OverlayIconPill(
                                onClick = onToggleSpeakLatest,
                                active = isSpeaking,
                            ) {
                                Icon(
                                    imageVector = if (isSpeaking) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
                                    contentDescription = if (isSpeaking) "停止朗读" else "朗读回复",
                                    tint = if (isSpeaking) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }

                        if (overlayMessages.isNotEmpty()) {
                            Spacer(Modifier.width(6.dp))
                            OverlayIconPill(onClick = onNewOverlayChat) {
                                Icon(
                                    Icons.Rounded.Add,
                                    contentDescription = "新对话",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }

                        Spacer(Modifier.width(6.dp))
                        OverlayIconPill(onClick = onExpandFullApp) {
                            Icon(
                                Icons.Rounded.OpenInFull,
                                contentDescription = "在 Zai 中打开",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }

                    // Compact scrollable turn preview (only shown once the user sends something)
                    if (overlayMessages.isNotEmpty() || isRunning) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp)
                                .verticalScroll(scrollState),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (lastUser != null) {
                                Text(
                                    text = lastUser.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }

                            if (isRunning && (lastAssistant == null || lastAssistant.text.isBlank())) {
                                OverlayLiveStatusRow(label = liveStepLabel.ifBlank { "思考中…" })
                            } else if (lastAssistant != null) {
                                if (isRunning && liveStepLabel.isNotBlank() && liveStepLabel != "思考中…") {
                                    OverlayLiveStatusRow(label = liveStepLabel)
                                }
                                if (lastAssistant.text.isNotBlank()) {
                                    Text(
                                        text = lastAssistant.text,
                                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 23.sp),
                                        color = if (lastAssistant.isError) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                }
                            }
                        }
                    }

                    // Inline sensitive confirmation banner if triggered during overlay run
                    if (pendingConfirmation != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = pendingConfirmation.title,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = pendingConfirmation.detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                text = "拒绝",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onResolveConfirmation(pendingConfirmation.callId, false) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                            Text(
                                text = "允许",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer)
                                    .clickable { onResolveConfirmation(pendingConfirmation.callId, true) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }

                    // Bottom composer row: tonal input + animated Mic + Send/Stop
                    val isListening = listenState == VoiceListenState.LISTENING
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .border(
                                width = if (isListening) 1.dp else 0.5.dp,
                                color = if (isListening) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                },
                                shape = RoundedCornerShape(20.dp),
                            )
                            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 6.dp),
                        ) {
                            if (inputText.isEmpty()) {
                                Text(
                                    text = if (isListening) "聆听中…" else "问 Zai…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isListening) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            BasicTextField(
                                value = inputText,
                                onValueChange = onInputChange,
                                maxLines = 4,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        Spacer(Modifier.width(6.dp))

                        // Mic button with calm pulse while listening
                        MicPulseButton(
                            listening = isListening,
                            onClick = onToggleMic,
                        )

                        Spacer(Modifier.width(6.dp))

                        // Send or Stop circle button
                        val canSend = inputText.isNotBlank() && piReady
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        isRunning -> MaterialTheme.colorScheme.error
                                        canSend -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.surfaceVariant
                                    },
                                )
                                .clickable(enabled = isRunning || canSend) {
                                    if (isRunning) onStopRun() else onSend()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = if (isRunning) Icons.Rounded.Stop else Icons.Rounded.ArrowUpward,
                                contentDescription = if (isRunning) "停止" else "发送",
                                tint = when {
                                    isRunning -> MaterialTheme.colorScheme.onError
                                    canSend -> MaterialTheme.colorScheme.onPrimary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                },
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OverlayIconPill(
    onClick: () -> Unit,
    active: Boolean = false,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(
                if (active) MaterialTheme.colorScheme.primaryContainer
                else Color.Transparent,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun MicPulseButton(
    listening: Boolean,
    onClick: () -> Unit,
) {
    val pulse = if (listening) {
        val transition = rememberInfiniteTransition(label = "micPulse")
        val alpha by transition.animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
            label = "micAlpha",
        )
        alpha
    } else {
        1f
    }

    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(
                if (listening) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                else Color.Transparent,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (listening) Icons.Rounded.GraphicEq else Icons.Rounded.Mic,
            contentDescription = if (listening) "停止语音" else "语音输入",
            tint = if (listening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(20.dp)
                .alpha(pulse),
        )
    }
}

@Composable
private fun OverlayLiveStatusRow(label: String) {
    val transition = rememberInfiniteTransition(label = "overlayStatus")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(750), RepeatMode.Reverse),
        label = "statusAlpha",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .alpha(alpha)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
