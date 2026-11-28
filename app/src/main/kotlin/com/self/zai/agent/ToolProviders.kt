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
import com.self.zai.pi.ToolExecutionResult
import com.self.zai.skills.SkillMarkdownParser
import com.self.zai.web.HttpWebSearchClient
import com.self.zai.web.WebSearchClient
import com.self.zai.web.WebSearchResponse
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class SkillToolProvider(
    private val repository: SessionRepository,
    private val onActivity: (String) -> Unit,
) : AgentToolProvider {

    override fun appendToolSpecs(settings: AppSettings, includeVisionTools: Boolean, target: JSONArray) {
        if (settings.skills.any { it.enabled }) {
            target.put(buildReadSkillSpec())
        }
    }

    override fun handles(toolName: String): Boolean = toolName == AgentToolDispatcher.READ_SKILL_TOOL

    override fun humanLabel(toolName: String, args: JSONObject): String =
        "加载技能 ${args.optString("name")}"

    override fun runningStatusLabel(toolName: String, args: JSONObject): String = "正在读取技能…"

    override suspend fun execute(
        sessionId: String,
        callId: String,
        tool: String,
        args: JSONObject,
    ): ToolExecutionResult {
        onActivity("正在读取技能…")
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

    private fun buildReadSkillSpec(): JSONObject = JSONObject()
        .put("name", AgentToolDispatcher.READ_SKILL_TOOL)
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
}

class WebToolProvider(
    private val repository: SessionRepository,
    private val credentialVault: CredentialVault,
    private val webSearchClient: WebSearchClient,
    private val onActivity: (String) -> Unit,
) : AgentToolProvider {

    override fun appendToolSpecs(settings: AppSettings, includeVisionTools: Boolean, target: JSONArray) {
        if (settings.webSearchEnabled) {
            target.put(buildWebSearchSpec())
            target.put(buildFetchUrlSpec())
        }
    }

    override fun handles(toolName: String): Boolean =
        toolName == AgentToolDispatcher.WEB_SEARCH_TOOL || toolName == AgentToolDispatcher.FETCH_URL_TOOL

    override fun humanLabel(toolName: String, args: JSONObject): String = when (toolName) {
        AgentToolDispatcher.WEB_SEARCH_TOOL -> {
            val q = args.optString("query").trim()
            if (q.isNotEmpty()) "联网搜索「${q.take(18)}」" else "联网搜索"
        }
        else -> "读取网页"
    }

    override fun runningStatusLabel(toolName: String, args: JSONObject): String = when (toolName) {
        AgentToolDispatcher.WEB_SEARCH_TOOL -> {
            val q = args.optString("query").trim()
            if (q.isNotEmpty()) "正在搜索「${q.take(16)}」…" else "正在联网搜索…"
        }
        else -> "正在读取网页…"
    }

    override suspend fun execute(
        sessionId: String,
        callId: String,
        tool: String,
        args: JSONObject,
    ): ToolExecutionResult {
        return if (tool == AgentToolDispatcher.WEB_SEARCH_TOOL) {
            onActivity(runningStatusLabel(tool, args))
            executeWebSearch(args)
        } else {
            onActivity("正在读取网页…")
            executeFetchUrl(args)
        }
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

    private fun loadWebSearchApiKeys(): Map<WebSearchProvider, String> {
        val out = mutableMapOf<WebSearchProvider, String>()
        for (provider in WebSearchProvider.entries) {
            if (!provider.requiresApiKey) continue
            val saved = credentialVault.getString(AgentToolDispatcher.webSearchApiKeyKey(provider))?.trim()
            if (!saved.isNullOrEmpty()) {
                out[provider] = saved
            }
        }
        return out
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

    private fun buildWebSearchSpec(): JSONObject = JSONObject()
        .put("name", AgentToolDispatcher.WEB_SEARCH_TOOL)
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
        .put("name", AgentToolDispatcher.FETCH_URL_TOOL)
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
}

class McpToolProvider(
    private val repository: SessionRepository,
    private val credentialVault: CredentialVault,
    private val mcpClient: McpToolClient,
    private val onActivity: (String) -> Unit,
) : AgentToolProvider {

    private val bindings = ConcurrentHashMap<String, AgentToolDispatcher.McpToolBinding>()

    override fun appendToolSpecs(settings: AppSettings, includeVisionTools: Boolean, target: JSONArray) {
        val next = AgentToolDispatcher.buildMcpBindings(settings.mcpServers.filter { it.enabled })
        bindings.clear()
        for (b in next) {
            bindings[b.exposedName] = b
            target.put(buildMcpToolSpec(b))
        }
    }

    override fun handles(toolName: String): Boolean = toolName.startsWith(AgentToolDispatcher.MCP_PREFIX)

    override fun humanLabel(toolName: String, args: JSONObject): String {
        val binding = bindings[toolName]
        return if (binding != null) "MCP: ${binding.server.name}/${binding.tool.name}" else "调用 MCP 工具"
    }

    override fun runningStatusLabel(toolName: String, args: JSONObject): String = "正在调用 MCP 工具…"

    override suspend fun execute(
        sessionId: String,
        callId: String,
        tool: String,
        args: JSONObject,
    ): ToolExecutionResult {
        val binding = resolveBinding(tool)
            ?: return ToolExecutionResult("未找到已启用的 MCP 工具：$tool", isError = true)
        onActivity("正在调用 ${binding.server.name}…")
        val authToken = credentialVault.getString(AgentToolDispatcher.mcpAuthKey(binding.server.id))
        return mcpClient.callTool(
            server = binding.server,
            authToken = authToken,
            remoteToolName = binding.tool.name,
            arguments = args,
        )
    }

    private fun resolveBinding(exposedName: String): AgentToolDispatcher.McpToolBinding? {
        bindings[exposedName]?.let { return it }
        val rebuilt = AgentToolDispatcher.buildMcpBindings(repository.settings.value.mcpServers.filter { it.enabled })
        bindings.clear()
        for (b in rebuilt) bindings[b.exposedName] = b
        return bindings[exposedName]
    }

    private fun buildMcpToolSpec(binding: AgentToolDispatcher.McpToolBinding): JSONObject {
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
}

class PhoneToolProvider(
    private val phoneController: PhoneController,
) : AgentToolProvider {

    override fun appendToolSpecs(settings: AppSettings, includeVisionTools: Boolean, target: JSONArray) {
        val specs = phoneController.toolRegistry.toolsJson(includeVisionTools = includeVisionTools)
        for (i in 0 until specs.length()) {
            target.put(specs.get(i))
        }
    }

    override fun handles(toolName: String): Boolean = phoneController.toolRegistry.get(toolName) != null

    override fun isPhoneNamespace(): Boolean = true

    override fun isScreenOperating(toolName: String): Boolean =
        phoneController.toolRegistry.get(toolName)?.interactsWithScreen != false

    override fun humanLabel(toolName: String, args: JSONObject): String =
        phoneController.toolRegistry.humanLabel(toolName, args)

    override suspend fun beforeTool(
        sessionId: String,
        callId: String,
        tool: String,
        args: JSONObject,
    ): BeforeToolVerdict? = phoneController.beforeTool(sessionId, callId, tool, args)

    override suspend fun execute(
        sessionId: String,
        callId: String,
        tool: String,
        args: JSONObject,
    ): ToolExecutionResult = phoneController.executeTool(sessionId, callId, tool, args)

    override fun cancel(sessionId: String, callId: String) {
        phoneController.cancelTool(sessionId, callId)
    }
}
