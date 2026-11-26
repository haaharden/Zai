package com.self.zai.ui.chat

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.self.zai.assistant.VoiceListenState
import com.self.zai.data.AttachmentManager
import com.self.zai.model.AttachmentKind
import com.self.zai.model.ChatAttachment
import com.self.zai.model.ChatMessage
import com.self.zai.model.ChatSession
import com.self.zai.model.MessageRole
import com.self.zai.model.ModelOption
import com.self.zai.model.PendingAttachment
import com.self.zai.model.PendingConfirmation
import com.self.zai.model.ProviderQuota
import com.self.zai.model.ProviderStatus
import com.self.zai.model.SessionContextUsage
import com.self.zai.model.SkillEntry
import com.self.zai.model.StepState
import com.self.zai.model.ThinkingLevelDisplay
import com.self.zai.permission.DevicePermissionHelper
import com.self.zai.shell.ShizukuStatus
import com.self.zai.ui.components.ZaiCopyIconButton
import com.self.zai.ui.components.ZaiEmblemMark
import com.self.zai.ui.components.ZaiInsetDivider
import com.self.zai.ui.components.ZaiMarkdownText
import com.self.zai.ui.components.ZaiModelPickerSheet
import com.self.zai.ui.components.ZaiMonogramMark
import com.self.zai.ui.components.ZaiPrimaryButton
import com.self.zai.ui.components.ZaiThinkingPickerSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    session: ChatSession,
    allSessions: List<ChatSession>,
    storageBytes: Long = 0L,
    providers: List<ProviderStatus>,
    providerQuotas: Map<String, ProviderQuota>,
    enabledSkills: List<SkillEntry>,
    sessionModel: ModelOption?,
    sessionThinkingLevel: String,
    contextUsage: SessionContextUsage?,
    piReady: Boolean,
    isRunning: Boolean,
    runningSessionId: String? = null,
    liveStepLabel: String,
    shizukuStatus: ShizukuStatus,
    accessibilityConnected: Boolean,
    hasNewUpdate: Boolean,
    pendingConfirmation: PendingConfirmation?,
    inputText: String,
    onInputTextChange: (String) -> Unit,
    pendingAttachments: List<PendingAttachment> = emptyList(),
    importingAttachments: Boolean = false,
    onPickImages: () -> Unit = {},
    onTakePhoto: () -> Unit = {},
    onPickFiles: () -> Unit = {},
    onPasteClipboardImage: () -> Unit = {},
    onRemoveAttachment: (String) -> Unit = {},
    voiceListenState: VoiceListenState,
    onToggleVoiceInput: () -> Unit,
    onCancelVoiceInput: () -> Unit,
    onRefreshQuota: (providerId: String, force: Boolean) -> Unit,
    onCompactSession: () -> Unit,
    onSendMessage: (String, List<PendingAttachment>) -> Boolean,
    onStopRun: () -> Unit,
    onFocusRunningSession: () -> Unit = {},
    onSelectSession: (String) -> Unit,
    onNewSession: () -> Unit,
    onDeleteSession: (String) -> Unit,
    onSelectSessionModel: (providerId: String, modelId: String) -> Unit,
    onSelectSessionThinking: (String) -> Unit,
    onOpenModels: () -> Unit,
    onOpenSettings: () -> Unit,
    onRepairAccessibility: suspend () -> Result<Unit> = { Result.failure(UnsupportedOperationException()) },
    onResolveConfirmation: (callId: String, allow: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var showSessionsSheet by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showThinkingPicker by remember { mutableStateOf(false) }
    var showContextSheet by remember { mutableStateOf(false) }
    var showAttachmentSheet by remember { mutableStateOf(false) }
    var lightboxData by remember { mutableStateOf<ImageLightboxData?>(null) }
    var hasWriteSecure by remember { mutableStateOf(DevicePermissionHelper.hasWriteSecureSettings(context)) }
    var a11ySettingOn by remember { mutableStateOf(DevicePermissionHelper.isAccessibilityEnabledInSettings(context)) }
    var repairingA11y by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasWriteSecure = DevicePermissionHelper.hasWriteSecureSettings(context)
                a11ySettingOn = DevicePermissionHelper.isAccessibilityEnabledInSettings(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val hasControlChannel = accessibilityConnected || shizukuStatus.isReady
    val isCurrentSessionRunning = isRunning && (runningSessionId == null || runningSessionId == session.id)
    val backgroundRunningSession = remember(isRunning, runningSessionId, session.id, allSessions) {
        if (isRunning && runningSessionId != null && runningSessionId != session.id) {
            allSessions.firstOrNull { it.id == runningSessionId }
        } else {
            null
        }
    }

    // Smart bottom-pinning: respects manual scroll-up so reading history is never interrupted
    var autoFollowBottom by remember(session.id) { mutableStateOf(true) }
    val canScrollDown by remember { derivedStateOf { listState.canScrollForward } }
    val bottomAnchorIndex = session.messages.size

    // Only real user finger drags toggle autoFollowBottom (never programmatic IME/streaming scrolls)
    LaunchedEffect(listState, session.id) {
        var userScrolling = false
        launch {
            listState.interactionSource.interactions.collect { interaction ->
                if (interaction is DragInteraction.Start) {
                    userScrolling = true
                }
            }
        }
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (inProgress, forward) ->
                if (userScrolling) {
                    if (forward) autoFollowBottom = false
                    if (!inProgress) {
                        userScrolling = false
                        autoFollowBottom = !forward
                    }
                } else if (!forward && !inProgress) {
                    autoFollowBottom = true
                }
            }
    }

    // Jump to bottom cleanly when switching sessions
    LaunchedEffect(session.id) {
        if (session.messages.isNotEmpty()) {
            listState.scrollToItem(bottomAnchorIndex)
        }
    }

    // Follow streaming output ONLY while pinned to bottom (never yank user away from earlier messages)
    val lastMsg = session.messages.lastOrNull()
    LaunchedEffect(
        session.id,
        session.messages.size,
        lastMsg?.steps?.size,
        lastMsg?.text?.length,
        lastMsg?.thinking?.length,
    ) {
        if (autoFollowBottom && session.messages.isNotEmpty()) {
            listState.scrollToItem(bottomAnchorIndex)
        }
    }

    // Post-layout 1:1 viewport tracking for keyboard (IME) animations, multi-line composer growth, etc.:
    // Runs strictly AFTER LazyColumn re-measures its new height so neither frame-by-frame nor instant IME
    // transitions ever clamp to a stale height.
    val latestMessageCount by rememberUpdatedState(session.messages.size)
    LaunchedEffect(listState, session.id) {
        var prevViewportHeight = 0
        snapshotFlow { listState.layoutInfo.viewportSize.height }
            .collect { currentHeight ->
                if (prevViewportHeight > 0 && currentHeight > 0 && currentHeight != prevViewportHeight) {
                    val shrinkDelta = prevViewportHeight - currentHeight
                    if (latestMessageCount > 0) {
                        if (shrinkDelta > 0) {
                            if (autoFollowBottom) {
                                listState.scrollBy(shrinkDelta.toFloat())
                                if (listState.canScrollForward) {
                                    listState.scrollToItem(latestMessageCount)
                                }
                            } else {
                                listState.scrollBy(shrinkDelta.toFloat())
                            }
                        } else if (!autoFollowBottom) {
                            // Keyboard closing while user was reading history: restore anchored position
                            listState.scrollBy(shrinkDelta.toFloat())
                        }
                    }
                }
                if (currentHeight > 0) {
                    prevViewportHeight = currentHeight
                }
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

                val canStartNewChat = session.messages.isNotEmpty()
                HeaderSquareTrigger(
                    enabled = canStartNewChat,
                    onClick = onNewSession,
                    contentDescription = "新对话",
                ) {
                    Icon(
                        Icons.Rounded.Add,
                        contentDescription = "新对话",
                        tint = if (canStartNewChat) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.32f)
                        },
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        // Background-task status strip when user browses another chat while an earlier session is still running
        if (backgroundRunningSession != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(0.6.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                    .clickable(onClick = onFocusRunningSession)
                    .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
                Spacer(Modifier.width(9.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "「${backgroundRunningSession.title.take(14)}」正在后台运行",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (liveStepLabel.isNotBlank()) {
                        Text(
                            text = liveStepLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    text = "查看",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onFocusRunningSession)
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                )
                Text(
                    text = "停止",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onStopRun)
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                )
            }
        }

        // Subtle actionable setup strip when needed (accent left bar, zero boxy alert feel)
        if (sessionModel == null || !hasControlChannel) {
            val missingModel = sessionModel == null
            val canAutoRepair = !missingModel && !accessibilityConnected &&
                (hasWriteSecure || shizukuStatus.isReady)
            val stripText = when {
                missingModel -> "先配置模型账号"
                repairingA11y -> "正在自动拉起无障碍…"
                canAutoRepair && a11ySettingOn -> "无障碍已脱绑，点此一键恢复"
                canAutoRepair -> "无障碍未连接，点此一键拉起"
                a11ySettingOn -> "无障碍已脱绑，点此直达无障碍重开"
                else -> "开启无障碍即可操控手机"
            }
            val stripAction = when {
                missingModel -> "设置"
                repairingA11y -> "恢复中"
                canAutoRepair -> "恢复"
                a11ySettingOn -> "去重开"
                else -> "去开启"
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                    .clickable(enabled = !repairingA11y) {
                        when {
                            missingModel -> onOpenModels()
                            canAutoRepair -> {
                                repairingA11y = true
                                scope.launch {
                                    val res = onRepairAccessibility()
                                    repairingA11y = false
                                    hasWriteSecure = DevicePermissionHelper.hasWriteSecureSettings(context)
                                    a11ySettingOn = DevicePermissionHelper.isAccessibilityEnabledInSettings(context)
                                    res.onSuccess {
                                        Toast.makeText(context, "已恢复无障碍", Toast.LENGTH_SHORT).show()
                                    }.onFailure { err ->
                                        Toast.makeText(
                                            context,
                                            err.message ?: "自动拉起失败，请手动开启",
                                            Toast.LENGTH_LONG,
                                        ).show()
                                        DevicePermissionHelper.openAccessibilitySettings(
                                            context,
                                            showGuideToast = false,
                                        )
                                    }
                                }
                            }
                            else -> DevicePermissionHelper.openAccessibilitySettings(context)
                        }
                    }
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
                    text = stripText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stripAction,
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
                    piReady = piReady,
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    items(session.messages, key = { it.id }) { msg ->
                        val isLatestAssistant = isCurrentSessionRunning && msg.id == session.messages.lastOrNull()?.id
                        when (msg.role) {
                            MessageRole.USER -> UserBubble(
                                message = msg,
                                onOpenLightbox = { lightboxData = it },
                            )
                            MessageRole.ASSISTANT -> AssistantTurn(
                                message = msg,
                                isActiveTurn = isLatestAssistant,
                                liveStepLabel = if (isLatestAssistant) liveStepLabel else "",
                            )
                            MessageRole.SYSTEM_NOTE -> Text(
                                text = msg.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    item(key = "bottom_anchor") { Spacer(Modifier.height(10.dp)) }
                }

                // Subtle floating jump-to-bottom button when user has scrolled up reading history
                val isDark = MaterialTheme.colorScheme.background.luminance() < 0.4f
                androidx.compose.animation.AnimatedVisibility(
                    visible = canScrollDown && !autoFollowBottom,
                    enter = fadeIn(tween(160)),
                    exit = fadeOut(tween(160)),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 18.dp, bottom = 10.dp),
                ) {
                    Surface(
                        onClick = {
                            autoFollowBottom = true
                            scope.launch {
                                listState.animateScrollToItem(bottomAnchorIndex)
                            }
                        },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(
                            0.75.dp,
                            if (isRunning) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                            else MaterialTheme.colorScheme.outlineVariant,
                        ),
                        shadowElevation = if (isDark) 0.dp else 3.dp,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Rounded.ArrowDownward,
                                contentDescription = "回到底部",
                                tint = if (isRunning) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }

        // Quiet inline slash-skill picker when user types `/` in the composer
        val slashMatches = remember(inputText, enabledSkills) {
            if (!inputText.startsWith("/") || inputText.contains(" ") || enabledSkills.isEmpty()) {
                emptyList()
            } else {
                val query = inputText.removePrefix("/").trim()
                enabledSkills.filter {
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
                            .clickable { onInputTextChange("/${skill.name} ") }
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

        // Model + dynamic thinking effort selector strip right above the chat composer
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ComposerControlPill(
                label = sessionModel?.name ?: "选择模型",
                monospace = false,
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

            if (session.messages.isNotEmpty() && contextUsage != null) {
                val nearLimit = contextUsage.percent >= (contextUsage.thresholdPercent - 10).coerceAtLeast(30)
                val ctxLabel = if (contextUsage.archivedBlocks > 0) {
                    "${contextUsage.percent}%·${contextUsage.archivedBlocks}块"
                } else {
                    "${contextUsage.percent}%"
                }
                Surface(
                    onClick = { showContextSheet = true },
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

        // Sculpted floating composer dock (unified attachment strip + input row)
        val isVoiceListening = voiceListenState == VoiceListenState.LISTENING
        val hasAttachments = pendingAttachments.isNotEmpty()
        val hasImageAttachments = pendingAttachments.any { it.promptImages.isNotEmpty() }
        val modelLacksVision = sessionModel != null && !sessionModel.supportsImages && hasImageAttachments
        val hasPayloadToSend = inputText.isNotBlank() || hasAttachments
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
                AnimatedVisibility(visible = hasAttachments || importingAttachments) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            items(pendingAttachments, key = { it.attachment.id }) { item ->
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
                                            lightboxData = ImageLightboxData(paths = paths, title = att.displayName)
                                        }
                                    },
                                    onRemove = { onRemoveAttachment(item.attachment.id) },
                                )
                            }
                            if (importingAttachments) {
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
                if (inputText.isEmpty() && inputLineCount != 1) {
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
                        onClick = { showAttachmentSheet = true },
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
                        if (inputText.isEmpty()) {
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
                            value = inputText,
                            onValueChange = { next ->
                                if (next.isEmpty()) inputLineCount = 1
                                if (isVoiceListening) onCancelVoiceInput()
                                onInputTextChange(next)
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
                                .onFocusChanged { state ->
                                    if (state.isFocused && isVoiceListening) {
                                        onCancelVoiceInput()
                                    }
                                },
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

                    if (!isRunning && (!hasPayloadToSend || isVoiceListening)) {
                        ChatMicButton(
                            listening = isVoiceListening,
                            onClick = onToggleVoiceInput,
                        )
                    }

                    if (hasPayloadToSend) {
                        if (isRunning || isVoiceListening) Spacer(Modifier.width(6.dp))
                        val canSend = piReady && !importingAttachments
                        Surface(
                            onClick = {
                                if (canSend) {
                                    if (isVoiceListening) onCancelVoiceInput()
                                    val toSendText = inputText
                                    val toSendAttachments = pendingAttachments
                                    val sent = onSendMessage(toSendText, toSendAttachments)
                                    if (sent) {
                                        onInputTextChange("")
                                        autoFollowBottom = true
                                        scope.launch {
                                            listState.animateScrollToItem(bottomAnchorIndex)
                                        }
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

    // Attachment source picker sheet
    if (showAttachmentSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val hasClipImg = remember(showAttachmentSheet) { AttachmentManager.hasClipboardImage(context) }
        ModalBottomSheet(
            onDismissRequest = { showAttachmentSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "添加附件",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "支持图片、PDF、Word / Excel / PPT、代码与文本 · 单条最多 ${AttachmentManager.MAX_ATTACHMENTS_PER_MESSAGE} 个",
                        style = MaterialTheme.typography.bodySmall,
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
                            showAttachmentSheet = false
                            onPickImages()
                        },
                        modifier = Modifier.weight(1f),
                    )
                    AttachmentSourceTile(
                        icon = Icons.Rounded.PhotoCamera,
                        title = "拍照",
                        subtitle = "即拍即发",
                        onClick = {
                            showAttachmentSheet = false
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
                            showAttachmentSheet = false
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
                            showAttachmentSheet = false
                            onPasteClipboardImage()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(Modifier.height(10.dp))
            }
        }
    }

    // Full-screen lightbox preview for images & rendered PDF pages
    lightboxData?.let { data ->
        ImageLightboxDialog(
            data = data,
            onDismiss = { lightboxData = null },
        )
    }

    // Current-session model picker sheet
    if (showModelPicker) {
        ZaiModelPickerSheet(
            title = "选择当前对话模型",
            providers = providers,
            selectedModel = sessionModel,
            providerQuotas = providerQuotas,
            onRefreshQuota = onRefreshQuota,
            onSelect = onSelectSessionModel,
            onManageProviders = onOpenModels,
            onDismiss = { showModelPicker = false },
        )
    }

    // Compact session context & manual compaction sheet
    if (showContextSheet && contextUsage != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showContextSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "上下文状态",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            showContextSheet = false
                            onOpenSettings()
                        },
                    ) {
                        Text("调整阈值", fontWeight = FontWeight.Medium)
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(14.dp),
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
                        if (contextUsage.mode != com.self.zai.model.CompactionMode.OFF) {
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
                    enabled = !isRunning && session.messages.size >= 4 && piReady,
                    onClick = {
                        showContextSheet = false
                        onCompactSession()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(14.dp))
            }
        }
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

    // Sessions bottom sheet (paginated by 40, with total storage size and Markdown export)
    if (showSessionsSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var visibleLimit by remember(showSessionsSheet) { mutableIntStateOf(40) }
        val visibleSessions = remember(allSessions, visibleLimit) { allSessions.take(visibleLimit) }
        val remainingCount = (allSessions.size - visibleSessions.size).coerceAtLeast(0)
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
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                ) {
                    itemsIndexed(visibleSessions, key = { _, item -> item.id }) { idx, item ->
                        val selected = item.id == session.id
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
                                    onSelectSession(item.id)
                                    showSessionsSheet = false
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
 * Minimal unboxed top-left Z settings mark: quiet editorial trigger with subtle update dot.
 */
@Composable
private fun ZSettingsButton(
    hasNewUpdate: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
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
private fun HeaderSquareTrigger(
    onClick: () -> Unit,
    contentDescription: String,
    enabled: Boolean = true,
    icon: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        icon()
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

private data class ImageLightboxData(
    val paths: List<String>,
    val title: String,
    val initialIndex: Int = 0,
)

@Composable
private fun AttachmentSourceTile(
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
private fun PendingAttachmentCard(
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
private fun LocalImageThumbnail(
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
private fun ImageLightboxDialog(
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

            // Top bar with title and close button
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

            // Multi-page switcher for PDF pages or multi-image attachments
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

@Composable
private fun UserBubble(
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
private fun AssistantTurn(
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

@Composable
private fun ChatMicButton(
    listening: Boolean,
    onClick: () -> Unit,
) {
    val pulse = if (listening) {
        val transition = rememberInfiniteTransition(label = "chatMicPulse")
        val alpha by transition.animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
            label = "chatMicAlpha",
        )
        alpha
    } else {
        1f
    }

    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (listening) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        contentColor = if (listening) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.size(38.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (listening) Icons.Rounded.GraphicEq else Icons.Rounded.Mic,
                contentDescription = if (listening) "停止语音" else "语音输入",
                modifier = Modifier
                    .size(19.dp)
                    .alpha(pulse),
            )
        }
    }
}

private fun formatStorageBytes(bytes: Long): String {
    if (bytes <= 0L) return ""
    return when {
        bytes < 1024L -> "${bytes} B"
        bytes < 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(java.util.Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
    }
}

private fun shareSessionAsMarkdown(context: android.content.Context, session: ChatSession) {
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
