package com.self.zai.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontFamily
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
import com.self.zai.model.ThemeMode
import com.self.zai.permission.DevicePermissionHelper
import com.self.zai.shell.ShizukuStatus
import com.self.zai.update.AppUpdater
import com.self.zai.update.ReleaseUpdateInfo
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    activeModel: ModelOption?,
    piVersion: String,
    hotUpdateTag: String?,
    latestRelease: ReleaseUpdateInfo?,
    shizukuStatus: ShizukuStatus,
    accessibilityConnected: Boolean,
    onReleaseChecked: (ReleaseUpdateInfo?) -> Unit,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
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
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            // 1. Model & Account
            SettingsGroup(title = "模型与账号") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenModels)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "ChatGPT 与自定义接口",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = activeModel?.let { "当前使用 ${it.providerName} / ${it.id}" } ?: "尚未配置模型",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "推理思考强度",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("off" to "关闭", "low" to "低", "medium" to "中", "high" to "高").forEach { (key, label) ->
                            FilterChip(
                                selected = settings.thinkingLevel == key,
                                onClick = { onUpdateSettings { it.copy(thinkingLevel = key) } },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }

            // 2. Phone Control Channels (Accessibility Primary, Optional Shizuku)
            SettingsGroup(title = "手机操控通道") {
                // Accessibility primary row (no Developer Options or Shizuku required)
                val a11yColor = when {
                    accessibilityConnected -> MaterialTheme.colorScheme.secondary
                    a11ySettingOn -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.outline
                }
                val a11ySubtitle = when {
                    accessibilityConnected -> "服务已真实绑定，无需开发者选项或 Shizuku 即可直接控手机"
                    a11ySettingOn -> "系统开关虽开但服务已脱绑，可一键恢复或在系统设置重开一次"
                    else -> "开启「Zai 手机操控」即可读屏、点击、滑动和输入文字"
                }
                val canAutoRepair = !accessibilityConnected && (hasWriteSecure || shizukuStatus.isReady)
                StatusActionRow(
                    dotColor = a11yColor,
                    title = "无障碍服务（主力通道）",
                    subtitle = a11ySubtitle,
                    actionLabel = if (canAutoRepair) "一键恢复" else "去设置",
                    onAction = {
                        if (canAutoRepair) {
                            scope.launch {
                                val res = onRepairAccessibility()
                                val msg = res.fold(
                                    onSuccess = { "已自动重新激活无障碍服务" },
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

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

                // Permanent zero-Shizuku self-healing permission row
                val grantCmd = "adb shell pm grant com.self.zai android.permission.WRITE_SECURE_SETTINGS"
                StatusActionRow(
                    dotColor = if (hasWriteSecure) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
                    title = "无障碍永久自愈（可选，免 Shizuku）",
                    subtitle = if (hasWriteSecure) {
                        "已授予 WRITE_SECURE_SETTINGS，关掉开发者选项与重启手机后依然可自动拉起无障碍"
                    } else {
                        "若系统偶发关闭无障碍，用电脑执行一次 ADB 授权即可永久自愈，授完即可关闭开发者选项"
                    },
                    actionLabel = if (hasWriteSecure) "已就绪" else "复制命令",
                    onAction = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Zai ADB", grantCmd))
                        Toast.makeText(context, "已复制授权命令，在电脑终端执行一次即可", Toast.LENGTH_LONG).show()
                    },
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

                // Optional Shizuku row
                val shizukuColor = when {
                    shizukuStatus.isReady -> MaterialTheme.colorScheme.secondary
                    shizukuStatus.binderAlive -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.outline
                }
                val shizukuSubtitle = when {
                    shizukuStatus.isReady -> "已连接 ${shizukuStatus.identityLabel}，可作为备用点击/截屏通道"
                    shizukuStatus.binderAlive && !shizukuStatus.permissionGranted -> "检测到 Shizuku 正在运行，可点右侧授权"
                    else -> "非必需：不装 Shizuku、关掉开发者选项也能完整操控手机"
                }
                StatusActionRow(
                    dotColor = shizukuColor,
                    title = "Shizuku 增强通道（可选）",
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
                                    onSuccess = { "Shizuku 响应正常：$it" },
                                    onFailure = { "测试失败：${it.message}" },
                                )
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                            shizukuStatus.binderAlive -> onRequestShizuku()
                            else -> DevicePermissionHelper.openShizukuApp(context)
                        }
                    },
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("操控模式", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = settings.channelMode == ControlChannelMode.ACCESSIBILITY_ONLY,
                            onClick = { onUpdateSettings { it.copy(channelMode = ControlChannelMode.ACCESSIBILITY_ONLY) } },
                            label = { Text("无障碍模式（免开发者选项）") },
                        )
                        FilterChip(
                            selected = settings.channelMode == ControlChannelMode.SHIZUKU_FIRST,
                            onClick = { onUpdateSettings { it.copy(channelMode = ControlChannelMode.SHIZUKU_FIRST) } },
                            label = { Text("Shizuku 优先（可选）") },
                        )
                    }

                    Text("读屏与视觉策略", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = settings.perceptionMode == PerceptionMode.HYBRID,
                            onClick = { onUpdateSettings { it.copy(perceptionMode = PerceptionMode.HYBRID) } },
                            label = { Text("智能混合") },
                        )
                        FilterChip(
                            selected = settings.perceptionMode == PerceptionMode.TREE_ONLY,
                            onClick = { onUpdateSettings { it.copy(perceptionMode = PerceptionMode.TREE_ONLY) } },
                            label = { Text("纯控件树") },
                        )
                        FilterChip(
                            selected = settings.perceptionMode == PerceptionMode.ALWAYS_VISION,
                            onClick = { onUpdateSettings { it.copy(perceptionMode = PerceptionMode.ALWAYS_VISION) } },
                            label = { Text("总是看标号图") },
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

                ToggleRow(
                    title = "无障碍掉线自动恢复",
                    subtitle = "已授予 WRITE_SECURE_SETTINGS 或连接 Shizuku 时，掉线自动拉起",
                    checked = settings.autoRestoreAccessibility,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(autoRestoreAccessibility = v) } },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                ToggleRow(
                    title = "操控时显示顶部提示胶囊",
                    subtitle = "在屏幕顶部显示当前步骤与停止按钮（已开无障碍时免悬浮窗权限，截屏自动避让）",
                    checked = settings.showFloatingCapsule,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(showFloatingCapsule = v) } },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                ToggleRow(
                    title = "操控时显示屏幕边缘光边与点击反馈",
                    subtitle = "操控期间屏幕四周亮起呼吸光边、保持常亮，并在点击落点闪现定位光圈",
                    checked = settings.showEdgeGlow,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(showEdgeGlow = v) } },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                ToggleRow(
                    title = "操控完成后自动返回 Zai",
                    subtitle = "任务结束后自动切回 Zai 查看结果（被系统拦截时发完成通知提醒）",
                    checked = settings.returnToZaiOnFinish,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(returnToZaiOnFinish = v) } },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                ToggleRow(
                    title = "敏感操作前弹窗确认",
                    subtitle = "遇到支付、转账、卸载和密码框时先暂停询问你",
                    checked = settings.confirmSensitiveActions,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(confirmSensitiveActions = v) } },
                )
            }

            // 3. Keep-alive & Xiaomi HyperOS permissions
            SettingsGroup(
                title = if (DevicePermissionHelper.isXiaomiDevice()) "小米保活与系统权限" else "后台保活与权限",
            ) {
                StatusActionRow(
                    dotColor = if (hasOverlay) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
                    title = "显示悬浮窗",
                    subtitle = "用于在操作其他应用时显示悬浮停止胶囊",
                    actionLabel = if (hasOverlay) "已开启" else "去开启",
                    onAction = { DevicePermissionHelper.openOverlaySettings(context) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                StatusActionRow(
                    dotColor = if (hasNotification) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
                    title = "通知权限",
                    subtitle = "任务执行时保持前台服务运行并提供通知栏停止按钮",
                    actionLabel = if (hasNotification) "已开启" else "去开启",
                    onAction = { DevicePermissionHelper.openNotificationSettings(context) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                StatusActionRow(
                    dotColor = if (batteryIgnored) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary,
                    title = "省电策略：无限制",
                    subtitle = "防止切到目标应用后系统冻结 Zai 导致任务中断",
                    actionLabel = if (batteryIgnored) "已放行" else "去设置",
                    onAction = { DevicePermissionHelper.openBatterySettings(context) },
                )
                if (DevicePermissionHelper.isXiaomiDevice()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                    StatusActionRow(
                        dotColor = MaterialTheme.colorScheme.primary,
                        title = "小米自启动",
                        subtitle = "系统私有项，建议开启防止后台进程被澎湃系统回收",
                        actionLabel = "去设置",
                        onAction = { DevicePermissionHelper.openXiaomiAutoStart(context) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                    StatusActionRow(
                        dotColor = MaterialTheme.colorScheme.primary,
                        title = "小米后台弹出界面",
                        subtitle = "在「其他权限」中勾选，改善从后台返回或启动应用体验",
                        actionLabel = "去设置",
                        onAction = { DevicePermissionHelper.openXiaomiOtherPermissions(context) },
                    )
                }
            }

            // 4. Appearance
            SettingsGroup(title = "外观") {
                FlowRow(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        ThemeMode.SYSTEM to "跟随系统",
                        ThemeMode.LIGHT to "浅色",
                        ThemeMode.DARK to "深色",
                        ThemeMode.AMOLED to "纯黑 OLED",
                    ).forEach { (mode, label) ->
                        FilterChip(
                            selected = settings.themeMode == mode,
                            onClick = { onUpdateSettings { it.copy(themeMode = mode) } },
                            label = { Text(label) },
                        )
                    }
                }
            }

            // 5. GitHub Releases In-App Update & Pi Kernel Hot-Update
            SettingsGroup(title = "应用更新与内核热更") {
                Column(
                    modifier = Modifier.padding(16.dp),
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
                                    "Pi 内核 ${piVersion.ifBlank { "1.5.1" }}（热更新 $hotUpdateTag）"
                                } else {
                                    "Pi 内核 ${piVersion.ifBlank { "1.5.1" }}（随包内置）"
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
                                updateStatusText = "正在检查 GitHub Release…"
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

                    OutlinedTextField(
                        value = repoInput,
                        onValueChange = {
                            repoInput = it
                            onUpdateSettings { s -> s.copy(githubUpdateRepo = it.trim()) }
                        },
                        label = { Text("GitHub 仓库 (owner/repo)") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (updateBusy) {
                        LinearProgressIndicator(
                            progress = { updateProgress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = "下载进度 ${(updateProgress * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    val rel = latestRelease
                    if (rel != null) {
                        if (rel.releaseNotes.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = rel.releaseNotes.take(400),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(12.dp),
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
                                Button(
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
                                                updateStatusText = err.message ?: "下载 APK 失败"
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(
                                        if (rel.isNewer) "下载并更新 ${rel.tagName} $sizeMb".trim()
                                        else "重装 ${rel.tagName} $sizeMb".trim(),
                                    )
                                }

                                if (shizukuStatus.isReady) {
                                    OutlinedButton(
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
                                                        updateStatusText = "Shizuku 安装失败，请改用普通更新：${err.message}"
                                                    }
                                                }.onFailure { err ->
                                                    updateBusy = false
                                                    updateStatusText = err.message ?: "下载失败"
                                                }
                                            }
                                        },
                                    ) {
                                        Text("Shizuku 装")
                                    }
                                }
                            }
                        }

                        if (rel.piHotDownloadUrl.isNotBlank()) {
                            OutlinedButton(
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
                                            updateStatusText = "Pi 内核已热更新至 $tag（无需重装 APK）"
                                            Toast.makeText(context, "Pi 内核热更新生效", Toast.LENGTH_SHORT).show()
                                        }.onFailure { err ->
                                            updateStatusText = err.message ?: "内核热更新失败"
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("免重装热更新 Pi 内核 (${rel.tagName})")
                            }
                        }
                    }

                    if (hotUpdateTag != null) {
                        TextButton(
                            onClick = {
                                onResetPiHotUpdate()
                                Toast.makeText(context, "已恢复为随包内置 Pi 内核", Toast.LENGTH_SHORT).show()
                            },
                        ) {
                            Text("恢复随包内置内核", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            Text(
                text = "Zai v${BuildConfig.VERSION_NAME}  Pi ${piVersion.ifBlank { "1.5.1" }}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                content()
            }
        }
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
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
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
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
