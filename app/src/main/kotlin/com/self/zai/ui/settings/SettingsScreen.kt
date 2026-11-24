package com.self.zai.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.self.zai.BuildConfig
import com.self.zai.model.AppSettings
import com.self.zai.model.ControlChannelMode
import com.self.zai.model.ModelOption
import com.self.zai.model.PerceptionMode
import com.self.zai.model.ProviderStatus
import com.self.zai.model.ThemeMode
import com.self.zai.model.ThinkingLevelDisplay
import com.self.zai.permission.DevicePermissionHelper
import com.self.zai.shell.ShizukuStatus
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
    defaultModel: ModelOption?,
    defaultThinkingLevel: String,
    piVersion: String,
    hotUpdateTag: String?,
    latestRelease: ReleaseUpdateInfo?,
    shizukuStatus: ShizukuStatus,
    accessibilityConnected: Boolean,
    onReleaseChecked: (ReleaseUpdateInfo?) -> Unit,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onSelectDefaultModel: (providerId: String, modelId: String) -> Unit,
    onSelectDefaultThinking: (String) -> Unit,
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
    var showDefaultModelSheet by remember { mutableStateOf(false) }

    var repoInput by remember(settings.githubUpdateRepo) { mutableStateOf(settings.githubUpdateRepo) }
    var updateChecking by remember { mutableStateOf(false) }
    var updateBusy by remember { mutableStateOf(false) }
    var updateProgress by remember { mutableFloatStateOf(0f) }
    var updateStatusText by remember(latestRelease) {
        mutableStateOf(
            when {
                latestRelease == null -> "当前版本 v${BuildConfig.VERSION_NAME}"
                latestRelease.isNewer -> "发现新版本 ${latestRelease.tagName}"
                else -> "已是最新版本 v${BuildConfig.VERSION_NAME}"
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
                .padding(horizontal = 20.dp, vertical = 10.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // 1. Default Model & Dynamic Reasoning Effort
            ZaiPaperGroup(title = "默认模型与推理") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenModels)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "账号与供应商管理",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(2.dp))
                        val connectedCount = providers.count { it.isConnected && it.models.isNotEmpty() }
                        Text(
                            text = if (connectedCount > 0) {
                                "已连接 $connectedCount 个渠道，管理 ChatGPT / DeepSeek / OpenRouter"
                            } else {
                                "登录 ChatGPT 或配置 DeepSeek、OpenRouter 与自定义接口"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }

                ZaiInsetDivider()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val hasAny = providers.any { it.isConnected && it.models.isNotEmpty() }
                            if (hasAny) showDefaultModelSheet = true else onOpenModels()
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "默认模型",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = defaultModel?.let { "${it.providerName}  ${it.id}" } ?: "尚未配置模型",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }

                ZaiInsetDivider()

                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "默认思考强度",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (defaultModel != null && defaultModel.supportsThinkingSelection) {
                        Text(
                            text = "以下为 ${defaultModel.id} 实际支持的思考档位，新建对话默认继承",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    } else {
                        Text(
                            text = if (defaultModel == null) {
                                "配置默认模型后将按模型实际能力显示可选档位"
                            } else {
                                "当前默认模型 ${defaultModel.id} 不支持调节思考强度"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // 2. Phone Control
            ZaiPaperGroup(title = "手机操控") {
                val a11yColor = when {
                    accessibilityConnected -> MaterialTheme.colorScheme.secondary
                    a11ySettingOn -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.outline
                }
                val a11ySubtitle = when {
                    accessibilityConnected -> "已真实绑定，无需开发者选项或 Shizuku 即可直接操控"
                    a11ySettingOn -> "系统开关开启但服务已脱绑，可一键恢复或重开开关"
                    else -> "开启「Zai 手机操控」即可读屏、点按、滑动与输入"
                }
                val canAutoRepair = !accessibilityConnected && (hasWriteSecure || shizukuStatus.isReady)
                StatusActionRow(
                    dotColor = a11yColor,
                    title = "无障碍服务",
                    subtitle = a11ySubtitle,
                    actionLabel = if (canAutoRepair) "一键恢复" else "去设置",
                    onAction = {
                        if (canAutoRepair) {
                            scope.launch {
                                val res = onRepairAccessibility()
                                val msg = res.fold(
                                    onSuccess = { "已重新激活无障碍服务" },
                                    onFailure = { "自动恢复失败，请手动开启：${it.message}" },
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

                val grantCmd = "adb shell pm grant com.self.zai android.permission.WRITE_SECURE_SETTINGS"
                StatusActionRow(
                    dotColor = if (hasWriteSecure) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
                    title = "免 Shizuku 永久自愈（可选）",
                    subtitle = if (hasWriteSecure) {
                        "已授权，关闭开发者选项与重启手机后依然可自动拉起无障碍"
                    } else {
                        "电脑执行一次 ADB 授权即可永久生效，授完即可关闭开发者选项"
                    },
                    actionLabel = if (hasWriteSecure) "已就绪" else "复制命令",
                    onAction = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Zai ADB", grantCmd))
                        Toast.makeText(context, "已复制授权命令", Toast.LENGTH_SHORT).show()
                    },
                )

                ZaiInsetDivider()

                val shizukuColor = when {
                    shizukuStatus.isReady -> MaterialTheme.colorScheme.secondary
                    shizukuStatus.binderAlive -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.outline
                }
                val shizukuSubtitle = when {
                    shizukuStatus.isReady -> "已连接 ${shizukuStatus.identityLabel}，可作为备用点击通道"
                    shizukuStatus.binderAlive && !shizukuStatus.permissionGranted -> "检测到 Shizuku 正在运行，点右侧授权"
                    else -> "非必需，不装也能完整操控手机"
                }
                StatusActionRow(
                    dotColor = shizukuColor,
                    title = "Shizuku 通道（可选）",
                    subtitle = shizukuSubtitle,
                    actionLabel = when {
                        shizukuStatus.isReady -> "测试"
                        shizukuStatus.binderAlive -> "授权"
                        else -> "了解"
                    },
                    onAction = {
                        when {
                            shizukuStatus.isReady -> scope.launch {
                                val res = onTestShizuku()
                                val msg = res.fold(
                                    onSuccess = { "Shizuku 正常：$it" },
                                    onFailure = { "测试失败：${it.message}" },
                                )
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                            shizukuStatus.binderAlive -> onRequestShizuku()
                            else -> DevicePermissionHelper.openShizukuApp(context)
                        }
                    },
                )

                ZaiInsetDivider()

                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("执行模式", style = MaterialTheme.typography.titleSmall)
                        ZaiSegmentedControl(
                            options = listOf(
                                ControlChannelMode.ACCESSIBILITY_ONLY to "无障碍模式",
                                ControlChannelMode.SHIZUKU_FIRST to "Shizuku 优先",
                            ),
                            selected = settings.channelMode,
                            onSelect = { m -> onUpdateSettings { it.copy(channelMode = m) } },
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("读屏与视觉策略", style = MaterialTheme.typography.titleSmall)
                        ZaiSegmentedControl(
                            options = listOf(
                                PerceptionMode.HYBRID to "智能混合",
                                PerceptionMode.TREE_ONLY to "纯控件树",
                                PerceptionMode.ALWAYS_VISION to "总是看图",
                            ),
                            selected = settings.perceptionMode,
                            onSelect = { m -> onUpdateSettings { it.copy(perceptionMode = m) } },
                        )
                    }
                }

                ZaiInsetDivider()
                ToggleRow(
                    title = "操控时显示顶部提示胶囊",
                    subtitle = "已开无障碍时免悬浮窗权限，截屏自动避让",
                    checked = settings.showFloatingCapsule,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(showFloatingCapsule = v) } },
                )
                ZaiInsetDivider()
                ToggleRow(
                    title = "屏幕呼吸光边与点击落点",
                    subtitle = "操控期间屏幕边缘亮起柔光、保持常亮并提示点击位置",
                    checked = settings.showEdgeGlow,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(showEdgeGlow = v) } },
                )
                ZaiInsetDivider()
                ToggleRow(
                    title = "操控完成后自动返回 Zai",
                    subtitle = "任务结束后自动切回 Zai 查看结果",
                    checked = settings.returnToZaiOnFinish,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(returnToZaiOnFinish = v) } },
                )
                ZaiInsetDivider()
                ToggleRow(
                    title = "无障碍掉线自动恢复",
                    subtitle = "已授永久自愈或连着 Shizuku 时自动拉起无障碍",
                    checked = settings.autoRestoreAccessibility,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(autoRestoreAccessibility = v) } },
                )
                ZaiInsetDivider()
                ToggleRow(
                    title = "敏感操作前弹窗确认",
                    subtitle = "涉及支付、转账、卸载和密码框时先暂停询问",
                    checked = settings.confirmSensitiveActions,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(confirmSensitiveActions = v) } },
                )
            }

            // 3. Keep-alive & Xiaomi Permissions
            ZaiPaperGroup(
                title = if (DevicePermissionHelper.isXiaomiDevice()) "小米保活与系统权限" else "后台保活与权限",
            ) {
                StatusActionRow(
                    dotColor = if (batteryIgnored) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary,
                    title = "省电策略：无限制",
                    subtitle = "防止切到目标应用后系统冻结 Zai",
                    actionLabel = if (batteryIgnored) "已放行" else "去设置",
                    onAction = { DevicePermissionHelper.openBatterySettings(context) },
                )
                ZaiInsetDivider()
                StatusActionRow(
                    dotColor = if (hasNotification) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
                    title = "通知权限",
                    subtitle = "用于前台任务保活与完成提醒",
                    actionLabel = if (hasNotification) "已开启" else "去开启",
                    onAction = { DevicePermissionHelper.openNotificationSettings(context) },
                )
                ZaiInsetDivider()
                StatusActionRow(
                    dotColor = if (hasOverlay) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
                    title = "显示悬浮窗（可选）",
                    subtitle = "无障碍未开启时的备用悬浮胶囊权限",
                    actionLabel = if (hasOverlay) "已开启" else "去开启",
                    onAction = { DevicePermissionHelper.openOverlaySettings(context) },
                )
                if (DevicePermissionHelper.isXiaomiDevice()) {
                    ZaiInsetDivider()
                    StatusActionRow(
                        dotColor = MaterialTheme.colorScheme.primary,
                        title = "小米自启动",
                        subtitle = "建议开启，防止澎湃系统回收后台服务",
                        actionLabel = "去检查",
                        onAction = { DevicePermissionHelper.openXiaomiAutoStart(context) },
                    )
                    ZaiInsetDivider()
                    val bgPopupColor = when (hasXiaomiBgPopup) {
                        true -> MaterialTheme.colorScheme.secondary
                        false -> MaterialTheme.colorScheme.tertiary
                        null -> MaterialTheme.colorScheme.primary
                    }
                    val bgPopupSubtitle = when (hasXiaomiBgPopup) {
                        true -> "已开启，操控完成后可自动切回 Zai"
                        false -> "未开启，请在「其他权限」允许以实现自动切回 Zai"
                        null -> "在「其他权限」中勾选，让操控完自动返回更稳定"
                    }
                    StatusActionRow(
                        dotColor = bgPopupColor,
                        title = "小米后台弹出界面",
                        subtitle = bgPopupSubtitle,
                        actionLabel = if (hasXiaomiBgPopup == true) "已开启" else "去开启",
                        onAction = { DevicePermissionHelper.openXiaomiOtherPermissions(context) },
                    )
                }
            }

            // 4. Appearance
            ZaiPaperGroup(title = "外观") {
                Box(modifier = Modifier.padding(16.dp)) {
                    ZaiSegmentedControl(
                        options = listOf(
                            ThemeMode.SYSTEM to "跟随系统",
                            ThemeMode.LIGHT to "浅色",
                            ThemeMode.DARK to "深色",
                            ThemeMode.AMOLED to "纯黑 OLED",
                        ),
                        selected = settings.themeMode,
                        onSelect = { mode -> onUpdateSettings { it.copy(themeMode = mode) } },
                    )
                }
            }

            // 5. GitHub Releases Updater & Pi Hot-Update
            ZaiPaperGroup(title = "应用更新与内核热更") {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
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
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = if (hotUpdateTag != null) {
                                    "Pi 内核 ${piVersion.ifBlank { "1.6.0" }}（热更新 $hotUpdateTag）"
                                } else {
                                    "Pi 内核 ${piVersion.ifBlank { "1.6.0" }}（随包内置）"
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
                                updateStatusText = "正在检查更新…"
                                scope.launch {
                                    val res = AppUpdater.checkLatestRelease(slug)
                                    updateChecking = false
                                    res.onSuccess { info ->
                                        onReleaseChecked(info)
                                        updateStatusText = if (info.isNewer) {
                                            "发现新版本 ${info.tagName}"
                                        } else {
                                            "已是最新版本 v${BuildConfig.VERSION_NAME}（最新 ${info.tagName}）"
                                        }
                                    }.onFailure { err ->
                                        updateStatusText = err.message ?: "检查更新失败"
                                    }
                                }
                            },
                        ) {
                            Text(if (updateChecking) "检查中…" else "检查更新", fontWeight = FontWeight.Medium)
                        }
                    }

                    ZaiTextField(
                        value = repoInput,
                        onValueChange = {
                            repoInput = it
                            onUpdateSettings { s -> s.copy(githubUpdateRepo = it.trim()) }
                        },
                        label = "GitHub 仓库",
                        placeholder = "haaharden/Zai",
                        monospace = true,
                    )

                    if (updateBusy) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            LinearProgressIndicator(
                                progress = { updateProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(3.dp)
                                    .clip(CircleShape),
                            )
                            Text(
                                text = "下载进度 ${(updateProgress * 100).roundToInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    val rel = latestRelease
                    if (rel != null) {
                        if (rel.releaseNotes.isNotBlank()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                    .padding(12.dp),
                            ) {
                                Text(
                                    text = rel.releaseNotes.take(400),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        if (rel.apkDownloadUrl.isNotBlank()) {
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
                                    text = if (rel.isNewer) "下载并更新 ${rel.tagName} $sizeMb".trim()
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
                                                        Toast.makeText(
                                                            context,
                                                            "请先允许「安装未知应用」，再点一次安装",
                                                            Toast.LENGTH_LONG,
                                                        ).show()
                                                    }
                                                }.onFailure { err ->
                                                    Toast.makeText(context, "拉起安装器失败：${err.message}", Toast.LENGTH_LONG).show()
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
                                        text = "Shizuku 装",
                                        enabled = !updateBusy && !updateChecking,
                                        onClick = {
                                            updateBusy = true
                                            updateProgress = 0f
                                            scope.launch {
                                                val dl = AppUpdater.downloadApk(context, rel.apkDownloadUrl) { p ->
                                                    updateProgress = p
                                                }
                                                dl.onSuccess { file ->
                                                    updateStatusText = "正在通过 Shizuku 安装…"
                                                    val inst = onShizukuInstallApk(file)
                                                    updateBusy = false
                                                    inst.onSuccess {
                                                        Toast.makeText(context, "Shizuku 安装完成", Toast.LENGTH_SHORT).show()
                                                    }.onFailure { err ->
                                                        updateStatusText = "Shizuku 安装失败：${err.message}"
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
                        }

                        if (rel.piHotDownloadUrl.isNotBlank()) {
                            ZaiSecondaryButton(
                                text = "免重装热更新 Pi 内核 (${rel.tagName})",
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
                                            updateStatusText = "Pi 内核已热更新至 $tag"
                                            Toast.makeText(context, "Pi 内核热更新生效", Toast.LENGTH_SHORT).show()
                                        }.onFailure { err ->
                                            updateStatusText = err.message ?: "内核热更新失败"
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    if (hotUpdateTag != null) {
                        TextButton(
                            onClick = {
                                onResetPiHotUpdate()
                                Toast.makeText(context, "已恢复随包内置内核", Toast.LENGTH_SHORT).show()
                            },
                        ) {
                            Text("恢复随包内置内核", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            Text(
                text = "Zai v${BuildConfig.VERSION_NAME}  Pi ${piVersion.ifBlank { "1.6.0" }}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showDefaultModelSheet) {
        ZaiModelPickerSheet(
            title = "选择默认模型",
            providers = providers,
            selectedModel = defaultModel,
            onSelect = onSelectDefaultModel,
            onManageProviders = onOpenModels,
            onDismiss = { showDefaultModelSheet = false },
        )
    }
}

@Composable
private fun StatusActionRow(
    dotColor: Color,
    title: String,
    subtitle: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onAction)
            .padding(start = 16.dp, end = 10.dp, top = 13.dp, bottom = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onAction) {
            Text(actionLabel, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = zaiSwitchColors(),
        )
    }
}
