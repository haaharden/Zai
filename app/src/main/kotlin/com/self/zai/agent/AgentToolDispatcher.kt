package com.self.zai.agent

import com.self.zai.data.CredentialVault
import com.self.zai.data.SessionRepository
import com.self.zai.mcp.McpToolClient
import com.self.zai.model.AppSettings
import com.self.zai.model.McpServerConfig
import com.self.zai.model.McpToolSummary
import com.self.zai.model.WebSearchProvider
import com.self.zai.phone.PhoneController
import com.self.zai.pi.BeforeToolVerdict
import com.self.zai.pi.PiToolHandler
import com.self.zai.pi.ToolExecutionResult
import com.self.zai.web.HttpWebSearchClient
import com.self.zai.web.WebSearchClient
import com.self.zai.web.WebSearchResponse
import org.json.JSONArray
import org.json.JSONObject

/**
 * Slim composite [PiToolHandler] routing tool specs, safety checks, status labels, and execution
 * through pluggable [AgentToolProvider] namespaces (Phone, Skills, Web Search, MCP).
 */
class AgentToolDispatcher(
    private val phoneProvider: PhoneToolProvider,
    private val webProvider: WebToolProvider,
    private val providers: List<AgentToolProvider>,
) : PiToolHandler {

    constructor(
        repository: SessionRepository,
        credentialVault: CredentialVault,
        phoneController: PhoneController,
        mcpClient: McpToolClient,
        webSearchClient: WebSearchClient = HttpWebSearchClient(),
        onNonPhoneToolActivity: (label: String) -> Unit = {},
    ) : this(
        phoneProvider = PhoneToolProvider(phoneController),
        webProvider = WebToolProvider(repository, credentialVault, webSearchClient, onNonPhoneToolActivity),
        providers = emptyList(),
    ) {
        // Re-composed cleanly via helper below using all standard namespaces
        activeProviders = listOf(
            phoneProvider,
            SkillToolProvider(repository, onNonPhoneToolActivity),
            webProvider,
            McpToolProvider(repository, credentialVault, mcpClient, onNonPhoneToolActivity),
        )
    }

    private var activeProviders: List<AgentToolProvider> =
        if (providers.isNotEmpty()) providers else listOf(phoneProvider, webProvider)

    data class McpToolBinding(
        val exposedName: String,
        val server: McpServerConfig,
        val tool: McpToolSummary,
    )

    private fun providerFor(toolName: String): AgentToolProvider? =
        activeProviders.firstOrNull { it.handles(toolName) }

    fun isPhoneTool(toolName: String): Boolean {
        val provider = providerFor(toolName) ?: return !toolName.startsWith(MCP_PREFIX) &&
            toolName != READ_SKILL_TOOL &&
            toolName != WEB_SEARCH_TOOL &&
            toolName != FETCH_URL_TOOL
        return provider.isPhoneNamespace()
    }

    /** True only when the tool interacts with the visible screen (triggers operating chrome / dismisses overlay). */
    fun isScreenOperatingTool(toolName: String): Boolean {
        val provider = providerFor(toolName) ?: return isPhoneTool(toolName)
        return provider.isScreenOperating(toolName)
    }

    fun humanLabel(toolName: String, args: JSONObject): String =
        providerFor(toolName)?.humanLabel(toolName, args) ?: phoneProvider.humanLabel(toolName, args)

    fun runningStatusLabel(toolName: String, args: JSONObject): String {
        INTERNAL_CONTEXT_TOOL_LABELS[toolName]?.let { return it }
        return providerFor(toolName)?.runningStatusLabel(toolName, args)
            ?: "正在${humanLabel(toolName, args)}…"
    }

    /**
     * Builds the unified JSON tool spec array for Pi across all registered [AgentToolProvider]s.
     */
    fun buildToolSpecs(settings: AppSettings, includeVisionTools: Boolean): JSONArray {
        val specs = JSONArray()
        for (provider in activeProviders) {
            provider.appendToolSpecs(settings, includeVisionTools, specs)
        }
        return specs
    }

    override suspend fun beforeTool(
        sessionId: String,
        callId: String,
        tool: String,
        args: JSONObject,
    ): BeforeToolVerdict? =
        providerFor(tool)?.beforeTool(sessionId, callId, tool, args)

    override suspend fun executeTool(
        sessionId: String,
        callId: String,
        tool: String,
        args: JSONObject,
    ): ToolExecutionResult {
        val provider = providerFor(tool) ?: phoneProvider
        return provider.execute(sessionId, callId, tool, args)
    }

    override fun cancelTool(sessionId: String, callId: String) {
        activeProviders.forEach { it.cancel(sessionId, callId) }
    }

    suspend fun testWebSearch(query: String = "OpenAI"): Result<WebSearchResponse> =
        webProvider.testWebSearch(query)

    companion object {
        const val READ_SKILL_TOOL = "read_skill"
        const val WEB_SEARCH_TOOL = "web_search"
        const val FETCH_URL_TOOL = "fetch_url"
        const val MCP_PREFIX = "mcp__"

        private val INTERNAL_CONTEXT_TOOL_LABELS = mapOf(
            "compress" to "正在压缩上下文…",
            "context_compress" to "正在压缩上下文…",
            "decompress" to "正在解压历史块…",
            "context_expand" to "正在解压历史块…",
            "search_context" to "正在检索压缩历史…",
            "context_search" to "正在检索压缩历史…",
            "acp_retrieve" to "正在读取归档原文…",
            "acp_status" to "正在检查上下文状态…",
        )

        fun mcpAuthKey(serverId: String): String = "mcp_auth_$serverId"

        fun webSearchApiKeyKey(provider: WebSearchProvider): String =
            "web_search_key_${provider.name.lowercase()}"

        /**
         * Produces unique, provider-safe tool identifiers (`^[a-zA-Z0-9_-]{1,64}$`) for all enabled MCP tools.
         */
        fun buildMcpBindings(servers: List<McpServerConfig>): List<McpToolBinding> {
            val used = HashSet<String>()
            val result = mutableListOf<McpToolBinding>()
            for (srv in servers) {
                val srvSlug = sanitizeSegment(srv.name, fallback = "srv").take(18)
                for (tool in srv.tools) {
                    val toolSlug = sanitizeSegment(tool.name, fallback = "tool").take(34)
                    var candidate = "$MCP_PREFIX${srvSlug}__$toolSlug".take(64)
                    var suffix = 2
                    while (!used.add(candidate)) {
                        val tag = "_$suffix"
                        candidate = candidate.take(64 - tag.length) + tag
                        suffix++
                    }
                    result += McpToolBinding(
                        exposedName = candidate,
                        server = srv,
                        tool = tool,
                    )
                }
            }
            return result
        }

        private fun sanitizeSegment(raw: String, fallback: String): String {
            val cleaned = raw.lowercase()
                .replace(Regex("[^a-z0-9_-]+"), "_")
                .trim('_', '-')
            return cleaned.ifEmpty { fallback }
        }
    }
}
