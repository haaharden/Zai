package com.self.zai.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.self.zai.model.ChatSession
import com.self.zai.model.CompactionMode
import com.self.zai.model.PendingConfirmation
import com.self.zai.model.SessionContextUsage
import com.self.zai.ui.components.LiveVoiceWaveform
import com.self.zai.ui.components.ZaiHaptics
import com.self.zai.ui.components.ZaiInsetDivider
import com.self.zai.ui.components.ZaiMonogramMark
import com.self.zai.ui.components.ZaiPrimaryButton
import com.self.zai.ui.components.applePress

@Composable
internal fun ZSettingsButton(
    hasNewUpdate: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .applePress(scaleDown = 0.92f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        ZaiMonogramMark(size = 22.dp)
        if (hasNewUpdate) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 5.dp, end = 5.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
internal fun HeaderSquareTrigger(
    onClick: () -> Unit,
    contentDescription: String,
    enabled: Boolean = true,
    icon: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .applePress(enabled = enabled, scaleDown = 0.92f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        icon()
    }
}

@Composable
internal fun ComposerControlPill(
    label: String,
    monospace: Boolean,
    leadingDotColor: Color? = null,
    accent: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (accent) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    val border = if (accent) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    val textColor = if (accent) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    val view = LocalView.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = bg,
        border = BorderStroke(0.6.dp, border),
        modifier = modifier.applePress(
            scaleDown = 0.95f,
            hapticTick = false,
            onClick = {
                ZaiHaptics.tap(view)
                onClick()
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 11.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingDotColor != null) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(leadingDotColor),
                )
                Spacer(Modifier.width(7.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.SansSerif,
                fontWeight = if (accent) FontWeight.SemiBold else FontWeight.Medium,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 185.dp),
            )
            Spacer(Modifier.width(2.dp))
            Icon(
                Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                tint = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

@Composable
internal fun ChatMicButton(
    listening: Boolean,
    rmsDb: Float = 0f,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(
                if (listening) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
            )
            .applePress(scaleDown = 0.9f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (listening) {
            LiveVoiceWaveform(
                rmsDb = rmsDb,
                listening = true,
                modifier = Modifier.size(width = 22.dp, height = 18.dp),
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.Mic,
                contentDescription = "语音输入",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

@Composable
internal fun ContextUsagePopoverCard(
    contextUsage: SessionContextUsage,
    isRunning: Boolean,
    messageCount: Int,
    piReady: Boolean,
    onOpenSettings: () -> Unit,
    onCompactSession: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.4f
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 0.6.dp,
            color = if (isDark) Color.White.copy(alpha = 0.12f) else MaterialTheme.colorScheme.outlineVariant,
        ),
        shadowElevation = if (isDark) 12.dp else 14.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "上下文状态",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "调整阈值",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .applePress(scaleDown = 0.94f) {
                            onDismiss()
                            onOpenSettings()
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val estK = String.format("%.1fK", contextUsage.estimatedTokens / 1000.0)
                val winK = "${contextUsage.contextWindow / 1000}K"
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "约 $estK / $winK tokens",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${contextUsage.percent}%",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction = (contextUsage.percent / 100f).coerceIn(0f, 1f))
                            .height(5.dp)
                            .clip(CircleShape)
                            .background(
                                if (contextUsage.percent >= contextUsage.thresholdPercent) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            ),
                    )
                }
                val modeLine = buildString {
                    append("策略：${contextUsage.mode.label()}")
                    if (contextUsage.mode != CompactionMode.OFF) {
                        append(" · 阈值 ${contextUsage.thresholdPercent}%")
                    }
                    if (contextUsage.archivedBlocks > 0) {
                        append(" · 已归档 ${contextUsage.archivedBlocks} 块")
                    }
                }
                Text(
                    text = modeLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            ZaiPrimaryButton(
                text = if (isRunning) "回复生成中，请稍候" else "立即压缩早期对话",
                enabled = !isRunning && messageCount >= 4 && piReady,
                onClick = {
                    onDismiss()
                    onCompactSession()
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
internal fun ChatSessionsOverlayCard(
    currentSessionId: String,
    allSessions: List<ChatSession>,
    storageBytes: Long,
    isRunning: Boolean,
    runningSessionId: String?,
    onSelectSession: (String) -> Unit,
    onNewSession: () -> Unit,
    onDeleteSession: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.4f
    var visibleLimit by remember { mutableIntStateOf(40) }
    val visibleSessions = remember(allSessions, visibleLimit) { allSessions.take(visibleLimit) }
    val remainingCount = (allSessions.size - visibleSessions.size).coerceAtLeast(0)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 0.6.dp,
            color = if (isDark) Color.White.copy(alpha = 0.10f) else MaterialTheme.colorScheme.outlineVariant,
        ),
        shadowElevation = if (isDark) 0.dp else 16.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "对话记录",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    val sizeText = formatStorageBytes(storageBytes)
                    Text(
                        text = if (sizeText.isNotEmpty()) {
                            "${allSessions.size} 个对话 · 占用 $sizeText"
                        } else {
                            "${allSessions.size} 个对话"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = {
                        ZaiHaptics.tap(view)
                        onDismiss()
                        onNewSession()
                    },
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("新对话", fontWeight = FontWeight.Medium)
                }
            }
            Spacer(Modifier.height(6.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
            ) {
                itemsIndexed(visibleSessions, key = { _, item -> item.id }) { idx, item ->
                    val selected = item.id == currentSessionId
                    val itemRunning = isRunning && item.id == runningSessionId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.surfaceContainerLow
                                else Color.Transparent,
                            )
                            .clickable {
                                ZaiHaptics.tick(view)
                                onSelectSession(item.id)
                                onDismiss()
                            }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (selected || itemRunning) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (itemRunning) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.primary,
                                    ),
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (selected || itemRunning) FontWeight.SemiBold else FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = if (itemRunning) {
                                    "${item.messages.size} 条记录 · 运行中"
                                } else {
                                    "${item.messages.size} 条记录"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (itemRunning) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        if (item.messages.isNotEmpty()) {
                            IconButton(
                                onClick = { shareSessionAsMarkdown(context, item) },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    Icons.Rounded.IosShare,
                                    contentDescription = "导出对话",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(17.dp),
                                )
                            }
                        }
                        if (allSessions.size > 1) {
                            IconButton(
                                onClick = {
                                    ZaiHaptics.reject(view)
                                    onDeleteSession(item.id)
                                },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    Icons.Rounded.DeleteOutline,
                                    contentDescription = "删除对话",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                    if (idx < visibleSessions.lastIndex) {
                        ZaiInsetDivider()
                    }
                }
                if (remainingCount > 0) {
                    item(key = "load_more_sessions") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            TextButton(onClick = { visibleLimit += 40 }) {
                                Text("加载更早对话（还有 $remainingCount 条）")
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
internal fun PendingConfirmationDialog(
    confirmation: PendingConfirmation,
    onResolve: (callId: String, allow: Boolean) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onResolve(confirmation.callId, false) },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                text = confirmation.title,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Text(
                text = confirmation.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            TextButton(onClick = { onResolve(confirmation.callId, true) }) {
                Text("允许一次", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = { onResolve(confirmation.callId, false) }) {
                Text("拒绝", color = MaterialTheme.colorScheme.error)
            }
        },
    )
}

internal fun formatStorageBytes(bytes: Long): String {
    if (bytes <= 0L) return ""
    return when {
        bytes < 1024L -> "${bytes} B"
        bytes < 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(java.util.Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
    }
}
