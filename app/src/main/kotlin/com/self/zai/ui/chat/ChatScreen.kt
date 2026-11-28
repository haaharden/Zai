package com.self.zai.ui.chat

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.self.zai.model.MessageRole
import com.self.zai.permission.DevicePermissionHelper
import com.self.zai.ui.components.ZaiModelPickerSheet
import com.self.zai.ui.components.ZaiMotion
import com.self.zai.ui.components.ZaiThinkingPopoverCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    state: ChatUiState,
    actions: ChatActions,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val lifecycleOwner = LocalLifecycleOwner.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var showSessionsSheet by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showThinkingPicker by remember { mutableStateOf(false) }
    var showContextSheet by remember { mutableStateOf(false) }
    var showAttachmentSheet by remember { mutableStateOf(false) }
    var composerAreaHeightPx by remember { mutableIntStateOf(0) }
    var lightboxData by remember { mutableStateOf<ImageLightboxData?>(null) }

    // Prevent IME collapse vs ModalBottomSheet entrance double-bounce when opening heavy full sheets
    fun openModalSheetAfterIme(open: () -> Unit) {
        showThinkingPicker = false
        if (imeBottomPx > 0) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
            scope.launch {
                delay(160L)
                open()
            }
        } else {
            open()
        }
    }
    var hasWriteSecure by remember { mutableStateOf(DevicePermissionHelper.hasWriteSecureSettings(context)) }
    var a11ySettingOn by remember { mutableStateOf(DevicePermissionHelper.isAccessibilityEnabledInSettings(context)) }
    var repairingA11y by remember { mutableStateOf(false) }

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

    val session = state.session
    val hasControlChannel = state.accessibilityConnected || state.shizukuStatus.isReady
    val isCurrentSessionRunning = state.isRunning &&
        (state.runningSessionId == null || state.runningSessionId == session.id)
    val backgroundRunningSession = remember(state.isRunning, state.runningSessionId, session.id, state.allSessions) {
        if (state.isRunning && state.runningSessionId != null && state.runningSessionId != session.id) {
            state.allSessions.firstOrNull { it.id == state.runningSessionId }
        } else {
            null
        }
    }

    // Smart bottom-pinning: respects manual scroll-up so reading history is never interrupted
    var autoFollowBottom by remember(session.id) { mutableStateOf(true) }
    val canScrollDown by remember { derivedStateOf { listState.canScrollForward } }
    val bottomAnchorIndex = session.messages.size

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

    LaunchedEffect(session.id) {
        if (session.messages.isNotEmpty()) {
            listState.scrollToItem(bottomAnchorIndex)
        }
    }

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
                            listState.scrollBy(shrinkDelta.toFloat())
                        }
                    }
                }
                if (currentHeight > 0) {
                    prevViewportHeight = currentHeight
                }
            }
    }

    val isDarkBg = MaterialTheme.colorScheme.background.luminance() < 0.4f
    val thinkingModel = state.sessionModel?.takeIf { it.supportsThinkingSelection }
    val thinkingPopoverVisible = showThinkingPicker && thinkingModel != null

    if (thinkingPopoverVisible) {
        BackHandler { showThinkingPicker = false }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        // Minimal editorial top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZSettingsButton(
                hasNewUpdate = state.hasNewUpdate,
                onClick = actions.onOpenSettings,
            )

            Spacer(Modifier.weight(1f))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeaderSquareTrigger(
                    onClick = { openModalSheetAfterIme { showSessionsSheet = true } },
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
                    onClick = actions.onNewSession,
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

        // Background-task status strip
        if (backgroundRunningSession != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(0.6.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                    .clickable(onClick = actions.onFocusRunningSession)
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
                    if (state.liveStepLabel.isNotBlank()) {
                        Text(
                            text = state.liveStepLabel,
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
                        .clickable(onClick = actions.onFocusRunningSession)
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                )
                Text(
                    text = "停止",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = actions.onStopRun)
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                )
            }
        }

        // Subtle actionable setup strip when model or control channel is missing
        if (state.sessionModel == null || !hasControlChannel) {
            val missingModel = state.sessionModel == null
            val canAutoRepair = !missingModel && !state.accessibilityConnected &&
                (hasWriteSecure || state.shizukuStatus.isReady)
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
                            missingModel -> actions.onOpenModels()
                            canAutoRepair -> {
                                repairingA11y = true
                                scope.launch {
                                    val res = actions.onRepairAccessibility()
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
                                            context = context,
                                            isCurrentlyConnected = state.accessibilityConnected,
                                            showGuideToast = false,
                                        )
                                    }
                                }
                            }
                            else -> DevicePermissionHelper.openAccessibilitySettings(
                                context = context,
                                isCurrentlyConnected = state.accessibilityConnected,
                            )
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
                EditorialEmptyCanvas(piReady = state.piReady)
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
                                liveStepLabel = if (isLatestAssistant) state.liveStepLabel else "",
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

                // Soft optical edge fades so messages glide gracefully behind the top bar & floating dock
                val bg = MaterialTheme.colorScheme.background
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(14.dp)
                        .background(Brush.verticalGradient(listOf(bg, Color.Transparent))),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(16.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, bg))),
                )

                val isDark = bg.luminance() < 0.4f
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
                            scope.launch { listState.animateScrollToItem(bottomAnchorIndex) }
                        },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(
                            0.75.dp,
                            if (state.isRunning) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                            else MaterialTheme.colorScheme.outlineVariant,
                        ),
                        shadowElevation = if (isDark) 0.dp else 3.dp,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Rounded.ArrowDownward,
                                contentDescription = "回到底部",
                                tint = if (state.isRunning) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }

        SlashSkillPopup(
            inputText = state.inputText,
            state = state,
            onSelectSkillPrefix = actions.onInputTextChange,
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { composerAreaHeightPx = it.height },
        ) {
            ComposerControlStrip(
                state = state,
                hasControlChannel = hasControlChannel,
                onOpenModelPicker = { openModalSheetAfterIme { showModelPicker = true } },
                onOpenModels = actions.onOpenModels,
                onOpenThinkingPicker = { showThinkingPicker = !showThinkingPicker },
                onOpenContextSheet = { openModalSheetAfterIme { showContextSheet = true } },
            )

            ComposerDock(
                state = state,
                actions = actions,
                isCurrentSessionRunning = isCurrentSessionRunning,
                backgroundRunningSession = backgroundRunningSession,
                onOpenAttachmentSheet = { openModalSheetAfterIme { showAttachmentSheet = true } },
                onOpenLightbox = { lightboxData = it },
                onSentSuccess = {
                    showThinkingPicker = false
                    autoFollowBottom = true
                    scope.launch { listState.animateScrollToItem(bottomAnchorIndex) }
                },
            )
        }
    }

    // In-window tap-catcher scrim for Apple thinking popover (keeps IME keyboard rock-steady!)
    androidx.compose.animation.AnimatedVisibility(
        visible = thinkingPopoverVisible,
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(120)),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = if (isDarkBg) 0.24f else 0.08f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { showThinkingPicker = false },
                ),
        )
    }

    // In-window Apple floating popover anchored directly above the composer control strip
    val popoverBottomPadding = with(density) { composerAreaHeightPx.toDp() } + 8.dp
    androidx.compose.animation.AnimatedVisibility(
        visible = thinkingPopoverVisible,
        enter = fadeIn(tween(150)) + scaleIn(
            initialScale = 0.90f,
            transformOrigin = TransformOrigin(0.35f, 1f),
            animationSpec = ZaiMotion.SpringSnappy,
        ),
        exit = fadeOut(tween(115)) + scaleOut(
            targetScale = 0.92f,
            transformOrigin = TransformOrigin(0.35f, 1f),
            animationSpec = tween(115),
        ),
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(start = 18.dp, end = 18.dp, bottom = popoverBottomPadding),
    ) {
        val modelForPopover = thinkingModel
        if (modelForPopover != null) {
            ZaiThinkingPopoverCard(
                model = modelForPopover,
                selectedLevel = state.sessionThinkingLevel,
                onSelect = actions.onSelectSessionThinking,
                onDismiss = { showThinkingPicker = false },
            )
        }
    }
    }

    if (showAttachmentSheet) {
        AttachmentPickerSheet(
            onPickImages = actions.onPickImages,
            onTakePhoto = actions.onTakePhoto,
            onPickFiles = actions.onPickFiles,
            onPasteClipboardImage = actions.onPasteClipboardImage,
            onDismiss = { showAttachmentSheet = false },
        )
    }

    lightboxData?.let { data ->
        ImageLightboxDialog(data = data, onDismiss = { lightboxData = null })
    }

    if (showModelPicker) {
        ZaiModelPickerSheet(
            title = "选择当前对话模型",
            providers = state.providers,
            selectedModel = state.sessionModel,
            providerQuotas = state.providerQuotas,
            onRefreshQuota = actions.onRefreshQuota,
            onSelect = actions.onSelectSessionModel,
            onManageProviders = actions.onOpenModels,
            onDismiss = { showModelPicker = false },
        )
    }

    val contextUsage = state.contextUsage
    if (showContextSheet && contextUsage != null) {
        ContextUsageSheet(
            contextUsage = contextUsage,
            isRunning = state.isRunning,
            messageCount = session.messages.size,
            piReady = state.piReady,
            onOpenSettings = actions.onOpenSettings,
            onCompactSession = actions.onCompactSession,
            onDismiss = { showContextSheet = false },
        )
    }

    if (showSessionsSheet) {
        ChatSessionsSheet(
            currentSessionId = session.id,
            allSessions = state.allSessions,
            storageBytes = state.storageBytes,
            isRunning = state.isRunning,
            runningSessionId = state.runningSessionId,
            onSelectSession = actions.onSelectSession,
            onNewSession = actions.onNewSession,
            onDeleteSession = actions.onDeleteSession,
            onDismiss = { showSessionsSheet = false },
        )
    }

    state.pendingConfirmation?.let { confirmation ->
        PendingConfirmationDialog(
            confirmation = confirmation,
            onResolve = actions.onResolveConfirmation,
        )
    }
}
