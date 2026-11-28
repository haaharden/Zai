package com.self.zai

import com.self.zai.agent.AgentToolDispatcher
import com.self.zai.agent.CapabilitiesManager
import com.self.zai.agent.RunActivityEvent
import com.self.zai.agent.RunControlEvent
import com.self.zai.agent.RunEventBus
import com.self.zai.data.CredentialVault
import com.self.zai.data.SessionRepository
import com.self.zai.mcp.McpToolClient
import com.self.zai.model.AppSettings
import com.self.zai.model.ChatSession
import com.self.zai.model.CompactionMode
import com.self.zai.model.McpServerConfig
import com.self.zai.model.McpToolSummary
import com.self.zai.model.WebSearchProvider
import com.self.zai.pi.ToolExecutionResult
import com.self.zai.web.WebPageContent
import com.self.zai.web.WebSearchClient
import com.self.zai.web.WebSearchResponse
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Automated architecture fitness test guarding Zai's high-cohesion, low-coupling boundaries:
 * 1. Enforces ZERO package-level dependency cycles across `com.self.zai.*`.
 * 2. Enforces strict layer rules (leaf `model`/`permission`, clean `phone`/`di`/`ui`/`service`).
 * 3. Verifies extracted P0/P2 components (`CapabilitiesManager`, `RunEventBus`).
 */
class ArchitectureGuardTest {

    @Test
    fun sourcePackages_haveZeroDependencyCycles_andRespectLayerBoundaries() {
        val srcRoot = locateKotlinSourceRoot()
        assertTrue("Could not locate kotlin source root at ${srcRoot.absolutePath}", srcRoot.isDirectory)

        val importRegex = Regex("""^import\s+com\.self\.zai\.([a-zA-Z0-9_.*]+)""", RegexOption.MULTILINE)
        val pkgDeps = mutableMapOf<String, MutableSet<String>>()
        val fileDeps = mutableMapOf<String, Set<String>>()

        srcRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val rel = file.relativeTo(srcRoot).path.replace('\\', '/')
                val pkg = rel.substringBeforeLast('/', missingDelimiterValue = "").substringBefore('/').ifEmpty { "(root)" }
                val text = file.readText(Charsets.UTF_8)
                val imports = importRegex.findAll(text)
                    .map { it.groupValues[1] }
                    .filterNot { it == "BuildConfig" || it == "R" || it.startsWith("R.") }
                    .toSet()
                fileDeps[rel] = imports

                val targets = pkgDeps.getOrPut(pkg) { mutableSetOf() }
                for (imp in imports) {
                    val first = imp.substringBefore('.')
                    val targetPkg = if (first.firstOrNull()?.isUpperCase() == true) "(root)" else first
                    if (targetPkg != pkg) {
                        targets.add(targetPkg)
                    }
                }
            }

        // 1. Detect ANY cycle (2-node or multi-node) via DFS
        val visited = mutableSetOf<String>()
        val inStack = mutableSetOf<String>()
        val cycles = mutableListOf<List<String>>()

        fun dfs(node: String, path: List<String>) {
            visited.add(node)
            inStack.add(node)
            for (next in pkgDeps[node].orEmpty().sorted()) {
                if (next in inStack) {
                    cycles.add(path.subList(path.indexOf(next), path.size) + next)
                } else if (next !in visited) {
                    dfs(next, path + next)
                }
            }
            inStack.remove(node)
        }

        for (pkg in pkgDeps.keys.sorted()) {
            if (pkg !in visited) dfs(pkg, listOf(pkg))
        }
        assertTrue(
            "Detected circular package dependencies:\n${cycles.joinToString("\n") { it.joinToString(" -> ") }}",
            cycles.isEmpty(),
        )

        // 2. Enforce strict layer boundaries
        assertTrue("model must have zero internal package deps: ${pkgDeps["model"]}", pkgDeps["model"].orEmpty().isEmpty())
        assertTrue(
            "permission must only depend on model, found: ${pkgDeps["permission"]}",
            pkgDeps["permission"].orEmpty().all { it == "model" },
        )
        assertFalse("phone must not depend on assistant", "assistant" in pkgDeps["phone"].orEmpty())
        assertFalse("di must not depend on assistant", "assistant" in pkgDeps["di"].orEmpty())
        assertFalse("di must not depend on ui", "ui" in pkgDeps["di"].orEmpty())
        assertFalse("ui must not depend on assistant", "assistant" in pkgDeps["ui"].orEmpty())
        assertFalse("ui must not depend on service", "service" in pkgDeps["ui"].orEmpty())

        // 3. No sub-package may import root MainActivity or ZaiApplication
        for ((rel, imports) in fileDeps) {
            if ('/' !in rel) continue
            assertFalse("$rel must not import MainActivity", "MainActivity" in imports)
            assertFalse("$rel must not import ZaiApplication", "ZaiApplication" in imports)
        }
    }

    @Test
    fun capabilitiesManager_managesSkillsMcpWebSearch_andInvalidatesConfig() {
        val repo = InMemoryRepo()
        val vault = InMemoryVault()
        val invalidatedUrls = mutableListOf<String>()
        var invalidationCount = 0
        val fakeMcp = object : McpToolClient {
            override suspend fun discoverTools(url: String, authToken: String?) = Result.success(emptyList<McpToolSummary>())
            override suspend fun callTool(
                server: McpServerConfig,
                authToken: String?,
                remoteToolName: String,
                arguments: JSONObject,
            ) = ToolExecutionResult("ok")

            override fun invalidateSession(serverUrl: String) {
                invalidatedUrls += serverUrl
            }
        }
        val fakeWeb = object : WebSearchClient {
            override suspend fun search(
                query: String,
                maxResults: Int,
                priority: List<WebSearchProvider>,
                apiKeys: Map<WebSearchProvider, String>,
            ) = Result.failure<WebSearchResponse>(UnsupportedOperationException())

            override suspend fun fetchUrl(url: String, maxChars: Int) =
                Result.failure<WebPageContent>(UnsupportedOperationException())
        }

        val mgr = CapabilitiesManager(
            repository = repo,
            credentialVault = vault,
            mcpClient = fakeMcp,
            webSearchClient = fakeWeb,
            onConfigInvalidated = { invalidationCount++ },
        )

        mgr.updateUserSystemPrompt("  保持简洁  ")
        assertEquals("保持简洁", repo.settings.value.userSystemPrompt)

        mgr.updateCompactionSettings(CompactionMode.AUTO, 120)
        assertEquals(95, repo.settings.value.compactionThresholdPercent)

        val sk = mgr.saveSkill(null, "Daily Brief", "早报", "汇总日程")
        assertEquals(1, repo.settings.value.skills.size)
        mgr.toggleSkill(sk.id, false)
        assertFalse(repo.settings.value.skills.first().enabled)
        mgr.deleteSkill(sk.id)
        assertTrue(repo.settings.value.skills.isEmpty())

        val srv = mgr.saveMcpServer(null, "Notion", "https://mcp.example.com/mcp", "tok_123", emptyList())
        assertEquals("tok_123", mgr.getMcpAuthToken(srv.id))
        mgr.deleteMcpServer(srv.id)
        assertNull(mgr.getMcpAuthToken(srv.id))
        assertTrue(invalidatedUrls.contains("https://mcp.example.com/mcp"))

        mgr.saveWebSearchApiKey(WebSearchProvider.TAVILY, "tvly-secret")
        assertTrue(WebSearchProvider.TAVILY in mgr.configuredWebSearchProviders())
        mgr.saveWebSearchApiKey(WebSearchProvider.TAVILY, "")
        assertFalse(WebSearchProvider.TAVILY in mgr.configuredWebSearchProviders())

        assertTrue("Expected multiple session config invalidations", invalidationCount >= 7)
    }

    @Test
    fun runEventBus_dispatchesActivityControlAndInvalidations() = runBlocking {
        withTimeout(2_000L) {
            val bus = RunEventBus()
            var receivedStep: String? = null
            var receivedControl: RunControlEvent? = null
            var receivedInvalidation: String? = "unset"

            val j1 = launch(start = CoroutineStart.UNDISPATCHED) {
                val ev = bus.activityEvents.first()
                if (ev is RunActivityEvent.PhoneOperationStep) receivedStep = ev.targetAppLabel
            }
            val j2 = launch(start = CoroutineStart.UNDISPATCHED) { receivedControl = bus.controlEvents.first() }
            val j3 = launch(start = CoroutineStart.UNDISPATCHED) { receivedInvalidation = bus.configInvalidations.first() }

            bus.emitPhoneOperationStep("微信")
            bus.requestStopActiveRun()
            bus.invalidateSessionConfig("s_9")

            j1.join()
            j2.join()
            j3.join()

            assertEquals("微信", receivedStep)
            assertEquals(RunControlEvent.StopActiveRun, receivedControl)
            assertEquals("s_9", receivedInvalidation)
            assertEquals("mcp_auth_abc", AgentToolDispatcher.mcpAuthKey("abc"))
        }
    }

    private fun locateKotlinSourceRoot(): File {
        val candidates = listOf(
            File("src/main/kotlin/com/self/zai"),
            File("app/src/main/kotlin/com/self/zai"),
        )
        return candidates.firstOrNull { it.isDirectory } ?: candidates.first()
    }

    private class InMemoryRepo : SessionRepository {
        private val _settings = MutableStateFlow(AppSettings())
        override val settings: StateFlow<AppSettings> = _settings
        override val sessions: StateFlow<List<ChatSession>> = MutableStateFlow(emptyList())
        override fun updateSettings(transform: (AppSettings) -> AppSettings): AppSettings =
            transform(_settings.value).also { _settings.value = it }
        override fun upsertSession(session: ChatSession, flushImmediately: Boolean) {}
        override fun deleteSession(sessionId: String) {}
        override fun createNewSession() = ChatSession("s1", "新对话", 1L)
        override fun flushNow() {}
    }

    private class InMemoryVault : CredentialVault {
        private val map = mutableMapOf<String, String>()
        override fun putString(key: String, value: String) { map[key] = value }
        override fun getString(key: String): String? = map[key]
        override fun remove(key: String) { map.remove(key) }
        override fun putProviderApiKey(providerId: String, apiKey: String) { map["p_$providerId"] = apiKey }
        override fun getProviderApiKey(providerId: String): String? = map["p_$providerId"]
        override fun removeProviderApiKey(providerId: String) { map.remove("p_$providerId") }
        override fun getAllProviderApiKeys(providerIds: Collection<String>) = emptyMap<String, String>()
        override fun savePiCredentialsJson(json: String) {}
        override fun loadPiCredentialsJson(): String? = null
    }
}
