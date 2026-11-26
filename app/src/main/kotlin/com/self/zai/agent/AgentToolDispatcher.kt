package com.self.zai.agent

import com.self.zai.data.CredentialVault
import com.self.zai.data.SessionRepository
import com.self.zai.mcp.McpToolClient
import com.self.zai.mcp.StreamableHttpMcpClient
import com.self.zai.model.AppSettings
import com.self.zai.model.McpServerConfig
import com.self.zai.model.McpToolSummary
import com.self.zai.model.WebSearchProvider
import com.self.zai.phone.PhoneController
import com.self.zai.pi.BeforeToolVerdict
import com.self.zai.pi.PiToolHandler
import com.self.zai.pi.ToolExecutionResult
import com.self.zai.skills.SkillMarkdownParser
import com.self.zai.web.HttpWebSearchClient
import com.self.zai.web.WebSearchClient
import com.self.zai.web.WebSearchResponse
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Composite [PiToolHandler] unifying isolated tool namespaces:
 * 1. `read_skill` — progressive-disclosure skill loader (local, non-phone)
 * 2. `web_search` / `fetch_url` — multi-engine web search & page reader (network, non-phone)
 * 3. `mcp__*` — remote MCP Streamable HTTP tools (network, non-phone)
 * 4. Built-in phone automation tools — delegated to [PhoneController]
 *
 * Non-phone tools (Skills, Web Search & MCP) never trigger the phone-operating overlay or auto-return.
 */
class AgentToolDispatcher(
    private val repository: SessionRepository,
    private val credentialVault: CredentialVault,
    private val phoneController: PhoneController,
    private val mcpClient: McpToolClient,
    private val webSearchClient: WebSearchClient = HttpWebSearchClient(),
    private val onNonPhoneToolActivity: (label: String) -> Unit = {},
) : PiToolHandler {

    data class McpToolBinding(
        val exposedName: String,
        val server: McpServerConfig,
        val tool: McpToolSummary,
    )

    private val mcpBindings = ConcurrentHashMap<String, McpToolBinding>()

    fun isPhoneTool(toolName: String): Boolean =
        toolName != READ_SKILL_TOOL &&
            toolName != WEB_SEARCH_TOOL &&
            toolName != FETCH_URL_TOOL &&
            !toolName.startsWith(MCP_PREFIX)

    /** True only when the tool interacts with the visible screen (triggers operating chrome / dismisses overlay). */
    fun isScreenOperatingTool(toolName: String): Boolean {
        if (!isPhoneTool(toolName)) return false
        return phoneController.toolRegistry.get(toolName)?.interactsWithScreen != false
    }

    fun humanLabel(toolName: String, args: JSONObject): String = when {
        toolName == READ_SKILL_TOOL -> "加载技能 ${args.optString("name")}"
        toolName == WEB_SEARCH_TOOL -> {
            val q = args.optString("query").trim()
            if (q.isNotEmpty()) "联网搜索「${q.take(18)}」" else "联网搜索"
        }
        toolName == FETCH_URL_TOOL -> "读取网页"
        toolName.startsWith(MCP_PREFIX) -> {
            val binding = mcpBindings[toolName]
            if (binding != null) "MCP: ${binding.server.name}/${binding.tool.name}" else "调用 MCP 工具"
        }
        else -> phoneController.toolRegistry.humanLabel(toolName, args)
    }

    /**
     * Builds the unified JSON tool spec array for Pi (phone tools + `read_skill` + web tools + enabled MCP tools)
     * and refreshes the live MCP tool routing table.
     */
    fun buildToolSpecs(settings: AppSettings, includeVisionTools: Boolean): JSONArray {
        val specs = phoneController.toolRegistry.toolsJson(includeVisionTools = includeVisionTools)

        val enabledSkills = settings.skills.filter { it.enabled }
        if (enabledSkills.isNotEmpty()) {
            specs.put(buildReadSkillSpec())
        }

        if (settings.webSearchEnabled) {
            specs.put(buildWebSearchSpec())
            specs.put(buildFetchUrlSpec())
        }

        val bindings = buildMcpBindings(settings.mcpServers.filter { it.enabled })
        mcpBindings.clear()
        for (b in bindings) {
            mcpBindings[b.exposedName] = b
            specs.put(buildMcpToolSpec(b))
        }
        return specs
    }

    override suspend fun beforeTool(
        sessionId: String,
        callId: String,
        tool: String,
        args: JSONObject,
    ): BeforeToolVerdict? {
        if (!isPhoneTool(tool)) return null
        return phoneController.beforeTool(sessionId, callId, tool, args)
    }

    override suspend fun executeTool(
        sessionId: String,
        callId: String,
        tool: String,
        args: JSONObject,
    ): ToolExecutionResult {
        if (tool == READ_SKILL_TOOL) {
            onNonPhoneToolActivity("正在读取技能…")
            return executeReadSkill(args)
        }
        if (tool == WEB_SEARCH_TOOL) {
            val q = args.optString("query").trim()
            onNonPhoneToolActivity(if (q.isNotEmpty()) "正在搜索「${q.take(16)}」…" else "正在联网搜索…")
            return executeWebSearch(args)
        }
        if (tool == FETCH_URL_TOOL) {
            onNonPhoneToolActivity("正在读取网页…")
            return executeFetchUrl(args)
        }
        if (tool.startsWith(MCP_PREFIX)) {
            val binding = resolveMcpBinding(tool)
                ?: return ToolExecutionResult("未找到已启用的 MCP 工具：$tool", isError = true)
            onNonPhoneToolActivity("正在调用 ${binding.server.name}…")
            val authToken = credentialVault.getString(mcpAuthKey(binding.server.id))
            return mcpClient.callTool(
                server = binding.server,
                authToken = authToken,
                remoteToolName = binding.tool.name,
                arguments = args,
            )
        }
        return phoneController.executeTool(sessionId, callId, tool, args)
    }

    override fun cancelTool(sessionId: String, callId: String) {
        phoneController.cancelTool(sessionId, callId)
    }

    private fun executeReadSkill(args: JSONObject): ToolExecutionResult {
        val query = args.optString("name").trim()
        if (query.isEmpty()) {
            return ToolExecutionResult("请提供要读取的技能名称 name。", isError = true)
        }
        val enabled = repository.settings.value.skills.filter { it.enabled }
        val normalizedQuery = SkillMarkdownParser.normalizeSkillName(query)
        val match = enabled.firstOrNull {
            it.name.equals(query, ignoreCase = true) ||
                SkillMarkdownParser.normalizeSkillName(it.name).equals(normalizedQuery, ignoreCase = true)
        } ?: return ToolExecutionResult(
            "未找到名为「$query」的已启用技能。可用技能：${enabled.joinToString(", ") { it.name }}",
            isError = true,
        )

        return ToolExecutionResult(
            text = buildString {
                appendLine("# Skill: ${match.name}")
                if (match.description.isNotBlank()) {
                    appendLine("说明：${match.description}")
                    appendLine()
                }
                append(match.instructions.trim())
            },
            isError = false,
        )
    }

    private fun loadWebSearchApiKeys(): Map<WebSearchProvider, String> {
        val out = mutableMapOf<WebSearchProvider, String>()
        for (provider in WebSearchProvider.entries) {
            if (!provider.requiresApiKey) continue
            val saved = credentialVault.getString(webSearchApiKeyKey(provider))?.trim()
            if (!saved.isNullOrEmpty()) {
                out[provider] = saved
            }
        }
        return out
    }

    suspend fun testWebSearch(query: String = "OpenAI"): Result<WebSearchResponse> {
        val settings = repository.settings.value
        return webSearchClient.search(
            query = query,
            maxResults = 3,
            priority = settings.webSearchPriority,
            apiKeys = loadWebSearchApiKeys(),
        )
    }

    private suspend fun executeWebSearch(args: JSONObject): ToolExecutionResult {
        val settings = repository.settings.value
        if (!settings.webSearchEnabled) {
            return ToolExecutionResult("联网搜索已在设置中关闭。", isError = true)
        }
        val query = args.optString("query").trim()
        if (query.isEmpty()) {
            return ToolExecutionResult("请提供搜索关键词 query。", isError = true)
        }
        val maxResults = args.optInt("max_results", WebSearchClient.DEFAULT_MAX_RESULTS).coerceIn(1, 10)
        return webSearchClient.search(
            query = query,
            maxResults = maxResults,
            priority = settings.webSearchPriority,
            apiKeys = loadWebSearchApiKeys(),
        ).fold(
            onSuccess = { resp ->
                ToolExecutionResult(HttpWebSearchClient.formatSearchResultForModel(resp), isError = false)
            },
            onFailure = { err ->
                ToolExecutionResult(err.message ?: "联网搜索失败", isError = true)
            },
        )
    }

    private suspend fun executeFetchUrl(args: JSONObject): ToolExecutionResult {
        if (!repository.settings.value.webSearchEnabled) {
            return ToolExecutionResult("联网搜索与读网页已在设置中关闭。", isError = true)
        }
        val url = args.optString("url").trim()
        if (url.isEmpty()) {
            return ToolExecutionResult("请提供要读取的网址 url。", isError = true)
        }
        val maxChars = args.optInt("max_chars", WebSearchClient.DEFAULT_MAX_FETCH_CHARS).coerceIn(1_000, 20_000)
        return webSearchClient.fetchUrl(url = url, maxChars = maxChars).fold(
            onSuccess = { page ->
                ToolExecutionResult(HttpWebSearchClient.formatPageContentForModel(page), isError = false)
            },
            onFailure = { err ->
                ToolExecutionResult(err.message ?: "网页读取失败", isError = true)
            },
        )
    }

    private fun resolveMcpBinding(exposedName: String): McpToolBinding? {
        mcpBindings[exposedName]?.let { return it }
        // Rebuild lazily in case session was restored before buildToolSpecs ran
        val rebuilt = buildMcpBindings(repository.settings.value.mcpServers.filter { it.enabled })
        mcpBindings.clear()
        for (b in rebuilt) mcpBindings[b.exposedName] = b
        return mcpBindings[exposedName]
    }

    private fun buildReadSkillSpec(): JSONObject = JSONObject()
        .put("name", READ_SKILL_TOOL)
        .put("label", "读取技能")
        .put("description", "按名称读取已启用技能（Skill）的完整执行指引。仅在任务与可用技能目录匹配时调用。")
        .put(
            "parameters",
            JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject().put(
                        "name",
                        JSONObject()
                            .put("type", "string")
                            .put("description", "技能名称，需与可用技能目录一致"),
                    ),
                )
                .put("required", JSONArray(listOf("name"))),
        )

    private fun buildWebSearchSpec(): JSONObject = JSONObject()
        .put("name", WEB_SEARCH_TOOL)
        .put("label", "联网搜索")
        .put("description", "联网搜索最新资讯、技术文档、实时数据与事实核查。自动按用户设置的搜索引擎优先级与免 Key 兜底执行，无需打开浏览器。")
        .put(
            "parameters",
            JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject()
                        .put(
                            "query",
                            JSONObject()
                                .put("type", "string")
                                .put("description", "搜索关键词或问题"),
                        )
                        .put(
                            "max_results",
                            JSONObject()
                                .put("type", "integer")
                                .put("description", "返回结果条数（1-10，默认 6）"),
                        ),
                )
                .put("required", JSONArray(listOf("query"))),
        )

    private fun buildFetchUrlSpec(): JSONObject = JSONObject()
        .put("name", FETCH_URL_TOOL)
        .put("label", "读取网页")
        .put("description", "抓取并提取指定 http/https 网页的可读正文内容，适合在 web_search 找到目标链接后深入阅读详情。")
        .put(
            "parameters",
            JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject()
                        .put(
                            "url",
                            JSONObject()
                                .put("type", "string")
                                .put("description", "要读取的完整 http/https 网址"),
                        )
                        .put(
                            "max_chars",
                            JSONObject()
                                .put("type", "integer")
                                .put("description", "最大返回字符数（1000-20000，默认 10000）"),
                        ),
                )
                .put("required", JSONArray(listOf("url"))),
        )

    private fun buildMcpToolSpec(binding: McpToolBinding): JSONObject {
        val schema = runCatching {
            StreamableHttpMcpClient.normalizeInputSchema(JSONObject(binding.tool.inputSchemaJson))
        }.getOrElse {
            StreamableHttpMcpClient.normalizeInputSchema(null)
        }
        val desc = buildString {
            append("[MCP:${binding.server.name}] ")
            append(binding.tool.description.ifBlank { binding.tool.name })
        }
        return JSONObject()
            .put("name", binding.exposedName)
            .put("label", "${binding.server.name}:${binding.tool.name}")
            .put("description", desc)
            .put("parameters", schema)
    }

    companion object {
        const val READ_SKILL_TOOL = "read_skill"
        const val WEB_SEARCH_TOOL = "web_search"
        const val FETCH_URL_TOOL = "fetch_url"
        const val MCP_PREFIX = "mcp__"

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
