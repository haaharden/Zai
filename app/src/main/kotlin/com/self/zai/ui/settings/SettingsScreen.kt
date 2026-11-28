package com.self.zai.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.self.zai.model.WebSearchProvider
import com.self.zai.ui.capabilities.UserPromptEditorSheet
import com.self.zai.ui.components.ZaiModelPickerSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var perms by remember { mutableStateOf(DevicePermissionsSnapshot.capture(context)) }
    var showDefaultModelSheet by remember { mutableStateOf(false) }
    var showUserPromptSheet by remember { mutableStateOf(false) }
    var showSelfCheckSheet by remember { mutableStateOf(false) }
    var editingWebKeyProvider by remember { mutableStateOf<WebSearchProvider?>(null) }
    var repairingA11y by remember { mutableStateOf(false) }

    val refreshPermissions = { perms = DevicePermissionsSnapshot.capture(context) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshPermissions()
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
                    IconButton(onClick = actions.onBack) {
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
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            ModelAndCompactionSection(
                state = state,
                actions = actions,
                onOpenDefaultModelSheet = { showDefaultModelSheet = true },
            )

            CapabilitiesAndSearchSection(
                state = state,
                actions = actions,
                onOpenUserPromptSheet = { showUserPromptSheet = true },
                onEditWebKey = { editingWebKeyProvider = it },
            )

            AssistantVoiceSection(
                state = state,
                perms = perms,
                actions = actions,
                onRefreshPermissions = refreshPermissions,
            )

            PhoneControlSection(
                state = state,
                perms = perms,
                actions = actions,
                repairingA11y = repairingA11y,
                onSetRepairingA11y = { repairingA11y = it },
                onRefreshPermissions = refreshPermissions,
                onOpenSelfCheckSheet = { showSelfCheckSheet = true },
            )

            AppearanceSection(
                themeMode = state.settings.themeMode,
                onSelectTheme = { mode -> actions.onUpdateSettings { it.copy(themeMode = mode) } },
            )

            SystemPermissionsSection(perms = perms)

            UpdatesAndKernelSection(state = state, actions = actions)

            Spacer(Modifier.height(14.dp))
        }
    }

    if (showDefaultModelSheet) {
        ZaiModelPickerSheet(
            title = "选择默认模型",
            providers = state.providers,
            selectedModel = state.defaultModel,
            providerQuotas = state.providerQuotas,
            onRefreshQuota = actions.onRefreshQuota,
            onSelect = actions.onSelectDefaultModel,
            onManageProviders = actions.onOpenModels,
            onDismiss = { showDefaultModelSheet = false },
        )
    }

    if (showUserPromptSheet) {
        UserPromptEditorSheet(
            initialPrompt = state.settings.userSystemPrompt,
            onSave = actions.onUpdateUserPrompt,
            onDismiss = { showUserPromptSheet = false },
        )
    }

    editingWebKeyProvider?.let { keyProvider ->
        WebSearchKeySheet(
            keyProvider = keyProvider,
            initialKey = remember(keyProvider) { actions.onGetWebSearchApiKey(keyProvider).orEmpty() },
            onSaveKey = actions.onSaveWebSearchApiKey,
            onDismiss = { editingWebKeyProvider = null },
        )
    }

    if (showSelfCheckSheet) {
        SelfCheckSheet(
            state = state,
            perms = perms,
            repairingA11y = repairingA11y,
            onSetRepairingA11y = { repairingA11y = it },
            onRefreshPermissions = refreshPermissions,
            onRepairAccessibility = actions.onRepairAccessibility,
            onOpenModels = actions.onOpenModels,
            onRunSelfCheckPrompt = actions.onRunSelfCheckPrompt,
            onDismiss = { showSelfCheckSheet = false },
        )
    }
}
