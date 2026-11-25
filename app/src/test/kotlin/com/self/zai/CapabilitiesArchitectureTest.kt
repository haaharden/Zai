package com.self.zai

import android.graphics.Bitmap
import android.graphics.Rect
import com.self.zai.agent.AgentToolDispatcher
import com.self.zai.agent.SystemPromptBuilder
import com.self.zai.data.CredentialVault
import com.self.zai.data.SessionRepository
import com.self.zai.mcp.McpToolClient
import com.self.zai.mcp.StreamableHttpMcpClient
import com.self.zai.model.AppSettings
import com.self.zai.model.ChatSession
import com.self.zai.model.McpServerConfig
import com.self.zai.model.McpToolSummary
import com.self.zai.model.SkillEntry
import com.self.zai.phone.AppDirectory
import com.self.zai.phone.PhoneController
import com.self.zai.phone.ScreenSnapshot
import com.self.zai.phone.channel.PhoneDeviceChannel
import com.self.zai.pi.ToolExecutionResult
import com.self.zai.skills.SkillMarkdownParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilitiesArchitectureTest {

    @Test
    fun skillMarkdownParser_parsesFrontmatterExportsAndMatchesSlash() {
        val raw = """
            ---
            name: price-check
            description: "跨平台比价并在下单前总结价格"
            ---

            1. 先分别在京东和淘宝搜索同款商品。
            2. 汇总券后价并询问用户是否继续。
        """.trimIndent()

        val parsed = SkillMarkdownParser.parse(raw)
        assertNotNull(parsed)
        assertEquals("price-check", parsed!!.name)
        assertEquals("跨平台比价并在下单前总结价格", parsed.description)
        assertTrue(parsed.instructions.contains("京东和淘宝"))

        val entry = SkillMarkdownParser.toSkillEntry(parsed, id = "sk_1")
        val exported = SkillMarkdownParser.exportMarkdown(entry)
        val reparsed = SkillMarkdownParser.parse(exported)
        assertEquals(parsed.name, reparsed?.name)
        assertEquals(parsed.description, reparsed?.description)

        val match = SkillMarkdownParser.matchSlashInvocation("/price-check 帮我找小米充电宝", listOf(entry))
        assertNotNull(match)
        assertEquals("price-check", match!!.skill.name)
        assertEquals("帮我找小米充电宝", match.remainingText)
    }

    @Test
    fun systemPromptBuilder_composesLayersAndDetectsConfigChanges() {
        val base = AppSettings()
        val prompt0 = SystemPromptBuilder.build(base, supportsImages = true)
        assertTrue(prompt0.contains("你是 Zai"))
        assertFalse(prompt0.contains("用户自定义指令"))
        assertFalse(prompt0.contains("可用技能"))

        val enriched = base.copy(
            userSystemPrompt = "回复尽量控制在两句话以内。",
            skills = listOf(
                SkillEntry("s1", "daily-note", "整理每日备忘", "完整步骤内容", enabled = true),
                SkillEntry("s2", "disabled-sk", "不该出现", "忽略", enabled = false),
            ),
            mcpServers = listOf(
                McpServerConfig(
                    id = "m1",
                    name = "Notion",
                    url = "https://mcp.notion.example/mcp",
                    enabled = true,
                    tools = listOf(McpToolSummary("search_pages", "搜索页面")),
                ),
            ),
        )
        val prompt1 = SystemPromptBuilder.build(enriched, supportsImages = true)
        assertTrue(prompt1.contains("回复尽量控制在两句话以内。"))
        assertTrue(prompt1.contains("daily-note: 整理每日备忘"))
        assertFalse(prompt1.contains("完整步骤内容")) // progressive disclosure!
        assertFalse(prompt1.contains("disabled-sk"))
        assertTrue(prompt1.contains("Notion(1个工具)"))

        assertNotEquals(
            SystemPromptBuilder.configFingerprint(base, true),
            SystemPromptBuilder.configFingerprint(enriched, true),
        )
        val offCompaction = base.copy(compactionMode = com.self.zai.model.CompactionMode.OFF)
        assertNotEquals(
            SystemPromptBuilder.configFingerprint(base, true),
            SystemPromptBuilder.configFingerprint(offCompaction, true),
        )
    }

    @Test
    fun mcpClient_parsesSseAndNormalizesSchemasAndResults() {
        val sseBody = """
            event: message
            data: {"jsonrpc":"2.0","method":"notifications/progress","params":{}}

            event: message
            data: {"jsonrpc":"2.0","id":7,"result":{"tools":[{"name":"search","description":"Web search"}]}}
        """.trimIndent()
        val parsed = StreamableHttpMcpClient.parseJsonRpcPayload("text/event-stream", sseBody, expectedId = 7L)
        assertNotNull(parsed)
        assertEquals(7L, parsed!!.getLong("id"))
        assertEquals("search", parsed.getJSONObject("result").getJSONArray("tools").getJSONObject(0).getString("name"))

        val normalized = StreamableHttpMcpClient.normalizeInputSchema(JSONObject("{}"))
        assertEquals("object", normalized.getString("type"))
        assertTrue(normalized.has("properties"))

        val callRes = StreamableHttpMcpClient.parseToolCallResult(
            JSONObject(
                """
                {
                  "content": [
                    {"type": "text", "text": "找到 3 条结果"},
                    {"type": "image", "data": "ZmFrZQ==", "mimeType": "image/png"}
                  ],
                  "isError": false
                }
                """.trimIndent(),
            ),
        )
        assertFalse(callRes.isError)
        assertEquals("找到 3 条结果", callRes.text)
        assertEquals("ZmFrZQ==", callRes.imageBase64)
        assertEquals("image/png", callRes.imageMimeType)
    }

    @Test
    fun agentToolDispatcher_routesSkillsAndMcpWithoutMarkingPhoneOperated() = runBlocking {
        val skill = SkillEntry("sk1", "travel-plan", "规划行程", "先查天气再查车票", enabled = true)
        val server = McpServerConfig(
            id = "srv1",
            name = "My Notion.Server",
            url = "https://mcp.example.com/mcp",
            enabled = true,
            tools = listOf(McpToolSummary("pages.search/v2", "搜索笔记")),
        )
        val repo = SimpleRepo(AppSettings(skills = listOf(skill), mcpServers = listOf(server)))
        val vault = SimpleVault().apply { putString(AgentToolDispatcher.mcpAuthKey("srv1"), "secret_tok") }
        var capturedToken: String? = null
        var capturedRemoteTool: String? = null
        val fakeMcp = object : McpToolClient {
            override suspend fun discoverTools(url: String, authToken: String?) = Result.success(emptyList<McpToolSummary>())
            override suspend fun callTool(
                server: McpServerConfig,
                authToken: String?,
                remoteToolName: String,
                arguments: JSONObject,
            ): ToolExecutionResult {
                capturedToken = authToken
                capturedRemoteTool = remoteToolName
                return ToolExecutionResult("mcp_result:${arguments.optString("q")}")
            }
            override fun invalidateSession(serverUrl: String) {}
        }
        val phoneController = PhoneController(repo, DummyChannel(), DummyApps())
        val dispatcher = AgentToolDispatcher(repo, vault, phoneController, fakeMcp)

        val specs = dispatcher.buildToolSpecs(repo.settings.value, includeVisionTools = true)
        val names = (0 until specs.length()).map { specs.getJSONObject(it).getString("name") }
        assertTrue(AgentToolDispatcher.READ_SKILL_TOOL in names)

        val mcpToolName = names.first { it.startsWith(AgentToolDispatcher.MCP_PREFIX) }
        assertTrue(Regex("^[a-zA-Z0-9_-]{1,64}$").matches(mcpToolName))

        // Execute read_skill
        val skillRes = dispatcher.executeTool("s1", "c1", AgentToolDispatcher.READ_SKILL_TOOL, JSONObject().put("name", "travel-plan"))
        assertFalse(skillRes.isError)
        assertTrue(skillRes.text.contains("先查天气再查车票"))

        // Execute MCP tool
        val mcpRes = dispatcher.executeTool("s1", "c2", mcpToolName, JSONObject().put("q", "周报"))
        assertFalse(mcpRes.isError)
        assertEquals("mcp_result:周报", mcpRes.text)
        assertEquals("secret_tok", capturedToken)
        assertEquals("pages.search/v2", capturedRemoteTool)

        // Neither read_skill nor MCP should falsely mark the phone as physically operated!
        assertFalse(phoneController.didOperatePhoneThisRun())
    }

    private class SimpleRepo(initial: AppSettings) : SessionRepository {
        private val _settings = MutableStateFlow(initial)
        override val settings: StateFlow<AppSettings> = _settings
        override val sessions: StateFlow<List<ChatSession>> = MutableStateFlow(emptyList())
        override fun updateSettings(transform: (AppSettings) -> AppSettings) =
            transform(_settings.value).also { _settings.value = it }
        override fun upsertSession(session: ChatSession, flushImmediately: Boolean) {}
        override fun deleteSession(sessionId: String) {}
        override fun createNewSession() = ChatSession("s1", "新对话", 1L)
        override fun flushNow() {}
    }

    private class SimpleVault : CredentialVault {
        private val map = mutableMapOf<String, String>()
        override fun putString(key: String, value: String) { map[key] = value }
        override fun getString(key: String): String? = map[key]
        override fun remove(key: String) { map.remove(key) }
        override fun putProviderApiKey(providerId: String, apiKey: String) { map[providerId] = apiKey }
        override fun getProviderApiKey(providerId: String): String? = map[providerId]
        override fun removeProviderApiKey(providerId: String) { map.remove(providerId) }
        override fun getAllProviderApiKeys(providerIds: Collection<String>) = emptyMap<String, String>()
        override fun savePiCredentialsJson(json: String) {}
        override fun loadPiCredentialsJson(): String? = null
    }

    private class DummyApps : AppDirectory {
        override fun resolveAppLabel(packageName: String) = packageName
        override fun findLaunchableApp(query: String) = null
        override fun queryLaunchableApps(query: String) = emptyList<Pair<String, String>>()
    }

    private class DummyChannel : PhoneDeviceChannel {
        override val ownPackageName = "com.self.zai"
        override fun screenDimensions() = 1080 to 2400
        override suspend fun ensureReady() {}
        override suspend fun captureTree(width: Int, height: Int): ScreenSnapshot? = null
        override suspend fun captureBitmap(): Bitmap? = null
        override suspend fun tap(x: Int, y: Int) = true
        override suspend fun longPress(x: Int, y: Int, durationMs: Int) = true
        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) = true
        override suspend fun pressKey(key: String) = true
        override suspend fun inputText(text: String, targetBounds: Rect?) = true
        override suspend fun currentForegroundPackage() = "com.android.settings"
        override suspend fun launchPackage(packageName: String) = true
    }
}
