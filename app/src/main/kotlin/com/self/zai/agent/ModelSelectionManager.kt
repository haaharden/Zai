package com.self.zai.agent

import com.self.zai.data.CredentialVault
import com.self.zai.data.SessionRepository
import com.self.zai.model.ChatSession
import com.self.zai.model.CustomModelEntry
import com.self.zai.model.CustomProviderConfig
import com.self.zai.model.ModelOption
import com.self.zai.model.ProviderStatus
import com.self.zai.pi.AgentRuntime
import java.util.UUID

/**
 * Manages global default vs per-session model & dynamic thinking-effort selection,
 * provider validity clamping, and custom provider CRUD.
 * Pure Kotlin with zero Android Context dependencies for straightforward unit testing.
 */
class ModelSelectionManager(
    private val repository: SessionRepository,
    private val credentialVault: CredentialVault,
    private val runtime: AgentRuntime,
    private val onSessionConfigInvalidated: (sessionId: String?) -> Unit,
) {
    fun allAvailableModels(): List<ModelOption> =
        runtime.providers.value
            .filter { it.isConnected }
            .flatMap { it.models }

    /** Global default model used when creating new conversations. */
    fun defaultModelOption(): ModelOption? {
        val s = repository.settings.value
        val available = allAvailableModels()
        return available.firstOrNull {
            it.providerId == s.activeProviderId && it.id == s.activeModelId
        } ?: available.firstOrNull()
    }

    fun defaultThinkingLevel(): String {
        val model = defaultModelOption()
        val saved = repository.settings.value.thinkingLevel
        return model?.clampThinking(saved) ?: saved
    }

    /** Active model for the specified conversation (falls back to global default). */
    fun sessionModelOption(session: ChatSession): ModelOption? {
        val available = allAvailableModels()
        if (!session.providerId.isNullOrBlank() && !session.modelId.isNullOrBlank()) {
            available.firstOrNull {
                it.providerId == session.providerId && it.id == session.modelId
            }?.let { return it }
        }
        return defaultModelOption()
    }

    /** Active thinking level for the specified conversation, clamped to the model's real supported levels. */
    fun sessionThinkingLevel(
        session: ChatSession,
        model: ModelOption? = sessionModelOption(session),
    ): String {
        val raw = session.thinkingLevel ?: repository.settings.value.thinkingLevel
        return model?.clampThinking(raw) ?: raw
    }

    /** Changes the global default model (Settings & Models page). */
    fun selectDefaultModel(providerId: String, modelId: String, currentEmptySession: ChatSession?) {
        val picked = allAvailableModels().firstOrNull { it.providerId == providerId && it.id == modelId }
        val clampedThinking = picked?.clampThinking(repository.settings.value.thinkingLevel)
            ?: repository.settings.value.thinkingLevel
        repository.updateSettings {
            it.copy(
                activeProviderId = providerId,
                activeModelId = modelId,
                thinkingLevel = clampedThinking,
            )
        }
        if (currentEmptySession != null && currentEmptySession.messages.isEmpty()) {
            repository.upsertSession(
                currentEmptySession.copy(
                    providerId = providerId,
                    modelId = modelId,
                    thinkingLevel = clampedThinking,
                ),
                flushImmediately = true,
            )
            onSessionConfigInvalidated(currentEmptySession.id)
        }
    }

    /** Changes the global default thinking level (Settings page). */
    fun selectDefaultThinkingLevel(level: String, currentEmptySession: ChatSession?) {
        val model = defaultModelOption()
        val valid = model?.clampThinking(level) ?: level
        repository.updateSettings { it.copy(thinkingLevel = valid) }
        if (currentEmptySession != null && currentEmptySession.messages.isEmpty()) {
            repository.upsertSession(
                currentEmptySession.copy(thinkingLevel = valid),
                flushImmediately = true,
            )
            onSessionConfigInvalidated(currentEmptySession.id)
        }
    }

    /** Changes model for the current conversation only (composer bar). */
    fun selectSessionModel(session: ChatSession, providerId: String, modelId: String) {
        val picked = allAvailableModels().firstOrNull { it.providerId == providerId && it.id == modelId }
        val nextThinking = picked?.clampThinking(session.thinkingLevel ?: repository.settings.value.thinkingLevel)
            ?: session.thinkingLevel
            ?: repository.settings.value.thinkingLevel
        repository.upsertSession(
            session.copy(
                providerId = providerId,
                modelId = modelId,
                thinkingLevel = nextThinking,
            ),
            flushImmediately = true,
        )
        onSessionConfigInvalidated(session.id)
    }

    /** Changes thinking effort for the current conversation only (composer bar). */
    fun selectSessionThinkingLevel(session: ChatSession, level: String) {
        val model = sessionModelOption(session)
        val valid = model?.clampThinking(level) ?: level
        repository.upsertSession(
            session.copy(thinkingLevel = valid),
            flushImmediately = true,
        )
        onSessionConfigInvalidated(session.id)
    }

    fun ensureValidDefaultModel(providers: List<ProviderStatus>) {
        val available = providers.filter { it.isConnected }.flatMap { it.models }
        if (available.isEmpty()) return
        val cur = repository.settings.value
        val currentModel = available.firstOrNull {
            it.providerId == cur.activeProviderId && it.id == cur.activeModelId
        }
        val target = currentModel ?: available.first()
        val clampedThinking = target.clampThinking(cur.thinkingLevel)
        if (currentModel == null || clampedThinking != cur.thinkingLevel) {
            repository.updateSettings {
                it.copy(
                    activeProviderId = target.providerId,
                    activeModelId = target.id,
                    thinkingLevel = clampedThinking,
                )
            }
        }
    }

    fun saveCustomProvider(
        existingId: String?,
        name: String,
        baseUrl: String,
        apiKey: String,
        models: List<CustomModelEntry>,
        defaultImages: Boolean,
    ): CustomProviderConfig {
        val id = existingId?.takeIf { it.isNotBlank() } ?: "custom_${UUID.randomUUID().toString().take(8)}"
        val cfg = CustomProviderConfig(
            id = id,
            name = name.trim().ifBlank { "自定义渠道" },
            baseUrl = baseUrl.trim().trimEnd('/'),
            models = models,
            defaultImages = defaultImages,
        )
        if (apiKey.isNotBlank()) {
            credentialVault.putProviderApiKey(id, apiKey.trim())
        }
        val nextSettings = repository.updateSettings { current ->
            val list = current.customProviders.toMutableList()
            val idx = list.indexOfFirst { it.id == id }
            if (idx >= 0) list[idx] = cfg else list.add(cfg)
            val firstModel = cfg.models.firstOrNull()?.id.orEmpty()
            val shouldActivate = current.activeProviderId.isBlank() || current.activeProviderId == id
            current.copy(
                customProviders = list,
                activeProviderId = if (shouldActivate && firstModel.isNotEmpty()) id else current.activeProviderId,
                activeModelId = if (shouldActivate && firstModel.isNotEmpty()) firstModel else current.activeModelId,
            )
        }
        onSessionConfigInvalidated(null)
        runtime.syncCustomProviders(nextSettings.customProviders)
        return cfg
    }

    fun deleteCustomProvider(providerId: String) {
        credentialVault.removeProviderApiKey(providerId)
        val next = repository.updateSettings { current ->
            val remaining = current.customProviders.filterNot { it.id == providerId }
            val resetActive = current.activeProviderId == providerId
            current.copy(
                customProviders = remaining,
                activeProviderId = if (resetActive) "" else current.activeProviderId,
                activeModelId = if (resetActive) "" else current.activeModelId,
            )
        }
        onSessionConfigInvalidated(null)
        runtime.syncCustomProviders(next.customProviders)
    }
}
