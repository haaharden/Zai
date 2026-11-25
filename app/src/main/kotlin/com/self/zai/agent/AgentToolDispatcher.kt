package com.self.zai.agent

import com.self.zai.data.CredentialVault
import com.self.zai.data.SessionRepository
import com.self.zai.mcp.McpToolClient
import com.self.zai.mcp.StreamableHttpMcpClient
import com.self.zai.model.AppSettings
import com.self.zai.model.McpServerConfig
import com.self.zai.model.McpToolSummary
import com.self.zai.phone.PhoneController
import com.self.zai.pi.BeforeToolVerdict
import com.self.zai.pi.PiToolHandler
import com.self.zai.pi.ToolExecutionResult
import com.self.zai.skills.SkillMarkdownParser
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Composite [PiToolHandler] unifying three isolated tool namespaces:
 * 1. `read_skill` — progressive-disclosure skill loader (local, non-phone)
 * 2. `mcp__*` — remote MCP Streamable HTTP tools (network, non-phone)
 * 3. Built-in phone automation tools — delegated to [PhoneController]
 *
 * Non-phone tools (Skills & MCP) never trigger the phone-operating overlay or auto-return.
 */
class AgentToolDispatcher(
    private val repository: SessionRepository,
    private val credentialVault: CredentialVault,
    private val phoneController: PhoneController,
    private val mcpClient: McpToolClient,
    private val onNonPhoneToolActivity: (label: String) -> Unit = {},
) : PiToolHandler {

    data class McpToolBinding(
        val exposedName: String,
        val server: McpServerConfig,
        val tool: McpToolSummary,
    )

    private val mcpBindings = ConcurrentHashMap<String, McpToolBinding>()

    fun isPhoneTool(toolName: String): Boolean =
        toolName != READ_SKILL_TOOL && !toolName.startsWith(MCP_PREFIX)

    /** True only when the tool interacts with the visible screen (triggers operating chrome / dismisses overlay). */
    fun isScreenOperatingTool(toolName: String): Boolean {
        if (!isPhoneTool(toolName)) return false
        return phoneController.toolRegistry.get(toolName)?.interactsWithScreen != false
    }

    fun humanLabel(toolName: String, args: JSONObject): String = when {
        toolName == READ_SKILL_TOOL -> "加载技能 ${args.optString("name")}"
        toolName.startsWith(MCP_PREFIX) -> {
            val binding = mcpBindings[toolName]
            if (binding != null) "MCP: ${binding.server.name}/${binding.tool.name}" else "调用 MCP 工具"
        }
        else -> phoneController.toolRegistry.humanLabel(toolName, args)
    }

    /**
     * Builds the unified JSON tool spec array for Pi (phone tools + `read_skill` + enabled MCP tools)
     * and refreshes the live MCP tool routing table.
     */
    fun buildToolSpecs(settings: AppSettings, includeVisionTools: Boolean): JSONArray {
        val specs = phoneController.toolRegistry.toolsJson(includeVisionTools = includeVisionTools)

        val enabledSkills = settings.skills.filter { it.enabled }
        if (enabledSkills.isNotEmpty()) {
            specs.put(buildReadSkillSpec())
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
        const val MCP_PREFIX = "mcp__"

        fun mcpAuthKey(serverId: String): String = "mcp_auth_$serverId"

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
