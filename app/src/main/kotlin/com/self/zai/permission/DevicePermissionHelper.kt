package com.self.zai.permission

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.self.zai.assistant.ZaiAssistActivity
import com.self.zai.assistant.ZaiNotificationListenerService
import com.self.zai.phone.ZaiAccessibilityService

object DevicePermissionHelper {

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

    fun isAccessibilityEnabledInSettings(context: Context): Boolean {
        val expected = ComponentName(context, ZaiAccessibilityService::class.java).flattenToString()
        val raw = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        }.getOrNull().orEmpty()
        return raw.split(":").any { it.equals(expected, ignoreCase = true) }
    }

    fun hasWriteSecureSettings(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_SECURE_SETTINGS,
        ) == PackageManager.PERMISSION_GRANTED

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

    fun isNotificationListenerEnabled(context: Context): Boolean {
        val expected = ComponentName(context, ZaiNotificationListenerService::class.java).flattenToString()
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
        val intent = Intent(context, ZaiAssistActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
            )
            putExtra(ZaiAssistActivity.EXTRA_FROM_ASSISTANT, true)
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

    /**
     * Restores Zai's AccessibilityService directly via Android Secure Settings without needing Shizuku
     * or Developer Options (requires a one-time `adb shell pm grant com.self.zai android.permission.WRITE_SECURE_SETTINGS`).
     */
    fun restoreAccessibilityViaSecureSettings(context: Context): Result<Unit> = runCatching {
        require(hasWriteSecureSettings(context)) { "未授予 WRITE_SECURE_SETTINGS 权限" }
        val component = ComponentName(context, ZaiAccessibilityService::class.java).flattenToString()
        val existingRaw = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        val services = existingRaw.split(":").map { it.trim() }.filter { it.isNotEmpty() }
        val merged = (services.filterNot { it.equals(component, ignoreCase = true) } + component)
            .joinToString(":")
        val ok1 = Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            merged,
        )
        val ok2 = Settings.Secure.putInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            1,
        )
        require(ok1 && ok2) { "系统拒绝写入无障碍配置" }
    }

    fun openAccessibilitySettings(context: Context) {
        startSafe(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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

    private fun startSafe(context: Context, intent: Intent): Boolean = runCatching {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}
