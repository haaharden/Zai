package com.self.zai.ui.models

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
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
import com.self.zai.ui.components.ZaiTextField
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

/**
 * Extensible descriptor for OAuth / account-based subscriptions.
 * Future subscriptions (Claude, Gemini, Copilot, etc.) register here as single compact rows.
 */
private data class SubscriptionEntry(
    val providerId: String,
    val title: String,
    val idleSubtitle: String,
    val supportsResetCards: Boolean = false,
)

private val SUBSCRIPTION_CATALOG = listOf(
    SubscriptionEntry(
        providerId = PiBridgeCodec.PRIMARY_OAUTH_PROVIDER_ID,
        title = "ChatGPT Codex",
        idleSubtitle = "Plus / Pro 订阅",
        supportsResetCards = true,
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsAndAuthScreen(
    settings: AppSettings,
    providers: List<ProviderStatus>,
    providerQuotas: Map<String, ProviderQuota>,
    activeModel: ModelOption?,
    authState: AuthUiState,
    onRefreshQuota: (providerId: String, force: Boolean) -> Unit,
    onConsumeResetCredit: suspend (providerId: String, creditId: String?) -> Result<String>,
    onUpdateSubscriptionModels: (providerId: String, selectedModelIds: List<String>) -> Unit,
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
    var editingProvider by remember { mutableStateOf<CustomProviderConfig?>(null) }
    var selectedPreset by remember { mutableStateOf<ProviderPreset?>(null) }
    var showEditorSheet by remember { mutableStateOf(false) }
    var activeSubscriptionDetail by remember { mutableStateOf<SubscriptionEntry?>(null) }
    var lastAutoOpenedUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(authState) {
        val url = (authState as? AuthUiState.InProgress)?.openUrl
        if (!url.isNullOrBlank() && url != lastAutoOpenedUrl) {
            lastAutoOpenedUrl = url
            onOpenBrowserUrl(url)
        }
        if (authState is AuthUiState.Idle) {
            lastAutoOpenedUrl = null
        }
    }

    // Quietly refresh quotas for connected subscriptions and balance-capable API providers
    val customIdsKey = remember(settings.customProviders) {
        settings.customProviders.joinToString(",") { "${it.id}:${it.baseUrl}" }
    }
    LaunchedEffect(providers.size, customIdsKey) {
        providers.filter { it.isConnected && it.supportsOAuth }.forEach { p ->
            onRefreshQuota(p.id, false)
        }
        settings.customProviders.forEach { cfg ->
            if (supportsBalanceLookup(cfg.baseUrl)) {
                onRefreshQuota(cfg.id, false)
            }
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
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            // Section 1: Subscriptions (ultra-clean 1 row per provider; tap for models, quota & reset cards)
            ZaiPaperGroup(title = "订阅") {
                SUBSCRIPTION_CATALOG.forEachIndexed { idx, entry ->
                    val status = providers.firstOrNull { it.id == entry.providerId }
                    val signedIn = status?.credentialType == "oauth"
                    val quota = providerQuotas[entry.providerId]
                    val allCount = status?.models?.size ?: 0
                    val chosenIds = settings.enabledSubscriptionModels[entry.providerId]
                    val enabledCount = if (chosenIds.isNullOrEmpty()) {
                        allCount
                    } else {
                        status?.models?.count { it.id in chosenIds } ?: chosenIds.size
                    }
                    val isLoggingIn = (authState as? AuthUiState.InProgress)?.providerId == entry.providerId

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { activeSubscriptionDetail = entry }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        signedIn -> MaterialTheme.colorScheme.secondary
                                        isLoggingIn -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.outline
                                    },
                                ),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val subLine = when {
                                isLoggingIn -> "登录中…"
                                signedIn -> buildString {
                                    append(quota?.planDisplayLabel() ?: "已连接")
                                    if (allCount > 0) append(" · 已选 $enabledCount/$allCount 模型")
                                }
                                else -> entry.idleSubtitle
                            }
                            Text(
                                text = subLine,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        if (signedIn) {
                            val badge = quota?.compactInlineLabel()
                            if (!badge.isNullOrBlank()) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = badge,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (quota.limitReached) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        } else {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "未登录",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = "查看订阅详情",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    if (idx < SUBSCRIPTION_CATALOG.lastIndex) {
                        ZaiInsetDivider()
                    }
                }
            }

            // Section 2: API Providers & Custom Endpoints (compact rows with inline remaining balance)
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
                        val quota = providerQuotas[cfg.id]
                        val isActiveProvider = activeModel?.providerId == cfg.id
                        val balanceSupported = supportsBalanceLookup(cfg.baseUrl)
                        val inlineBalance = quota?.compactInlineLabel()
                            ?: quota?.error?.takeIf { balanceSupported }
                            ?: if (balanceSupported) "查余额" else null

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    editingProvider = cfg
                                    selectedPreset = null
                                    showEditorSheet = true
                                }
                                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isActiveProvider) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f),
                                    ),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = cfg.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    if (!inlineBalance.isNullOrBlank()) {
                                        Text(
                                            text = inlineBalance,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = when {
                                                quota?.limitReached == true -> MaterialTheme.colorScheme.error
                                                !quota?.summaryText.isNullOrBlank() -> MaterialTheme.colorScheme.primary
                                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                                .clickable { onRefreshQuota(cfg.id, true) }
                                                .padding(horizontal = 7.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                                val host = cfg.baseUrl
                                    .removePrefix("https://")
                                    .removePrefix("http://")
                                    .substringBefore("/")
                                Text(
                                    text = "${cfg.models.size} 个模型 · $host",
                                    style = MaterialTheme.typography.labelSmall,
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
                                    modifier = Modifier.size(17.dp),
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
                                    modifier = Modifier.size(17.dp),
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }

    val openSub = activeSubscriptionDetail
    if (openSub != null) {
        SubscriptionDetailSheet(
            entry = openSub,
            providerStatus = providers.firstOrNull { it.id == openSub.providerId },
            quota = providerQuotas[openSub.providerId],
            enabledModelIds = settings.enabledSubscriptionModels[openSub.providerId],
            activeModel = activeModel,
            authState = authState,
            onRefreshQuota = { onRefreshQuota(openSub.providerId, true) },
            onConsumeResetCredit = { creditId -> onConsumeResetCredit(openSub.providerId, creditId) },
            onSaveEnabledModels = { ids -> onUpdateSubscriptionModels(openSub.providerId, ids) },
            onSelectDefaultModel = { modelId -> onSelectModel(openSub.providerId, modelId) },
            onStartLogin = onStartChatGptLogin,
            onCancelLogin = onCancelLogin,
            onSubmitAuthPrompt = onSubmitAuthPrompt,
            onLogout = onLogoutChatGpt,
            onOpenBrowserUrl = onOpenBrowserUrl,
            onDismiss = { activeSubscriptionDetail = null },
        )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubscriptionDetailSheet(
    entry: SubscriptionEntry,
    providerStatus: ProviderStatus?,
    quota: ProviderQuota?,
    enabledModelIds: List<String>?,
    activeModel: ModelOption?,
    authState: AuthUiState,
    onRefreshQuota: () -> Unit,
    onConsumeResetCredit: suspend (creditId: String?) -> Result<String>,
    onSaveEnabledModels: (List<String>) -> Unit,
    onSelectDefaultModel: (String) -> Unit,
    onStartLogin: (method: String) -> Unit,
    onCancelLogin: () -> Unit,
    onSubmitAuthPrompt: (promptId: String, value: String) -> Unit,
    onLogout: () -> Unit,
    onOpenBrowserUrl: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val signedIn = providerStatus?.credentialType == "oauth"
    val allModels = providerStatus?.models.orEmpty()

    var pasteCallbackInput by remember(authState) { mutableStateOf("") }
    var showResetConfirmDialog by remember { mutableStateOf(false) }
    var redeemingResetCard by remember { mutableStateOf(false) }

    val selectedIds = remember(entry.providerId, enabledModelIds, allModels) {
        mutableStateListOf<String>().apply {
            val initial = if (enabledModelIds.isNullOrEmpty()) {
                allModels.map { it.id }
            } else {
                allModels.map { it.id }.filter { it in enabledModelIds }.ifEmpty { allModels.map { it.id } }
            }
            addAll(initial)
        }
    }

    LaunchedEffect(signedIn) {
        if (signedIn) onRefreshQuota()
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
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = entry.title,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        val plan = quota?.planDisplayLabel()
                        if (!plan.isNullOrBlank()) {
                            Text(
                                text = plan,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f))
                                    .padding(horizontal = 7.dp, vertical = 2.dp),
                            )
                        }
                    }
                    val accountLine = quota?.email?.takeIf { it.isNotBlank() }
                        ?: if (signedIn) "已通过 OAuth 连接" else entry.idleSubtitle
                    Text(
                        text = accountLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (signedIn && authState is AuthUiState.Idle) {
                    TextButton(onClick = onLogout) {
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
                            CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp)
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
                                ) { Text("复制") }
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
                                    onClick = { onSubmitAuthPrompt(authState.promptId, pasteCallbackInput.trim()) },
                                    modifier = Modifier.weight(1f),
                                )
                                ZaiSecondaryButton(text = "取消", onClick = onCancelLogin)
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
                            onClick = { onStartLogin("browser") },
                            modifier = Modifier.weight(1f),
                        )
                        ZaiSecondaryButton(
                            text = "设备码登录",
                            onClick = { onStartLogin("device_code") },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                AuthUiState.Idle -> {
                    if (!signedIn) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ZaiPrimaryButton(
                                text = "浏览器登录 ${entry.title}",
                                onClick = { onStartLogin("browser") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            ZaiSecondaryButton(
                                text = "设备码登录",
                                onClick = { onStartLogin("device_code") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    } else {
                        // 1. Live Quota Detail
                        ProviderQuotaStrip(
                            quota = quota,
                            onRefresh = onRefreshQuota,
                        )

                        // 2. GPT Rate-Limit Reset Card (重置卡)
                        if (entry.supportsResetCards) {
                            val rc = quota?.resetCredits
                            val avail = rc?.availableCount ?: 0
                            val firstCard = rc?.credits?.firstOrNull()
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                                    .padding(horizontal = 13.dp, vertical = 11.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "额度重置卡",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        val desc = when {
                                            rc == null -> "刷新额度以同步账号重置卡"
                                            avail > 0 -> buildString {
                                                append("当前可用 $avail 张")
                                                val exp = firstCard?.expiresAtMs ?: 0L
                                                if (exp > 0L) {
                                                    val fmt = SimpleDateFormat("MM-dd", Locale.CHINA)
                                                    append(" · 有效期至 ${fmt.format(Date(exp))}")
                                                }
                                            }
                                            else -> "当前暂无可用重置卡"
                                        }
                                        Text(
                                            text = desc,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (redeemingResetCard) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    } else {
                                        TextButton(
                                            enabled = avail > 0,
                                            onClick = { showResetConfirmDialog = true },
                                        ) {
                                            Text(
                                                text = if (avail > 0) "使用重置卡" else "暂无可用",
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 3. Selectable Subscription Models Checklist
                        if (allModels.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "可选模型（已启用 ${selectedIds.size}/${allModels.size}）",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(
                                    onClick = {
                                        val allSelected = selectedIds.size == allModels.size
                                        selectedIds.clear()
                                        if (allSelected) {
                                            // Keep at least the active or first flagship model enabled
                                            val keepId = activeModel?.takeIf { it.providerId == entry.providerId }?.id
                                                ?: allModels.first().id
                                            selectedIds.add(keepId)
                                        } else {
                                            allModels.forEach { selectedIds.add(it.id) }
                                        }
                                        onSaveEnabledModels(selectedIds.toList())
                                    },
                                ) {
                                    Text(if (selectedIds.size == allModels.size) "仅留 1 个" else "全选")
                                }
                            }

                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 280.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                    .padding(vertical = 4.dp),
                            ) {
                                items(allModels, key = { it.id }) { m ->
                                    val checked = selectedIds.contains(m.id)
                                    val isDefault = activeModel?.providerId == entry.providerId && activeModel.id == m.id
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                if (checked) {
                                                    if (selectedIds.size <= 1) {
                                                        Toast.makeText(context, "至少保留 1 个启用模型", Toast.LENGTH_SHORT).show()
                                                        return@clickable
                                                    }
                                                    selectedIds.remove(m.id)
                                                } else {
                                                    selectedIds.add(m.id)
                                                }
                                                onSaveEnabledModels(selectedIds.toList())
                                            }
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Checkbox(
                                            checked = checked,
                                            onCheckedChange = { next ->
                                                if (!next) {
                                                    if (selectedIds.size <= 1) {
                                                        Toast.makeText(context, "至少保留 1 个启用模型", Toast.LENGTH_SHORT).show()
                                                        return@Checkbox
                                                    }
                                                    selectedIds.remove(m.id)
                                                } else if (!selectedIds.contains(m.id)) {
                                                    selectedIds.add(m.id)
                                                }
                                                onSaveEnabledModels(selectedIds.toList())
                                            },
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = m.id,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = if (isDefault) FontWeight.SemiBold else FontWeight.Normal,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = buildString {
                                                    append("${m.contextWindow / 1000}K 上下文")
                                                    if (m.supportsImages) append("  视觉")
                                                    if (m.supportsThinkingSelection) append("  思考 ${m.thinkingLevels.size} 档")
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        if (checked) {
                                            TextButton(
                                                onClick = {
                                                    onSelectDefaultModel(m.id)
                                                    Toast.makeText(context, "已设为默认：${m.id}", Toast.LENGTH_SHORT).show()
                                                },
                                            ) {
                                                Text(
                                                    text = if (isDefault) "当前默认" else "设默认",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (isDefault) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    if (showResetConfirmDialog) {
        val firstCreditId = quota?.resetCredits?.credits?.firstOrNull()?.creditId
        AlertDialog(
            onDismissRequest = { showResetConfirmDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp),
            title = { Text("确认使用重置卡？", style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    text = "将消耗 1 张 ChatGPT Codex 额度重置卡，立即刷新当前限额周期。操作一旦执行不可撤销。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmDialog = false }) { Text("取消") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetConfirmDialog = false
                        redeemingResetCard = true
                        scope.launch {
                            val res = onConsumeResetCredit(firstCreditId)
                            redeemingResetCard = false
                            val msg = res.fold(
                                onSuccess = { it },
                                onFailure = { it.message ?: "使用重置卡失败" },
                            )
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        }
                    },
                ) {
                    Text("确认使用", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
            },
        )
    }
}

private fun supportsBalanceLookup(baseUrl: String): Boolean {
    val lower = baseUrl.lowercase()
    return lower.contains("openrouter.ai") || lower.contains("deepseek.com")
}
