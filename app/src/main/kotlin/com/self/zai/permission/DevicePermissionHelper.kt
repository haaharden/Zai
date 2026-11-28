package com.self.zai.permission

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.speech.RecognitionService
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.self.zai.model.ZaiComponentNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class AccessibilityRestorePlan(
    val alreadyPresent: Boolean,
    val withoutSelf: String,
    val withSelf: String,
)

/**
 * Leaf-level Android permission & settings helper depending ONLY on `com.self.zai.model`.
 * Zero imports of concrete Activity/Service classes so there are zero package cycles.
 */
object DevicePermissionHelper {

    private const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
    private const val EXTRA_SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"
    private const val REBIND_TOGGLE_DELAY_MS = 180L
    private const val BIND_POLL_INTERVAL_MS = 50L

    private fun accessibilityComponent(context: Context): ComponentName =
        ComponentName(context.packageName, ZaiComponentNames.ACCESSIBILITY_SERVICE)

    private fun voiceInteractionComponent(context: Context): ComponentName =
        ComponentName(context.packageName, ZaiComponentNames.VOICE_INTERACTION_SERVICE)

    private fun notificationListenerComponent(context: Context): ComponentName =
        ComponentName(context.packageName, ZaiComponentNames.NOTIFICATION_LISTENER_SERVICE)

    fun isXiaomiDevice(): Boolean {
        val brand = Build.BRAND.lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()
        return brand.contains("xiaomi") ||
            brand.contains("redmi") ||
            brand.contains("poco") ||
            manufacturer.contains("xiaomi")
    }

    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.areNotificationsEnabled()
    }

    fun hasOverlayPermission(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun isComponentEnabledInAccessibilityList(
        rawEnabledServices: String?,
        fullComponent: String,
        shortComponent: String,
    ): Boolean {
        val cleaned = rawEnabledServices?.trim().orEmpty()
        if (cleaned.isEmpty() || cleaned.equals("null", ignoreCase = true)) return false
        return cleaned
            .split(":")
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .any {
                it.equals(fullComponent, ignoreCase = true) ||
                    it.equals(shortComponent, ignoreCase = true)
            }
    }

    fun buildAccessibilityRestorePlan(
        rawEnabledServices: String?,
        fullComponent: String,
        shortComponent: String,
    ): AccessibilityRestorePlan {
        val cleaned = rawEnabledServices?.trim().orEmpty()
        val entries = if (cleaned.isEmpty() || cleaned.equals("null", ignoreCase = true)) {
            emptyList()
        } else {
            cleaned.split(":").map { it.trim() }.filter { it.isNotEmpty() }
        }
        val alreadyPresent = entries.any {
            it.equals(fullComponent, ignoreCase = true) ||
                it.equals(shortComponent, ignoreCase = true)
        }
        val others = entries.filterNot {
            it.equals(fullComponent, ignoreCase = true) ||
                it.equals(shortComponent, ignoreCase = true)
        }
        return AccessibilityRestorePlan(
            alreadyPresent = alreadyPresent,
            withoutSelf = others.joinToString(":"),
            withSelf = (others + fullComponent).joinToString(":"),
        )
    }

    fun isAccessibilityEnabledInSettings(context: Context): Boolean {
        val component = accessibilityComponent(context)
        val raw = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        }.getOrNull()
        return isComponentEnabledInAccessibilityList(
            rawEnabledServices = raw,
            fullComponent = component.flattenToString(),
            shortComponent = component.flattenToShortString(),
        )
    }

    fun hasWriteSecureSettings(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_SECURE_SETTINGS,
        ) == PackageManager.PERMISSION_GRANTED

    fun canAutoRestoreAccessibility(context: Context, shizukuReady: Boolean): Boolean =
        hasWriteSecureSettings(context) || shizukuReady

    fun hasAudioPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

    fun isDefaultAssistant(context: Context): Boolean {
        val roleHeld = runCatching {
            val rm = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
            rm?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
        }.getOrDefault(false)
        if (roleHeld) return true
        val secureAssist = runCatching {
            Settings.Secure.getString(context.contentResolver, "assistant").orEmpty()
        }.getOrDefault("")
        val secureVoice = runCatching {
            Settings.Secure.getString(context.contentResolver, "voice_interaction_service").orEmpty()
        }.getOrDefault("")
        return secureAssist.startsWith("${context.packageName}/") ||
            secureVoice.startsWith("${context.packageName}/")
    }

    fun isVoiceInteractionBindingHealthy(
        secureVoiceInteraction: String?,
        expectedFullComponent: String,
        expectedShortComponent: String,
    ): Boolean {
        val cleaned = secureVoiceInteraction?.trim().orEmpty()
        if (cleaned.isEmpty() || cleaned.equals("null", ignoreCase = true)) return false
        return cleaned.equals(expectedFullComponent, ignoreCase = true) ||
            cleaned.equals(expectedShortComponent, ignoreCase = true)
    }

    fun isVoiceInteractionActiveInSettings(context: Context): Boolean {
        val component = voiceInteractionComponent(context)
        val raw = runCatching {
            Settings.Secure.getString(context.contentResolver, "voice_interaction_service")
        }.getOrNull()
        return isVoiceInteractionBindingHealthy(
            secureVoiceInteraction = raw,
            expectedFullComponent = component.flattenToString(),
            expectedShortComponent = component.flattenToShortString(),
        )
    }

    /**
     * True when Zai is selected as the system default assistant (`ROLE_ASSISTANT` / `assistant`),
     * yet HyperOS/Android cleared `voice_interaction_service` to empty.
     */
    fun needsVoiceInteractionRepair(context: Context): Boolean =
        isDefaultAssistant(context) && !isVoiceInteractionActiveInSettings(context)

    fun currentConfiguredRecognitionService(context: Context): ComponentName? {
        val raw = runCatching {
            Settings.Secure.getString(context.contentResolver, "voice_recognition_service")
        }.getOrNull()?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return ComponentName.unflattenFromString(raw)
    }

    fun ensureSystemVoiceRecognitionService(context: Context, externalComponent: ComponentName?) {
        if (externalComponent == null) return
        val current = currentConfiguredRecognitionService(context)
        if (current != null && current.packageName != context.packageName) return
        if (!hasWriteSecureSettings(context)) return
        runCatching {
            Settings.Secure.putString(
                context.contentResolver,
                "voice_recognition_service",
                externalComponent.flattenToString(),
            )
        }
    }

    fun resolveExternalRecognitionService(context: Context): ComponentName? {
        val intent = Intent(RecognitionService.SERVICE_INTERFACE)
        val resolved = runCatching {
            context.packageManager.queryIntentServices(intent, 0)
        }.getOrDefault(emptyList())
            .mapNotNull { it.serviceInfo }
            .filter { it.enabled && it.packageName != context.packageName }

        if (resolved.isEmpty()) return null
        val preferred = resolved.firstOrNull { it.packageName == "com.xiaomi.mibrain.speech" }
            ?: resolved.firstOrNull { it.packageName.contains("google") }
            ?: resolved.first()
        return ComponentName(preferred.packageName, preferred.name)
    }

    /**
     * Restores `Settings.Secure.voice_interaction_service` (and preserves external ASR in
     * `voice_recognition_service`) when Zai holds `WRITE_SECURE_SETTINGS`.
     */
    fun restoreVoiceInteractionServiceIfNeeded(context: Context): Result<Boolean> = runCatching {
        if (!needsVoiceInteractionRepair(context)) return@runCatching false
        require(hasWriteSecureSettings(context)) { "未授予 WRITE_SECURE_SETTINGS 权限" }
        val component = voiceInteractionComponent(context).flattenToString()
        val okVoice = Settings.Secure.putString(
            context.contentResolver,
            "voice_interaction_service",
            component,
        )
        val currentAssist = runCatching {
            Settings.Secure.getString(context.contentResolver, "assistant").orEmpty()
        }.getOrDefault("")
        if (!currentAssist.startsWith("${context.packageName}/")) {
            Settings.Secure.putString(context.contentResolver, "assistant", component)
        }
        val externalAsr = resolveExternalRecognitionService(context.applicationContext)
        ensureSystemVoiceRecognitionService(context.applicationContext, externalAsr)
        require(okVoice) { "系统拒绝写入 voice_interaction_service" }
        true
    }

    fun isNotificationListenerEnabled(context: Context): Boolean {
        val expected = notificationListenerComponent(context).flattenToString()
        val raw = runCatching {
            Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        }.getOrNull().orEmpty()
        return raw.split(":").any {
            it.equals(expected, ignoreCase = true) ||
                it.contains("${context.packageName}/.assistant.ZaiNotificationListenerService", ignoreCase = true)
        }
    }

    fun openVoiceInputSettings(context: Context) {
        val ok = startSafe(context, Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) ||
            startSafe(context, Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        if (!ok) openAppDetails(context)
    }

    fun openNotificationListenerSettings(context: Context) {
        val ok = startSafe(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        if (!ok) openAppDetails(context)
    }

    fun openAssistOverlayPreview(context: Context) {
        val intent = Intent().apply {
            setClassName(context.packageName, ZaiComponentNames.ASSIST_ACTIVITY)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
            )
            putExtra(ZaiComponentNames.EXTRA_FROM_ASSISTANT, true)
        }
        startSafe(context, intent)
    }

    /**
     * Checks Xiaomi MIUI/HyperOS proprietary OP_BACKGROUND_START_ACTIVITY (op 10021).
     * Returns null if unavailable on non-Xiaomi or restricted ROMs.
     */
    fun hasXiaomiBgPopupPermission(context: Context): Boolean? {
        if (!isXiaomiDevice()) return true
        return runCatching {
            val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val method = ops.javaClass.getMethod(
                "checkOpNoThrow",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
            )
            val mode = method.invoke(ops, 10021, Process.myUid(), context.packageName) as Int
            mode == AppOpsManager.MODE_ALLOWED
        }.getOrNull()
    }

    suspend fun awaitAccessibilityConnected(
        isAccessibilityConnected: () -> Boolean,
        timeoutMs: Long = 1_500L,
    ): Boolean {
        if (isAccessibilityConnected()) return true
        val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(100L)
        while (System.currentTimeMillis() < deadline) {
            delay(BIND_POLL_INTERVAL_MS)
            if (isAccessibilityConnected()) return true
        }
        return isAccessibilityConnected()
    }

    suspend fun restoreAccessibilityViaSecureSettings(
        context: Context,
        isAccessibilityConnected: () -> Boolean,
        waitForBindTimeoutMs: Long = 1_500L,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (isAccessibilityConnected()) return@runCatching
            require(hasWriteSecureSettings(context)) { "未授予 WRITE_SECURE_SETTINGS 权限" }
            val component = accessibilityComponent(context)
            val existingRaw = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
            val plan = buildAccessibilityRestorePlan(
                rawEnabledServices = existingRaw,
                fullComponent = component.flattenToString(),
                shortComponent = component.flattenToShortString(),
            )
            if (plan.alreadyPresent) {
                Settings.Secure.putString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    plan.withoutSelf,
                )
                delay(REBIND_TOGGLE_DELAY_MS)
            }
            val ok1 = Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                plan.withSelf,
            )
            val ok2 = Settings.Secure.putInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                1,
            )
            require(ok1 && ok2) { "系统拒绝写入无障碍配置" }
            val bound = awaitAccessibilityConnected(isAccessibilityConnected, waitForBindTimeoutMs)
            require(bound) {
                "已写入无障碍配置，但系统未完成绑定，请在无障碍页面手动确认（如开关变灰请先允许受限制的设置）"
            }
        }
    }

    fun buildAccessibilitySettingsIntent(
        component: ComponentName,
        includeFragmentArgs: Boolean = true,
    ): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
        if (includeFragmentArgs) {
            val flattened = component.flattenToString()
            putExtra(EXTRA_FRAGMENT_ARG_KEY, flattened)
            putExtra(
                EXTRA_SHOW_FRAGMENT_ARGS,
                Bundle().apply { putString(EXTRA_FRAGMENT_ARG_KEY, flattened) },
            )
        }
    }

    fun openAccessibilitySettings(
        context: Context,
        isCurrentlyConnected: Boolean = false,
        showGuideToast: Boolean = true,
    ) {
        val component = accessibilityComponent(context)
        val wasSettingOnButUnbound =
            isAccessibilityEnabledInSettings(context) && !isCurrentlyConnected

        val launched = startSafe(
            context = context,
            intent = buildAccessibilitySettingsIntent(component, includeFragmentArgs = true),
            preferCurrentTask = true,
            clearTopOnNewTask = true,
        ) || startSafe(
            context = context,
            intent = buildAccessibilitySettingsIntent(component, includeFragmentArgs = false),
            preferCurrentTask = true,
            clearTopOnNewTask = true,
        )

        if (!launched) {
            openAppDetails(context)
            return
        }

        if (showGuideToast) {
            val guide = when {
                wasSettingOnButUnbound && isXiaomiDevice() ->
                    "请在「已下载的应用」中将「Zai 手机操控」关闭后重开"
                wasSettingOnButUnbound ->
                    "请将「Zai 手机操控」关闭后重开以重新连接"
                isXiaomiDevice() ->
                    "请在「已下载的应用」中开启「Zai 手机操控」"
                else ->
                    "请找到并开启「Zai 手机操控」"
            }
            Toast.makeText(context.applicationContext, guide, Toast.LENGTH_LONG).show()
        }
    }

    fun openOverlaySettings(context: Context) {
        val ok = startSafe(
            context,
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ),
        )
        if (!ok) openAppDetails(context)
    }

    fun openNotificationSettings(context: Context) {
        val ok = startSafe(
            context,
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            },
        )
        if (!ok) openAppDetails(context)
    }

    fun openBatterySettings(context: Context) {
        val launched = if (isXiaomiDevice()) {
            openXiaomiBatterySaver(context) || startSafe(
                context,
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                },
            )
        } else {
            startSafe(
                context,
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                },
            )
        }
        if (!launched) openAppDetails(context)
    }

    fun openShizukuApp(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        if (intent != null && startSafe(context, intent)) return true
        return startSafe(
            context,
            Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/")),
        )
    }

    fun openXiaomiAutoStart(context: Context) {
        val candidates = listOf(
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                ),
            ),
            Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
        )
        if (!candidates.any { startSafe(context, it) }) openAppDetails(context)
    }

    fun openXiaomiOtherPermissions(context: Context) {
        val candidates = listOf(
            Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity",
                )
                putExtra("extra_pkgname", context.packageName)
            },
            Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                putExtra("extra_pkgname", context.packageName)
            },
        )
        if (!candidates.any { startSafe(context, it) }) openAppDetails(context)
    }

    private fun openXiaomiBatterySaver(context: Context): Boolean {
        val intent = Intent().apply {
            setComponent(
                ComponentName(
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
                ),
            )
            putExtra("package_name", context.packageName)
            putExtra("package_label", "Zai")
        }
        return startSafe(context, intent)
    }

    fun openAppDetails(context: Context): Boolean = startSafe(
        context,
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        ),
    )

    private fun findActivity(context: Context): Activity? {
        var cur: Context? = context
        while (cur is ContextWrapper) {
            if (cur is Activity) return cur
            cur = cur.baseContext
        }
        return cur as? Activity
    }

    private fun startSafe(
        context: Context,
        intent: Intent,
        preferCurrentTask: Boolean = false,
        clearTopOnNewTask: Boolean = false,
    ): Boolean {
        if (preferCurrentTask) {
            val activity = findActivity(context)
            if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                val launchedInTask = runCatching {
                    activity.startActivity(intent)
                    true
                }.getOrDefault(false)
                if (launchedInTask) return true
            }
        }
        return runCatching {
            val newTaskIntent = Intent(intent).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (clearTopOnNewTask) addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(newTaskIntent)
            true
        }.getOrDefault(false)
    }
}
