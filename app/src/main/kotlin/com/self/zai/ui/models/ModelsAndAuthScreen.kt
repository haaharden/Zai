package com.self.zai.ui.models

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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.self.zai.model.AppSettings
import com.self.zai.model.AuthUiState
import com.self.zai.model.CustomModelEntry
import com.self.zai.model.CustomProviderConfig
import com.self.zai.model.ModelOption
import com.self.zai.model.ProviderQuota
import com.self.zai.model.ProviderStatus
import com.self.zai.pi.PiBridgeCodec
import com.self.zai.ui.components.ProviderQuotaStrip
import com.self.zai.ui.components.ZaiInsetDivider
import com.self.zai.ui.components.ZaiPaperGroup
import com.self.zai.ui.components.ZaiPrimaryButton
import com.self.zai.ui.components.ZaiSecondaryButton
import com.self.zai.ui.components.ZaiSelectPill
import com.self.zai.ui.components.ZaiTextField

private val PRESET_PROVIDERS = listOf(
    ProviderPreset(
        name = "DeepSeek",
        baseUrl = "https://api.deepseek.com/v1",
        defaultImages = false,
    ),
    ProviderPreset(
        name = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1",
        defaultImages = true,
    ),
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ModelsAndAuthScreen(
    settings: AppSettings,
    providers: List<ProviderStatus>,
    providerQuotas: Map<String, ProviderQuota>,
    activeModel: ModelOption?,
    authState: AuthUiState,
    onRefreshQuota: (providerId: String, force: Boolean) -> Unit,
    onSelectModel: (providerId: String, modelId: String) -> Unit,
    onStartChatGptLogin: (method: String) -> Unit,
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

    val chatGptProvider = providers.firstOrNull { it.id == PiBridgeCodec.PRIMARY_OAUTH_PROVIDER_ID }
    val codexSignedIn = chatGptProvider?.credentialType == "oauth"

    LaunchedEffect(codexSignedIn) {
        if (codexSignedIn) {
            onRefreshQuota(PiBridgeCodec.PRIMARY_OAUTH_PROVIDER_ID, false)
        }
    }

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
                .padding(horizontal = 20.dp, vertical = 10.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // Section 1: ChatGPT Subscription (OpenAI Codex channel)
            ZaiPaperGroup(title = "ChatGPT Codex 订阅") {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    val signedIn = chatGptProvider?.credentialType == "oauth"
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(
                                    if (signedIn) MaterialTheme.colorScheme.secondary
                                    else MaterialTheme.colorScheme.outline,
                                ),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (signedIn) "ChatGPT Codex" else "ChatGPT",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = if (signedIn) "已连接" else "Plus / Pro 订阅",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (signedIn && authState is AuthUiState.Idle) {
                            TextButton(onClick = onLogoutChatGpt) {
                                Text("退出", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }

                    when (authState) {
                        is AuthUiState.InProgress -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(15.dp),
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
                                    ZaiSecondaryButton(
                                        text = "重新打开浏览器登录页",
                                        onClick = { onOpenBrowserUrl(authState.openUrl) },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }

                                if (!authState.promptId.isNullOrBlank()) {
                                    ZaiTextField(
                                        value = pasteCallbackInput,
                                        onValueChange = { pasteCallbackInput = it },
                                        label = "未能自动跳回时粘贴回调地址",
                                        placeholder = authState.promptPlaceholder.orEmpty(),
                                        monospace = true,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        ZaiPrimaryButton(
                                            text = "提交链接",
                                            enabled = pasteCallbackInput.isNotBlank(),
                                            onClick = {
                                                onSubmitAuthPrompt(authState.promptId, pasteCallbackInput.trim())
                                            },
                                            modifier = Modifier.weight(1f),
                                        )
                                        ZaiSecondaryButton(
                                            text = "取消",
                                            onClick = onCancelLogin,
                                        )
                                    }
                                } else {
                                    ZaiSecondaryButton(
                                        text = "取消登录",
                                        onClick = onCancelLogin,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }

                        is AuthUiState.Failed -> {
                            Text(
                                text = authState.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                ZaiPrimaryButton(
                                    text = "浏览器重试",
                                    onClick = { onStartChatGptLogin("browser") },
                                    modifier = Modifier.weight(1f),
                                )
                                ZaiSecondaryButton(
                                    text = "设备码登录",
                                    onClick = { onStartChatGptLogin("device_code") },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }

                        AuthUiState.Idle -> {
                            if (signedIn) {
                                ProviderQuotaStrip(
                                    quota = providerQuotas[PiBridgeCodec.PRIMARY_OAUTH_PROVIDER_ID],
                                    onRefresh = { onRefreshQuota(PiBridgeCodec.PRIMARY_OAUTH_PROVIDER_ID, true) },
                                )
                                val gptModels = chatGptProvider.models
                                if (gptModels.isNotEmpty()) {
                                    ZaiInsetDivider()
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        gptModels.forEach { m ->
                                            val isCurrent = activeModel?.providerId == PiBridgeCodec.PRIMARY_OAUTH_PROVIDER_ID &&
                                                activeModel.id == m.id
                                            ZaiSelectPill(
                                                label = m.id,
                                                selected = isCurrent,
                                                monospace = true,
                                                onClick = { onSelectModel(PiBridgeCodec.PRIMARY_OAUTH_PROVIDER_ID, m.id) },
                                            )
                                        }
                                    }
                                }
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ZaiPrimaryButton(
                                        text = "浏览器登录 ChatGPT",
                                        onClick = { onStartChatGptLogin("browser") },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    ZaiSecondaryButton(
                                        text = "设备码登录",
                                        onClick = { onStartChatGptLogin("device_code") },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Section 2: Presets (DeepSeek & OpenRouter) + Custom Endpoints
            ZaiPaperGroup(
                title = "供应商与自定义接口",
                trailing = {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                editingProvider = null
                                selectedPreset = null
                                showEditorSheet = true
                            }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = "自定义",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PRESET_PROVIDERS.forEach { preset ->
                        ZaiSecondaryButton(
                            text = preset.name,
                            onClick = {
                                editingProvider = null
                                selectedPreset = preset
                                showEditorSheet = true
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                if (settings.customProviders.isNotEmpty()) {
                    settings.customProviders.forEach { cfg ->
                        ZaiInsetDivider()
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
                                        selectedPreset = null
                                        showEditorSheet = true
                                    },
                                    modifier = Modifier.size(34.dp),
                                ) {
                                    Icon(
                                        Icons.Rounded.Edit,
                                        contentDescription = "编辑渠道",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                IconButton(
                                    onClick = { onDeleteCustomProvider(cfg.id) },
                                    modifier = Modifier.size(34.dp),
                                ) {
                                    Icon(
                                        Icons.Rounded.DeleteOutline,
                                        contentDescription = "删除渠道",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }

                            if (cfg.models.isNotEmpty()) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    cfg.models.forEach { m ->
                                        val isCurrent = activeModel?.providerId == cfg.id && activeModel.id == m.id
                                        ZaiSelectPill(
                                            label = m.id,
                                            selected = isCurrent,
                                            monospace = true,
                                            onClick = { onSelectModel(cfg.id, m.id) },
                                        )
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
