package com.self.zai.data

import android.content.Context
import com.self.zai.model.AppSettings
import com.self.zai.model.ChatSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Thread-safe JSON store for [AppSettings] and [ChatSession] history.
 *
 * - State updates are applied atomically in memory so Compose UI remains instant.
 * - High-frequency session mutations (e.g. streaming text deltas) are debounced and flushed on [ioDispatcher]
 *   rather than blocking the main thread on every token.
 * - Writes use temp-file + backup rotation so an unexpected process kill never corrupts user history.
 */
class SettingsStore(
    private val baseDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val sessionDebounceMs: Long = 350L,
) : SessionRepository {

    constructor(context: Context) : this(context.filesDir)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val stateLock = Any()
    private val fileWriteLock = Any()
    private val ioScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val settingsFile = File(baseDir, "zai_settings.json")
    private val settingsBackupFile = File(baseDir, "zai_settings.json.bak")
    private val sessionsFile = File(baseDir, "zai_sessions.json")
    private val sessionsBackupFile = File(baseDir, "zai_sessions.json.bak")

    private val _settings = MutableStateFlow(loadSettings())
    override val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _sessions = MutableStateFlow(loadSessions())
    override val sessions: StateFlow<List<ChatSession>> = _sessions.asStateFlow()

    @Volatile
    private var pendingSessionsFlushJob: Job? = null

    @Volatile
    private var sessionsDirty: Boolean = false

    override fun updateSettings(transform: (AppSettings) -> AppSettings): AppSettings {
        val next = synchronized(stateLock) {
            val updated = transform(_settings.value)
            _settings.value = updated
            updated
        }
        ioScope.launch { writeSettingsAtomic(next) }
        return next
    }

    override fun upsertSession(session: ChatSession, flushImmediately: Boolean) {
        val sorted = synchronized(stateLock) {
            val current = _sessions.value.toMutableList()
            val idx = current.indexOfFirst { it.id == session.id }
            if (idx >= 0) current[idx] = session else current.add(0, session)
            val result = current.sortedByDescending { it.updatedAtMs }.take(MAX_STORED_SESSIONS)
            _sessions.value = result
            sessionsDirty = true
            result
        }
        if (flushImmediately) {
            pendingSessionsFlushJob?.cancel()
            pendingSessionsFlushJob = null
            ioScope.launch { writeSessionsAtomic(sorted) }
        } else {
            scheduleSessionsFlush()
        }
    }

    override fun deleteSession(sessionId: String) {
        val next = synchronized(stateLock) {
            val filtered = _sessions.value.filterNot { it.id == sessionId }
            _sessions.value = filtered
            sessionsDirty = true
            filtered
        }
        pendingSessionsFlushJob?.cancel()
        pendingSessionsFlushJob = null
        ioScope.launch { writeSessionsAtomic(next) }
    }

    override fun createNewSession(): ChatSession {
        val session = ChatSession(
            id = "s_${UUID.randomUUID().toString().take(8)}",
            title = "新对话",
            updatedAtMs = System.currentTimeMillis(),
        )
        upsertSession(session, flushImmediately = true)
        return session
    }

    override fun flushNow() {
        val snapshot = synchronized(stateLock) {
            if (!sessionsDirty) return
            _sessions.value
        }
        pendingSessionsFlushJob?.cancel()
        pendingSessionsFlushJob = null
        ioScope.launch { writeSessionsAtomic(snapshot) }
    }

    private fun scheduleSessionsFlush() {
        pendingSessionsFlushJob?.cancel()
        pendingSessionsFlushJob = ioScope.launch {
            delay(sessionDebounceMs)
            val snapshot = synchronized(stateLock) { _sessions.value }
            writeSessionsAtomic(snapshot)
        }
    }

    private fun loadSettings(): AppSettings =
        readWithBackupFallback(settingsFile, settingsBackupFile) { raw ->
            json.decodeFromString<AppSettings>(raw)
        } ?: AppSettings()

    private fun loadSessions(): List<ChatSession> =
        readWithBackupFallback(sessionsFile, sessionsBackupFile) { raw ->
            json.decodeFromString<List<ChatSession>>(raw)
        } ?: emptyList()

    private fun writeSettingsAtomic(value: AppSettings) {
        writeAtomic(settingsFile, settingsBackupFile, json.encodeToString(value))
    }

    private fun writeSessionsAtomic(value: List<ChatSession>) {
        synchronized(stateLock) { sessionsDirty = false }
        writeAtomic(sessionsFile, sessionsBackupFile, json.encodeToString(value))
    }

    private fun <T> readWithBackupFallback(primary: File, backup: File, decode: (String) -> T): T? {
        for (candidate in listOf(primary, backup)) {
            if (!candidate.exists()) continue
            val parsed = runCatching {
                decode(candidate.readText(Charsets.UTF_8))
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    private fun writeAtomic(target: File, backup: File, payload: String) {
        synchronized(fileWriteLock) {
            runCatching {
                target.parentFile?.mkdirs()
                val tmp = File(target.parentFile, "${target.name}.tmp")
                tmp.writeText(payload, Charsets.UTF_8)
                if (target.exists()) {
                    runCatching { target.copyTo(backup, overwrite = true) }
                }
                if (!tmp.renameTo(target)) {
                    // Fallback on filesystems where rename over existing target fails
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
            }
        }
    }

    companion object {
        private const val MAX_STORED_SESSIONS = 40
    }
}
