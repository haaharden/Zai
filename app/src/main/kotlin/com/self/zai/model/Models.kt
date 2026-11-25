package com.self.zai.model

import kotlinx.serialization.Serializable

@Serializable
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    AMOLED,
}

@Serializable
enum class ControlChannelMode {
    SHIZUKU_FIRST,
    ACCESSIBILITY_ONLY,
}

@Serializable
enum class PerceptionMode {
    HYBRID,
    TREE_ONLY,
    ALWAYS_VISION,
}

@Serializable
enum class TtsReadoutMode {
    OFF,
    HEADSET_ONLY,
    ALWAYS,
}

@Serializable
enum class CompactionMode {
    OFF,
    AUTO,
    ACP,
    ;

    fun toWire(): String = when (this) {
        OFF -> "off"
        AUTO -> "auto"
        ACP -> "acp"
    }

    fun label(): String = when (this) {
        OFF -> "关闭"
        AUTO -> "自动"
        ACP -> "ACP"
    }
}

@Serializable
data class CustomModelEntry(
    val id: String,
    val contextWindow: Int = 128_000,
    val supportsImages: Boolean = true,
    val reasoning: Boolean = false,
    val efforts: List<String> = emptyList(),
    val api: String = "openai-completions",
)

@Serializable
data class CustomProviderConfig(
    val id: String,
    val name: String,
    val baseUrl: String,
    val models: List<CustomModelEntry> = emptyList(),
    val defaultImages: Boolean = true,
)

@Serializable
data class McpToolSummary(
    val name: String,
    val description: String = "",
    val inputSchemaJson: String = "{\"type\":\"object\",\"properties\":{}}",
)

@Serializable
data class McpServerConfig(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val tools: List<McpToolSummary> = emptyList(),
)

@Serializable
data class SkillEntry(
    val id: String,
    val name: String,
    val description: String,
    val instructions: String,
    val enabled: Boolean = true,
)

@Serializable
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val channelMode: ControlChannelMode = ControlChannelMode.ACCESSIBILITY_ONLY,
    val perceptionMode: PerceptionMode = PerceptionMode.HYBRID,
    val autoRestoreAccessibility: Boolean = true,
    val showFloatingCapsule: Boolean = true,
    val showEdgeGlow: Boolean = true,
    val returnToZaiOnFinish: Boolean = true,
    val confirmSensitiveActions: Boolean = true,
    val maxStepsPerRun: Int = 40,
    val activeProviderId: String = "",
    val activeModelId: String = "",
    val thinkingLevel: String = "off",
    val githubUpdateRepo: String = "haaharden/Zai",
    val customProviders: List<CustomProviderConfig> = emptyList(),
    val userSystemPrompt: String = "",
    val skills: List<SkillEntry> = emptyList(),
    val mcpServers: List<McpServerConfig> = emptyList(),
    val assistantAutoListen: Boolean = true,
    val assistantAttachScreenDefault: Boolean = false,
    val assistantTtsMode: TtsReadoutMode = TtsReadoutMode.HEADSET_ONLY,
    val compactionMode: CompactionMode = CompactionMode.ACP,
    val compactionThresholdPercent: Int = 75,
    val enabledSubscriptionModels: Map<String, List<String>> = emptyMap(),
)

data class ModelOption(
    val providerId: String,
    val providerName: String,
    val id: String,
    val name: String,
    val contextWindow: Int,
    val supportsImages: Boolean,
    val thinkingLevels: List<String>,
) {
    val supportsThinkingSelection: Boolean
        get() = thinkingLevels.size > 1 || (thinkingLevels.size == 1 && thinkingLevels.first() != "off")

    fun clampThinking(requested: String?): String {
        val supported = thinkingLevels.ifEmpty { listOf("off") }
        if (!requested.isNullOrBlank() && requested in supported) return requested
        val order = listOf("off", "minimal", "low", "medium", "high", "xhigh", "max")
        val idx = requested?.let { order.indexOf(it) } ?: -1
        if (idx >= 0) {
            for (i in idx until order.size) {
                if (order[i] in supported) return order[i]
            }
            for (i in idx - 1 downTo 0) {
                if (order[i] in supported) return order[i]
            }
        }
        return if ("medium" in supported) "medium" else supported.first()
    }
}

object ThinkingLevelDisplay {
    fun label(level: String): String = when (level.lowercase()) {
        "off", "none" -> "关闭"
        "minimal" -> "极低"
        "low" -> "低"
        "medium" -> "中"
        "high" -> "高"
        "xhigh" -> "极高"
        "max" -> "最大"
        else -> level
    }
}

data class ProviderStatus(
    val id: String,
    val name: String,
    val credentialType: String?, // "oauth", "api_key", or null
    val isCustom: Boolean,
    val supportsOAuth: Boolean,
    val models: List<ModelOption>,
) {
    val isConnected: Boolean get() = !credentialType.isNullOrBlank()
}

@Serializable
enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM_NOTE,
}

@Serializable
enum class StepState {
    RUNNING,
    DONE,
    ERROR,
}

@Serializable
data class ActionStep(
    val id: String,
    val tool: String,
    val label: String,
    val detail: String = "",
    val state: StepState = StepState.RUNNING,
    val timestampMs: Long = System.currentTimeMillis(),
)

@Serializable
data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val text: String = "",
    val thinking: String = "",
    val steps: List<ActionStep> = emptyList(),
    val isError: Boolean = false,
    val timestampMs: Long = System.currentTimeMillis(),
)

@Serializable
data class ChatSession(
    val id: String,
    val title: String,
    val updatedAtMs: Long,
    val messages: List<ChatMessage> = emptyList(),
    val piTranscriptJson: String? = null,
    val providerId: String? = null,
    val modelId: String? = null,
    val thinkingLevel: String? = null,
)

sealed interface AuthUiState {
    data object Idle : AuthUiState
    data class InProgress(
        val providerId: String,
        val statusMessage: String,
        val openUrl: String? = null,
        val userCode: String? = null,
        val promptId: String? = null,
        val promptMessage: String? = null,
        val promptPlaceholder: String? = null,
    ) : AuthUiState

    data class Failed(val message: String) : AuthUiState
}

data class PendingConfirmation(
    val callId: String,
    val title: String,
    val detail: String,
)

data class QuotaWindow(
    val usedPercent: Int,
    val remainingPercent: Int,
    val windowMinutes: Int,
    val resetAtMs: Long,
) {
    fun windowLabel(): String = when {
        windowMinutes <= 0 -> "周期"
        windowMinutes < 60 -> "${windowMinutes}m"
        windowMinutes % (24 * 60) == 0 -> {
            val days = windowMinutes / (24 * 60)
            if (days == 7) "周" else "${days}天"
        }
        windowMinutes % 60 == 0 -> "${windowMinutes / 60}h"
        else -> "${windowMinutes}m"
    }

    fun formatResetCountdown(nowMs: Long = System.currentTimeMillis()): String {
        if (resetAtMs <= nowMs) return ""
        val mins = ((resetAtMs - nowMs) / 60_000L).coerceAtLeast(1L)
        val days = mins / (24 * 60)
        val hours = (mins % (24 * 60)) / 60
        val remMins = mins % 60
        return when {
            days >= 1 -> "${days}天${if (hours > 0) "${hours}h" else ""}后重置"
            hours >= 1 -> "${hours}h${if (remMins > 0) "${remMins}m" else ""}后重置"
            else -> "${remMins}分钟后重置"
        }
    }
}

data class ResetCreditItem(
    val creditId: String,
    val status: String = "available",
    val resetType: String? = null,
    val expiresAtMs: Long = 0L,
)

data class ResetCreditsInfo(
    val availableCount: Int = 0,
    val applicableCount: Int = 0,
    val credits: List<ResetCreditItem> = emptyList(),
)

data class ProviderQuota(
    val providerId: String,
    val kind: String = "subscription", // "subscription" or "balance"
    val summaryText: String? = null,
    val detailText: String? = null,
    val planType: String? = null,
    val email: String? = null,
    val primary: QuotaWindow? = null,
    val secondary: QuotaWindow? = null,
    val limitReached: Boolean = false,
    val creditsUnlimited: Boolean? = null,
    val creditsBalance: String? = null,
    val resetCredits: ResetCreditsInfo? = null,
    val updatedAtMs: Long = System.currentTimeMillis(),
    val error: String? = null,
) {
    fun planDisplayLabel(): String? = when (planType?.lowercase()) {
        "plus" -> "Plus"
        "pro" -> "Pro"
        "team" -> "Team"
        "enterprise" -> "Enterprise"
        "free" -> "Free"
        null, "" -> null
        else -> planType.replaceFirstChar { it.uppercase() }
    }

    fun compactInlineLabel(): String? = when {
        !summaryText.isNullOrBlank() -> summaryText
        primary != null -> "${primary.windowLabel()} ${primary.remainingPercent}%"
        !creditsBalance.isNullOrBlank() -> "余 $creditsBalance"
        else -> null
    }
}

data class SessionContextUsage(
    val estimatedTokens: Int,
    val contextWindow: Int,
    val percent: Int,
    val archivedBlocks: Int = 0,
    val mode: CompactionMode = CompactionMode.ACP,
    val thresholdPercent: Int = 75,
)
