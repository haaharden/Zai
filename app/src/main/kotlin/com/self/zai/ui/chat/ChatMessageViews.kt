package com.self.zai.ui.chat

import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.self.zai.data.AttachmentManager
import com.self.zai.model.AttachmentKind
import com.self.zai.model.ChatMessage
import com.self.zai.model.ChatSession
import com.self.zai.model.MessageRole
import com.self.zai.model.StepState
import com.self.zai.ui.components.ZaiCopyIconButton
import com.self.zai.ui.components.ZaiEmblemMark
import com.self.zai.ui.components.ZaiMarkdownText

@Composable
internal fun EditorialEmptyCanvas(
    piReady: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ZaiEmblemMark(size = 46.dp)
        Spacer(Modifier.height(22.dp))
        Text(
            text = "有什么可以帮你？",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (!piReady) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "启动中…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun UserBubble(
    message: ChatMessage,
    onOpenLightbox: (ImageLightboxData) -> Unit,
) {
    val bubbleBg = MaterialTheme.colorScheme.surfaceVariant
    val bubbleFg = MaterialTheme.colorScheme.onSurface
    val imageAttachments = remember(message.attachments) {
        message.attachments.filter { it.kind == AttachmentKind.IMAGE }
    }
    val fileAttachments = remember(message.attachments) {
        message.attachments.filter { it.kind == AttachmentKind.FILE }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (imageAttachments.isNotEmpty()) {
            val allPaths = imageAttachments.mapNotNull { it.localPath }
            if (imageAttachments.size == 1) {
                val img = imageAttachments.first()
                LocalImageThumbnail(
                    path = img.localPath,
                    contentDescription = img.displayName,
                    maxSidePx = 560,
                    modifier = Modifier
                        .size(width = 186.dp, height = 148.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                        .clickable(enabled = !img.localPath.isNullOrBlank()) {
                            img.localPath?.let { path ->
                                onOpenLightbox(ImageLightboxData(paths = listOf(path), title = img.displayName))
                            }
                        },
                )
            } else {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.widthIn(max = 290.dp),
                ) {
                    itemsIndexed(imageAttachments, key = { _, att -> att.id }) { idx, img ->
                        LocalImageThumbnail(
                            path = img.localPath,
                            contentDescription = img.displayName,
                            maxSidePx = 360,
                            modifier = Modifier
                                .size(104.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                                .clickable(enabled = allPaths.isNotEmpty()) {
                                    onOpenLightbox(
                                        ImageLightboxData(
                                            paths = allPaths,
                                            title = img.displayName,
                                            initialIndex = idx.coerceAtMost(allPaths.lastIndex),
                                        ),
                                    )
                                },
                        )
                    }
                }
            }
        }

        if (fileAttachments.isNotEmpty()) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                fileAttachments.forEach { fileAtt ->
                    val previewPaths = when {
                        fileAtt.pageImagePaths.isNotEmpty() -> fileAtt.pageImagePaths
                        !fileAtt.localPath.isNullOrBlank() -> listOf(fileAtt.localPath)
                        else -> emptyList()
                    }
                    Surface(
                        onClick = {
                            if (previewPaths.isNotEmpty()) {
                                onOpenLightbox(ImageLightboxData(paths = previewPaths, title = fileAtt.displayName))
                            }
                        },
                        enabled = previewPaths.isNotEmpty(),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.widthIn(max = 270.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                            if (!fileAtt.localPath.isNullOrBlank()) {
                                LocalImageThumbnail(
                                    path = fileAtt.localPath,
                                    contentDescription = fileAtt.displayName,
                                    maxSidePx = 160,
                                    modifier = Modifier
                                        .size(width = 30.dp, height = 38.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)),
                                )
                            } else {
                                Icon(
                                    imageVector = if (fileAtt.mimeType == "application/pdf") {
                                        Icons.Rounded.PictureAsPdf
                                    } else {
                                        Icons.Rounded.Description
                                    },
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text(
                                    text = fileAtt.displayName,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = fileAtt.metaLabel.ifBlank { AttachmentManager.formatFileSize(fileAtt.sizeBytes) },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (message.text.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(
                    topStart = 20.dp,
                    topEnd = 20.dp,
                    bottomStart = 20.dp,
                    bottomEnd = 6.dp,
                ),
                color = bubbleBg,
                contentColor = bubbleFg,
                modifier = Modifier.widthIn(max = 290.dp),
            ) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                )
            }
        }
    }
}

@Composable
internal fun AssistantTurn(
    message: ChatMessage,
    isActiveTurn: Boolean,
    liveStepLabel: String,
) {
    var thinkingExpanded by remember(message.id) { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (message.thinking.isNotBlank()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { thinkingExpanded = !thinkingExpanded }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "思考过程",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        if (thinkingExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
                if (thinkingExpanded) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = message.thinking,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Minimal phone-operation indicator: never lists per-step details
        val hasRunningPhoneStep = message.steps.any { it.state == StepState.RUNNING }
        if (message.steps.isNotEmpty()) {
            val operatingNow = hasRunningPhoneStep || (isActiveTurn && message.text.isBlank())
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(
                        0.5.dp,
                        if (operatingNow) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                        else MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(14.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (operatingNow) {
                    PulsingDot(color = MaterialTheme.colorScheme.primary)
                } else {
                    Box(
                        modifier = Modifier
                            .size(6.5.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondary),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (operatingNow) "正在操作手机" else "已操作手机",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (operatingNow) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        if (message.text.isNotBlank()) {
            ZaiMarkdownText(
                markdown = message.text,
                textColor = if (message.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground,
                baseTextStyle = MaterialTheme.typography.bodyLarge.copy(lineHeight = 25.sp),
            )
            if (isActiveTurn && !hasRunningPhoneStep && liveStepLabel.isNotBlank() && liveStepLabel != "思考中…" && liveStepLabel != "正在操作手机") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PulsingDot(color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = liveStepLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (!isActiveTurn) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start,
                ) {
                    ZaiCopyIconButton(textToCopy = message.text)
                }
            }
        } else if (isActiveTurn && message.steps.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulsingDot(color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = liveStepLabel.ifBlank { "思考中…" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun PulsingDot(color: Color) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )
    Box(
        modifier = Modifier
            .size(6.5.dp)
            .alpha(alpha)
            .clip(CircleShape)
            .background(color),
    )
}

internal fun shareSessionAsMarkdown(context: Context, session: ChatSession) {
    val md = buildString {
        append("# ").append(session.title).append("\n\n")
        for (msg in session.messages) {
            val heading = when (msg.role) {
                MessageRole.USER -> "## 用户"
                MessageRole.ASSISTANT -> "## Zai"
                MessageRole.SYSTEM_NOTE -> "## 系统记录"
            }
            append(heading).append("\n\n")
            if (msg.attachments.isNotEmpty()) {
                for (att in msg.attachments) {
                    val label = if (att.kind == AttachmentKind.IMAGE) "图片" else "文件"
                    val meta = att.metaLabel.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                    append("[").append(label).append("：").append(att.displayName).append(meta).append("]\n")
                }
                append("\n")
            }
            if (msg.text.isNotBlank()) {
                append(msg.text.trim()).append("\n\n")
            }
        }
    }.trim()
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, session.title)
        putExtra(Intent.EXTRA_TEXT, md)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "导出对话"))
    }
}
