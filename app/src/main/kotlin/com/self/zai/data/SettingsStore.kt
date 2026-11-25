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
 * Thread-safe JSON store for [AppSettings] and unlimited [ChatSession] history.
 *
 * - Each conversation is persisted independently under `zai_sessions/<id>.json` (+ `.bak`) so there is
 *   no hard session-count eviction cap and streaming writes only touch the active session file.
 * - Legacy single-file `zai_sessions.json` installs are migrated transparently on startup.
 * - High-frequency session mutations (e.g. streaming text deltas) are debounced and flushed on [ioDispatcher].
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
    private val legacySessionsFile = File(baseDir, "zai_sessions.json")
    private val legacySessionsBackupFile = File(baseDir, "zai_sessions.json.bak")
    private val sessionsDir = File(baseDir, "zai_sessions")

    private val _settings = MutableStateFlow(loadSettings())
    override val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _sessions = MutableStateFlow(loadSessionsAndMigrateIfNeeded())
    override val sessions: StateFlow<List<ChatSession>> = _sessions.asStateFlow()

    private val _storageBytes = MutableStateFlow(computeSessionsStorageBytes())
    override val storageBytes: StateFlow<Long> = _storageBytes.asStateFlow()

    @Volatile
    private var pendingSessionsFlushJob: Job? = null

    @Volatile
    private var settingsDirty: Boolean = false

    private val dirtySessionIds = LinkedHashSet<String>()
    private val deletedSessionIds = LinkedHashSet<String>()

    override fun updateSettings(transform: (AppSettings) -> AppSettings): AppSettings {
        val next = synchronized(stateLock) {
            val updated = transform(_settings.value)
            _settings.value = updated
            settingsDirty = true
            updated
        }
        ioScope.launch { writeLatestSettingsAtomic() }
        return next
    }

    override fun upsertSession(session: ChatSession, flushImmediately: Boolean) {
        synchronized(stateLock) {
            val current = _sessions.value.toMutableList()
            val idx = current.indexOfFirst { it.id == session.id }
            if (idx >= 0) current[idx] = session else current.add(0, session)
            _sessions.value = current.sortedByDescending { it.updatedAtMs }
            dirtySessionIds.add(session.id)
            deletedSessionIds.remove(session.id)
        }
        if (flushImmediately) {
            pendingSessionsFlushJob?.cancel()
            pendingSessionsFlushJob = null
            ioScope.launch { writeDirtySessionsAtomic() }
        } else {
            scheduleSessionsFlush()
        }
    }

    override fun deleteSession(sessionId: String) {
        synchronized(stateLock) {
            val filtered = _sessions.value.filterNot { it.id == sessionId }
            _sessions.value = filtered
            dirtySessionIds.remove(sessionId)
            deletedSessionIds.add(sessionId)
        }
        pendingSessionsFlushJob?.cancel()
        pendingSessionsFlushJob = null
        ioScope.launch { writeDirtySessionsAtomic() }
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
        val needsFlush = synchronized(stateLock) {
            dirtySessionIds.isNotEmpty() || deletedSessionIds.isNotEmpty()
        }
        if (!needsFlush) return
        pendingSessionsFlushJob?.cancel()
        pendingSessionsFlushJob = null
        ioScope.launch { writeDirtySessionsAtomic() }
    }

    private fun scheduleSessionsFlush() {
        pendingSessionsFlushJob?.cancel()
        pendingSessionsFlushJob = ioScope.launch {
            delay(sessionDebounceMs)
            writeDirtySessionsAtomic()
        }
    }

    private fun loadSettings(): AppSettings =
        readWithBackupFallback(settingsFile, settingsBackupFile) { raw ->
            json.decodeFromString<AppSettings>(raw)
        } ?: AppSettings()

    private fun loadSessionsAndMigrateIfNeeded(): List<ChatSession> {
        val byId = LinkedHashMap<String, ChatSession>()

        // 1. Load per-session files from zai_sessions/
        if (sessionsDir.exists() && sessionsDir.isDirectory) {
            val keys = LinkedHashSet<String>()
            sessionsDir.listFiles().orEmpty().forEach { f ->
                val name = f.name
                when {
                    name.endsWith(".json") -> keys.add(name.removeSuffix(".json"))
                    name.endsWith(".json.bak") -> keys.add(name.removeSuffix(".json.bak"))
                }
            }
            for (key in keys) {
                val primary = File(sessionsDir, "$key.json")
                val backup = File(sessionsDir, "$key.json.bak")
                val decoded = readWithBackupFallback(primary, backup) { raw ->
                    json.decodeFromString<ChatSession>(raw)
                }
                if (decoded != null) {
                    byId[decoded.id] = decoded
                }
            }
        }

        // 2. Transparently migrate any legacy monolithic zai_sessions.json (and .bak)
        if (legacySessionsFile.exists() || legacySessionsBackupFile.exists()) {
            val legacy = readWithBackupFallback(legacySessionsFile, legacySessionsBackupFile) { raw ->
                json.decodeFromString<List<ChatSession>>(raw)
            }.orEmpty()
            var allMigrated = true
            for (s in legacy) {
                if (s.id !in byId) {
                    byId[s.id] = s
                    val ok = writeAtomicUnlocked(
                        sessionFile(s.id),
                        sessionBackupFile(s.id),
                        json.encodeToString(s),
                    )
                    if (!ok) allMigrated = false
                }
            }
            if (allMigrated && legacy.isNotEmpty()) {
                runCatching { legacySessionsFile.delete() }
                runCatching { legacySessionsBackupFile.delete() }
            }
        }

        return byId.values.sortedByDescending { it.updatedAtMs }
    }

    private fun writeLatestSettingsAtomic() {
        synchronized(fileWriteLock) {
            val snapshot = synchronized(stateLock) {
                if (!settingsDirty) return
                settingsDirty = false
                _settings.value
            }
            val ok = writeAtomicUnlocked(settingsFile, settingsBackupFile, json.encodeToString(snapshot))
            if (!ok) synchronized(stateLock) { settingsDirty = true }
        }
    }

    private fun writeDirtySessionsAtomic() {
        synchronized(fileWriteLock) {
            val toWrite: List<ChatSession>
            val toDelete: List<String>
            synchronized(stateLock) {
                if (dirtySessionIds.isEmpty() && deletedSessionIds.isEmpty()) return
                val byId = _sessions.value.associateBy { it.id }
                toWrite = dirtySessionIds.mapNotNull { byId[it] }
                toDelete = deletedSessionIds.toList()
                dirtySessionIds.clear()
                deletedSessionIds.clear()
            }

            val failedWrites = mutableListOf<String>()
            for (session in toWrite) {
                val ok = writeAtomicUnlocked(
                    sessionFile(session.id),
                    sessionBackupFile(session.id),
                    json.encodeToString(session),
                )
                if (!ok) failedWrites.add(session.id)
            }
            for (deletedId in toDelete) {
                runCatching { sessionFile(deletedId).delete() }
                runCatching { sessionBackupFile(deletedId).delete() }
                runCatching { File(sessionsDir, "${safeSessionKey(deletedId)}.json.tmp").delete() }
            }

            if (failedWrites.isNotEmpty()) {
                synchronized(stateLock) {
                    dirtySessionIds.addAll(failedWrites)
                }
            }
            _storageBytes.value = computeSessionsStorageBytes()
        }
    }

    private fun computeSessionsStorageBytes(): Long {
        val dir = sessionsDir
        if (!dir.exists() || !dir.isDirectory) return 0L
        return dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".json") }
            .sumOf { it.length() }
    }

    private fun sessionFile(sessionId: String): File =
        File(sessionsDir, "${safeSessionKey(sessionId)}.json")

    private fun sessionBackupFile(sessionId: String): File =
        File(sessionsDir, "${safeSessionKey(sessionId)}.json.bak")

    private fun safeSessionKey(sessionId: String): String =
        sessionId.replace(SAFE_ID_REGEX, "_").ifBlank { "session" }

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

    private fun writeAtomicUnlocked(target: File, backup: File, payload: String): Boolean =
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
            true
        }.getOrDefault(false)

    companion object {
        private val SAFE_ID_REGEX = Regex("[^a-zA-Z0-9._-]")
    }
}
