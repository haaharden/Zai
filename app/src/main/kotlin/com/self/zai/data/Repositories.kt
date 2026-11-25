package com.self.zai.data

import com.self.zai.model.AppSettings
import com.self.zai.model.ChatSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction for app settings and chat session persistence.
 * Enables non-blocking debounced persistence in production and lightweight in-memory fakes in unit tests.
 */
interface SessionRepository {
    val settings: StateFlow<AppSettings>
    val sessions: StateFlow<List<ChatSession>>
    val storageBytes: StateFlow<Long>
        get() = MutableStateFlow(0L)

    fun updateSettings(transform: (AppSettings) -> AppSettings): AppSettings
    fun upsertSession(session: ChatSession, flushImmediately: Boolean = false)
    fun deleteSession(sessionId: String)
    fun createNewSession(): ChatSession
    fun flushNow()
}

/**
 * Abstraction for encrypted credential and secret storage (OAuth tokens, provider API keys, device UUID).
 */
interface CredentialVault {
    fun putString(key: String, value: String)
    fun getString(key: String): String?
    fun remove(key: String)
    fun putProviderApiKey(providerId: String, apiKey: String)
    fun getProviderApiKey(providerId: String): String?
    fun removeProviderApiKey(providerId: String)
    fun getAllProviderApiKeys(providerIds: Collection<String>): Map<String, String>
    fun savePiCredentialsJson(json: String)
    fun loadPiCredentialsJson(): String?
}
