package com.self.zai.ui.models

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.self.zai.data.ModelDiscoveryClient
import com.self.zai.model.CustomModelEntry
import com.self.zai.model.CustomProviderConfig
import com.self.zai.ui.components.ZaiPrimaryButton
import com.self.zai.ui.components.ZaiSecondaryButton
import com.self.zai.ui.components.ZaiSegmentedControl
import com.self.zai.ui.components.ZaiTextField
import com.self.zai.ui.components.zaiSwitchColors
import kotlinx.coroutines.launch

internal data class ProviderPreset(
    val name: String,
    val baseUrl: String,
    val api: String = "openai-completions",
    val defaultImages: Boolean = true,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CustomProviderEditorSheet(
    editing: CustomProviderConfig?,
    preset: ProviderPreset?,
    initialApiKey: String,
    onLookupCatalogMetadata: (List<String>, (Map<String, Triple<Int, Boolean, Boolean>>) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        baseUrl: String,
        apiKey: String,
        models: List<CustomModelEntry>,
        defaultImages: Boolean,
    ) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var name by remember(editing, preset) { mutableStateOf(editing?.name ?: preset?.name.orEmpty()) }
    var baseUrl by remember(editing, preset) { mutableStateOf(editing?.baseUrl ?: preset?.baseUrl.orEmpty()) }
    var apiKey by remember(editing, initialApiKey) { mutableStateOf(initialApiKey) }
    var keyVisible by remember { mutableStateOf(false) }
    var defaultImages by remember(editing, preset) {
        mutableStateOf(editing?.defaultImages ?: preset?.defaultImages ?: true)
    }
    var apiProtocol by remember(editing, preset) {
        mutableStateOf(editing?.models?.firstOrNull()?.api ?: preset?.api ?: "openai-completions")
    }

    var fetching by remember { mutableStateOf(false) }
    var fetchError by remember { mutableStateOf<String?>(null) }
    var filterQuery by remember { mutableStateOf("") }
    var manualModelInput by remember { mutableStateOf("") }

    val discoveredPool = remember(editing) {
        mutableStateListOf<CustomModelEntry>().apply {
            editing?.models?.let { addAll(it) }
        }
    }
    val selectedIds = remember(editing) {
        mutableStateListOf<String>().apply {
            editing?.models?.map { it.id }?.let { addAll(it) }
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = when {
                    editing != null -> "编辑渠道"
                    preset != null -> "连接 ${preset.name}"
                    else -> "添加自定义接口"
                },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )

            ZaiTextField(
                value = name,
                onValueChange = { name = it },
                label = "名称",
                placeholder = "例如 DeepSeek / 我的中转",
            )

            ZaiTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = "接口地址 (Base URL)",
                placeholder = "https://api.example.com/v1",
                monospace = true,
            )

            ZaiTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = "API 密钥",
                placeholder = "sk-...",
                monospace = true,
                visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailing = {
                    IconButton(onClick = { keyVisible = !keyVisible }) {
                        Icon(
                            if (keyVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = "显示或隐藏密钥",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                },
            )

            ZaiSegmentedControl(
                options = listOf(
                    "openai-completions" to "OpenAI 兼容",
                    "openai-responses" to "Responses",
                    "anthropic-messages" to "Anthropic",
                ),
                selected = apiProtocol,
                onSelect = { apiProtocol = it },
            )

            ZaiPrimaryButton(
                text = if (fetching) "正在拉取模型列表…" else "自动拉取模型",
                enabled = !fetching && baseUrl.isNotBlank(),
                onClick = {
                    fetching = true
                    fetchError = null
                    scope.launch {
                        val res = ModelDiscoveryClient.fetchModels(baseUrl, apiKey)
                        fetching = false
                        res.onSuccess { found ->
                            baseUrl = found.resolvedBaseUrl
                            val ids = found.models.map { it.id }
                            onLookupCatalogMetadata(ids) { catalogMeta ->
                                discoveredPool.clear()
                                found.models.forEach { d ->
                                    val meta = catalogMeta[d.id]
                                    discoveredPool += CustomModelEntry(
                                        id = d.id,
                                        contextWindow = d.contextWindow ?: meta?.first ?: 128_000,
                                        supportsImages = d.supportsImages ?: meta?.second ?: defaultImages,
                                        reasoning = d.reasoning ?: meta?.third ?: false,
                                        efforts = d.efforts,
                                        api = apiProtocol,
                                    )
                                }
                                if (selectedIds.isEmpty()) {
                                    discoveredPool.take(3).forEach { selectedIds.add(it.id) }
                                }
                            }
                        }.onFailure { err ->
                            fetchError = err.message ?: "拉取模型失败"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            if (!fetchError.isNullOrBlank()) {
                Text(
                    text = fetchError!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ZaiTextField(
                    value = manualModelInput,
                    onValueChange = { manualModelInput = it },
                    label = "手动补充模型 ID（可选）",
                    placeholder = "例如 deepseek-chat",
                    monospace = true,
                    modifier = Modifier.weight(1f),
                )
                ZaiSecondaryButton(
                    text = "添加",
                    enabled = manualModelInput.isNotBlank(),
                    onClick = {
                        val id = manualModelInput.trim()
                        if (id.isNotEmpty()) {
                            if (discoveredPool.none { it.id == id }) {
                                discoveredPool.add(
                                    0,
                                    CustomModelEntry(
                                        id = id,
                                        supportsImages = defaultImages,
                                        api = apiProtocol,
                                    ),
                                )
                            }
                            if (!selectedIds.contains(id)) selectedIds.add(id)
                            manualModelInput = ""
                        }
                    },
                )
            }

            if (discoveredPool.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "已发现 ${discoveredPool.size} 个模型，已选 ${selectedIds.size} 个",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (discoveredPool.size > 1) {
                        TextButton(
                            onClick = {
                                if (selectedIds.size == discoveredPool.size) {
                                    selectedIds.clear()
                                } else {
                                    selectedIds.clear()
                                    discoveredPool.forEach { selectedIds.add(it.id) }
                                }
                            },
                        ) {
                            Text(if (selectedIds.size == discoveredPool.size) "全不选" else "全选")
                        }
                    }
                }

                if (discoveredPool.size > 8) {
                    ZaiTextField(
                        value = filterQuery,
                        onValueChange = { filterQuery = it },
                        placeholder = "筛选模型名称",
                    )
                }

                val filtered = discoveredPool.filter {
                    filterQuery.isBlank() || it.id.contains(filterQuery.trim(), ignoreCase = true)
                }.take(50)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(vertical = 4.dp),
                ) {
                    filtered.forEach { m ->
                        val checked = selectedIds.contains(m.id)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (checked) selectedIds.remove(m.id) else selectedIds.add(m.id)
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = {
                                    if (it) {
                                        if (!selectedIds.contains(m.id)) selectedIds.add(m.id)
                                    } else {
                                        selectedIds.remove(m.id)
                                    }
                                },
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = m.id,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = buildString {
                                        append("${m.contextWindow / 1000}K 上下文")
                                        if (m.supportsImages) append("  支持看图")
                                        if (m.reasoning) append("  推理思考")
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("默认允许发送截图", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "模型无法自动识别视觉能力时按此项处理",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = defaultImages,
                    onCheckedChange = { defaultImages = it },
                    colors = zaiSwitchColors(),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ZaiSecondaryButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                val chosenModels = discoveredPool
                    .filter { selectedIds.contains(it.id) }
                    .map { it.copy(api = apiProtocol) }
                ZaiPrimaryButton(
                    text = "保存渠道",
                    enabled = baseUrl.isNotBlank() && chosenModels.isNotEmpty(),
                    onClick = {
                        onSave(
                            name.ifBlank {
                                ModelDiscoveryClient.normalizeBaseUrl(baseUrl)
                                    .substringAfter("://")
                                    .substringBefore("/")
                            },
                            ModelDiscoveryClient.normalizeBaseUrl(baseUrl),
                            apiKey,
                            chosenModels,
                            defaultImages,
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}
