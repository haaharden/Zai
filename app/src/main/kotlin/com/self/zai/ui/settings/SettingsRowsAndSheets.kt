package com.self.zai.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.self.zai.model.WebSearchProvider
import com.self.zai.permission.DevicePermissionHelper
import com.self.zai.ui.components.ZaiInsetDivider
import com.self.zai.ui.components.ZaiPaperGroup
import com.self.zai.ui.components.ZaiPrimaryButton
import com.self.zai.ui.components.ZaiSecondaryButton
import com.self.zai.ui.components.ZaiTextField
import com.self.zai.ui.components.zaiSwitchColors
import kotlinx.coroutines.launch

@Composable
internal fun CompactNavRow(
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
internal fun CompactStatusRow(
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
internal fun CompactToggleRow(
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
internal fun DisclosureRow(
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WebSearchKeySheet(
    keyProvider: WebSearchProvider,
    initialKey: String,
    onSaveKey: (WebSearchProvider, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var keyInput by remember(keyProvider) { mutableStateOf(initialKey) }
    var revealKey by remember(keyProvider) { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
            Text(
                text = "${keyProvider.detailLabel()} API Key",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "仅加密保存在本地密钥库中；留空保存即清除该 Key 并自动跳过此引擎。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ZaiTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                placeholder = keyProvider.keyPlaceholder(),
                monospace = true,
                visualTransformation = if (revealKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailing = {
                    IconButton(onClick = { revealKey = !revealKey }) {
                        Icon(
                            if (revealKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = if (revealKey) "隐藏" else "显示",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (initialKey.isNotBlank()) {
                    ZaiSecondaryButton(
                        text = "清除",
                        onClick = {
                            onSaveKey(keyProvider, "")
                            Toast.makeText(context, "已清除 ${keyProvider.label()} Key", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        },
                    )
                }
                ZaiPrimaryButton(
                    text = "保存",
                    onClick = {
                        onSaveKey(keyProvider, keyInput.trim())
                        val toast = if (keyInput.trim().isEmpty()) {
                            "已清除 ${keyProvider.label()} Key"
                        } else {
                            "已保存 ${keyProvider.label()} Key"
                        }
                        Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelfCheckSheet(
    state: SettingsUiState,
    perms: DevicePermissionsSnapshot,
    repairingA11y: Boolean,
    onSetRepairingA11y: (Boolean) -> Unit,
    onRefreshPermissions: () -> Unit,
    onRepairAccessibility: suspend () -> Result<Unit>,
    onOpenModels: () -> Unit,
    onRunSelfCheckPrompt: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val modelReady = state.piReady && state.defaultModel != null
    val controlReady = state.accessibilityConnected || state.shizukuStatus.isReady
    val canAutoRepair = !state.accessibilityConnected && (perms.hasWriteSecure || state.shizukuStatus.isReady)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
                    text = "配置自检与试跑",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) {
                    Text("跳过")
                }
            }

            Text(
                text = "通过一个小任务（打开系统设置并读出页面标题）一次验证模型连接、打开应用与读屏通道。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ZaiPaperGroup {
                CompactStatusRow(
                    dotColor = if (modelReady) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                    title = "1. 模型与引擎",
                    statusText = when {
                        !state.piReady -> "引擎初始化中"
                        state.defaultModel == null -> "未配置账号"
                        else -> state.defaultModel.name.take(14)
                    },
                    actionLabel = if (modelReady) "正常" else "去配置",
                    onAction = {
                        if (!modelReady) {
                            onDismiss()
                            onOpenModels()
                        }
                    },
                )
                ZaiInsetDivider()
                CompactStatusRow(
                    dotColor = if (controlReady) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                    title = "2. 手机操控通道",
                    statusText = when {
                        state.accessibilityConnected -> "无障碍已连"
                        state.shizukuStatus.isReady -> "Shizuku 就绪"
                        perms.a11ySettingOn -> "无障碍已脱绑"
                        else -> "未开启"
                    },
                    actionLabel = when {
                        repairingA11y -> "恢复中"
                        controlReady -> "正常"
                        canAutoRepair -> "一键恢复"
                        else -> "去开启"
                    },
                    onAction = {
                        if (!repairingA11y) {
                            when {
                                controlReady -> Unit
                                canAutoRepair -> {
                                    onSetRepairingA11y(true)
                                    scope.launch {
                                        val res = onRepairAccessibility()
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
                                }
                                else -> DevicePermissionHelper.openAccessibilitySettings(
                                    context = context,
                                    isCurrentlyConnected = state.accessibilityConnected,
                                )
                            }
                        }
                    },
                )
            }

            val blockerHint = when {
                !state.piReady -> "当前卡在：Pi 引擎仍在启动，请稍等 1-2 秒。"
                state.defaultModel == null -> "当前卡在：尚未登录模型账号或添加渠道，请点上方「去配置」。"
                !controlReady -> "当前卡在：无障碍和 Shizuku 均未就绪，请点上方「去开启」打开无障碍服务。"
                else -> null
            }
            if (blockerHint != null) {
                Text(
                    text = blockerHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            ZaiPrimaryButton(
                text = if (modelReady && controlReady) "开始试跑：打开设置并读标题" else "请先完成上方红点项",
                enabled = modelReady && controlReady,
                onClick = {
                    onDismiss()
                    onRunSelfCheckPrompt("请打开系统设置，并读出当前页面的顶部标题告诉我。")
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
        }
    }
}
