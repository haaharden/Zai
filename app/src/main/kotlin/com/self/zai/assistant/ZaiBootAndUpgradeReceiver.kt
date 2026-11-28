package com.self.zai.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.self.zai.di.ZaiContainerProvider

/**
 * Automatically heals `voice_interaction_service`, `voice_recognition_service`, and Accessibility
 * binding immediately after Zai APK upgrades or device reboots so long-pressing Power 0.5s
 * continues to invoke Zai voice reliably without manual reconfiguration.
 */
class ZaiBootAndUpgradeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }
        val host = context.applicationContext as? ZaiContainerProvider ?: return
        host.container.deviceHealthCoordinator.onBootOrUpgrade()
    }
}
