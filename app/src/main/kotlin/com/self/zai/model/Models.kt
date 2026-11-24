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
data class CustomModelEntry(
    val id: String,
    val contextWindow: Int = 128_000,
    val supportsImages: Boolean = true,
    val reasoning: Boolean = false,
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
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val channelMode: ControlChannelMode = ControlChannelMode.ACCESSIBILITY_ONLY,
    val perceptionMode: PerceptionMode = PerceptionMode.HYBRID,
    val autoRestoreAccessibility: Boolean = true,
    val showFloatingCapsule: Boolean = true,
    val confirmSensitiveActions: Boolean = true,
    val maxStepsPerRun: Int = 40,
    val activeProviderId: String = "",
    val activeModelId: String = "",
    val thinkingLevel: String = "off",
    val githubUpdateRepo: String = "haaharden/Zai",
    val customProviders: List<CustomProviderConfig> = emptyList(),
)

data class ModelOption(
    val providerId: String,
    val providerName: String,
    val id: String,
    val name: String,
    val contextWindow: Int,
    val supportsImages: Boolean,
    val thinkingLevels: List<String>,
)

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
