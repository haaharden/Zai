package com.self.zai

import android.graphics.Bitmap
import android.graphics.Rect
import com.self.zai.agent.AgentToolDispatcher
import com.self.zai.agent.SystemPromptBuilder
import com.self.zai.data.CredentialVault
import com.self.zai.data.SessionRepository
import com.self.zai.mcp.McpToolClient
import com.self.zai.model.AppSettings
import com.self.zai.model.ChatSession
import com.self.zai.model.McpServerConfig
import com.self.zai.model.McpToolSummary
import com.self.zai.model.WebSearchProvider
import com.self.zai.phone.AppDirectory
import com.self.zai.phone.PhoneController
import com.self.zai.phone.ScreenSnapshot
import com.self.zai.phone.channel.PhoneDeviceChannel
import com.self.zai.pi.ToolExecutionResult
import com.self.zai.web.HttpWebSearchClient
import com.self.zai.web.WebPageContent
import com.self.zai.web.WebSearchClient
import com.self.zai.web.WebSearchResponse
import com.self.zai.web.WebSearchResultItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Base64

class WebSearchTest {

    @Test
    fun priorityChain_worksKeylessByDefaultAndUsesBraveWhenConfiguredWithFallback() = runBlocking {
        val triedDefault = mutableListOf<WebSearchProvider>()
        val keylessRes = HttpWebSearchClient.executeWithPriority(
            query = "Android 15",
            maxResults = 5,
            priority = WebSearchProvider.DEFAULT_PRIORITY,
            apiKeys = emptyMap(),
        ) { provider, _ ->
            triedDefault += provider
            Result.success(
                WebSearchResponse(
                    provider = provider,
                    providerDetail = "免 Key · Bing",
                    query = "Android 15",
                    results = listOf(WebSearchResultItem("Android 15", "https://android.com", "Release notes")),
                ),
            )
        }
        assertTrue(keylessRes.isSuccess)
        assertEquals(listOf(WebSearchProvider.FREE), triedDefault)
        assertEquals(WebSearchProvider.FREE, keylessRes.getOrThrow().provider)

        // When Brave key exists, Brave runs first; on 429 it falls back to FREE
        val triedWithKey = mutableListOf<WebSearchProvider>()
        val fallbackRes = HttpWebSearchClient.executeWithPriority(
            query = "Kotlin 2.1",
            maxResults = 5,
            priority = WebSearchProvider.DEFAULT_PRIORITY,
            apiKeys = mapOf(WebSearchProvider.BRAVE to "BSA_test_key"),
        ) { provider, key ->
            triedWithKey += provider
            if (provider == WebSearchProvider.BRAVE) {
                assertEquals("BSA_test_key", key)
                Result.failure(IOException("Brave 额度已达上限 (429)"))
            } else {
                Result.success(
                    WebSearchResponse(
                        provider = provider,
                        providerDetail = "免 Key · Bing",
                        query = "Kotlin 2.1",
                        results = listOf(WebSearchResultItem("Kotlin 2.1", "https://kotlinlang.org", "What's new")),
                    ),
                )
            }
        }
        assertTrue(fallbackRes.isSuccess)
        assertEquals(listOf(WebSearchProvider.BRAVE, WebSearchProvider.FREE), triedWithKey)
        val resp = fallbackRes.getOrThrow()
        assertEquals(WebSearchProvider.FREE, resp.provider)
        assertEquals(1, resp.fallbackNotes.size)
        assertTrue(resp.fallbackNotes.first().contains("Brave"))

        // Custom priority putting FREE above BRAVE respects user ordering even when Brave key is set
        val triedCustom = mutableListOf<WebSearchProvider>()
        val customRes = HttpWebSearchClient.executeWithPriority(
            query = "weather",
            maxResults = 3,
            priority = listOf(WebSearchProvider.FREE, WebSearchProvider.BRAVE),
            apiKeys = mapOf(WebSearchProvider.BRAVE to "BSA_test_key"),
        ) { provider, _ ->
            triedCustom += provider
            Result.success(
                WebSearchResponse(
                    provider = provider,
                    providerDetail = "免 Key · Bing",
                    query = "weather",
                    results = listOf(WebSearchResultItem("Weather", "https://weather.example", "Sunny")),
                ),
            )
        }
        assertTrue(customRes.isSuccess)
        assertEquals(listOf(WebSearchProvider.FREE), triedCustom)
    }

    @Test
    fun parsers_parseBraveTavilySerperBingRssAndDuckDuckGo() {
        val braveJson = """
            {
              "web": {
                "results": [
                  {
                    "title": "<strong>Brave</strong> Search API",
                    "url": "https://brave.com/search/api/",
                    "description": "Independent search index.",
                    "extra_snippets": ["Fast JSON responses."],
                    "age": "2 days ago"
                  }
                ]
              }
            }
        """.trimIndent()
        val brave = HttpWebSearchClient.parseBraveJson(braveJson, 5)
        assertEquals(1, brave.size)
        assertEquals("Brave Search API", brave[0].title)
        assertTrue(brave[0].snippet.contains("Fast JSON responses."))
        assertEquals("2 days ago", brave[0].publishedAt)

        val tavilyJson = """
            {
              "answer": "Kotlin 2.1 introduces guard conditions.",
              "results": [
                {"title": "Kotlin Docs", "url": "https://kotlinlang.org/docs", "content": "Detailed docs"}
              ]
            }
        """.trimIndent()
        val (tavilyAnswer, tavilyItems) = HttpWebSearchClient.parseTavilyJson(tavilyJson, 5)
        assertEquals("Kotlin 2.1 introduces guard conditions.", tavilyAnswer)
        assertEquals(1, tavilyItems.size)
        assertEquals("https://kotlinlang.org/docs", tavilyItems[0].url)

        val serperJson = """
            {
              "answerBox": {"answer": "42"},
              "organic": [
                {"title": "Result 1", "link": "https://example.org/1", "snippet": "Snippet 1", "date": "2026-01-01"}
              ]
            }
        """.trimIndent()
        val (serperAnswer, serperItems) = HttpWebSearchClient.parseSerperJson(serperJson, 5)
        assertEquals("42", serperAnswer)
        assertEquals(1, serperItems.size)
        assertEquals("https://example.org/1", serperItems[0].url)

        val encodedTarget = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("https://developer.android.com/guide".toByteArray(StandardCharsets.UTF_8))
        val bingXml = """
            <?xml version="1.0" encoding="utf-8"?>
            <rss version="2.0"><channel>
              <item>
                <title><![CDATA[Android Developers &amp; Guide]]></title>
                <link>https://www.bing.com/ck/a?u=a1$encodedTarget&amp;ntb=1</link>
                <description>Official &lt;b&gt;Android&lt;/b&gt; documentation.</description>
              </item>
            </channel></rss>
        """.trimIndent()
        val bing = HttpWebSearchClient.parseBingRss(bingXml, 5)
        assertEquals(1, bing.size)
        assertEquals("Android Developers & Guide", bing[0].title)
        assertEquals("https://developer.android.com/guide", bing[0].url)
        assertEquals("Official Android documentation.", bing[0].snippet)

        val ddgHtml = """
            <div class="result">
              <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fopenai.com%2Fnews">OpenAI <b>News</b></a>
              <a class="result__snippet">Latest announcements &amp; updates.</a>
            </div>
        """.trimIndent()
        val ddg = HttpWebSearchClient.parseDuckDuckGoHtml(ddgHtml, 5)
        assertEquals(1, ddg.size)
        assertEquals("OpenAI News", ddg[0].title)
        assertEquals("https://openai.com/news", ddg[0].url)
        assertEquals("Latest announcements & updates.", ddg[0].snippet)
    }

    @Test
    fun htmlExtractionAndSsrfGuard_cleanMarkupAndBlockPrivateUrls() {
        val html = """
            <html>
              <head>
                <title>Zai &amp; Release Notes</title>
                <style>.hide { display: none; }</style>
                <script>alert('xss')</script>
              </head>
              <body>
                <nav>Home | Login</nav>
                <article>
                  <h1>Version 0.2.14</h1>
                  <p>Built-in web search works out of the box.</p>
                </article>
              </body>
            </html>
        """.trimIndent()
        val (title, text) = HttpWebSearchClient.extractReadableHtml(html)
        assertEquals("Zai & Release Notes", title)
        assertTrue(text.contains("Version 0.2.14"))
        assertTrue(text.contains("Built-in web search works out of the box."))
        assertFalse(text.contains("alert("))
        assertFalse(text.contains("display: none"))
        assertFalse(text.contains("Home | Login"))

        assertTrue(HttpWebSearchClient.validatePublicHttpUrl("https://example.com/article?id=1").isSuccess)
        assertTrue(HttpWebSearchClient.validatePublicHttpUrl("file:///etc/passwd").isFailure)
        assertTrue(HttpWebSearchClient.validatePublicHttpUrl("http://127.0.0.1:8080/admin").isFailure)
        assertTrue(HttpWebSearchClient.validatePublicHttpUrl("http://localhost/secret").isFailure)
        assertTrue(HttpWebSearchClient.validatePublicHttpUrl("http://192.168.1.1/").isFailure)
        assertTrue(HttpWebSearchClient.validatePublicHttpUrl("http://10.0.0.5/internal").isFailure)
        assertTrue(HttpWebSearchClient.validatePublicHttpUrl("http://169.254.169.254/latest/meta-data").isFailure)
        assertTrue(HttpWebSearchClient.validatePublicHttpUrl("http://[::1]/").isFailure)
    }

    @Test
    fun dispatcherAndPrompt_exposeWebToolsWhenEnabledAndRespectToggle() = runBlocking {
        val repo = TestRepo(
            AppSettings(
                webSearchEnabled = true,
                webSearchPriority = listOf(WebSearchProvider.BRAVE, WebSearchProvider.FREE),
            ),
        )
        val vault = TestVault().apply {
            putString(AgentToolDispatcher.webSearchApiKeyKey(WebSearchProvider.BRAVE), "BSA_secret")
        }
        var capturedKeys: Map<WebSearchProvider, String> = emptyMap()
        var capturedPriority: List<WebSearchProvider> = emptyList()
        val fakeWeb = object : WebSearchClient {
            override suspend fun search(
                query: String,
                maxResults: Int,
                priority: List<WebSearchProvider>,
                apiKeys: Map<WebSearchProvider, String>,
            ): Result<WebSearchResponse> {
                capturedKeys = apiKeys
                capturedPriority = priority
                return Result.success(
                    WebSearchResponse(
                        provider = WebSearchProvider.BRAVE,
                        providerDetail = "Brave Search",
                        query = query,
                        results = listOf(WebSearchResultItem("Hit", "https://example.com", "Body")),
                    ),
                )
            }

            override suspend fun fetchUrl(url: String, maxChars: Int): Result<WebPageContent> =
                Result.success(WebPageContent(url, url, "Example", "Readable content", truncated = false))
        }
        val phoneController = PhoneController(repo, DummyChannel(), DummyApps())
        val dispatcher = AgentToolDispatcher(
            repository = repo,
            credentialVault = vault,
            phoneController = phoneController,
            mcpClient = DummyMcp(),
            webSearchClient = fakeWeb,
        )

        assertFalse(dispatcher.isPhoneTool(AgentToolDispatcher.WEB_SEARCH_TOOL))
        assertFalse(dispatcher.isScreenOperatingTool(AgentToolDispatcher.WEB_SEARCH_TOOL))
        assertFalse(dispatcher.isPhoneTool(AgentToolDispatcher.FETCH_URL_TOOL))
        assertFalse(dispatcher.isScreenOperatingTool(AgentToolDispatcher.FETCH_URL_TOOL))

        val specsEnabled = dispatcher.buildToolSpecs(repo.settings.value, includeVisionTools = true)
        val enabledNames = (0 until specsEnabled.length()).map { specsEnabled.getJSONObject(it).getString("name") }
        assertTrue(AgentToolDispatcher.WEB_SEARCH_TOOL in enabledNames)
        assertTrue(AgentToolDispatcher.FETCH_URL_TOOL in enabledNames)

        val searchRes = dispatcher.executeTool(
            "s1",
            "c1",
            AgentToolDispatcher.WEB_SEARCH_TOOL,
            JSONObject().put("query", "Xiaomi 13 Ultra"),
        )
        assertFalse(searchRes.isError)
        assertTrue(searchRes.text.contains("Brave Search"))
        assertEquals("BSA_secret", capturedKeys[WebSearchProvider.BRAVE])
        assertEquals(listOf(WebSearchProvider.BRAVE, WebSearchProvider.FREE), capturedPriority)

        val fetchRes = dispatcher.executeTool(
            "s1",
            "c2",
            AgentToolDispatcher.FETCH_URL_TOOL,
            JSONObject().put("url", "https://example.com"),
        )
        assertFalse(fetchRes.isError)
        assertTrue(fetchRes.text.contains("Readable content"))
        assertFalse(phoneController.didOperatePhoneThisRun())

        // Disabling webSearchEnabled hides tools and changes prompt + fingerprint
        val disabledSettings = repo.settings.value.copy(webSearchEnabled = false)
        val specsDisabled = dispatcher.buildToolSpecs(disabledSettings, includeVisionTools = true)
        val disabledNames = (0 until specsDisabled.length()).map { specsDisabled.getJSONObject(it).getString("name") }
        assertFalse(AgentToolDispatcher.WEB_SEARCH_TOOL in disabledNames)
        assertFalse(AgentToolDispatcher.FETCH_URL_TOOL in disabledNames)

        val promptOn = SystemPromptBuilder.build(repo.settings.value, supportsImages = true)
        val promptOff = SystemPromptBuilder.build(disabledSettings, supportsImages = true)
        assertTrue(promptOn.contains("web_search"))
        assertFalse(promptOff.contains("web_search"))
        assertNotEquals(
            SystemPromptBuilder.configFingerprint(repo.settings.value, true),
            SystemPromptBuilder.configFingerprint(disabledSettings, true),
        )
    }

    private class TestRepo(initial: AppSettings) : SessionRepository {
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

    private class TestVault : CredentialVault {
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

    private class DummyMcp : McpToolClient {
        override suspend fun discoverTools(url: String, authToken: String?) = Result.success(emptyList<McpToolSummary>())
        override suspend fun callTool(
            server: McpServerConfig,
            authToken: String?,
            remoteToolName: String,
            arguments: JSONObject,
        ) = ToolExecutionResult("")
        override fun invalidateSession(serverUrl: String) {}
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
