package com.self.zai.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.self.zai.data.AttachmentManager
import com.self.zai.model.AttachmentKind
import com.self.zai.model.PendingAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun AttachmentPopoverCard(
    onPickImages: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickFiles: () -> Unit,
    onPasteClipboardImage: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.4f
    val hasClipImg = remember { AttachmentManager.hasClipboardImage(context) }

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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "添加附件",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "支持图片、PDF、Office 文档与代码 · 单条最多 ${AttachmentManager.MAX_ATTACHMENTS_PER_MESSAGE} 个",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AttachmentSourceTile(
                    icon = Icons.Rounded.PhotoLibrary,
                    title = "相册图片",
                    subtitle = "单张或多张",
                    onClick = {
                        onDismiss()
                        onPickImages()
                    },
                    modifier = Modifier.weight(1f),
                )
                AttachmentSourceTile(
                    icon = Icons.Rounded.PhotoCamera,
                    title = "拍照",
                    subtitle = "即拍即发",
                    onClick = {
                        onDismiss()
                        onTakePhoto()
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AttachmentSourceTile(
                    icon = Icons.Rounded.AttachFile,
                    title = "选择文件",
                    subtitle = "PDF / 文档 / 代码",
                    onClick = {
                        onDismiss()
                        onPickFiles()
                    },
                    modifier = Modifier.weight(1f),
                )
                AttachmentSourceTile(
                    icon = Icons.Rounded.ContentPaste,
                    title = "粘贴图片",
                    subtitle = if (hasClipImg) "剪贴板有图片" else "读取剪贴板截图",
                    accent = hasClipImg,
                    onClick = {
                        onDismiss()
                        onPasteClipboardImage()
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
internal fun AttachmentSourceTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = if (accent) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        border = BorderStroke(
            0.6.dp,
            if (accent) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(
                        if (accent) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        else MaterialTheme.colorScheme.surface,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(19.dp),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun PendingAttachmentCard(
    item: PendingAttachment,
    onPreview: () -> Unit,
    onRemove: () -> Unit,
) {
    val att = item.attachment
    if (att.kind == AttachmentKind.IMAGE) {
        Box(modifier = Modifier.size(62.dp)) {
            LocalImageThumbnail(
                path = att.localPath,
                contentDescription = att.displayName,
                maxSidePx = 280,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(14.dp))
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                    .clickable(onClick = onPreview),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .size(19.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.65f))
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "移除附件",
                    tint = Color.White,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    } else {
        val hasVisualPreview = !att.localPath.isNullOrBlank() || att.pageImagePaths.isNotEmpty()
        Row(
            modifier = Modifier
                .height(62.dp)
                .widthIn(min = 132.dp, max = 220.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                .clickable(enabled = hasVisualPreview, onClick = onPreview)
                .padding(start = 8.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!att.localPath.isNullOrBlank()) {
                LocalImageThumbnail(
                    path = att.localPath,
                    contentDescription = att.displayName,
                    maxSidePx = 200,
                    modifier = Modifier
                        .size(width = 36.dp, height = 46.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (att.mimeType == "application/pdf") {
                            Icons.Rounded.PictureAsPdf
                        } else {
                            Icons.Rounded.Description
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = att.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = att.metaLabel.ifBlank { AttachmentManager.formatFileSize(att.sizeBytes) },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "移除文件",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
internal fun LocalImageThumbnail(
    path: String?,
    contentDescription: String,
    maxSidePx: Int = 360,
    contentScale: ContentScale = ContentScale.Crop,
    modifier: Modifier = Modifier,
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path, maxSidePx) {
        value = if (path.isNullOrBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                AttachmentManager.loadThumbnailBitmap(path, maxSidePx)?.asImageBitmap()
            }
        }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = modifier,
        )
    } else {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerLow),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Image,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
internal fun ImageLightboxDialog(
    data: ImageLightboxData,
    onDismiss: () -> Unit,
) {
    var pageIdx by remember(data) { mutableIntStateOf(data.initialIndex.coerceIn(0, (data.paths.size - 1).coerceAtLeast(0))) }
    val currentPath = data.paths.getOrNull(pageIdx)
    val bitmap by produceState<ImageBitmap?>(initialValue = null, currentPath) {
        value = if (currentPath.isNullOrBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                AttachmentManager.loadPreviewBitmap(currentPath, 1600)?.asImageBitmap()
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                )
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap!!,
                    contentDescription = data.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 64.dp),
                )
            } else {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (data.paths.size > 1) "${data.title} (${pageIdx + 1}/${data.paths.size})" else data.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.14f))
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "关闭预览",
                        tint = Color.White,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }

            if (data.paths.size > 1) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 20.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.White.copy(alpha = 0.14f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        enabled = pageIdx > 0,
                        onClick = { if (pageIdx > 0) pageIdx-- },
                    ) {
                        Text("上一页", color = if (pageIdx > 0) Color.White else Color.White.copy(alpha = 0.35f))
                    }
                    Text(
                        text = "${pageIdx + 1} / ${data.paths.size}",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = Color.White,
                    )
                    TextButton(
                        enabled = pageIdx < data.paths.lastIndex,
                        onClick = { if (pageIdx < data.paths.lastIndex) pageIdx++ },
                    ) {
                        Text("下一页", color = if (pageIdx < data.paths.lastIndex) Color.White else Color.White.copy(alpha = 0.35f))
                    }
                }
            }
        }
    }
}
