package com.self.zai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.self.zai.model.ModelOption
import com.self.zai.model.ProviderQuota
import com.self.zai.model.ProviderStatus
import com.self.zai.model.QuotaWindow
import com.self.zai.model.ThinkingLevelDisplay

/**
 * Shared clean bottom sheet for picking a connected model (used both above the chat composer
 * for per-conversation switching and inside Settings for global default model selection).
 * Shows the live remaining quota card strictly for the currently selected model's provider.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZaiModelPickerSheet(
    title: String,
    providers: List<ProviderStatus>,
    selectedModel: ModelOption?,
    providerQuotas: Map<String, ProviderQuota> = emptyMap(),
    onRefreshQuota: ((providerId: String, force: Boolean) -> Unit)? = null,
    onSelect: (providerId: String, modelId: String) -> Unit,
    onManageProviders: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val connected = providers.filter { it.isConnected && it.models.isNotEmpty() }
    val activeProvider = connected.firstOrNull { it.id == selectedModel?.providerId } ?: connected.firstOrNull()

    LaunchedEffect(activeProvider?.id) {
        val pid = activeProvider?.id
        if (!pid.isNullOrBlank()) {
            onRefreshQuota?.invoke(pid, false)
        }
    }

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
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (onManageProviders != null) {
                    TextButton(
                        onClick = {
                            onDismiss()
                            onManageProviders()
                        },
                    ) {
                        Text("管理账号", fontWeight = FontWeight.Medium)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            if (connected.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "尚未连接任何模型渠道",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (onManageProviders != null) {
                        ZaiPrimaryButton(
                            text = "前往登录或添加供应商",
                            onClick = {
                                onDismiss()
                                onManageProviders()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            } else {
                // Show remaining quota strictly for the currently active model's provider
                if (activeProvider != null) {
                    val activeQuota = providerQuotas[activeProvider.id]
                    val expectsQuota = activeQuota != null ||
                        activeProvider.supportsOAuth ||
                        activeProvider.name.contains("openrouter", ignoreCase = true) ||
                        activeProvider.name.contains("deepseek", ignoreCase = true)
                    if (expectsQuota) {
                        ProviderQuotaStrip(
                            providerLabel = activeProvider.name,
                            quota = activeQuota,
                            onRefresh = { onRefreshQuota?.invoke(activeProvider.id, true) },
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    connected.forEach { provider ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = provider.name,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                val q = providerQuotas[provider.id]
                                val badge = q?.planDisplayLabel() ?: q?.compactInlineLabel()
                                if (!badge.isNullOrBlank()) {
                                    Text(
                                        text = badge,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Medium,
                                        color = if (q?.limitReached == true) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        },
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f))
                                            .padding(horizontal = 7.dp, vertical = 2.dp),
                                    )
                                }
                            }

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
                            ) {
                                provider.models.forEachIndexed { idx, model ->
                                    val isSelected = selectedModel?.providerId == model.providerId &&
                                        selectedModel.id == model.id
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(
                                                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                                else Color.Transparent,
                                            )
                                            .clickable {
                                                onSelect(model.providerId, model.id)
                                                onDismiss()
                                            }
                                            .padding(horizontal = 14.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = model.id,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            val meta = buildString {
                                                append("${model.contextWindow / 1000}K 上下文")
                                                if (model.supportsImages) append("  视觉")
                                                if (model.supportsThinkingSelection) {
                                                    append("  思考 ${model.thinkingLevels.size} 档")
                                                }
                                            }
                                            Text(
                                                text = meta,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        if (isSelected) {
                                            Spacer(Modifier.width(8.dp))
                                            Icon(
                                                Icons.Rounded.Check,
                                                contentDescription = "已选中",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                    if (idx < provider.models.lastIndex) {
                                        ZaiInsetDivider()
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

/**
 * Unified Anthropic-styled quota strip supporting both rolling-window subscriptions (5h / weekly + reset cards)
 * and prepaid API balance providers (DeepSeek / OpenRouter).
 */
@Composable
fun ProviderQuotaStrip(
    quota: ProviderQuota?,
    onRefresh: () -> Unit,
    providerLabel: String? = null,
    modifier: Modifier = Modifier,
) {
    val prefix = providerLabel?.takeIf { it.isNotBlank() }?.let { "$it · " }.orEmpty()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .padding(horizontal = 13.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val headerLabel = when {
                quota == null -> "${prefix}正在查询剩余额度…"
                quota.limitReached -> "${prefix}额度已用尽"
                quota.kind == "balance" -> "${prefix}账户余额"
                quota.primary != null || quota.secondary != null -> "${prefix}剩余额度"
                !quota.error.isNullOrBlank() -> "${prefix}${quota.error}"
                else -> "${prefix}账号额度"
            }
            Text(
                text = headerLabel,
                style = MaterialTheme.typography.labelMedium,
                color = if (quota?.limitReached == true) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val resetCount = quota?.resetCredits?.availableCount ?: 0
            if (resetCount > 0) {
                Text(
                    text = "重置卡 $resetCount",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 6.dp),
                )
            } else if (quota?.kind != "balance" && !quota?.creditsBalance.isNullOrBlank()) {
                Text(
                    text = "余额 ${quota?.creditsBalance}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onRefresh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Refresh,
                    contentDescription = "刷新额度",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp),
                )
            }
        }

        if (quota != null) {
            val windows = listOfNotNull(quota.primary, quota.secondary)
            if (windows.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    windows.forEach { win ->
                        QuotaWindowCell(
                            window = win,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            } else if (quota.kind == "balance" && !quota.summaryText.isNullOrBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = quota.summaryText,
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = if (quota.limitReached) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                    if (!quota.detailText.isNullOrBlank()) {
                        Text(
                            text = quota.detailText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            if (!quota.error.isNullOrBlank() && (windows.isNotEmpty() || !quota.summaryText.isNullOrBlank())) {
                Text(
                    text = quota.error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                )
            }
        }
    }
}

@Composable
private fun QuotaWindowCell(
    window: QuotaWindow,
    modifier: Modifier = Modifier,
) {
    val barColor = when {
        window.remainingPercent <= 15 -> MaterialTheme.colorScheme.error
        window.remainingPercent <= 35 -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.secondary
    }
    val resetHint = window.formatResetCountdown()

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${window.windowLabel()} 剩 ${window.remainingPercent}%",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = (window.remainingPercent / 100f).coerceIn(0f, 1f))
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(barColor),
            )
        }
        if (resetHint.isNotEmpty()) {
            Text(
                text = resetHint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Compact dynamic thinking-effort picker showing ONLY the exact levels exposed by [model].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZaiThinkingPickerSheet(
    model: ModelOption,
    selectedLevel: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
        ) {
            Text(
                text = "思考强度",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "${model.id} 支持的档位",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                model.thinkingLevels.forEachIndexed { idx, level ->
                    val isSelected = level == selectedLevel
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                else Color.Transparent,
                            )
                            .clickable {
                                onSelect(level)
                                onDismiss()
                            }
                            .padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                                ),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = ThinkingLevelDisplay.label(level),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = level,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (isSelected) {
                            Spacer(Modifier.width(10.dp))
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = "已选中",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    if (idx < model.thinkingLevels.lastIndex) {
                        ZaiInsetDivider()
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
        }
    }
}
