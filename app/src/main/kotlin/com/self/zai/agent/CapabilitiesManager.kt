package com.self.zai.agent

import com.self.zai.data.CredentialVault
import com.self.zai.data.SessionRepository
import com.self.zai.mcp.McpToolClient
import com.self.zai.mcp.StreamableHttpMcpClient
import com.self.zai.model.CompactionMode
import com.self.zai.model.McpServerConfig
import com.self.zai.model.McpToolSummary
import com.self.zai.model.SkillEntry
import com.self.zai.model.WebSearchProvider
import com.self.zai.skills.SkillMarkdownParser
import com.self.zai.web.WebSearchClient
import java.util.UUID

/**
 * High-cohesion manager for Zai's extensible capability configuration:
 * user system prompt, context compaction policy, Skills CRUD, MCP servers & tokens,
 * and Web Search provider priority / encrypted API keys.
 *
 * Pure Kotlin (zero Android dependencies) so all capability workflows are directly JVM-testable.
 */
class CapabilitiesManager(
    private val repository: SessionRepository,
    private val credentialVault: CredentialVault,
    private val mcpClient: McpToolClient,
    private val webSearchClient: WebSearchClient,
    private val onConfigInvalidated: () -> Unit = {},
) {

    fun updateUserSystemPrompt(prompt: String) {
        repository.updateSettings { it.copy(userSystemPrompt = prompt.trim()) }
        onConfigInvalidated()
    }

    fun updateCompactionSettings(mode: CompactionMode, thresholdPercent: Int) {
        val clamped = thresholdPercent.coerceIn(40, 95)
        repository.updateSettings {
            it.copy(
                compactionMode = mode,
                compactionThresholdPercent = clamped,
            )
        }
        onConfigInvalidated()
    }

    // --- Skills ---

    fun saveSkill(
        existingId: String?,
        name: String,
        description: String,
        instructions: String,
        enabled: Boolean = true,
    ): SkillEntry {
        val id = existingId?.takeIf { it.isNotBlank() } ?: "sk_${UUID.randomUUID().toString().take(8)}"
        val entry = SkillEntry(
            id = id,
            name = SkillMarkdownParser.normalizeSkillName(name),
            description = description.trim().ifBlank { "自定义技能" },
            instructions = instructions.trim(),
            enabled = enabled,
        )
        repository.updateSettings { cur ->
            val list = cur.skills.toMutableList()
            val idx = list.indexOfFirst { it.id == id }
            if (idx >= 0) list[idx] = entry else list.add(0, entry)
            cur.copy(skills = list)
        }
        onConfigInvalidated()
        return entry
    }

    fun toggleSkill(skillId: String, enabled: Boolean) {
        repository.updateSettings { cur ->
            cur.copy(skills = cur.skills.map { if (it.id == skillId) it.copy(enabled = enabled) else it })
        }
        onConfigInvalidated()
    }

    fun deleteSkill(skillId: String) {
        repository.updateSettings { cur ->
            cur.copy(skills = cur.skills.filterNot { it.id == skillId })
        }
        onConfigInvalidated()
    }

    // --- MCP Servers ---

    fun saveMcpServer(
        existingId: String?,
        name: String,
        url: String,
        authToken: String?,
        tools: List<McpToolSummary>,
        enabled: Boolean = true,
    ): McpServerConfig {
        val id = existingId?.takeIf { it.isNotBlank() } ?: "mcp_${UUID.randomUUID().toString().take(8)}"
        val normalizedUrl = StreamableHttpMcpClient.normalizeUrl(url)
        val cfg = McpServerConfig(
            id = id,
            name = name.trim().ifBlank {
                normalizedUrl.substringAfter("://").substringBefore("/").ifBlank { "MCP 服务" }
            },
            url = normalizedUrl,
            enabled = enabled,
            tools = tools,
        )
        val trimmedToken = authToken?.trim()
        if (trimmedToken != null) {
            if (trimmedToken.isEmpty()) {
                credentialVault.remove(AgentToolDispatcher.mcpAuthKey(id))
            } else {
                credentialVault.putString(AgentToolDispatcher.mcpAuthKey(id), trimmedToken)
            }
        }
        mcpClient.invalidateSession(normalizedUrl)
        repository.updateSettings { cur ->
            val list = cur.mcpServers.toMutableList()
            val idx = list.indexOfFirst { it.id == id }
            if (idx >= 0) list[idx] = cfg else list.add(0, cfg)
            cur.copy(mcpServers = list)
        }
        onConfigInvalidated()
        return cfg
    }

    fun toggleMcpServer(serverId: String, enabled: Boolean) {
        repository.updateSettings { cur ->
            cur.copy(mcpServers = cur.mcpServers.map { if (it.id == serverId) it.copy(enabled = enabled) else it })
        }
        onConfigInvalidated()
    }

    fun deleteMcpServer(serverId: String) {
        val existing = repository.settings.value.mcpServers.firstOrNull { it.id == serverId }
        if (existing != null) mcpClient.invalidateSession(existing.url)
        credentialVault.remove(AgentToolDispatcher.mcpAuthKey(serverId))
        repository.updateSettings { cur ->
            cur.copy(mcpServers = cur.mcpServers.filterNot { it.id == serverId })
        }
        onConfigInvalidated()
    }

    fun getMcpAuthToken(serverId: String): String? =
        credentialVault.getString(AgentToolDispatcher.mcpAuthKey(serverId))

    suspend fun discoverMcpTools(url: String, authToken: String?): Result<List<McpToolSummary>> =
        mcpClient.discoverTools(url, authToken)

    // --- Web Search ---

    fun updateWebSearchEnabled(enabled: Boolean) {
        repository.updateSettings { it.copy(webSearchEnabled = enabled) }
        onConfigInvalidated()
    }

    fun updateWebSearchPriority(priority: List<WebSearchProvider>) {
        val normalized = WebSearchProvider.normalizePriority(priority)
        repository.updateSettings { it.copy(webSearchPriority = normalized) }
    }

    fun saveWebSearchApiKey(provider: WebSearchProvider, apiKey: String) {
        if (!provider.requiresApiKey) return
        val keyName = AgentToolDispatcher.webSearchApiKeyKey(provider)
        val trimmed = apiKey.trim()
        if (trimmed.isEmpty()) {
            credentialVault.remove(keyName)
        } else {
            credentialVault.putString(keyName, trimmed)
        }
    }

    fun getWebSearchApiKey(provider: WebSearchProvider): String? {
        if (!provider.requiresApiKey) return null
        return credentialVault.getString(AgentToolDispatcher.webSearchApiKeyKey(provider))
    }

    fun configuredWebSearchProviders(): Set<WebSearchProvider> =
        WebSearchProvider.entries
            .filter { it.requiresApiKey && !getWebSearchApiKey(it).isNullOrBlank() }
            .toSet()

    suspend fun testWebSearch(query: String = "OpenAI"): Result<String> {
        val settings = repository.settings.value
        val apiKeys = WebSearchProvider.entries
            .filter { it.requiresApiKey }
            .mapNotNull { provider ->
                getWebSearchApiKey(provider)?.trim()?.takeIf { it.isNotEmpty() }?.let { provider to it }
            }
            .toMap()
        return webSearchClient.search(
            query = query,
            maxResults = 3,
            priority = settings.webSearchPriority,
            apiKeys = apiKeys,
        ).map { resp ->
            val fallbackHint = if (resp.fallbackNotes.isNotEmpty()) {
                "（已回退：${resp.fallbackNotes.joinToString("；")}）"
            } else {
                ""
            }
            "已通：${resp.providerDetail} · ${resp.results.size} 条$fallbackHint"
        }
    }
}
