package com.self.zai.assistant

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.self.zai.ZaiApplication
import com.self.zai.phone.ZaiAccessibilityService

/**
 * Quick Settings tile ("Z 助手") offering a zero-friction backup trigger alongside Power 0.5s.
 */
class ZaiAssistTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val svc = ZaiAccessibilityService.instance
        if (svc != null) {
            val dm = resources.displayMetrics
            val snap = runCatching { svc.captureScreenTree(dm.widthPixels, dm.heightPixels) }.getOrNull()
            if (snap != null && snap.packageName.isNotBlank() && snap.packageName != packageName) {
                val app = applicationContext as? ZaiApplication
                val label = app?.container?.appDirectory?.resolveAppLabel(snap.packageName)
                    ?.takeIf { it.isNotBlank() } ?: snap.packageName.substringAfterLast('.')
                AssistantContextBridge.updateScreenContext(
                    CapturedScreenContext(
                        packageName = snap.packageName,
                        appLabel = label,
                        summary = snap.formatForModel(label).take(3_600),
                    ),
                )
            }
        }

        val intent = Intent(this, ZaiAssistActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
            )
            putExtra(ZaiAssistActivity.EXTRA_FROM_ASSISTANT, true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(
                this,
                3021,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
