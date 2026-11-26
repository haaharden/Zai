package com.self.zai.web

import com.self.zai.model.WebSearchProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.TimeUnit

data class WebSearchResultItem(
    val title: String,
    val url: String,
    val snippet: String,
    val publishedAt: String? = null,
)

data class WebSearchResponse(
    val provider: WebSearchProvider,
    val providerDetail: String,
    val query: String,
    val answer: String? = null,
    val results: List<WebSearchResultItem>,
    val fallbackNotes: List<String> = emptyList(),
)

data class WebPageContent(
    val url: String,
    val finalUrl: String,
    val title: String,
    val content: String,
    val truncated: Boolean,
)

interface WebSearchClient {
    suspend fun search(
        query: String,
        maxResults: Int = DEFAULT_MAX_RESULTS,
        priority: List<WebSearchProvider> = WebSearchProvider.DEFAULT_PRIORITY,
        apiKeys: Map<WebSearchProvider, String> = emptyMap(),
    ): Result<WebSearchResponse>

    suspend fun fetchUrl(
        url: String,
        maxChars: Int = DEFAULT_MAX_FETCH_CHARS,
    ): Result<WebPageContent>

    companion object {
        const val DEFAULT_MAX_RESULTS = 6
        const val DEFAULT_MAX_FETCH_CHARS = 10_000
    }
}

/**
 * Multi-engine web search & page reader supporting prioritized execution with automatic fallback:
 * - Brave Search API (`X-Subscription-Token`)
 * - Tavily Search API (`api_key`)
 * - Serper Google Search API (`X-API-KEY`)
 * - Zero-config keyless search (`FREE`: Bing RSS -> DuckDuckGo HTML -> Bing CN RSS)
 */
class HttpWebSearchClient(
    private val httpClient: OkHttpClient = defaultClient(),
    private val safeFetchClient: OkHttpClient = defaultSafeFetchClient(),
) : WebSearchClient {

    override suspend fun search(
        query: String,
        maxResults: Int,
        priority: List<WebSearchProvider>,
        apiKeys: Map<WebSearchProvider, String>,
    ): Result<WebSearchResponse> = withContext(Dispatchers.IO) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("搜索词不能为空"))
        }
        val limit = maxResults.coerceIn(1, 10)
        executeWithPriority(
            query = trimmedQuery,
            maxResults = limit,
            priority = priority,
            apiKeys = apiKeys,
        ) { provider, key ->
            when (provider) {
                WebSearchProvider.BRAVE -> searchBrave(trimmedQuery, limit, key.orEmpty())
                WebSearchProvider.TAVILY -> searchTavily(trimmedQuery, limit, key.orEmpty())
                WebSearchProvider.SERPER -> searchSerper(trimmedQuery, limit, key.orEmpty())
                WebSearchProvider.FREE -> searchFreeChain(trimmedQuery, limit)
            }
        }
    }

    override suspend fun fetchUrl(
        url: String,
        maxChars: Int,
    ): Result<WebPageContent> = withContext(Dispatchers.IO) {
        val checkedUrl = validatePublicHttpUrl(url).getOrElse {
            return@withContext Result.failure(it)
        }
        val limitChars = maxChars.coerceIn(1_000, 20_000)
        val req = Request.Builder()
            .url(checkedUrl)
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "text/html,application/xhtml+xml,text/plain,application/json;q=0.9,*/*;q=0.5")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .get()
            .build()

        runCatching {
            safeFetchClient.newCall(req).execute().use { resp ->
                val finalUrl = resp.request.url.toString()
                validatePublicHttpUrl(finalUrl).getOrThrow()

                if (!resp.isSuccessful) {
                    throw IOException("网页请求失败 (HTTP ${resp.code})")
                }
                val contentType = resp.header("Content-Type").orEmpty().lowercase()
                if (isUnsupportedBinaryContentType(contentType)) {
                    throw IOException("暂不支持直接读取该二进制内容类型：$contentType")
                }
                val bodyBytes = readLimitedBytes(resp.body, MAX_FETCH_BYTES)
                val charset = resp.body?.contentType()?.charset(StandardCharsets.UTF_8) ?: StandardCharsets.UTF_8
                val rawText = String(bodyBytes, charset)

                val (title, extracted) = if (contentType.contains("html") || looksLikeHtml(rawText)) {
                    extractReadableHtml(rawText)
                } else {
                    "" to cleanPlainText(rawText)
                }
                if (extracted.isBlank()) {
                    throw IOException("未能从该页面提取到可读正文（可能需要登录或纯前端渲染）")
                }
                val truncated = extracted.length > limitChars
                val finalText = if (truncated) extracted.take(limitChars).trimEnd() else extracted
                WebPageContent(
                    url = checkedUrl,
                    finalUrl = finalUrl,
                    title = title,
                    content = finalText,
                    truncated = truncated,
                )
            }
        }
    }

    private fun searchBrave(query: String, maxResults: Int, apiKey: String): Result<WebSearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://api.search.brave.com/res/v1/web/search?q=$encoded&count=$maxResults&extra_snippets=true"
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("X-Subscription-Token", apiKey)
            .header("User-Agent", "Zai/1.0")
            .get()
            .build()

        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IOException(
                        when (resp.code) {
                            401, 403 -> "Brave Key 无效或无权限 (${resp.code})"
                            429 -> "Brave 额度已达上限 (429)"
                            else -> "Brave 返回 HTTP ${resp.code}"
                        },
                    )
                }
                val items = parseBraveJson(body, maxResults)
                if (items.isEmpty()) throw IOException("Brave 未返回匹配结果")
                WebSearchResponse(
                    provider = WebSearchProvider.BRAVE,
                    providerDetail = "Brave Search",
                    query = query,
                    results = items,
                )
            }
        }
    }

    private fun searchTavily(query: String, maxResults: Int, apiKey: String): Result<WebSearchResponse> {
        val payload = JSONObject()
            .put("api_key", apiKey)
            .put("query", query)
            .put("max_results", maxResults)
            .put("search_depth", "basic")
            .put("include_answer", true)
            .toString()
        val req = Request.Builder()
            .url("https://api.tavily.com/search")
            .header("Accept", "application/json")
            .header("User-Agent", "Zai/1.0")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IOException(
                        when (resp.code) {
                            401, 403 -> "Tavily Key 无效 (${resp.code})"
                            429 -> "Tavily 额度已达上限 (429)"
                            else -> "Tavily 返回 HTTP ${resp.code}"
                        },
                    )
                }
                val (answer, items) = parseTavilyJson(body, maxResults)
                if (items.isEmpty() && answer.isNullOrBlank()) {
                    throw IOException("Tavily 未返回匹配结果")
                }
                WebSearchResponse(
                    provider = WebSearchProvider.TAVILY,
                    providerDetail = "Tavily",
                    query = query,
                    answer = answer,
                    results = items,
                )
            }
        }
    }

    private fun searchSerper(query: String, maxResults: Int, apiKey: String): Result<WebSearchResponse> {
        val payload = JSONObject()
            .put("q", query)
            .put("num", maxResults)
            .toString()
        val req = Request.Builder()
            .url("https://google.serper.dev/search")
            .header("Accept", "application/json")
            .header("X-API-KEY", apiKey)
            .header("User-Agent", "Zai/1.0")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IOException(
                        when (resp.code) {
                            401, 403 -> "Serper Key 无效 (${resp.code})"
                            429 -> "Serper 额度已达上限 (429)"
                            else -> "Serper 返回 HTTP ${resp.code}"
                        },
                    )
                }
                val (answer, items) = parseSerperJson(body, maxResults)
                if (items.isEmpty() && answer.isNullOrBlank()) {
                    throw IOException("Serper 未返回匹配结果")
                }
                WebSearchResponse(
                    provider = WebSearchProvider.SERPER,
                    providerDetail = "Serper (Google)",
                    query = query,
                    answer = answer,
                    results = items,
                )
            }
        }
    }

    private fun searchFreeChain(query: String, maxResults: Int): Result<WebSearchResponse> {
        val errors = mutableListOf<String>()

        searchBingRss("https://www.bing.com/search", "免 Key · Bing", query, maxResults)
            .onSuccess { return Result.success(it) }
            .onFailure { errors += "Bing: ${it.message}" }

        searchDuckDuckGoHtml(query, maxResults)
            .onSuccess { return Result.success(it) }
            .onFailure { errors += "DDG: ${it.message}" }

        searchBingRss("https://cn.bing.com/search", "免 Key · Bing CN", query, maxResults)
            .onSuccess { return Result.success(it) }
            .onFailure { errors += "Bing CN: ${it.message}" }

        return Result.failure(IOException(errors.joinToString("；").ifBlank { "免 Key 搜索暂不可用" }))
    }

    private fun searchBingRss(
        endpoint: String,
        label: String,
        query: String,
        maxResults: Int,
    ): Result<WebSearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$endpoint?q=$encoded&format=rss&count=$maxResults"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "application/rss+xml,application/xml,text/xml;q=0.9,*/*;q=0.5")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .get()
            .build()

        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IOException("HTTP ${resp.code}")
                }
                val items = parseBingRss(body, maxResults)
                if (items.isEmpty()) throw IOException("无结果")
                WebSearchResponse(
                    provider = WebSearchProvider.FREE,
                    providerDetail = label,
                    query = query,
                    results = items,
                )
            }
        }
    }

    private fun searchDuckDuckGoHtml(query: String, maxResults: Int): Result<WebSearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://html.duckduckgo.com/html/?q=$encoded"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .get()
            .build()

        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.code == 202 || body.contains("anomaly-modal", ignoreCase = true)) {
                    throw IOException("触发反爬验证")
                }
                if (!resp.isSuccessful) {
                    throw IOException("HTTP ${resp.code}")
                }
                val items = parseDuckDuckGoHtml(body, maxResults)
                if (items.isEmpty()) throw IOException("无结果")
                WebSearchResponse(
                    provider = WebSearchProvider.FREE,
                    providerDetail = "免 Key · DuckDuckGo",
                    query = query,
                    results = items,
                )
            }
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MAX_FETCH_BYTES = 512 * 1024
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        private fun defaultSafeFetchClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .dns(PublicOnlyDns)
            .build()

        private object PublicOnlyDns : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val addresses = Dns.SYSTEM.lookup(hostname)
                if (addresses.any { isRestrictedAddress(it) }) {
                    throw IOException("出于安全限制，不允许访问本机或内网地址：$hostname")
                }
                return addresses
            }
        }

        /**
         * Executes providers in normalized priority order, automatically skipping key-required engines
         * without a configured key and falling back when an engine fails or returns no results.
         */
        suspend fun executeWithPriority(
            query: String,
            maxResults: Int,
            priority: List<WebSearchProvider>,
            apiKeys: Map<WebSearchProvider, String>,
            runner: suspend (provider: WebSearchProvider, apiKey: String?) -> Result<WebSearchResponse>,
        ): Result<WebSearchResponse> {
            val ordered = WebSearchProvider.normalizePriority(priority)
            val fallbackNotes = mutableListOf<String>()

            for (provider in ordered) {
                val key = apiKeys[provider]?.trim()?.takeIf { it.isNotEmpty() }
                if (provider.requiresApiKey && key == null) {
                    continue
                }
                val attempt = runCatching { runner(provider, key) }.getOrElse { Result.failure(it) }
                val value = attempt.getOrNull()
                if (value != null && (value.results.isNotEmpty() || !value.answer.isNullOrBlank())) {
                    return Result.success(
                        value.copy(
                            query = query,
                            results = value.results.take(maxResults),
                            fallbackNotes = fallbackNotes.toList(),
                        ),
                    )
                }
                val reason = attempt.exceptionOrNull()?.message?.ifBlank { "无结果" } ?: "无结果"
                fallbackNotes += "${provider.label()}: $reason"
            }

            val detail = fallbackNotes.joinToString("；").ifBlank { "没有可用的搜索引擎" }
            return Result.failure(IOException("联网搜索失败（$detail）"))
        }

        fun formatSearchResultForModel(response: WebSearchResponse): String = buildString {
            appendLine("搜索词：${response.query}")
            append("引擎：${response.providerDetail}")
            if (response.fallbackNotes.isNotEmpty()) {
                append("（已自动回退：${response.fallbackNotes.joinToString("；")}）")
            }
            appendLine()
            if (!response.answer.isNullOrBlank()) {
                appendLine()
                appendLine("摘要回答：${response.answer.trim()}")
            }
            if (response.results.isNotEmpty()) {
                appendLine()
                response.results.forEachIndexed { idx, item ->
                    appendLine("${idx + 1}. ${item.title}")
                    appendLine("   链接: ${item.url}")
                    if (!item.publishedAt.isNullOrBlank()) {
                        appendLine("   时间: ${item.publishedAt}")
                    }
                    if (item.snippet.isNotBlank()) {
                        appendLine("   摘要: ${item.snippet}")
                    }
                }
            }
        }.trim()

        fun formatPageContentForModel(page: WebPageContent): String = buildString {
            if (page.title.isNotBlank()) {
                appendLine("标题：${page.title}")
            }
            appendLine("链接：${page.finalUrl}")
            appendLine()
            append(page.content)
            if (page.truncated) {
                append("\n\n[正文较长，已按字数上限截断]")
            }
        }.trim()

        fun parseBraveJson(rawJson: String, maxResults: Int): List<WebSearchResultItem> {
            val root = JSONObject(rawJson)
            val webResults = root.optJSONObject("web")?.optJSONArray("results")
            val primary = parseBraveResultArray(webResults, maxResults)
            if (primary.isNotEmpty()) return primary
            val newsResults = root.optJSONObject("news")?.optJSONArray("results")
            return parseBraveResultArray(newsResults, maxResults)
        }

        private fun parseBraveResultArray(arr: JSONArray?, maxResults: Int): List<WebSearchResultItem> {
            if (arr == null || arr.length() == 0) return emptyList()
            val out = mutableListOf<WebSearchResultItem>()
            for (i in 0 until arr.length()) {
                if (out.size >= maxResults) break
                val obj = arr.optJSONObject(i) ?: continue
                val url = obj.optString("url").trim()
                val title = stripInlineMarkup(obj.optString("title"))
                if (url.isEmpty() || title.isEmpty()) continue
                val desc = stripInlineMarkup(obj.optString("description"))
                val extras = obj.optJSONArray("extra_snippets")?.let { ex ->
                    (0 until ex.length())
                        .mapNotNull { idx -> stripInlineMarkup(ex.optString(idx)).takeIf { it.isNotEmpty() } }
                        .take(2)
                        .joinToString(" ")
                }.orEmpty()
                val snippet = when {
                    desc.isBlank() -> extras
                    extras.isBlank() || desc.length >= 160 -> desc
                    else -> "$desc $extras"
                }.take(420)
                val age = obj.optString("age").ifBlank { obj.optString("page_age") }.trim().takeIf { it.isNotEmpty() }
                out += WebSearchResultItem(
                    title = title,
                    url = url,
                    snippet = snippet,
                    publishedAt = age,
                )
            }
            return out
        }

        fun parseTavilyJson(rawJson: String, maxResults: Int): Pair<String?, List<WebSearchResultItem>> {
            val root = JSONObject(rawJson)
            val answer = root.optString("answer").trim().takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
            val arr = root.optJSONArray("results") ?: return answer to emptyList()
            val out = mutableListOf<WebSearchResultItem>()
            for (i in 0 until arr.length()) {
                if (out.size >= maxResults) break
                val obj = arr.optJSONObject(i) ?: continue
                val url = obj.optString("url").trim()
                val title = cleanPlainText(obj.optString("title"))
                if (url.isEmpty() || title.isEmpty()) continue
                val snippet = cleanPlainText(obj.optString("content")).take(420)
                val published = obj.optString("published_date").trim().takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
                out += WebSearchResultItem(
                    title = title,
                    url = url,
                    snippet = snippet,
                    publishedAt = published,
                )
            }
            return answer to out
        }

        fun parseSerperJson(rawJson: String, maxResults: Int): Pair<String?, List<WebSearchResultItem>> {
            val root = JSONObject(rawJson)
            val answerBox = root.optJSONObject("answerBox")
            val kg = root.optJSONObject("knowledgeGraph")
            val answer = listOfNotNull(
                answerBox?.optString("answer")?.trim()?.takeIf { it.isNotEmpty() },
                answerBox?.optString("snippet")?.trim()?.takeIf { it.isNotEmpty() },
                kg?.optString("description")?.trim()?.takeIf { it.isNotEmpty() },
            ).firstOrNull()

            val arr = root.optJSONArray("organic") ?: return answer to emptyList()
            val out = mutableListOf<WebSearchResultItem>()
            for (i in 0 until arr.length()) {
                if (out.size >= maxResults) break
                val obj = arr.optJSONObject(i) ?: continue
                val url = obj.optString("link").trim()
                val title = cleanPlainText(obj.optString("title"))
                if (url.isEmpty() || title.isEmpty()) continue
                val snippet = cleanPlainText(obj.optString("snippet")).take(420)
                val date = obj.optString("date").trim().takeIf { it.isNotEmpty() }
                out += WebSearchResultItem(
                    title = title,
                    url = url,
                    snippet = snippet,
                    publishedAt = date,
                )
            }
            return answer to out
        }

        fun parseBingRss(xml: String, maxResults: Int): List<WebSearchResultItem> {
            val itemRegex = Regex("(?is)<item\\b[^>]*>(.*?)</item>")
            val out = mutableListOf<WebSearchResultItem>()
            for (match in itemRegex.findAll(xml)) {
                if (out.size >= maxResults) break
                val block = match.groupValues[1]
                val title = stripInlineMarkup(extractXmlTag(block, "title"))
                val rawLink = decodeHtmlEntities(extractXmlTag(block, "link")).trim()
                val url = unwrapSearchRedirectUrl(rawLink)
                val desc = stripInlineMarkup(extractXmlTag(block, "description")).take(420)
                val pubDate = stripInlineMarkup(extractXmlTag(block, "pubDate")).takeIf { it.isNotEmpty() }
                if (title.isEmpty() || !url.startsWith("http")) continue
                out += WebSearchResultItem(
                    title = title,
                    url = url,
                    snippet = desc,
                    publishedAt = pubDate,
                )
            }
            return out
        }

        fun parseDuckDuckGoHtml(html: String, maxResults: Int): List<WebSearchResultItem> {
            val linkRegex = Regex(
                """(?is)<a\b[^>]*class="[^"]*result__a[^"]*"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
            )
            val snippetRegex = Regex(
                """(?is)<(?:a|div|span)\b[^>]*class="[^"]*result__snippet[^"]*"[^>]*>(.*?)</(?:a|div|span)>""",
            )
            val links = linkRegex.findAll(html).toList()
            if (links.isEmpty()) return emptyList()
            val out = mutableListOf<WebSearchResultItem>()
            for (i in links.indices) {
                if (out.size >= maxResults) break
                val cur = links[i]
                val nextStart = if (i + 1 < links.size) links[i + 1].range.first else html.length
                val slice = html.substring(cur.range.last + 1, nextStart)

                val rawHref = decodeHtmlEntities(cur.groupValues[1]).trim()
                val url = unwrapSearchRedirectUrl(rawHref)
                val title = stripInlineMarkup(cur.groupValues[2])
                if (title.isEmpty() || !url.startsWith("http")) continue
                val snippet = snippetRegex.find(slice)?.groupValues?.get(1)
                    ?.let { stripInlineMarkup(it) }
                    .orEmpty()
                    .take(420)
                out += WebSearchResultItem(
                    title = title,
                    url = url,
                    snippet = snippet,
                )
            }
            return out
        }

        fun unwrapSearchRedirectUrl(rawUrl: String): String {
            var url = rawUrl.trim()
            if (url.startsWith("//")) url = "https:$url"
            if (url.startsWith("/l/?") || url.contains("duckduckgo.com/l/?")) {
                val queryPart = url.substringAfter('?', "")
                val uddg = queryPart.split('&')
                    .firstOrNull { it.startsWith("uddg=") }
                    ?.substringAfter("uddg=")
                if (!uddg.isNullOrBlank()) {
                    val decoded = runCatching { URLDecoder.decode(uddg, "UTF-8") }.getOrNull()
                    if (!decoded.isNullOrBlank() && decoded.startsWith("http")) return decoded
                }
            }
            if (url.contains("bing.com/ck/a", ignoreCase = true)) {
                val queryPart = url.substringAfter('?', "")
                val uParam = queryPart.split('&')
                    .firstOrNull { it.startsWith("u=") }
                    ?.substringAfter("u=")
                if (!uParam.isNullOrBlank() && uParam.startsWith("a1") && uParam.length > 2) {
                    val payload = uParam.substring(2)
                    val decoded = runCatching {
                        val stripped = payload.trimEnd('=')
                        val padded = stripped + "=".repeat((4 - stripped.length % 4) % 4)
                        String(Base64.getUrlDecoder().decode(padded), StandardCharsets.UTF_8)
                    }.getOrNull()
                    if (!decoded.isNullOrBlank() && decoded.startsWith("http")) return decoded
                }
            }
            return url
        }

        fun validatePublicHttpUrl(rawUrl: String): Result<String> {
            val trimmed = rawUrl.trim()
            val parsed = trimmed.toHttpUrlOrNull()
                ?: return Result.failure(IllegalArgumentException("无效的网页链接：$rawUrl"))
            if (parsed.scheme != "http" && parsed.scheme != "https") {
                return Result.failure(IllegalArgumentException("仅支持 http/https 链接"))
            }
            val host = parsed.host.lowercase()
            if (isRestrictedHostName(host)) {
                return Result.failure(IllegalArgumentException("出于安全限制，不允许访问本机或内网地址：$host"))
            }
            return Result.success(parsed.toString())
        }

        fun isRestrictedHostName(host: String): Boolean {
            val h = host.trim().lowercase().trim('[', ']')
            if (h.isEmpty()) return true
            if (h == "localhost" || h == "0.0.0.0" || h == "::1" || h.endsWith(".local") || h.endsWith(".internal")) {
                return true
            }
            val ipv4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""").matchEntire(h)
            if (ipv4 != null) {
                val a = ipv4.groupValues[1].toIntOrNull() ?: return true
                val b = ipv4.groupValues[2].toIntOrNull() ?: return true
                return a == 0 ||
                    a == 10 ||
                    a == 127 ||
                    (a == 169 && b == 254) ||
                    (a == 172 && b in 16..31) ||
                    (a == 192 && b == 168) ||
                    (a == 100 && b in 64..127)
            }
            if (h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80:")) {
                return true
            }
            return false
        }

        private fun isRestrictedAddress(addr: InetAddress): Boolean {
            if (addr.isAnyLocalAddress ||
                addr.isLoopbackAddress ||
                addr.isLinkLocalAddress ||
                addr.isSiteLocalAddress ||
                addr.isMulticastAddress
            ) {
                return true
            }
            val bytes = addr.address
            if (bytes.size == 4) {
                val a = bytes[0].toInt() and 0xFF
                val b = bytes[1].toInt() and 0xFF
                if (a == 100 && b in 64..127) return true // CGNAT 100.64.0.0/10
            }
            return false
        }

        fun extractReadableHtml(html: String): Pair<String, String> {
            val titleMatch = Regex("(?is)<title\\b[^>]*>(.*?)</title>").find(html)
            val title = titleMatch?.groupValues?.get(1)?.let { stripInlineMarkup(it) }.orEmpty()

            var cleaned = html
                .replace(Regex("(?is)<!--.*?-->"), " ")
                .replace(Regex("(?is)<(script|style|noscript|svg|template|iframe|canvas)\\b[^>]*>.*?</\\1>"), " ")
                .replace(Regex("(?is)<(nav|footer|aside)\\b[^>]*>.*?</\\1>"), "\n")
                .replace(Regex("(?is)<br\\s*/?>"), "\n")
                .replace(Regex("(?is)</(p|div|li|tr|h[1-6]|section|article|blockquote|pre)>"), "\n")
                .replace(Regex("(?is)<[^>]+>"), " ")

            cleaned = decodeHtmlEntities(cleaned)
            return title to cleanPlainText(cleaned)
        }

        fun cleanPlainText(text: String): String {
            val lines = text
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .lineSequence()
                .map { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }
                .toList()
            val sb = StringBuilder()
            var consecutiveBlank = 0
            for (line in lines) {
                if (line.isEmpty()) {
                    consecutiveBlank++
                    if (consecutiveBlank <= 1 && sb.isNotEmpty()) sb.append('\n')
                } else {
                    consecutiveBlank = 0
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(line)
                }
            }
            return sb.toString().trim()
        }

        private fun stripInlineMarkup(raw: String): String {
            val decoded = decodeHtmlEntities(raw)
            val noInline = decoded.replace(
                Regex("(?is)</?(?:b|strong|em|i|u|span|mark|small|sub|sup|code)(?:\\s+[^>]*)?>"),
                "",
            )
            val noTags = noInline.replace(Regex("(?is)</?[a-z][a-z0-9]*(?:\\s+[^>]*)?/?>"), " ")
            return decodeHtmlEntities(noTags)
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        private fun extractXmlTag(block: String, tag: String): String {
            val match = Regex("(?is)<$tag\\b[^>]*>(.*?)</$tag>").find(block) ?: return ""
            val inner = match.groupValues[1].trim()
            val cdata = Regex("(?s)^<!\\[CDATA\\[(.*?)\\]\\]>$").find(inner)
            return cdata?.groupValues?.get(1) ?: inner
        }

        fun decodeHtmlEntities(input: String): String {
            if (!input.contains('&')) return input
            var out = input
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&nbsp;", " ")
                .replace("&mdash;", "—")
                .replace("&ndash;", "–")
                .replace("&hellip;", "…")
                .replace("&laquo;", "«")
                .replace("&raquo;", "»")
            out = Regex("&#x([0-9a-fA-F]{1,6});").replace(out) { m ->
                val cp = m.groupValues[1].toIntOrNull(16)
                if (cp != null && Character.isValidCodePoint(cp)) String(Character.toChars(cp)) else m.value
            }
            out = Regex("&#(\\d{1,7});").replace(out) { m ->
                val cp = m.groupValues[1].toIntOrNull()
                if (cp != null && Character.isValidCodePoint(cp)) String(Character.toChars(cp)) else m.value
            }
            return out
        }

        private fun looksLikeHtml(raw: String): Boolean {
            val head = raw.take(512).lowercase()
            return head.contains("<!doctype html") || head.contains("<html") || head.contains("<body")
        }

        private fun isUnsupportedBinaryContentType(contentType: String): Boolean {
            if (contentType.isBlank()) return false
            return contentType.startsWith("image/") ||
                contentType.startsWith("video/") ||
                contentType.startsWith("audio/") ||
                contentType.contains("application/pdf") ||
                contentType.contains("application/zip") ||
                contentType.contains("application/octet-stream")
        }

        private fun readLimitedBytes(body: okhttp3.ResponseBody?, maxBytes: Int): ByteArray {
            if (body == null) return ByteArray(0)
            body.byteStream().use { input ->
                val buffer = ByteArray(8 * 1024)
                val out = java.io.ByteArrayOutputStream()
                var total = 0
                while (total < maxBytes) {
                    val toRead = minOf(buffer.size, maxBytes - total)
                    val read = input.read(buffer, 0, toRead)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    total += read
                }
                return out.toByteArray()
            }
        }
    }
}
