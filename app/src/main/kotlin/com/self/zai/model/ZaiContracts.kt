package com.self.zai.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pure domain contracts and manifest component names shared across layers so lower layers
 * (`permission`, `phone`, `service`, `agent`, `ui`) never create circular package dependencies.
 */
enum class VoiceListenState {
    IDLE,
    LISTENING,
    ERROR,
}

/** Implemented by Application so foreground services can trigger stop without importing Application directly. */
interface AgentStopHandler {
    fun stopActiveRun()
}

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

/** Read-only notification access abstraction injected into phone native tools. */
interface NotificationSnapshotSource {
    fun isConnected(): Boolean
    fun snapshotRecentNotifications(limit: Int = 20): List<ActiveNotificationItem>
}

object UnavailableNotificationSnapshotSource : NotificationSnapshotSource {
    override fun isConnected(): Boolean = false
    override fun snapshotRecentNotifications(limit: Int): List<ActiveNotificationItem> = emptyList()
}

/** Lightweight registry bridging Android's system-bound NotificationListenerService to phone tools without package cycles. */
object LiveNotificationSnapshotRegistry : NotificationSnapshotSource {
    @Volatile
    private var delegate: NotificationSnapshotSource? = null

    fun attach(source: NotificationSnapshotSource) {
        delegate = source
    }

    fun detach(source: NotificationSnapshotSource) {
        if (delegate === source) delegate = null
    }

    override fun isConnected(): Boolean = delegate?.isConnected() == true

    override fun snapshotRecentNotifications(limit: Int): List<ActiveNotificationItem> =
        delegate?.snapshotRecentNotifications(limit).orEmpty()
}

data class CapturedScreenContext(
    val packageName: String,
    val appLabel: String,
    val summary: String,
    val capturedAtMs: Long = System.currentTimeMillis(),
)

/** Canonical Android component class names matching AndroidManifest.xml. */
object ZaiComponentNames {
    const val MAIN_ACTIVITY = "com.self.zai.MainActivity"
    const val ASSIST_ACTIVITY = "com.self.zai.assistant.ZaiAssistActivity"
    const val ACCESSIBILITY_SERVICE = "com.self.zai.phone.ZaiAccessibilityService"
    const val VOICE_INTERACTION_SERVICE = "com.self.zai.assistant.ZaiVoiceInteractionService"
    const val NOTIFICATION_LISTENER_SERVICE = "com.self.zai.assistant.ZaiNotificationListenerService"
    const val EXTRA_FROM_ASSISTANT = "extra_from_assistant"
    const val EXTRA_SHOW_FLAGS = "extra_show_flags"
}
