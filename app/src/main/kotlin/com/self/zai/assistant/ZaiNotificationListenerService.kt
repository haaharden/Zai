package com.self.zai.assistant

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.self.zai.model.ActiveNotificationItem
import com.self.zai.model.LiveNotificationSnapshotRegistry
import com.self.zai.model.NotificationSnapshotSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Optional, zero-persistence [NotificationListenerService] that lets Zai summarize or query
 * recent notifications on demand when the user grants Notification Access.
 */
class ZaiNotificationListenerService : NotificationListenerService(), NotificationSnapshotSource {

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        _isConnected.value = true
        LiveNotificationSnapshotRegistry.attach(this)
    }

    override fun onListenerDisconnected() {
        LiveNotificationSnapshotRegistry.detach(this)
        if (instance === this) instance = null
        _isConnected.value = false
        super.onListenerDisconnected()
    }

    override fun isConnected(): Boolean = _isConnected.value

    override fun snapshotRecentNotifications(limit: Int): List<ActiveNotificationItem> {
        val active: Array<StatusBarNotification> = runCatching { activeNotifications }.getOrNull() ?: return emptyList()
        return active
            .asSequence()
            .filter { it.packageName != packageName }
            .sortedByDescending { it.postTime }
            .mapNotNull { sbn ->
                val extras = sbn.notification?.extras ?: return@mapNotNull null
                val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
                val text = (
                    extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                        ?: extras.getCharSequence(Notification.EXTRA_TEXT)
                    )?.toString()?.trim().orEmpty()
                if (title.isEmpty() && text.isEmpty()) return@mapNotNull null
                ActiveNotificationItem(
                    packageName = sbn.packageName.orEmpty(),
                    title = title.take(80),
                    text = text.take(180),
                    postTimeMs = sbn.postTime,
                )
            }
            .take(limit.coerceIn(1, 50))
            .toList()
    }

    companion object {
        @Volatile
        var instance: ZaiNotificationListenerService? = null
            private set

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    }
}
