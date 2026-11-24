package com.self.zai.ui.models

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
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.self.zai.data.ModelDiscoveryClient
import com.self.zai.model.AppSettings
import com.self.zai.model.AuthUiState
import com.self.zai.model.CustomModelEntry
import com.self.zai.model.CustomProviderConfig
import com.self.zai.model.ModelOption
import com.self.zai.model.ProviderStatus
import kotlinx.coroutines.launch

private data class ProviderPreset(
    val name: String,
    val baseUrl: String,
    val api: String = "openai-completions",
    val defaultImages: Boolean = true,
    val hint: String = "",
)

private val PRESET_PROVIDERS = listOf(
    ProviderPreset(
        name = "DeepSeek",
        baseUrl = "https://api.deepseek.com/v1",
        defaultImages = false,
        hint = "官方接口",
    ),
    ProviderPreset(
        name = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1",
        defaultImages = true,
        hint = "聚合多模型",
    ),
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ModelsAndAuthScreen(
    settings: AppSettings,
    providers: List<ProviderStatus>,
    activeModel: ModelOption?,
    authState: AuthUiState,
    onSelectModel: (providerId: String, modelId: String) -> Unit,
    onStartChatGptLogin: () -> Unit,
    onCancelLogin: () -> Unit,
    onSubmitAuthPrompt: (promptId: String, value: String) -> Unit,
    onLogoutChatGpt: () -> Unit,
    onOpenBrowserUrl: (String) -> Unit,
    onLookupCatalogMetadata: (List<String>, (Map<String, Triple<Int, Boolean, Boolean>>) -> Unit) -> Unit,
    onSaveCustomProvider: (
        existingId: String?,
        name: String,
        baseUrl: String,
        apiKey: String,
        models: List<CustomModelEntry>,
        defaultImages: Boolean,
    ) -> Unit,
    onDeleteCustomProvider: (String) -> Unit,
    onGetSavedApiKey: (String) -> String?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    var editingProvider by remember { mutableStateOf<CustomProviderConfig?>(null) }
    var selectedPreset by remember { mutableStateOf<ProviderPreset?>(null) }
    var showEditorSheet by remember { mutableStateOf(false) }
    var lastAutoOpenedUrl by remember { mutableStateOf<String?>(null) }
    var pasteCallbackInput by remember { mutableStateOf("") }

    // Auto-open browser once when Pi emits the ChatGPT OAuth URL
    LaunchedEffect(authState) {
        val url = (authState as? AuthUiState.InProgress)?.openUrl
        if (!url.isNullOrBlank() && url != lastAutoOpenedUrl) {
            lastAutoOpenedUrl = url
            onOpenBrowserUrl(url)
        }
        if (authState is AuthUiState.Idle) {
            lastAutoOpenedUrl = null
            pasteCallbackInput = ""
        }
    }

    val chatGptProvider = providers.firstOrNull { it.id == "openai" }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                title = { Text("模型与账号", fontWeight = FontWeight.SemiBold) },
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
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // Section 1: ChatGPT Subscription Login (via Pi OAuth)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "ChatGPT 订阅账号",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val signedIn = chatGptProvider?.credentialType == "oauth"
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (signedIn) MaterialTheme.colorScheme.secondary
                                        else MaterialTheme.colorScheme.outline,
                                    ),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (signedIn) "已登录 ChatGPT" else "未登录 ChatGPT",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = if (signedIn) {
                                        "使用你的 ChatGPT Plus / Pro 订阅额度，由 Pi 内核自动刷新令牌"
                                    } else {
                                        "通过浏览器 OAuth 登录，凭据经 Android Keystore 加密存储在本机"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        when (authState) {
                            is AuthUiState.InProgress -> {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp,
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            Text(
                                                text = authState.statusMessage,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface,
                                            )
                                        }

                                        if (!authState.userCode.isNullOrBlank()) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                Text(
                                                    text = authState.userCode,
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = MaterialTheme.colorScheme.primary,
                                                )
                                                TextButton(
                                                    onClick = {
                                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                        cm.setPrimaryClip(ClipData.newPlainText("Zai", authState.userCode))
                                                        Toast.makeText(context, "已复制授权码", Toast.LENGTH_SHORT).show()
                                                    },
                                                ) {
                                                    Text("复制")
                                                }
                                            }
                                        }

                                        if (!authState.openUrl.isNullOrBlank()) {
                                            OutlinedButton(
                                                onClick = { onOpenBrowserUrl(authState.openUrl) },
                                                modifier = Modifier.fillMaxWidth(),
                                            ) {
                                                Icon(Icons.Rounded.OpenInBrowser, contentDescription = null, modifier = Modifier.size(18.dp))
                                                Spacer(Modifier.width(6.dp))
                                                Text("在浏览器中打开登录页")
                                            }
                                        }

                                        if (!authState.promptId.isNullOrBlank()) {
                                            Text(
                                                text = authState.promptMessage.orEmpty(),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            OutlinedTextField(
                                                value = pasteCallbackInput,
                                                onValueChange = { pasteCallbackInput = it },
                                                placeholder = {
                                                    Text(
                                                        authState.promptPlaceholder.orEmpty(),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                },
                                                singleLine = true,
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Button(
                                                    onClick = {
                                                        if (pasteCallbackInput.isNotBlank()) {
                                                            onSubmitAuthPrompt(authState.promptId, pasteCallbackInput.trim())
                                                        }
                                                    },
                                                    enabled = pasteCallbackInput.isNotBlank(),
                                                ) {
                                                    Text("提交回调链接")
                                                }
                                                TextButton(onClick = onCancelLogin) {
                                                    Text("取消登录")
                                                }
                                            }
                                        } else {
                                            TextButton(onClick = onCancelLogin) {
                                                Text("取消登录")
                                            }
                                        }
                                    }
                                }
                            }

                            is AuthUiState.Failed -> {
                                Text(
                                    text = authState.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Button(
                                    onClick = onStartChatGptLogin,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("重新登录 ChatGPT")
                                }
                            }

                            AuthUiState.Idle -> {
                                if (signedIn) {
                                    val gptModels = chatGptProvider.models
                                    if (gptModels.isNotEmpty()) {
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            gptModels.forEach { m ->
                                                val isCurrent = activeModel?.providerId == "openai" && activeModel.id == m.id
                                                FilterChip(
                                                    selected = isCurrent,
                                                    onClick = { onSelectModel("openai", m.id) },
                                                    label = {
                                                        Text(
                                                            text = m.id,
                                                            fontFamily = FontFamily.Monospace,
                                                            style = MaterialTheme.typography.labelMedium,
                                                        )
                                                    },
                                                    leadingIcon = if (isCurrent) {
                                                        { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                                    } else null,
                                                )
                                            }
                                        }
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                    ) {
                                        TextButton(onClick = onLogoutChatGpt) {
                                            Text("退出登录", color = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                } else {
                                    Button(
                                        onClick = onStartChatGptLogin,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text("登录 ChatGPT")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Section 2: Custom Providers (Presets + API Key + Base URL auto-discover models)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "供应商与自定义接口",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            editingProvider = null
                            selectedPreset = null
                            showEditorSheet = true
                        },
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("自定义")
                    }
                }

                // Quick-add preset providers
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "快速添加预设供应商（点选后填密钥即可自动拉取模型）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            PRESET_PROVIDERS.forEach { preset ->
                                FilterChip(
                                    selected = false,
                                    onClick = {
                                        editingProvider = null
                                        selectedPreset = preset
                                        showEditorSheet = true
                                    },
                                    label = {
                                        Text(
                                            text = preset.name,
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }

                if (settings.customProviders.isEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = "也可以填入任意中转站或本地模型的地址和密钥，Zai 会自动请求 /models 发现可用模型。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(
                                onClick = {
                                    editingProvider = null
                                    selectedPreset = null
                                    showEditorSheet = true
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("手动添加中转站 / 自定义地址")
                            }
                        }
                    }
                } else {
                    settings.customProviders.forEach { cfg ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = cfg.name,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            text = cfg.baseUrl,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            editingProvider = cfg
                                            showEditorSheet = true
                                        },
                                    ) {
                                        Icon(
                                            Icons.Rounded.Edit,
                                            contentDescription = "编辑渠道",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(19.dp),
                                        )
                                    }
                                    IconButton(onClick = { onDeleteCustomProvider(cfg.id) }) {
                                        Icon(
                                            Icons.Rounded.DeleteOutline,
                                            contentDescription = "删除渠道",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(19.dp),
                                        )
                                    }
                                }

                                if (cfg.models.isNotEmpty()) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        cfg.models.forEach { m ->
                                            val isCurrent = activeModel?.providerId == cfg.id && activeModel.id == m.id
                                            FilterChip(
                                                selected = isCurrent,
                                                onClick = { onSelectModel(cfg.id, m.id) },
                                                label = {
                                                    Text(
                                                        text = m.id,
                                                        fontFamily = FontFamily.Monospace,
                                                        style = MaterialTheme.typography.labelMedium,
                                                    )
                                                },
                                                leadingIcon = if (isCurrent) {
                                                    { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                                } else null,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }

    if (showEditorSheet) {
        CustomProviderEditorSheet(
            editing = editingProvider,
            preset = selectedPreset,
            initialApiKey = editingProvider?.let { onGetSavedApiKey(it.id) }.orEmpty(),
            onLookupCatalogMetadata = onLookupCatalogMetadata,
            onDismiss = {
                showEditorSheet = false
                selectedPreset = null
            },
            onSave = { name, baseUrl, apiKey, models, defaultImages ->
                onSaveCustomProvider(
                    editingProvider?.id,
                    name,
                    baseUrl,
                    apiKey,
                    models,
                    defaultImages,
                )
                showEditorSheet = false
                selectedPreset = null
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CustomProviderEditorSheet(
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
    var defaultImages by remember(editing, preset) { mutableStateOf(editing?.defaultImages ?: preset?.defaultImages ?: true) }
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (editing == null) "添加自定义渠道" else "编辑渠道",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("渠道名称") },
                placeholder = { Text("例如 OpenRouter / 我的中转") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("接口地址 (Base URL)") },
                placeholder = { Text("https://api.example.com/v1") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API 密钥") },
                placeholder = { Text("sk-...") },
                singleLine = true,
                visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { keyVisible = !keyVisible }) {
                        Icon(
                            if (keyVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = "显示或隐藏密钥",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            // API protocol selector
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(
                    "openai-completions" to "OpenAI 兼容",
                    "openai-responses" to "OpenAI Responses",
                    "anthropic-messages" to "Anthropic Messages",
                ).forEach { (value, label) ->
                    FilterChip(
                        selected = apiProtocol == value,
                        onClick = { apiProtocol = value },
                        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }

            // One-tap Auto-Discover Models button
            Button(
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
                enabled = !fetching && baseUrl.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (fetching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("正在拉取模型列表…")
                } else {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("自动拉取模型")
                }
            }

            if (!fetchError.isNullOrBlank()) {
                Text(
                    text = fetchError!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // Manual fallback model add
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = manualModelInput,
                    onValueChange = { manualModelInput = it },
                    label = { Text("手动添加模型 ID（可选）") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
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
                    enabled = manualModelInput.isNotBlank(),
                ) {
                    Text("添加")
                }
            }

            // Discovered models checklist
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
                    OutlinedTextField(
                        value = filterQuery,
                        onValueChange = { filterQuery = it },
                        placeholder = { Text("筛选模型名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                val filtered = discoveredPool.filter {
                    filterQuery.isBlank() || it.id.contains(filterQuery.trim(), ignoreCase = true)
                }.take(50)

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
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
                                            if (m.supportsImages) append(" 支持看图")
                                            if (m.reasoning) append(" 深度思考")
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
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
                        "模型无法自动识别视觉能力时按此选项处理",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = defaultImages, onCheckedChange = { defaultImages = it })
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("取消")
                }
                val chosenModels = discoveredPool
                    .filter { selectedIds.contains(it.id) }
                    .map { it.copy(api = apiProtocol) }
                Button(
                    onClick = {
                        onSave(
                            name.ifBlank { ModelDiscoveryClient.normalizeBaseUrl(baseUrl).substringAfter("://").substringBefore("/") },
                            ModelDiscoveryClient.normalizeBaseUrl(baseUrl),
                            apiKey,
                            chosenModels,
                            defaultImages,
                        )
                    },
                    enabled = baseUrl.isNotBlank() && chosenModels.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("保存渠道")
                }
            }

            Spacer(Modifier.height(18.dp))
        }
    }
}
