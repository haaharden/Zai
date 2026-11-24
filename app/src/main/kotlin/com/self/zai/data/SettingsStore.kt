package com.self.zai.data

import android.content.Context
import com.self.zai.model.AppSettings
import com.self.zai.model.ChatSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

class SettingsStore(context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val settingsFile = File(context.filesDir, "zai_settings.json")
    private val sessionsFile = File(context.filesDir, "zai_sessions.json")

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _sessions = MutableStateFlow(loadSessions())
    val sessions: StateFlow<List<ChatSession>> = _sessions.asStateFlow()

    fun updateSettings(transform: (AppSettings) -> AppSettings): AppSettings {
        val next = transform(_settings.value)
        _settings.value = next
        saveSettings(next)
        return next
    }

    fun upsertSession(session: ChatSession) {
        val current = _sessions.value.toMutableList()
        val idx = current.indexOfFirst { it.id == session.id }
        if (idx >= 0) current[idx] = session else current.add(0, session)
        val sorted = current.sortedByDescending { it.updatedAtMs }.take(40)
        _sessions.value = sorted
        saveSessions(sorted)
    }

    fun deleteSession(sessionId: String) {
        val next = _sessions.value.filterNot { it.id == sessionId }
        _sessions.value = next
        saveSessions(next)
    }

    fun createNewSession(): ChatSession {
        val session = ChatSession(
            id = "s_${UUID.randomUUID().toString().take(8)}",
            title = "新对话",
            updatedAtMs = System.currentTimeMillis(),
        )
        upsertSession(session)
        return session
    }

    private fun loadSettings(): AppSettings {
        if (!settingsFile.exists()) return AppSettings()
        return runCatching {
            json.decodeFromString<AppSettings>(settingsFile.readText(Charsets.UTF_8))
        }.getOrDefault(AppSettings())
    }

    private fun saveSettings(value: AppSettings) {
        runCatching {
            val tmp = File(settingsFile.parentFile, "${settingsFile.name}.tmp")
            tmp.writeText(json.encodeToString(value), Charsets.UTF_8)
            tmp.renameTo(settingsFile)
        }
    }

    private fun loadSessions(): List<ChatSession> {
        if (!sessionsFile.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<ChatSession>>(sessionsFile.readText(Charsets.UTF_8))
        }.getOrDefault(emptyList())
    }

    private fun saveSessions(value: List<ChatSession>) {
        runCatching {
            val tmp = File(sessionsFile.parentFile, "${sessionsFile.name}.tmp")
            tmp.writeText(json.encodeToString(value), Charsets.UTF_8)
            tmp.renameTo(sessionsFile)
        }
    }
}
