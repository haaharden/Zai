package com.self.zai.pi

import com.self.zai.model.AuthUiState
import com.self.zai.model.CustomProviderConfig
import com.self.zai.model.ModelOption
import com.self.zai.model.ProviderStatus
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure JSON codec translating between the Pi JS bridge protocol and typed Kotlin domain models.
 * Extracted from [PiEngine] for separation of concerns and direct JVM unit testability.
 */
object PiBridgeCodec {

    const val PRIMARY_OAUTH_PROVIDER_ID = "openai-codex"

    fun parseProvidersStatus(providersArray: JSONArray?): List<ProviderStatus> {
        val arr = providersArray ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val pId = p.optString("id")
                val pName = p.optString("name", pId)
                val cred = p.optString("credential").takeIf { it.isNotBlank() && it != "null" }
                val modelsArr = p.optJSONArray("models") ?: JSONArray()
                val modelList = buildList {
                    for (j in 0 until modelsArr.length()) {
                        val m = modelsArr.optJSONObject(j) ?: continue
                        val inputArr = m.optJSONArray("input") ?: JSONArray()
                        var hasImg = false
                        for (k in 0 until inputArr.length()) {
                            if (inputArr.optString(k) == "image") hasImg = true
                        }
                        val thinkArr = m.optJSONArray("thinkingLevels") ?: JSONArray()
                        val thinks = buildList {
                            for (k in 0 until thinkArr.length()) {
                                val lvl = thinkArr.optString(k)
                                if (lvl.isNotBlank()) add(lvl)
                            }
                        }
                        add(
                            ModelOption(
                                providerId = pId,
                                providerName = pName,
                                id = m.optString("id"),
                                name = m.optString("name", m.optString("id")),
                                contextWindow = m.optInt("contextWindow", 128_000),
                                supportsImages = hasImg,
                                thinkingLevels = thinks,
                            ),
                        )
                    }
                }
                add(
                    ProviderStatus(
                        id = pId,
                        name = pName,
                        credentialType = cred,
                        isCustom = p.optBoolean("custom", false),
                        supportsOAuth = p.optBoolean("supportsOAuth", false),
                        models = modelList,
                    ),
                )
            }
        }
    }

    fun parseAuthEvent(currentState: AuthUiState, event: JSONObject?): AuthUiState.InProgress? {
        if (event == null) return null
        val current = currentState as? AuthUiState.InProgress
            ?: AuthUiState.InProgress(
                providerId = PRIMARY_OAUTH_PROVIDER_ID,
                statusMessage = "正在登录 ChatGPT Codex…",
            )
        val type = event.optString("type")
        val url = event.optString("url").takeIf { it.isNotBlank() }
            ?: event.optString("verificationUri").takeIf { it.isNotBlank() }
        val userCode = event.optString("userCode").takeIf { it.isNotBlank() }
            ?: event.optString("code").takeIf { it.isNotBlank() }
        val instructions = event.optString("instructions").takeIf { it.isNotBlank() }
            ?: event.optString("message").takeIf { it.isNotBlank() }

        return current.copy(
            statusMessage = instructions ?: when (type) {
                "auth_url", "open_url", "url" -> "请在浏览器中完成 ChatGPT 授权登录"
                "device_code" -> "请在打开的页面中输入下方授权码完成登录"
                else -> current.statusMessage
            },
            openUrl = url ?: current.openUrl,
            userCode = userCode ?: current.userCode,
        )
    }

    fun parseSessionEvent(msg: JSONObject): PiSessionEvent? = when (msg.optString("type")) {
        "configured" -> PiSessionEvent.Configured(
            sessionId = msg.optString("session"),
            provider = msg.optString("provider"),
            model = msg.optString("model"),
            supportsImages = msg.optBoolean("supportsImages", false),
        )
        "text_delta" -> PiSessionEvent.TextDelta(msg.optString("session"), msg.optString("delta"))
        "thinking_start" -> PiSessionEvent.ThinkingStart(msg.optString("session"))
        "thinking_delta" -> PiSessionEvent.ThinkingDelta(msg.optString("session"), msg.optString("delta"))
        "thinking_end" -> PiSessionEvent.ThinkingEnd(msg.optString("session"), msg.optString("text"))
        "tool_start" -> PiSessionEvent.ToolStart(
            sessionId = msg.optString("session"),
            tool = msg.optString("tool"),
            args = msg.optJSONObject("args") ?: JSONObject(),
        )
        "tool_end" -> PiSessionEvent.ToolEnd(
            sessionId = msg.optString("session"),
            tool = msg.optString("tool"),
            isError = msg.optBoolean("isError", false),
            output = msg.optString("output"),
        )
        "assistant_end" -> PiSessionEvent.AssistantEnd(
            sessionId = msg.optString("session"),
            text = msg.optString("text"),
            stopReason = msg.optString("stopReason"),
            errorMessage = msg.optString("errorMessage").takeIf { it.isNotBlank() && it != "null" },
        )
        "steered" -> PiSessionEvent.Steered(msg.optString("session"), msg.optString("text"))
        "transcript" -> msg.optJSONArray("messages")?.let { arr ->
            PiSessionEvent.Transcript(msg.optString("session"), arr.toString())
        }
        "idle" -> PiSessionEvent.Idle(msg.optString("session"))
        "error" -> {
            val sessionId = msg.optString("session")
            if (sessionId.isNotEmpty()) {
                PiSessionEvent.Error(sessionId, msg.optString("message", "Pi 运行时错误"))
            } else {
                null
            }
        }
        else -> null
    }

    fun encodeCustomProviders(list: List<CustomProviderConfig>): JSONArray {
        val arr = JSONArray()
        for (p in list) {
            val modelsArr = JSONArray()
            for (m in p.models) {
                val modelObj = JSONObject()
                    .put("id", m.id)
                    .put("contextWindow", m.contextWindow)
                    .put("images", m.supportsImages)
                    .put("reasoning", m.reasoning)
                    .put("api", m.api)
                if (m.efforts.isNotEmpty()) {
                    modelObj.put("efforts", JSONArray(m.efforts))
                }
                modelsArr.put(modelObj)
            }
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("baseUrl", p.baseUrl)
                    .put("images", p.defaultImages)
                    .put("models", modelsArr),
            )
        }
        return arr
    }

    fun parseLookupModelsResult(rawEvalResult: String): Map<String, Triple<Int, Boolean, Boolean>> =
        runCatching {
            val unquoted = JSONObject("{\"v\":$rawEvalResult}").optString("v", "{}")
            val obj = JSONObject(unquoted)
            buildMap {
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val id = keys.next()
                    val entry = obj.optJSONObject(id) ?: continue
                    put(
                        id,
                        Triple(
                            entry.optInt("contextWindow", 128_000),
                            entry.optBoolean("images", true),
                            entry.optBoolean("reasoning", false),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyMap())
}
