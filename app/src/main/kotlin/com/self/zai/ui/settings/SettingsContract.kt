package com.self.zai.ui.settings

import android.content.Context
import com.self.zai.model.AppSettings
import com.self.zai.model.CompactionMode
import com.self.zai.model.ModelOption
import com.self.zai.model.ProviderQuota
import com.self.zai.model.ProviderStatus
import com.self.zai.model.WebSearchProvider
import com.self.zai.permission.DevicePermissionHelper
import com.self.zai.shell.ShizukuStatus
import com.self.zai.update.ReleaseUpdateInfo
import java.io.File

/** Snapshot of all system/ROM permission states refreshed on Activity resume. */
data class DevicePermissionsSnapshot(
    val hasOverlay: Boolean,
    val hasNotification: Boolean,
    val batteryIgnored: Boolean,
    val a11ySettingOn: Boolean,
    val hasWriteSecure: Boolean,
    val hasXiaomiBgPopup: Boolean?,
    val isDefaultAssistant: Boolean,
    val voiceInteractionHealthy: Boolean,
    val hasNotifListener: Boolean,
) {
    companion object {
        fun capture(context: Context): DevicePermissionsSnapshot = DevicePermissionsSnapshot(
            hasOverlay = DevicePermissionHelper.hasOverlayPermission(context),
            hasNotification = DevicePermissionHelper.hasNotificationPermission(context),
            batteryIgnored = DevicePermissionHelper.isIgnoringBatteryOptimizations(context),
            a11ySettingOn = DevicePermissionHelper.isAccessibilityEnabledInSettings(context),
            hasWriteSecure = DevicePermissionHelper.hasWriteSecureSettings(context),
            hasXiaomiBgPopup = DevicePermissionHelper.hasXiaomiBgPopupPermission(context),
            isDefaultAssistant = DevicePermissionHelper.isDefaultAssistant(context),
            voiceInteractionHealthy = !DevicePermissionHelper.needsVoiceInteractionRepair(context),
            hasNotifListener = DevicePermissionHelper.isNotificationListenerEnabled(context),
        )
    }
}

/** Immutable UI state contract for [SettingsScreen], replacing 35+ positional parameters. */
data class SettingsUiState(
    val settings: AppSettings,
    val providers: List<ProviderStatus>,
    val providerQuotas: Map<String, ProviderQuota>,
    val defaultModel: ModelOption?,
    val defaultThinkingLevel: String,
    val piReady: Boolean = true,
    val piVersion: String,
    val hotUpdateTag: String?,
    val latestRelease: ReleaseUpdateInfo?,
    val shizukuStatus: ShizukuStatus,
    val accessibilityConnected: Boolean,
    val configuredWebSearchProviders: Set<WebSearchProvider> = emptySet(),
)

/** Explicit user-action callbacks for [SettingsScreen]. */
data class SettingsActions(
    val onReleaseChecked: (ReleaseUpdateInfo?) -> Unit,
    val onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    val onUpdateCompaction: (CompactionMode, Int) -> Unit,
    val onRefreshQuota: (providerId: String, force: Boolean) -> Unit,
    val onSelectDefaultModel: (providerId: String, modelId: String) -> Unit,
    val onSelectDefaultThinking: (String) -> Unit,
    val onUpdateUserPrompt: (String) -> Unit,
    val onOpenSkills: () -> Unit,
    val onOpenMcpServers: () -> Unit,
    val onOpenModels: () -> Unit,
    val onUpdateWebSearchEnabled: (Boolean) -> Unit = {},
    val onUpdateWebSearchPriority: (List<WebSearchProvider>) -> Unit = {},
    val onGetWebSearchApiKey: (WebSearchProvider) -> String? = { null },
    val onSaveWebSearchApiKey: (WebSearchProvider, String) -> Unit = { _, _ -> },
    val onTestWebSearch: suspend () -> Result<String> = { Result.failure(UnsupportedOperationException()) },
    val onRequestShizuku: () -> Unit,
    val onTestShizuku: suspend () -> Result<String>,
    val onRepairAccessibility: suspend () -> Result<Unit>,
    val onGrantWriteSecureViaShizuku: suspend () -> Result<Unit> = { Result.failure(UnsupportedOperationException()) },
    val onShizukuInstallApk: suspend (File) -> Result<String>,
    val onReloadPiHotUpdate: () -> Unit,
    val onResetPiHotUpdate: () -> Unit,
    val onRunSelfCheckPrompt: (String) -> Unit = {},
    val onBack: () -> Unit,
)
