package com.self.zai.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class DiscoveredModel(
    val id: String,
    val contextWindow: Int? = null,
    val supportsImages: Boolean? = null,
    val reasoning: Boolean? = null,
)

data class ModelDiscoveryResult(
    val resolvedBaseUrl: String,
    val models: List<DiscoveredModel>,
)

/**
 * Fetches available models from an OpenAI-compatible (or Anthropic / Ollama-style) endpoint
 * using the user-provided base URL and API key.
 */
object ModelDiscoveryClient {

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    fun normalizeBaseUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
        return withScheme
            .removeSuffix("/chat/completions")
            .removeSuffix("/responses")
            .removeSuffix("/models")
            .trimEnd('/')
    }

    fun candidateEndpoints(normalizedBase: String): List<Pair<String, String>> {
        if (normalizedBase.isEmpty()) return emptyList()
        val list = mutableListOf<Pair<String, String>>()
        val hasVersionSegment = Regex("/v\\d+(?:[a-z0-9_-]*)?(?:/.*)?$", RegexOption.IGNORE_CASE)
            .containsMatchIn(normalizedBase)
        if (hasVersionSegment) {
            list += normalizedBase to "$normalizedBase/models"
        } else {
            list += "$normalizedBase/v1" to "$normalizedBase/v1/models"
            list += normalizedBase to "$normalizedBase/models"
        }
        return list.distinctBy { it.second }
    }

    suspend fun fetchModels(rawBaseUrl: String, apiKey: String): Result<ModelDiscoveryResult> =
        withContext(Dispatchers.IO) {
            val normalized = normalizeBaseUrl(rawBaseUrl)
            if (normalized.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("请输入接口地址"))
            }
            val cleanKey = apiKey.trim()
            val candidates = candidateEndpoints(normalized)
            var lastError = "无法连接到接口地址"

            for ((resolvedBase, endpoint) in candidates) {
                val attempt = runCatching {
                    val reqBuilder = Request.Builder()
                        .url(endpoint)
                        .get()
                        .header("Accept", "application/json")
                    if (cleanKey.isNotEmpty()) {
                        reqBuilder.header("Authorization", "Bearer $cleanKey")
                        reqBuilder.header("x-api-key", cleanKey)
                    }
                    client.newCall(reqBuilder.build()).execute().use { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            val snippet = body.take(180).replace("\n", " ").trim()
                            throw IllegalStateException("HTTP ${resp.code}: ${snippet.ifEmpty { resp.message }}")
                        }
                        val parsed = parseModelsPayload(body)
                        if (parsed.isEmpty()) {
                            throw IllegalStateException("接口返回成功，但模型列表为空")
                        }
                        ModelDiscoveryResult(resolvedBaseUrl = resolvedBase, models = parsed)
                    }
                }
                if (attempt.isSuccess) return@withContext attempt
                lastError = attempt.exceptionOrNull()?.message ?: lastError
            }
            Result.failure(IllegalStateException(lastError))
        }

    fun parseModelsPayload(body: String): List<DiscoveredModel> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return emptyList()

        val root = runCatching { json.parseToJsonElement(trimmed) }.getOrNull() ?: return emptyList()
        val result = linkedMapOf<String, DiscoveredModel>()
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> {
                (root["data"] as? JsonArray)
                    ?: (root["models"] as? JsonArray)
                    ?: (root["items"] as? JsonArray)
            }
            else -> null
        }
        if (array != null) {
            extractArray(array, result)
        }
        return result.values.sortedBy { it.id.lowercase() }
    }

    private fun extractArray(array: JsonArray, out: MutableMap<String, DiscoveredModel>) {
        for (item in array) {
            when (item) {
                is JsonPrimitive -> {
                    val id = item.contentOrNull?.trim().orEmpty()
                    if (id.isNotEmpty()) out.putIfAbsent(id, DiscoveredModel(id = id))
                }
                is JsonObject -> {
                    val id = (item["id"]?.jsonPrimitive?.contentOrNull
                        ?: item["name"]?.jsonPrimitive?.contentOrNull)
                        ?.trim()
                        .orEmpty()
                    if (id.isEmpty()) continue
                    val ctx = item["context_length"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
                        ?: item["context_window"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
                        ?: item["max_model_len"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
                    val vision = detectVisionHint(id, item)
                    val reasoning = detectReasoningHint(id, item)
                    out.putIfAbsent(
                        id,
                        DiscoveredModel(
                            id = id,
                            contextWindow = ctx,
                            supportsImages = vision,
                            reasoning = reasoning,
                        ),
                    )
                }
                else -> Unit
            }
        }
    }

    private fun detectVisionHint(id: String, obj: JsonObject): Boolean? {
        val modalities = (obj["architecture"] as? JsonObject)?.get("input_modalities") as? JsonArray
            ?: obj["input_modalities"] as? JsonArray
        if (modalities != null) {
            return modalities.any {
                (it as? JsonPrimitive)?.contentOrNull.equals("image", ignoreCase = true)
            }
        }
        val lower = id.lowercase()
        return when {
            lower.contains("vl") || lower.contains("vision") ||
                lower.contains("gpt-4o") || lower.contains("gpt-5") ||
                lower.contains("gemini") || lower.contains("claude-3") ||
                lower.contains("claude-4") || lower.contains("claude-sonnet") ||
                lower.contains("claude-opus") || lower.contains("o3") ||
                lower.contains("o4") || lower.contains("qwen2.5-vl") ||
                lower.contains("qwen3-vl") || lower.contains("glm-4v") ||
                lower.contains("glm-4.6v") -> true
            lower.contains("embedding") || lower.contains("whisper") || lower.contains("tts") -> false
            else -> null
        }
    }

    private fun detectReasoningHint(id: String, obj: JsonObject): Boolean? {
        val explicit = (obj["reasoning"] as? JsonPrimitive)?.booleanOrNull
        if (explicit != null) return explicit
        val lower = id.lowercase()
        return when {
            lower.contains("reasoner") || lower.contains("r1") ||
                lower.contains("thinking") || lower.startsWith("o1") ||
                lower.startsWith("o3") || lower.startsWith("o4") ||
                lower.contains("gpt-5") || lower.contains("codex") -> true
            else -> null
        }
    }
}
