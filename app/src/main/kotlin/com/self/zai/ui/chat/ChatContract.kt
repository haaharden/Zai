package com.self.zai.ui.chat

import com.self.zai.model.ChatSession
import com.self.zai.model.ModelOption
import com.self.zai.model.PendingAttachment
import com.self.zai.model.PendingConfirmation
import com.self.zai.model.ProviderQuota
import com.self.zai.model.ProviderStatus
import com.self.zai.model.SessionContextUsage
import com.self.zai.model.SkillEntry
import com.self.zai.model.VoiceListenState
import com.self.zai.shell.ShizukuStatus

/** Immutable UI state contract for [ChatScreen], replacing 40+ positional parameters. */
data class ChatUiState(
    val session: ChatSession,
    val allSessions: List<ChatSession>,
    val storageBytes: Long = 0L,
    val providers: List<ProviderStatus>,
    val providerQuotas: Map<String, ProviderQuota>,
    val enabledSkills: List<SkillEntry>,
    val sessionModel: ModelOption?,
    val sessionThinkingLevel: String,
    val contextUsage: SessionContextUsage?,
    val piReady: Boolean,
    val isRunning: Boolean,
    val runningSessionId: String? = null,
    val liveStepLabel: String,
    val shizukuStatus: ShizukuStatus,
    val accessibilityConnected: Boolean,
    val hasNewUpdate: Boolean,
    val pendingConfirmation: PendingConfirmation?,
    val inputText: String,
    val pendingAttachments: List<PendingAttachment> = emptyList(),
    val importingAttachments: Boolean = false,
    val voiceListenState: VoiceListenState = VoiceListenState.IDLE,
    val voiceRmsDb: Float = 0f,
)

/** Explicit user-action callbacks for [ChatScreen]. */
data class ChatActions(
    val onInputTextChange: (String) -> Unit,
    val onPickImages: () -> Unit = {},
    val onTakePhoto: () -> Unit = {},
    val onPickFiles: () -> Unit = {},
    val onPasteClipboardImage: () -> Unit = {},
    val onRemoveAttachment: (String) -> Unit = {},
    val onToggleVoiceInput: () -> Unit = {},
    val onCancelVoiceInput: () -> Unit = {},
    val onRefreshQuota: (providerId: String, force: Boolean) -> Unit = { _, _ -> },
    val onCompactSession: () -> Unit = {},
    val onSendMessage: (String, List<PendingAttachment>) -> Boolean,
    val onStopRun: () -> Unit,
    val onFocusRunningSession: () -> Unit = {},
    val onSelectSession: (String) -> Unit,
    val onNewSession: () -> Unit,
    val onDeleteSession: (String) -> Unit,
    val onSelectSessionModel: (providerId: String, modelId: String) -> Unit,
    val onSelectSessionThinking: (String) -> Unit,
    val onOpenModels: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onRepairAccessibility: suspend () -> Result<Unit> = { Result.failure(UnsupportedOperationException()) },
    val onResolveConfirmation: (callId: String, allow: Boolean) -> Unit,
)

internal data class ImageLightboxData(
    val paths: List<String>,
    val initialIndex: Int = 0,
    val title: String = "",
)
