package com.self.zai.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.self.zai.model.ControlChannelMode
import com.self.zai.model.PerceptionMode
import com.self.zai.model.ThemeMode
import com.self.zai.permission.DevicePermissionHelper
import com.self.zai.ui.components.ZaiInsetDivider
import com.self.zai.ui.components.ZaiPaperGroup
import com.self.zai.ui.components.ZaiSegmentedControl
import kotlinx.coroutines.launch

@Composable
internal fun PhoneControlSection(
    state: SettingsUiState,
    perms: DevicePermissionsSnapshot,
    actions: SettingsActions,
    repairingA11y: Boolean,
    onSetRepairingA11y: (Boolean) -> Unit,
    onRefreshPermissions: () -> Unit,
    onOpenSelfCheckSheet: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = state.settings
    var showAdvancedControl by remember { mutableStateOf(false) }

    ZaiPaperGroup(title = "手机操控") {
        val modelReady = state.piReady && state.defaultModel != null
        val controlReady = state.accessibilityConnected || state.shizukuStatus.isReady
        CompactNavRow(
            title = "首次配置自检与试跑",
            value = if (modelReady && controlReady) "可试跑" else "待检查",
            onClick = onOpenSelfCheckSheet,
        )

        ZaiInsetDivider()

        val a11yColor = when {
            state.accessibilityConnected -> MaterialTheme.colorScheme.secondary
            perms.a11ySettingOn -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.outline
        }
        val canAutoRepair = !state.accessibilityConnected && (perms.hasWriteSecure || state.shizukuStatus.isReady)
        CompactStatusRow(
            dotColor = a11yColor,
            title = "无障碍服务",
            statusText = when {
                repairingA11y -> "恢复中…"
                state.accessibilityConnected -> "已连接"
                perms.a11ySettingOn -> "已脱绑"
                else -> "未开启"
            },
            actionLabel = when {
                repairingA11y -> "恢复中"
                canAutoRepair -> "恢复"
                state.accessibilityConnected -> "管理"
                else -> "去开启"
            },
            onAction = {
                if (!repairingA11y) {
                    if (canAutoRepair) {
                        onSetRepairingA11y(true)
                        scope.launch {
                            val res = actions.onRepairAccessibility()
                            onSetRepairingA11y(false)
                            onRefreshPermissions()
                            val msg = res.fold(
                                onSuccess = { "已恢复无障碍" },
                                onFailure = { "恢复失败：${it.message}" },
                            )
                            Toast.makeText(
                                context,
                                msg,
                                if (res.isFailure) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
                            ).show()
                            if (res.isFailure) {
                                DevicePermissionHelper.openAccessibilitySettings(
                                    context = context,
                                    isCurrentlyConnected = state.accessibilityConnected,
                                    showGuideToast = false,
                                )
                            }
                        }
                    } else {
                        DevicePermissionHelper.openAccessibilitySettings(
                            context = context,
                            isCurrentlyConnected = state.accessibilityConnected,
                        )
                    }
                }
            },
        )

        ZaiInsetDivider()
        CompactToggleRow(
            title = "操控提示胶囊",
            checked = settings.showFloatingCapsule,
            onCheckedChange = { v -> actions.onUpdateSettings { it.copy(showFloatingCapsule = v) } },
        )
        ZaiInsetDivider()
        CompactToggleRow(
            title = "屏幕呼吸光边",
            checked = settings.showEdgeGlow,
            onCheckedChange = { v -> actions.onUpdateSettings { it.copy(showEdgeGlow = v) } },
        )
        ZaiInsetDivider()
        CompactToggleRow(
            title = "完成后自动返回",
            checked = settings.returnToZaiOnFinish,
            onCheckedChange = { v -> actions.onUpdateSettings { it.copy(returnToZaiOnFinish = v) } },
        )
        ZaiInsetDivider()
        CompactToggleRow(
            title = "敏感操作前确认",
            checked = settings.confirmSensitiveActions,
            onCheckedChange = { v -> actions.onUpdateSettings { it.copy(confirmSensitiveActions = v) } },
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
                            onSelect = { m -> actions.onUpdateSettings { it.copy(channelMode = m) } },
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
                            onSelect = { m -> actions.onUpdateSettings { it.copy(perceptionMode = m) } },
                        )
                    }
                }

                ZaiInsetDivider()
                CompactToggleRow(
                    title = "无障碍掉线自动恢复",
                    checked = settings.autoRestoreAccessibility,
                    onCheckedChange = { v -> actions.onUpdateSettings { it.copy(autoRestoreAccessibility = v) } },
                )

                ZaiInsetDivider()
                val grantCmd = "adb shell pm grant com.self.zai android.permission.WRITE_SECURE_SETTINGS"
                val canGrantViaShizuku = !perms.hasWriteSecure && state.shizukuStatus.isReady
                CompactStatusRow(
                    dotColor = if (perms.hasWriteSecure) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
                    title = "ADB 永久自愈",
                    statusText = if (perms.hasWriteSecure) "已授权" else "未授权",
                    actionLabel = when {
                        perms.hasWriteSecure -> "复制"
                        canGrantViaShizuku -> "Shizuku 授权"
                        else -> "复制命令"
                    },
                    onAction = {
                        if (canGrantViaShizuku) {
                            scope.launch {
                                val res = actions.onGrantWriteSecureViaShizuku()
                                val grantedNow = DevicePermissionHelper.hasWriteSecureSettings(context)
                                onRefreshPermissions()
                                if (res.isSuccess && grantedNow) {
                                    Toast.makeText(context, "已永久授予自愈权限（重启依然有效）", Toast.LENGTH_SHORT).show()
                                    if (!state.accessibilityConnected) {
                                        actions.onRepairAccessibility()
                                        onRefreshPermissions()
                                    }
                                } else {
                                    val err = res.exceptionOrNull()?.message ?: "授权未生效"
                                    Toast.makeText(context, "Shizuku 授权失败：$err", Toast.LENGTH_LONG).show()
                                }
                            }
                        } else {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("Zai ADB", grantCmd))
                            Toast.makeText(context, "已复制命令", Toast.LENGTH_SHORT).show()
                        }
                    },
                )

                ZaiInsetDivider()
                val shizukuColor = when {
                    state.shizukuStatus.isReady -> MaterialTheme.colorScheme.secondary
                    state.shizukuStatus.binderAlive -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.outline
                }
                CompactStatusRow(
                    dotColor = shizukuColor,
                    title = "Shizuku（可选）",
                    statusText = when {
                        state.shizukuStatus.isReady -> state.shizukuStatus.identityLabel
                        state.shizukuStatus.binderAlive -> "待授权"
                        else -> "未运行"
                    },
                    actionLabel = when {
                        state.shizukuStatus.isReady -> "测试"
                        state.shizukuStatus.binderAlive -> "授权"
                        else -> "打开"
                    },
                    onAction = {
                        when {
                            state.shizukuStatus.isReady -> scope.launch {
                                val res = actions.onTestShizuku()
                                val msg = res.fold(
                                    onSuccess = { "正常：$it" },
                                    onFailure = { "失败：${it.message}" },
                                )
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                            state.shizukuStatus.binderAlive -> actions.onRequestShizuku()
                            else -> DevicePermissionHelper.openShizukuApp(context)
                        }
                    },
                )
            }
        }
    }
}

@Composable
internal fun AppearanceSection(
    themeMode: ThemeMode,
    onSelectTheme: (ThemeMode) -> Unit,
) {
    ZaiPaperGroup(title = "外观") {
        Box(modifier = Modifier.padding(14.dp)) {
            ZaiSegmentedControl(
                options = listOf(
                    ThemeMode.SYSTEM to "跟随系统",
                    ThemeMode.LIGHT to "浅色",
                    ThemeMode.DARK to "深色",
                    ThemeMode.AMOLED to "纯黑",
                ),
                selected = themeMode,
                onSelect = onSelectTheme,
            )
        }
    }
}

@Composable
internal fun SystemPermissionsSection(
    perms: DevicePermissionsSnapshot,
) {
    val context = LocalContext.current
    var showSystemPerms by remember { mutableStateOf(false) }

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
                    dotColor = if (perms.batteryIgnored) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary,
                    title = "省电策略无限制",
                    actionLabel = if (perms.batteryIgnored) "已放行" else "去设置",
                    onAction = { DevicePermissionHelper.openBatterySettings(context) },
                )
                ZaiInsetDivider()
                CompactStatusRow(
                    dotColor = if (perms.hasNotification) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
                    title = "通知权限",
                    actionLabel = if (perms.hasNotification) "已开启" else "去开启",
                    onAction = { DevicePermissionHelper.openNotificationSettings(context) },
                )
                ZaiInsetDivider()
                CompactStatusRow(
                    dotColor = if (perms.hasOverlay) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
                    title = "悬浮窗权限",
                    actionLabel = if (perms.hasOverlay) "已开启" else "去开启",
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
                    val bgColor = when (perms.hasXiaomiBgPopup) {
                        true -> MaterialTheme.colorScheme.secondary
                        false -> MaterialTheme.colorScheme.tertiary
                        null -> MaterialTheme.colorScheme.primary
                    }
                    CompactStatusRow(
                        dotColor = bgColor,
                        title = "后台弹出界面",
                        actionLabel = if (perms.hasXiaomiBgPopup == true) "已开启" else "去开启",
                        onAction = { DevicePermissionHelper.openXiaomiOtherPermissions(context) },
                    )
                }
            }
        }
    }
}
