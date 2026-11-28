package com.self.zai.ui.settings

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.self.zai.model.CompactionMode
import com.self.zai.model.ThinkingLevelDisplay
import com.self.zai.model.TtsReadoutMode
import com.self.zai.model.WebSearchProvider
import com.self.zai.permission.DevicePermissionHelper
import com.self.zai.ui.components.ZaiInsetDivider
import com.self.zai.ui.components.ZaiPaperGroup
import com.self.zai.ui.components.ZaiSegmentedControl
import com.self.zai.ui.components.ZaiSelectPill
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModelAndCompactionSection(
    state: SettingsUiState,
    actions: SettingsActions,
    onOpenDefaultModelSheet: () -> Unit,
) {
    var showCompactionConfig by remember { mutableStateOf(false) }

    ZaiPaperGroup(title = "模型") {
        val connectedCount = state.providers.count { it.isConnected && it.models.isNotEmpty() }
        CompactNavRow(
            title = "账号与渠道",
            value = if (connectedCount > 0) "已连 $connectedCount 个" else "去登录",
            onClick = actions.onOpenModels,
        )

        ZaiInsetDivider()

        CompactNavRow(
            title = "默认模型",
            value = state.defaultModel?.name ?: "未配置",
            onClick = {
                if (connectedCount > 0) onOpenDefaultModelSheet() else actions.onOpenModels()
            },
        )

        val defaultModel = state.defaultModel
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
                            selected = state.defaultThinkingLevel == level,
                            onClick = { actions.onSelectDefaultThinking(level) },
                        )
                    }
                }
            }
        }

        ZaiInsetDivider()

        val settings = state.settings
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
                        actions.onUpdateCompaction(mode, settings.compactionThresholdPercent)
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
                                    onClick = { actions.onUpdateCompaction(settings.compactionMode, pct) },
                                )
                            }
                        }
                    }
                    Text(
                        text = if (settings.compactionMode == CompactionMode.ACP) {
                            "内置原版 acp-kernel（billion-context 同源 3 层 LSM 内核）：支持 m 标号精准选区压缩、搜索、解压与大结果外置。"
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
}

@Composable
internal fun CapabilitiesAndSearchSection(
    state: SettingsUiState,
    actions: SettingsActions,
    onOpenUserPromptSheet: () -> Unit,
    onEditWebKey: (WebSearchProvider) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = state.settings
    var showWebSearchConfig by remember { mutableStateOf(false) }
    var testingWebSearch by remember { mutableStateOf(false) }

    ZaiPaperGroup(title = "能力") {
        CompactNavRow(
            title = "用户提示词",
            value = if (settings.userSystemPrompt.isNotBlank()) "已设置" else "未设置",
            onClick = onOpenUserPromptSheet,
        )

        ZaiInsetDivider()

        val enabledSkillsCount = settings.skills.count { it.enabled }
        CompactNavRow(
            title = "Skills",
            value = if (settings.skills.isEmpty()) "0" else "$enabledSkillsCount/${settings.skills.size}",
            onClick = actions.onOpenSkills,
        )

        ZaiInsetDivider()

        val enabledTools = settings.mcpServers.filter { it.enabled }.sumOf { it.tools.size }
        CompactNavRow(
            title = "MCP 工具",
            value = if (settings.mcpServers.isEmpty()) "0" else "$enabledTools 个工具",
            onClick = actions.onOpenMcpServers,
        )

        ZaiInsetDivider()

        val orderedSearchProviders = remember(settings.webSearchPriority) {
            WebSearchProvider.normalizePriority(settings.webSearchPriority)
        }
        val topEffectiveProvider = orderedSearchProviders.firstOrNull {
            !it.requiresApiKey || it in state.configuredWebSearchProviders
        } ?: WebSearchProvider.FREE
        val webSearchSummary = when {
            !settings.webSearchEnabled -> "已关闭"
            topEffectiveProvider == WebSearchProvider.FREE && state.configuredWebSearchProviders.isEmpty() -> "免 Key"
            else -> "${topEffectiveProvider.label()} 优先"
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showWebSearchConfig = !showWebSearchConfig }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "联网搜索",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = webSearchSummary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                if (showWebSearchConfig) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }

        AnimatedVisibility(visible = showWebSearchConfig) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            ) {
                ZaiInsetDivider()
                CompactToggleRow(
                    title = "启用联网搜索与读网页",
                    checked = settings.webSearchEnabled,
                    onCheckedChange = actions.onUpdateWebSearchEnabled,
                )

                if (settings.webSearchEnabled) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "引擎优先级（未配或失败自动回退）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            enabled = !testingWebSearch,
                            onClick = {
                                testingWebSearch = true
                                scope.launch {
                                    val res = actions.onTestWebSearch()
                                    testingWebSearch = false
                                    val msg = res.fold(
                                        onSuccess = { it },
                                        onFailure = { it.message ?: "搜索测试失败" },
                                    )
                                    Toast.makeText(
                                        context,
                                        msg,
                                        if (res.isFailure) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                        ) {
                            Text(
                                text = if (testingWebSearch) "测试中…" else "测试",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }

                    orderedSearchProviders.forEachIndexed { idx, provider ->
                        val hasKey = !provider.requiresApiKey || provider in state.configuredWebSearchProviders
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (provider.requiresApiKey) {
                                        Modifier.clickable { onEditWebKey(provider) }
                                    } else {
                                        Modifier
                                    },
                                )
                                .padding(start = 16.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${idx + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(18.dp),
                            )
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (hasKey) MaterialTheme.colorScheme.secondary
                                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
                                    ),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = provider.label(),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = when {
                                    !provider.requiresApiKey -> "Bing / DDG"
                                    hasKey -> "已配 Key"
                                    else -> "未配"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (provider.requiresApiKey) {
                                Text(
                                    text = if (hasKey) "改 Key" else "配 Key",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { onEditWebKey(provider) }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                            IconButton(
                                onClick = {
                                    if (idx > 0) {
                                        val swapped = orderedSearchProviders.toMutableList()
                                        swapped[idx] = swapped[idx - 1].also { swapped[idx - 1] = swapped[idx] }
                                        actions.onUpdateWebSearchPriority(swapped)
                                    }
                                },
                                enabled = idx > 0,
                                modifier = Modifier.size(30.dp),
                            ) {
                                Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "上移", modifier = Modifier.size(18.dp))
                            }
                            IconButton(
                                onClick = {
                                    if (idx < orderedSearchProviders.lastIndex) {
                                        val swapped = orderedSearchProviders.toMutableList()
                                        swapped[idx] = swapped[idx + 1].also { swapped[idx + 1] = swapped[idx] }
                                        actions.onUpdateWebSearchPriority(swapped)
                                    }
                                },
                                enabled = idx < orderedSearchProviders.lastIndex,
                                modifier = Modifier.size(30.dp),
                            ) {
                                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "下移", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AssistantVoiceSection(
    state: SettingsUiState,
    perms: DevicePermissionsSnapshot,
    actions: SettingsActions,
    onRefreshPermissions: () -> Unit,
) {
    val context = LocalContext.current
    val settings = state.settings

    ZaiPaperGroup(title = "助手与语音") {
        val assistantBroken = perms.isDefaultAssistant && !perms.voiceInteractionHealthy
        CompactStatusRow(
            dotColor = when {
                assistantBroken -> MaterialTheme.colorScheme.error
                perms.isDefaultAssistant -> MaterialTheme.colorScheme.secondary
                else -> MaterialTheme.colorScheme.outline
            },
            title = "默认数字助手",
            statusText = when {
                assistantBroken -> "语音服务脱绑"
                perms.isDefaultAssistant -> "已设为默认"
                else -> null
            },
            actionLabel = when {
                assistantBroken && perms.hasWriteSecure -> "修复"
                assistantBroken -> "重选"
                perms.isDefaultAssistant -> "试呼出"
                else -> "去设置"
            },
            onRowClick = { DevicePermissionHelper.openVoiceInputSettings(context) },
            onAction = {
                when {
                    assistantBroken && perms.hasWriteSecure -> {
                        val res = DevicePermissionHelper.restoreVoiceInteractionServiceIfNeeded(context)
                        onRefreshPermissions()
                        val msg = res.fold(
                            onSuccess = { "已恢复电源键语音助手绑定" },
                            onFailure = { "恢复失败：${it.message}" },
                        )
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                    assistantBroken -> DevicePermissionHelper.openVoiceInputSettings(context)
                    perms.isDefaultAssistant -> DevicePermissionHelper.openAssistOverlayPreview(context)
                    else -> DevicePermissionHelper.openVoiceInputSettings(context)
                }
            },
        )

        ZaiInsetDivider()

        CompactToggleRow(
            title = "呼出即自动语音",
            checked = settings.assistantAutoListen,
            onCheckedChange = { v -> actions.onUpdateSettings { it.copy(assistantAutoListen = v) } },
        )

        ZaiInsetDivider()

        CompactToggleRow(
            title = "浮层默认附带屏幕",
            checked = settings.assistantAttachScreenDefault,
            onCheckedChange = { v -> actions.onUpdateSettings { it.copy(assistantAttachScreenDefault = v) } },
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
                onSelect = { m -> actions.onUpdateSettings { it.copy(assistantTtsMode = m) } },
            )
        }

        ZaiInsetDivider()

        CompactStatusRow(
            dotColor = if (perms.hasNotifListener) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
            title = "通知读取权限",
            actionLabel = if (perms.hasNotifListener) "已开启" else "去开启",
            onAction = { DevicePermissionHelper.openNotificationListenerSettings(context) },
        )
    }
}
