package com.self.zai.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.self.zai.model.ChatSession
import com.self.zai.model.ThinkingLevelDisplay
import com.self.zai.model.VoiceListenState
import com.self.zai.ui.components.ZaiInsetDivider

@Composable
internal fun SlashSkillPopup(
    inputText: String,
    state: ChatUiState,
    onSelectSkillPrefix: (String) -> Unit,
) {
    val slashMatches = remember(inputText, state.enabledSkills) {
        if (!inputText.startsWith("/") || inputText.contains(" ") || state.enabledSkills.isEmpty()) {
            emptyList()
        } else {
            val query = inputText.removePrefix("/").trim()
            state.enabledSkills.filter {
                query.isEmpty() ||
                    it.name.contains(query, ignoreCase = true) ||
                    it.description.contains(query, ignoreCase = true)
            }.take(5)
        }
    }
    AnimatedVisibility(visible = slashMatches.isNotEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
        ) {
            slashMatches.forEachIndexed { idx, skill ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectSkillPrefix("/${skill.name} ") }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "/${skill.name}",
                        style = MaterialTheme.typography.labelLarge,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = skill.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (idx < slashMatches.lastIndex) {
                    ZaiInsetDivider()
                }
            }
        }
    }
}

@Composable
internal fun ComposerControlStrip(
    state: ChatUiState,
    hasControlChannel: Boolean,
    onOpenModelPicker: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenThinkingPicker: () -> Unit,
    onOpenContextSheet: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ComposerControlPill(
            label = state.sessionModel?.name ?: "选择模型",
            monospace = false,
            leadingDotColor = if (hasControlChannel) {
                MaterialTheme.colorScheme.secondary
            } else {
                MaterialTheme.colorScheme.tertiary
            },
            onClick = {
                val hasAny = state.providers.any { it.isConnected && it.models.isNotEmpty() }
                if (hasAny) onOpenModelPicker() else onOpenModels()
            },
            modifier = Modifier.weight(1f, fill = false),
        )

        if (state.sessionModel?.supportsThinkingSelection == true) {
            ComposerControlPill(
                label = "思考 ${ThinkingLevelDisplay.label(state.sessionThinkingLevel)}",
                monospace = false,
                accent = state.sessionThinkingLevel != "off",
                onClick = onOpenThinkingPicker,
            )
        }

        val contextUsage = state.contextUsage
        if (state.session.messages.isNotEmpty() && contextUsage != null) {
            val nearLimit = contextUsage.percent >= (contextUsage.thresholdPercent - 10).coerceAtLeast(30)
            val ctxLabel = if (contextUsage.archivedBlocks > 0) {
                "${contextUsage.percent}%·${contextUsage.archivedBlocks}块"
            } else {
                "${contextUsage.percent}%"
            }
            Surface(
                onClick = onOpenContextSheet,
                shape = RoundedCornerShape(16.dp),
                color = if (nearLimit) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                } else {
                    MaterialTheme.colorScheme.surface
                },
                border = BorderStroke(
                    0.6.dp,
                    if (nearLimit) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                    else MaterialTheme.colorScheme.outlineVariant,
                ),
            ) {
                Text(
                    text = ctxLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = if (nearLimit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
internal fun ComposerDock(
    state: ChatUiState,
    actions: ChatActions,
    isCurrentSessionRunning: Boolean,
    backgroundRunningSession: ChatSession?,
    onOpenAttachmentSheet: () -> Unit,
    onOpenLightbox: (ImageLightboxData) -> Unit,
    onSentSuccess: () -> Unit,
) {
    val isVoiceListening = state.voiceListenState == VoiceListenState.LISTENING
    val hasAttachments = state.pendingAttachments.isNotEmpty()
    val hasImageAttachments = state.pendingAttachments.any { it.promptImages.isNotEmpty() }
    val modelLacksVision = state.sessionModel != null && !state.sessionModel.supportsImages && hasImageAttachments
    val hasPayloadToSend = state.inputText.isNotBlank() || hasAttachments

    Surface(
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = if (isVoiceListening) 1.dp else 0.5.dp,
            color = if (isVoiceListening) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            AnimatedVisibility(visible = hasAttachments || state.importingAttachments) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        items(state.pendingAttachments, key = { it.attachment.id }) { item ->
                            PendingAttachmentCard(
                                item = item,
                                onPreview = {
                                    val att = item.attachment
                                    val paths = when {
                                        att.pageImagePaths.isNotEmpty() -> att.pageImagePaths
                                        !att.localPath.isNullOrBlank() -> listOf(att.localPath)
                                        else -> emptyList()
                                    }
                                    if (paths.isNotEmpty()) {
                                        onOpenLightbox(ImageLightboxData(paths = paths, title = att.displayName))
                                    }
                                },
                                onRemove = { actions.onRemoveAttachment(item.attachment.id) },
                            )
                        }
                        if (state.importingAttachments) {
                            item(key = "importing_indicator") {
                                Row(
                                    modifier = Modifier
                                        .height(60.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                        .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                                        .padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(15.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = "读取中…",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    if (modelLacksVision) {
                        Text(
                            text = "当前模型不支持看图，请先点上方切换支持视觉的模型",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 4.dp),
                        )
                    }
                }
            }

            var inputLineCount by remember { mutableIntStateOf(1) }
            if (state.inputText.isEmpty() && inputLineCount != 1) {
                inputLineCount = 1
            }
            val composerTextStyle = MaterialTheme.typography.bodyLarge.copy(
                lineHeight = 22.sp,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.None,
                ),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = if (inputLineCount <= 1) Alignment.CenterVertically else Alignment.Bottom,
            ) {
                Surface(
                    onClick = onOpenAttachmentSheet,
                    shape = CircleShape,
                    color = if (hasAttachments) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
                    contentColor = if (hasAttachments) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(38.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.Add,
                            contentDescription = "添加图片或文件",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                Spacer(Modifier.width(10.dp))

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 38.dp)
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (state.inputText.isEmpty()) {
                        Text(
                            text = when {
                                isVoiceListening -> "聆听中…"
                                hasAttachments -> "添加说明，或直接发送…"
                                isCurrentSessionRunning -> "输入可随时补充…"
                                backgroundRunningSession != null -> "后台有任务运行中…"
                                else -> "问 Zai…"
                            },
                            style = composerTextStyle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (isVoiceListening) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                            },
                        )
                    }
                    BasicTextField(
                        value = state.inputText,
                        onValueChange = { next ->
                            if (next.isEmpty()) inputLineCount = 1
                            if (isVoiceListening) actions.onCancelVoiceInput()
                            actions.onInputTextChange(next)
                        },
                        onTextLayout = { layout ->
                            inputLineCount = layout.lineCount.coerceAtLeast(1)
                        },
                        textStyle = composerTextStyle.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        maxLines = 5,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { focus ->
                                if (focus.isFocused && isVoiceListening) {
                                    actions.onCancelVoiceInput()
                                }
                            },
                    )
                }

                Spacer(Modifier.width(8.dp))

                if (state.isRunning) {
                    Surface(
                        onClick = actions.onStopRun,
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                        modifier = Modifier.size(38.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Rounded.Stop,
                                contentDescription = "立即停止",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }

                if (!state.isRunning && (!hasPayloadToSend || isVoiceListening)) {
                    ChatMicButton(
                        listening = isVoiceListening,
                        onClick = actions.onToggleVoiceInput,
                    )
                }

                if (hasPayloadToSend) {
                    if (state.isRunning || isVoiceListening) Spacer(Modifier.width(6.dp))
                    val canSend = state.piReady && !state.importingAttachments
                    Surface(
                        onClick = {
                            if (canSend) {
                                if (isVoiceListening) actions.onCancelVoiceInput()
                                val sent = actions.onSendMessage(state.inputText, state.pendingAttachments)
                                if (sent) {
                                    actions.onInputTextChange("")
                                    onSentSuccess()
                                }
                            }
                        },
                        shape = CircleShape,
                        color = if (canSend) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        },
                        contentColor = if (canSend) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                        },
                        modifier = Modifier.size(38.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Rounded.ArrowUpward,
                                contentDescription = "发送",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
