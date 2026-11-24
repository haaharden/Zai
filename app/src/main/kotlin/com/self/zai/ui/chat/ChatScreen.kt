package com.self.zai.ui.chat

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.self.zai.model.ChatMessage
import com.self.zai.model.ChatSession
import com.self.zai.model.MessageRole
import com.self.zai.model.ModelOption
import com.self.zai.model.PendingConfirmation
import com.self.zai.model.ProviderStatus
import com.self.zai.model.StepState
import com.self.zai.model.ThinkingLevelDisplay
import com.self.zai.shell.ShizukuStatus
import com.self.zai.ui.components.ZaiEmblemMark
import com.self.zai.ui.components.ZaiInsetDivider
import com.self.zai.ui.components.ZaiModelPickerSheet
import com.self.zai.ui.components.ZaiMonogramMark
import com.self.zai.ui.components.ZaiThinkingPickerSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    session: ChatSession,
    allSessions: List<ChatSession>,
    providers: List<ProviderStatus>,
    sessionModel: ModelOption?,
    sessionThinkingLevel: String,
    piReady: Boolean,
    isRunning: Boolean,
    liveStepLabel: String,
    shizukuStatus: ShizukuStatus,
    accessibilityConnected: Boolean,
    hasNewUpdate: Boolean,
    pendingConfirmation: PendingConfirmation?,
    onSendMessage: (String) -> Unit,
    onStopRun: () -> Unit,
    onSelectSession: (String) -> Unit,
    onNewSession: () -> Unit,
    onDeleteSession: (String) -> Unit,
    onSelectSessionModel: (providerId: String, modelId: String) -> Unit,
    onSelectSessionThinking: (String) -> Unit,
    onOpenModels: () -> Unit,
    onOpenSettings: () -> Unit,
    onResolveConfirmation: (callId: String, allow: Boolean) -> Unit,
) {
    var inputText by remember { mutableStateOf("") }
    var showSessionsSheet by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showThinkingPicker by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val hasControlChannel = accessibilityConnected || shizukuStatus.isReady
    val channelLabel = when {
        accessibilityConnected && shizukuStatus.isReady -> "无障碍与 Shizuku 已就绪"
        accessibilityConnected -> "无障碍已就绪"
        shizukuStatus.isReady -> "Shizuku 已就绪"
        else -> "尚未开启无障碍"
    }

    LaunchedEffect(
        session.id,
        session.messages.size,
        session.messages.lastOrNull()?.steps?.size,
        session.messages.lastOrNull()?.text?.length,
    ) {
        if (session.messages.isNotEmpty()) {
            listState.animateScrollToItem(session.messages.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        // Minimal editorial top bar: sculpted Z settings keycap on left, History & '+' on right
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZSettingsButton(
                hasNewUpdate = hasNewUpdate,
                onClick = onOpenSettings,
            )

            Spacer(Modifier.weight(1f))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeaderSquareTrigger(
                    onClick = { showSessionsSheet = true },
                    contentDescription = "会话记录",
                ) {
                    Icon(
                        Icons.Rounded.History,
                        contentDescription = "会话记录",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp),
                    )
                }

                HeaderSquareTrigger(
                    onClick = onNewSession,
                    contentDescription = "新对话",
                ) {
                    Icon(
                        Icons.Rounded.Add,
                        contentDescription = "新对话",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        // Subtle actionable setup strip when needed (accent left bar, zero boxy alert feel)
        if (sessionModel == null || !hasControlChannel) {
            val missingModel = sessionModel == null
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                    .clickable { if (missingModel) onOpenModels() else onOpenSettings() }
                    .padding(start = 12.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(16.dp)
                        .clip(CircleShape)
                        .background(
                            if (missingModel) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.tertiary,
                        ),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (missingModel) {
                        "先登录 ChatGPT 或添加供应商密钥"
                    } else {
                        "开启无障碍服务后即可控制手机"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (missingModel) "去配置" else "去开启",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        // Main conversation canvas
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (session.messages.isEmpty()) {
                EditorialEmptyCanvas(
                    statusLine = if (!piReady) "正在启动 Pi 内核" else channelLabel,
                    onPickPrompt = { starter ->
                        inputText = ""
                        onSendMessage(starter)
                    },
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    items(session.messages, key = { it.id }) { msg ->
                        when (msg.role) {
                            MessageRole.USER -> UserBubble(msg.text)
                            MessageRole.ASSISTANT -> AssistantTurn(
                                message = msg,
                                isActiveTurn = isRunning && msg.id == session.messages.lastOrNull()?.id,
                            )
                            MessageRole.SYSTEM_NOTE -> Text(
                                text = msg.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    item { Spacer(Modifier.height(10.dp)) }
                }
            }
        }

        // Quiet single-status indicator above composer only while running
        AnimatedVisibility(visible = isRunning && liveStepLabel.isNotBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PulsingDot(color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = liveStepLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Model + dynamic thinking effort selector strip right above the chat composer
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ComposerControlPill(
                label = sessionModel?.id ?: "配置模型",
                monospace = sessionModel != null,
                leadingDotColor = if (hasControlChannel) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.tertiary
                },
                onClick = {
                    val hasAny = providers.any { it.isConnected && it.models.isNotEmpty() }
                    if (hasAny) showModelPicker = true else onOpenModels()
                },
                modifier = Modifier.weight(1f, fill = false),
            )

            if (sessionModel?.supportsThinkingSelection == true) {
                ComposerControlPill(
                    label = "思考 ${ThinkingLevelDisplay.label(sessionThinkingLevel)}",
                    monospace = false,
                    accent = sessionThinkingLevel != "off",
                    onClick = { showThinkingPicker = true },
                )
            }
        }

        // Sculpted floating composer dock
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 9.dp),
                ) {
                    if (inputText.isEmpty()) {
                        Text(
                            text = if (isRunning) "输入指令可随时插话调整" else "告诉 Z 在手机上做什么",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                        )
                    }
                    BasicTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.width(8.dp))

                if (isRunning) {
                    Surface(
                        onClick = onStopRun,
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

                if (!isRunning || inputText.isNotBlank()) {
                    if (isRunning) Spacer(Modifier.width(6.dp))
                    val canSend = inputText.isNotBlank() && piReady
                    Surface(
                        onClick = {
                            if (canSend) {
                                val toSend = inputText
                                inputText = ""
                                onSendMessage(toSend)
                            }
                        },
                        shape = CircleShape,
                        color = if (canSend) {
                            MaterialTheme.colorScheme.onBackground
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        },
                        contentColor = if (canSend) {
                            MaterialTheme.colorScheme.background
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
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

    // Current-session model picker sheet
    if (showModelPicker) {
        ZaiModelPickerSheet(
            title = "选择当前对话模型",
            providers = providers,
            selectedModel = sessionModel,
            onSelect = onSelectSessionModel,
            onManageProviders = onOpenModels,
            onDismiss = { showModelPicker = false },
        )
    }

    // Dynamic thinking-level picker sheet (strictly reflects provider-exposed levels)
    if (showThinkingPicker && sessionModel != null && sessionModel.supportsThinkingSelection) {
        ZaiThinkingPickerSheet(
            model = sessionModel,
            selectedLevel = sessionThinkingLevel,
            onSelect = onSelectSessionThinking,
            onDismiss = { showThinkingPicker = false },
        )
    }

    // Sessions bottom sheet
    if (showSessionsSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showSessionsSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .navigationBarsPadding(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "对话记录",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            showSessionsSheet = false
                            onNewSession()
                        },
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("新对话", fontWeight = FontWeight.Medium)
                    }
                }
                Spacer(Modifier.height(6.dp))
                allSessions.forEachIndexed { idx, item ->
                    val selected = item.id == session.id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.surfaceContainerLow
                                else Color.Transparent,
                            )
                            .clickable {
                                onSelectSession(item.id)
                                showSessionsSheet = false
                            }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (selected) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${item.messages.size} 条记录",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (allSessions.size > 1) {
                            IconButton(
                                onClick = { onDeleteSession(item.id) },
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
                    if (idx < allSessions.lastIndex) {
                        ZaiInsetDivider()
                    }
                }
                Spacer(Modifier.height(22.dp))
            }
        }
    }

    if (pendingConfirmation != null) {
        AlertDialog(
            onDismissRequest = { onResolveConfirmation(pendingConfirmation.callId, false) },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp),
            title = {
                Text(
                    text = pendingConfirmation.title,
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            text = {
                Text(
                    text = pendingConfirmation.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(onClick = { onResolveConfirmation(pendingConfirmation.callId, true) }) {
                    Text("允许一次", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { onResolveConfirmation(pendingConfirmation.callId, false) }) {
                    Text("拒绝", color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }
}

/**
 * Tactile sculpted top-left "Z" keycap button that serves as the unified Settings entry point.
 * Styled with lifted surface contrast, crisp rim, gentle elevation, and a quiet settings cue so
 * users immediately recognize it as an interactive button.
 */
@Composable
private fun ZSettingsButton(
    hasNewUpdate: Boolean,
    onClick: () -> Unit,
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.4f
    val borderColor = if (isDark) {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.42f)
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)
    }

    Box {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(13.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(0.85.dp, borderColor),
            shadowElevation = if (isDark) 0.dp else 2.dp,
            modifier = Modifier.height(38.dp),
        ) {
            Row(
                modifier = Modifier.padding(start = 11.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                ZaiMonogramMark(size = 20.dp)
                Box(
                    modifier = Modifier
                        .width(0.75.dp)
                        .height(13.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                Icon(
                    Icons.Rounded.Tune,
                    contentDescription = "打开设置",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        if (hasNewUpdate) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 4.dp)
                    .size(6.5.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun HeaderSquareTrigger(
    onClick: () -> Unit,
    contentDescription: String,
    icon: @Composable () -> Unit,
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.4f
    val borderColor = if (isDark) {
        MaterialTheme.colorScheme.outlineVariant
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.24f)
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(13.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.75.dp, borderColor),
        shadowElevation = if (isDark) 0.dp else 1.5.dp,
        modifier = Modifier.size(38.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            icon()
        }
    }
}

@Composable
private fun ComposerControlPill(
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

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = bg,
        border = BorderStroke(0.6.dp, border),
        modifier = modifier,
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
private fun EditorialEmptyCanvas(
    statusLine: String,
    onPickPrompt: (String) -> Unit,
) {
    val starters = remember {
        listOf(
            "打开系统设置，查看当前剩余存储空间",
            "打开高德地图，搜索附近的咖啡店",
            "读取当前屏幕，告诉我上面有哪些按钮",
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        ZaiEmblemMark(size = 50.dp)

        Spacer(Modifier.height(20.dp))

        Text(
            text = "你想让手机做什么",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = statusLine,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(32.dp))

        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            starters.forEach { sample ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPickPrompt(sample) }
                        .padding(vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = sample,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.88f),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(18.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.4f
    val bubbleBg = if (isDark) Color(0xFF1D2432) else MaterialTheme.colorScheme.onBackground
    val bubbleFg = if (isDark) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.background
    val border = if (isDark) BorderStroke(0.5.dp, Color(0xFF2C3649)) else null

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = 20.dp,
                bottomEnd = 6.dp,
            ),
            color = bubbleBg,
            contentColor = bubbleFg,
            border = border,
            modifier = Modifier.widthIn(max = 286.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
            )
        }
    }
}

@Composable
private fun AssistantTurn(
    message: ChatMessage,
    isActiveTurn: Boolean,
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
        if (message.steps.isNotEmpty()) {
            val operatingNow = message.steps.any { it.state == StepState.RUNNING } ||
                (isActiveTurn && message.text.isBlank())
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
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 25.sp),
                color = if (message.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground,
            )
        } else if (isActiveTurn && message.steps.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulsingDot(color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "思考中…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PulsingDot(color: Color) {
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
