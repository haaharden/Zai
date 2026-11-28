package com.self.zai.agent

import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.self.zai.model.ZaiComponentNames
import com.self.zai.phone.ZaiAccessibilityService
import com.self.zai.service.ZaiAgentService
import com.self.zai.shell.ShizukuBridge
import kotlinx.coroutines.delay

/**
 * Abstraction for returning the app to the foreground once an external phone automation run finishes.
 */
interface AppReturnNavigator {
    /**
     * Attempts to bring the app to the foreground BEFORE tearing down active foreground services / overlays.
     * Returns true if the app actually resumed into the foreground, false if OEM restrictions blocked it.
     */
    suspend fun attemptBringToFront(isForeground: () -> Boolean): Boolean
}

/**
 * Multi-strategy Android/HyperOS foreground bring-back sequence:
 * 1. ActivityManager.appTasks.moveToFront + AccessibilityService/FGS startActivity
 * 2. PendingIntent with Android 14–16 explicit BAL allow option
 * 3. Shizuku `am start` fallback when connected
 */
class AndroidAppReturnNavigator(
    private val context: Context,
    private val shizuku: ShizukuBridge,
) : AppReturnNavigator {

    override suspend fun attemptBringToFront(isForeground: () -> Boolean): Boolean {
        val launchIntent = Intent().apply {
            setClassName(context.packageName, ZaiComponentNames.MAIN_ACTIVITY)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
            )
        }
        val launcherIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
            )
        } ?: launchIntent

        // Attempt 1: Move existing task to front + launch via active privileged contexts
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.appTasks.firstOrNull()?.moveToFront()
        }
        ZaiAccessibilityService.instance?.let { svc -> runCatching { svc.startActivity(launchIntent) } }
        ZaiAgentService.instance?.let { svc -> runCatching { svc.startActivity(launchIntent) } }
        runCatching { context.startActivity(launchIntent) }

        delay(360L)
        if (isForeground()) return true

        // Attempt 2: PendingIntent with Android 14–16 explicit BAL allowance
        runCatching {
            val pi = PendingIntent.getActivity(
                context,
                902,
                launcherIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val mode = if (Build.VERSION.SDK_INT >= 36) {
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                } else {
                    @Suppress("DEPRECATION")
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                }
                val opts = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(mode)
                pi.send(context, 0, null, null, null, null, opts.toBundle())
            } else {
                pi.send()
            }
        }

        delay(360L)
        if (isForeground()) return true

        // Attempt 3: Shizuku shell am start if ready
        if (shizuku.status.value.isReady) {
            shizuku.exec("am start -n ${context.packageName}/${ZaiComponentNames.MAIN_ACTIVITY}", 4_000)
            delay(360L)
            if (isForeground()) return true
        }

        return isForeground()
    }
}
