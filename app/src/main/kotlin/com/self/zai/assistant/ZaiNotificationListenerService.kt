package com.self.zai.assistant

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ActiveNotificationItem(
    val packageName: String,
    val title: String,
    val text: String,
    val postTimeMs: Long,
) {
    fun formatLine(appLabel: String? = null): String {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(postTimeMs))
        val source = appLabel?.ifBlank { packageName } ?: packageName
        return buildString {
            append("[$time] $source")
            if (title.isNotBlank()) append(" · ").append(title)
            if (text.isNotBlank()) append(": ").append(text)
        }
    }
}

/**
 * Optional, zero-persistence [NotificationListenerService] that lets Zai summarize or query
 * recent notifications on demand when the user grants Notification Access.
 */
class ZaiNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        _isConnected.value = true
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        _isConnected.value = false
        super.onListenerDisconnected()
    }

    fun snapshotRecentNotifications(limit: Int = 20): List<ActiveNotificationItem> {
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
