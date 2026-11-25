package com.self.zai.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.self.zai.BuildConfig
import com.self.zai.model.AppSettings
import com.self.zai.model.CompactionMode
import com.self.zai.model.ControlChannelMode
import com.self.zai.model.ModelOption
import com.self.zai.model.PerceptionMode
import com.self.zai.model.ProviderQuota
import com.self.zai.model.ProviderStatus
import com.self.zai.model.ThemeMode
import com.self.zai.model.ThinkingLevelDisplay
import com.self.zai.model.TtsReadoutMode
import com.self.zai.permission.DevicePermissionHelper
import com.self.zai.shell.ShizukuStatus
import com.self.zai.ui.capabilities.UserPromptEditorSheet
import com.self.zai.ui.components.ZaiInsetDivider
import com.self.zai.ui.components.ZaiModelPickerSheet
import com.self.zai.ui.components.ZaiPaperGroup
import com.self.zai.ui.components.ZaiPrimaryButton
import com.self.zai.ui.components.ZaiSecondaryButton
import com.self.zai.ui.components.ZaiSegmentedControl
import com.self.zai.ui.components.ZaiSelectPill
import com.self.zai.ui.components.ZaiTextField
import com.self.zai.ui.components.zaiSwitchColors
import com.self.zai.update.AppUpdater
import com.self.zai.update.ReleaseUpdateInfo
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    providers: List<ProviderStatus>,
    providerQuotas: Map<String, ProviderQuota>,
    defaultModel: ModelOption?,
    defaultThinkingLevel: String,
    piVersion: String,
    hotUpdateTag: String?,
    latestRelease: ReleaseUpdateInfo?,
    shizukuStatus: ShizukuStatus,
    accessibilityConnected: Boolean,
    onReleaseChecked: (ReleaseUpdateInfo?) -> Unit,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onUpdateCompaction: (CompactionMode, Int) -> Unit,
    onRefreshQuota: (providerId: String, force: Boolean) -> Unit,
    onSelectDefaultModel: (providerId: String, modelId: String) -> Unit,
    onSelectDefaultThinking: (String) -> Unit,
    onUpdateUserPrompt: (String) -> Unit,
    onOpenSkills: () -> Unit,
    onOpenMcpServers: () -> Unit,
    onOpenModels: () -> Unit,
    onRequestShizuku: () -> Unit,
    onTestShizuku: suspend () -> Result<String>,
    onRepairAccessibility: suspend () -> Result<Unit>,
    onShizukuInstallApk: suspend (File) -> Result<String>,
    onReloadPiHotUpdate: () -> Unit,
    onResetPiHotUpdate: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasOverlay by remember { mutableStateOf(DevicePermissionHelper.hasOverlayPermission(context)) }
    var hasNotification by remember { mutableStateOf(DevicePermissionHelper.hasNotificationPermission(context)) }
    var batteryIgnored by remember { mutableStateOf(DevicePermissionHelper.isIgnoringBatteryOptimizations(context)) }
    var a11ySettingOn by remember { mutableStateOf(DevicePermissionHelper.isAccessibilityEnabledInSettings(context)) }
    var hasWriteSecure by remember { mutableStateOf(DevicePermissionHelper.hasWriteSecureSettings(context)) }
    var hasXiaomiBgPopup by remember { mutableStateOf(DevicePermissionHelper.hasXiaomiBgPopupPermission(context)) }
    var isDefaultAssistant by remember { mutableStateOf(DevicePermissionHelper.isDefaultAssistant(context)) }
    var hasNotifListener by remember { mutableStateOf(DevicePermissionHelper.isNotificationListenerEnabled(context)) }

    var showDefaultModelSheet by remember { mutableStateOf(false) }
    var showUserPromptSheet by remember { mutableStateOf(false) }
    var showCompactionConfig by remember { mutableStateOf(false) }
    var showAdvancedControl by remember { mutableStateOf(false) }
    var showSystemPerms by remember { mutableStateOf(false) }
    var showRepoEditor by remember { mutableStateOf(false) }

    var repoInput by remember(settings.githubUpdateRepo) { mutableStateOf(settings.githubUpdateRepo) }
    var updateChecking by remember { mutableStateOf(false) }
    var updateBusy by remember { mutableStateOf(false) }
    var updateProgress by remember { mutableFloatStateOf(0f) }
    var updateStatusText by remember(latestRelease) {
        mutableStateOf(
            when {
                latestRelease?.isNewer == true -> "新版本 ${latestRelease.tagName}"
                else -> "v${BuildConfig.VERSION_NAME}"
            },
        )
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlay = DevicePermissionHelper.hasOverlayPermission(context)
                hasNotification = DevicePermissionHelper.hasNotificationPermission(context)
                batteryIgnored = DevicePermissionHelper.isIgnoringBatteryOptimizations(context)
                a11ySettingOn = DevicePermissionHelper.isAccessibilityEnabledInSettings(context)
                hasWriteSecure = DevicePermissionHelper.hasWriteSecureSettings(context)
                hasXiaomiBgPopup = DevicePermissionHelper.hasXiaomiBgPopupPermission(context)
                isDefaultAssistant = DevicePermissionHelper.isDefaultAssistant(context)
                hasNotifListener = DevicePermissionHelper.isNotificationListenerEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                title = { Text("设置", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // 1. Model & Thinking
            ZaiPaperGroup(title = "模型") {
                val connectedCount = providers.count { it.isConnected && it.models.isNotEmpty() }
                CompactNavRow(
                    title = "账号与渠道",
                    value = if (connectedCount > 0) "已连 $connectedCount 个" else "去登录",
                    onClick = onOpenModels,
                )

                ZaiInsetDivider()

                CompactNavRow(
                    title = "默认模型",
                    value = defaultModel?.name ?: "未配置",
                    onClick = {
                        if (connectedCount > 0) showDefaultModelSheet = true else onOpenModels()
                    },
                )

                if (defaultModel != null && defaultModel.supportsThinkingSelection) {
                    ZaiInsetDivider()
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "默认思考强度",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            defaultModel.thinkingLevels.forEach { level ->
                                ZaiSelectPill(
                                    label = ThinkingLevelDisplay.label(level),
                                    selected = defaultThinkingLevel == level,
                                    onClick = { onSelectDefaultThinking(level) },
                                )
                            }
                        }
                    }
                }

                ZaiInsetDivider()

                val compactionSummary = if (settings.compactionMode == CompactionMode.OFF) {
                    "关闭"
                } else {
                    "${settings.compactionMode.label()} · ${settings.compactionThresholdPercent}%"
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showCompactionConfig = !showCompactionConfig }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "上下文压缩",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = compactionSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        if (showCompactionConfig) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }

                AnimatedVisibility(visible = showCompactionConfig) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ZaiSegmentedControl(
                            options = listOf(
                                CompactionMode.OFF to "关闭",
                                CompactionMode.AUTO to "自动",
                                CompactionMode.ACP to "ACP 主动",
                            ),
                            selected = settings.compactionMode,
                            onSelect = { mode ->
                                onUpdateCompaction(mode, settings.compactionThresholdPercent)
                            },
                        )

                        if (settings.compactionMode != CompactionMode.OFF) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "触发阈值（占模型上下文比例）",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    listOf(50, 65, 75, 85).forEach { pct ->
                                        ZaiSelectPill(
                                            label = "$pct%",
                                            selected = settings.compactionThresholdPercent == pct,
                                            monospace = true,
                                            onClick = { onUpdateCompaction(settings.compactionMode, pct) },
                                        )
                                    }
                                }
                            }
                            Text(
                                text = if (settings.compactionMode == CompactionMode.ACP) {
                                    "超阈值自动折叠旧历史；模型也可按需压缩、检索与解压历史块。"
                                } else {
                                    "超过阈值时自动摘要折叠早期对话，保留近期消息与当前上下文。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // 2. Prompt, Skills & MCP
            ZaiPaperGroup(title = "能力") {
                CompactNavRow(
                    title = "用户提示词",
                    value = if (settings.userSystemPrompt.isNotBlank()) "已设置" else "未设置",
                    onClick = { showUserPromptSheet = true },
                )

                ZaiInsetDivider()

                val enabledSkillsCount = settings.skills.count { it.enabled }
                CompactNavRow(
                    title = "Skills",
                    value = if (settings.skills.isEmpty()) "0" else "$enabledSkillsCount/${settings.skills.size}",
                    onClick = onOpenSkills,
                )

                ZaiInsetDivider()

                val enabledTools = settings.mcpServers.filter { it.enabled }.sumOf { it.tools.size }
                CompactNavRow(
                    title = "MCP 工具",
                    value = if (settings.mcpServers.isEmpty()) "0" else "$enabledTools 个工具",
                    onClick = onOpenMcpServers,
                )
            }

            // 3. System Personal Assistant & Voice
            ZaiPaperGroup(title = "助手与语音") {
                CompactStatusRow(
                    dotColor = if (isDefaultAssistant) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
                    title = "默认数字助手",
                    statusText = if (isDefaultAssistant) "已设为默认" else null,
                    actionLabel = if (isDefaultAssistant) "试呼出" else "去设置",
                    onRowClick = { DevicePermissionHelper.openVoiceInputSettings(context) },
                    onAction = {
                        if (isDefaultAssistant) {
                            DevicePermissionHelper.openAssistOverlayPreview(context)
                        } else {
                            DevicePermissionHelper.openVoiceInputSettings(context)
                        }
                    },
                )

                ZaiInsetDivider()

                CompactToggleRow(
                    title = "呼出即自动语音",
                    checked = settings.assistantAutoListen,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(assistantAutoListen = v) } },
                )

                ZaiInsetDivider()

                CompactToggleRow(
                    title = "浮层默认附带屏幕",
                    checked = settings.assistantAttachScreenDefault,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(assistantAttachScreenDefault = v) } },
                )

                ZaiInsetDivider()

                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("语音播报", style = MaterialTheme.typography.titleSmall)
                    ZaiSegmentedControl(
                        options = listOf(
                            TtsReadoutMode.OFF to "关闭",
                            TtsReadoutMode.HEADSET_ONLY to "仅耳机",
                            TtsReadoutMode.ALWAYS to "总是",
                        ),
                        selected = settings.assistantTtsMode,
                        onSelect = { m -> onUpdateSettings { it.copy(assistantTtsMode = m) } },
                    )
                }

                ZaiInsetDivider()

                CompactStatusRow(
                    dotColor = if (hasNotifListener) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
                    title = "通知读取权限",
                    actionLabel = if (hasNotifListener) "已开启" else "去开启",
                    onAction = { DevicePermissionHelper.openNotificationListenerSettings(context) },
                )
            }

            // 4. Phone Control (essential items visible, technical options tucked cleanly)
            ZaiPaperGroup(title = "手机操控") {
                val a11yColor = when {
                    accessibilityConnected -> MaterialTheme.colorScheme.secondary
                    a11ySettingOn -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.outline
                }
                val canAutoRepair = !accessibilityConnected && (hasWriteSecure || shizukuStatus.isReady)
                CompactStatusRow(
                    dotColor = a11yColor,
                    title = "无障碍服务",
                    statusText = when {
                        accessibilityConnected -> "已连接"
                        a11ySettingOn -> "已脱绑"
                        else -> "未开启"
                    },
                    actionLabel = when {
                        canAutoRepair -> "恢复"
                        accessibilityConnected -> "管理"
                        else -> "去开启"
                    },
                    onAction = {
                        if (canAutoRepair) {
                            scope.launch {
                                val res = onRepairAccessibility()
                                val msg = res.fold(
                                    onSuccess = { "已恢复无障碍" },
                                    onFailure = { "恢复失败：${it.message}" },
                                )
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                if (res.isFailure) DevicePermissionHelper.openAccessibilitySettings(context)
                            }
                        } else {
                            DevicePermissionHelper.openAccessibilitySettings(context)
                        }
                    },
                )

                ZaiInsetDivider()
                CompactToggleRow(
                    title = "操控提示胶囊",
                    checked = settings.showFloatingCapsule,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(showFloatingCapsule = v) } },
                )
                ZaiInsetDivider()
                CompactToggleRow(
                    title = "屏幕呼吸光边",
                    checked = settings.showEdgeGlow,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(showEdgeGlow = v) } },
                )
                ZaiInsetDivider()
                CompactToggleRow(
                    title = "完成后自动返回",
                    checked = settings.returnToZaiOnFinish,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(returnToZaiOnFinish = v) } },
                )
                ZaiInsetDivider()
                CompactToggleRow(
                    title = "敏感操作前确认",
                    checked = settings.confirmSensitiveActions,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(confirmSensitiveActions = v) } },
                )

                ZaiInsetDivider()
                DisclosureRow(
                    title = "高级操控与自愈",
                    expanded = showAdvancedControl,
                    onToggle = { showAdvancedControl = !showAdvancedControl },
                )

                AnimatedVisibility(visible = showAdvancedControl) {
                    Column {
                        ZaiInsetDivider()
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("执行通道", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                ZaiSegmentedControl(
                                    options = listOf(
                                        ControlChannelMode.ACCESSIBILITY_ONLY to "无障碍",
                                        ControlChannelMode.SHIZUKU_FIRST to "Shizuku 优先",
                                    ),
                                    selected = settings.channelMode,
                                    onSelect = { m -> onUpdateSettings { it.copy(channelMode = m) } },
                                )
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("视觉策略", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                ZaiSegmentedControl(
                                    options = listOf(
                                        PerceptionMode.HYBRID to "混合",
                                        PerceptionMode.TREE_ONLY to "纯控件树",
                                        PerceptionMode.ALWAYS_VISION to "总是看图",
                                    ),
                                    selected = settings.perceptionMode,
                                    onSelect = { m -> onUpdateSettings { it.copy(perceptionMode = m) } },
                                )
                            }
                        }

                        ZaiInsetDivider()
                        CompactToggleRow(
                            title = "无障碍掉线自动恢复",
                            checked = settings.autoRestoreAccessibility,
                            onCheckedChange = { v -> onUpdateSettings { it.copy(autoRestoreAccessibility = v) } },
                        )

                        ZaiInsetDivider()
                        val grantCmd = "adb shell pm grant com.self.zai android.permission.WRITE_SECURE_SETTINGS"
                        CompactStatusRow(
                            dotColor = if (hasWriteSecure) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
                            title = "ADB 永久自愈",
                            statusText = if (hasWriteSecure) "已授权" else "未授权",
                            actionLabel = if (hasWriteSecure) "复制" else "复制命令",
                            onAction = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("Zai ADB", grantCmd))
                                Toast.makeText(context, "已复制命令", Toast.LENGTH_SHORT).show()
                            },
                        )

                        ZaiInsetDivider()
                        val shizukuColor = when {
                            shizukuStatus.isReady -> MaterialTheme.colorScheme.secondary
                            shizukuStatus.binderAlive -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.outline
                        }
                        CompactStatusRow(
                            dotColor = shizukuColor,
                            title = "Shizuku（可选）",
                            statusText = when {
                                shizukuStatus.isReady -> shizukuStatus.identityLabel
                                shizukuStatus.binderAlive -> "待授权"
                                else -> "未运行"
                            },
                            actionLabel = when {
                                shizukuStatus.isReady -> "测试"
                                shizukuStatus.binderAlive -> "授权"
                                else -> "打开"
                            },
                            onAction = {
                                when {
                                    shizukuStatus.isReady -> scope.launch {
                                        val res = onTestShizuku()
                                        val msg = res.fold(
                                            onSuccess = { "正常：$it" },
                                            onFailure = { "失败：${it.message}" },
                                        )
                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                    }
                                    shizukuStatus.binderAlive -> onRequestShizuku()
                                    else -> DevicePermissionHelper.openShizukuApp(context)
                                }
                            },
                        )
                    }
                }
            }

            // 5. Appearance
            ZaiPaperGroup(title = "外观") {
                Box(modifier = Modifier.padding(14.dp)) {
                    ZaiSegmentedControl(
                        options = listOf(
                            ThemeMode.SYSTEM to "跟随系统",
                            ThemeMode.LIGHT to "浅色",
                            ThemeMode.DARK to "深色",
                            ThemeMode.AMOLED to "纯黑",
                        ),
                        selected = settings.themeMode,
                        onSelect = { mode -> onUpdateSettings { it.copy(themeMode = mode) } },
                    )
                }
            }

            // 6. System Permissions & Keep-alive (collapsible to stay airy)
            ZaiPaperGroup {
                DisclosureRow(
                    title = if (DevicePermissionHelper.isXiaomiDevice()) "小米权限与保活" else "后台权限与保活",
                    expanded = showSystemPerms,
                    onToggle = { showSystemPerms = !showSystemPerms },
                )
                AnimatedVisibility(visible = showSystemPerms) {
                    Column {
                        ZaiInsetDivider()
                        CompactStatusRow(
                            dotColor = if (batteryIgnored) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary,
                            title = "省电策略无限制",
                            actionLabel = if (batteryIgnored) "已放行" else "去设置",
                            onAction = { DevicePermissionHelper.openBatterySettings(context) },
                        )
                        ZaiInsetDivider()
                        CompactStatusRow(
                            dotColor = if (hasNotification) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
                            title = "通知权限",
                            actionLabel = if (hasNotification) "已开启" else "去开启",
                            onAction = { DevicePermissionHelper.openNotificationSettings(context) },
                        )
                        ZaiInsetDivider()
                        CompactStatusRow(
                            dotColor = if (hasOverlay) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
                            title = "悬浮窗权限",
                            actionLabel = if (hasOverlay) "已开启" else "去开启",
                            onAction = { DevicePermissionHelper.openOverlaySettings(context) },
                        )
                        if (DevicePermissionHelper.isXiaomiDevice()) {
                            ZaiInsetDivider()
                            CompactStatusRow(
                                dotColor = MaterialTheme.colorScheme.primary,
                                title = "自启动",
                                actionLabel = "检查",
                                onAction = { DevicePermissionHelper.openXiaomiAutoStart(context) },
                            )
                            ZaiInsetDivider()
                            val bgColor = when (hasXiaomiBgPopup) {
                                true -> MaterialTheme.colorScheme.secondary
                                false -> MaterialTheme.colorScheme.tertiary
                                null -> MaterialTheme.colorScheme.primary
                            }
                            CompactStatusRow(
                                dotColor = bgColor,
                                title = "后台弹出界面",
                                actionLabel = if (hasXiaomiBgPopup == true) "已开启" else "去开启",
                                onAction = { DevicePermissionHelper.openXiaomiOtherPermissions(context) },
                            )
                        }
                    }
                }
            }

            // 7. Updates & Kernel
            ZaiPaperGroup(title = "版本与更新") {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = updateStatusText,
                                style = MaterialTheme.typography.titleSmall,
                                color = if (latestRelease?.isNewer == true) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                            Text(
                                text = if (hotUpdateTag != null) {
                                    "Pi ${piVersion.ifBlank { "1.6.0" }} · 热更 $hotUpdateTag"
                                } else {
                                    "Pi ${piVersion.ifBlank { "1.6.0" }}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(
                            enabled = !updateChecking && !updateBusy,
                            onClick = {
                                val slug = repoInput.trim()
                                onUpdateSettings { it.copy(githubUpdateRepo = slug) }
                                updateChecking = true
                                updateStatusText = "检查中…"
                                scope.launch {
                                    val res = AppUpdater.checkLatestRelease(slug)
                                    updateChecking = false
                                    res.onSuccess { info ->
                                        onReleaseChecked(info)
                                        updateStatusText = if (info.isNewer) {
                                            "新版本 ${info.tagName}"
                                        } else {
                                            "已是最新 v${BuildConfig.VERSION_NAME}"
                                        }
                                    }.onFailure { err ->
                                        updateStatusText = err.message ?: "检查失败"
                                    }
                                }
                            },
                        ) {
                            Text(if (updateChecking) "检查中…" else "检查更新", fontWeight = FontWeight.Medium)
                        }
                    }

                    if (updateBusy) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            LinearProgressIndicator(
                                progress = { updateProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(3.dp)
                                    .clip(CircleShape),
                            )
                            Text(
                                text = "${(updateProgress * 100).roundToInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    val rel = latestRelease
                    if (rel != null && rel.apkDownloadUrl.isNotBlank()) {
                        val sizeMb = if (rel.apkSizeBytes > 0) {
                            String.format("%.1f MB", rel.apkSizeBytes / 1048576.0)
                        } else {
                            ""
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ZaiPrimaryButton(
                                text = if (rel.isNewer) "更新 ${rel.tagName} $sizeMb".trim()
                                else "重装 ${rel.tagName} $sizeMb".trim(),
                                enabled = !updateBusy && !updateChecking,
                                onClick = {
                                    updateBusy = true
                                    updateProgress = 0f
                                    scope.launch {
                                        val dl = AppUpdater.downloadApk(context, rel.apkDownloadUrl) { p ->
                                            updateProgress = p
                                        }
                                        updateBusy = false
                                        dl.onSuccess { file ->
                                            AppUpdater.installApk(context, file).onSuccess { launched ->
                                                if (!launched) {
                                                    Toast.makeText(context, "请允许安装未知应用后重试", Toast.LENGTH_LONG).show()
                                                }
                                            }.onFailure { err ->
                                                Toast.makeText(context, "安装失败：${err.message}", Toast.LENGTH_LONG).show()
                                            }
                                        }.onFailure { err ->
                                            updateStatusText = err.message ?: "下载失败"
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )

                            if (shizukuStatus.isReady) {
                                ZaiSecondaryButton(
                                    text = "Shizuku",
                                    enabled = !updateBusy && !updateChecking,
                                    onClick = {
                                        updateBusy = true
                                        updateProgress = 0f
                                        scope.launch {
                                            val dl = AppUpdater.downloadApk(context, rel.apkDownloadUrl) { p ->
                                                updateProgress = p
                                            }
                                            dl.onSuccess { file ->
                                                val inst = onShizukuInstallApk(file)
                                                updateBusy = false
                                                inst.onSuccess {
                                                    Toast.makeText(context, "安装完成", Toast.LENGTH_SHORT).show()
                                                }.onFailure { err ->
                                                    updateStatusText = "安装失败：${err.message}"
                                                }
                                            }.onFailure { err ->
                                                updateBusy = false
                                                updateStatusText = err.message ?: "下载失败"
                                            }
                                        }
                                    },
                                )
                            }
                        }

                        if (rel.piHotDownloadUrl.isNotBlank()) {
                            ZaiSecondaryButton(
                                text = "热更新 Pi 内核 (${rel.tagName})",
                                enabled = !updateBusy && !updateChecking,
                                onClick = {
                                    updateBusy = true
                                    updateProgress = 0f
                                    scope.launch {
                                        val res = AppUpdater.applyPiHotUpdate(
                                            context = context,
                                            downloadUrl = rel.piHotDownloadUrl,
                                            tagName = rel.tagName,
                                            onProgress = { p -> updateProgress = p },
                                        )
                                        updateBusy = false
                                        res.onSuccess { tag ->
                                            onReloadPiHotUpdate()
                                            updateStatusText = "内核已热更 $tag"
                                            Toast.makeText(context, "Pi 热更新已生效", Toast.LENGTH_SHORT).show()
                                        }.onFailure { err ->
                                            updateStatusText = err.message ?: "热更新失败"
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (showRepoEditor) "收起更新源" else "更新源：${settings.githubUpdateRepo}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { showRepoEditor = !showRepoEditor }
                                .padding(vertical = 4.dp),
                        )
                        if (hotUpdateTag != null) {
                            Text(
                                text = "重置内核",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable {
                                        onResetPiHotUpdate()
                                        Toast.makeText(context, "已恢复内置内核", Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(vertical = 4.dp),
                            )
                        }
                    }

                    AnimatedVisibility(visible = showRepoEditor) {
                        ZaiTextField(
                            value = repoInput,
                            onValueChange = {
                                repoInput = it
                                onUpdateSettings { s -> s.copy(githubUpdateRepo = it.trim()) }
                            },
                            placeholder = "haaharden/Zai",
                            monospace = true,
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
        }
    }

    if (showDefaultModelSheet) {
        ZaiModelPickerSheet(
            title = "选择默认模型",
            providers = providers,
            selectedModel = defaultModel,
            providerQuotas = providerQuotas,
            onRefreshQuota = onRefreshQuota,
            onSelect = onSelectDefaultModel,
            onManageProviders = onOpenModels,
            onDismiss = { showDefaultModelSheet = false },
        )
    }

    if (showUserPromptSheet) {
        UserPromptEditorSheet(
            initialPrompt = settings.userSystemPrompt,
            onSave = onUpdateUserPrompt,
            onDismiss = { showUserPromptSheet = false },
        )
    }
}

@Composable
private fun CompactNavRow(
    title: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun CompactStatusRow(
    dotColor: Color,
    title: String,
    statusText: String? = null,
    actionLabel: String,
    onRowClick: (() -> Unit)? = null,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onRowClick ?: onAction)
            .padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.5.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (!statusText.isNullOrBlank()) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
        }
        TextButton(onClick = onAction) {
            Text(actionLabel, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun CompactToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = zaiSwitchColors(),
        )
    }
}

@Composable
private fun DisclosureRow(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(
            if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}
