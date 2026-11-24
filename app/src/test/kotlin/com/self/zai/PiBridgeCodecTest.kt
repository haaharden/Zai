package com.self.zai

import com.self.zai.model.AuthUiState
import com.self.zai.model.CustomModelEntry
import com.self.zai.model.CustomProviderConfig
import com.self.zai.pi.PiBridgeCodec
import com.self.zai.pi.PiSessionEvent
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PiBridgeCodecTest {

    @Test
    fun parseProvidersStatus_preserves500kContextAndDynamicThinkingLevels() {
        val raw = JSONArray(
            """
            [
              {
                "id": "openai-codex",
                "name": "ChatGPT Codex",
                "credential": "oauth",
                "custom": false,
                "supportsOAuth": true,
                "models": [
                  {
                    "id": "gpt-6.2-sol",
                    "name": "GPT-6.2 Sol",
                    "contextWindow": 500000,
                    "input": ["text", "image"],
                    "thinkingLevels": ["minimal", "low", "medium", "high", "xhigh", "max"]
                  }
                ]
              }
            ]
            """.trimIndent(),
        )

        val providers = PiBridgeCodec.parseProvidersStatus(raw)
        assertEquals(1, providers.size)
        val codex = providers.first()
        assertTrue(codex.isConnected)
        assertEquals("openai-codex", codex.id)
        val sol = codex.models.first()
        assertEquals(500_000, sol.contextWindow)
        assertTrue(sol.supportsImages)
        assertTrue(sol.supportsThinkingSelection)
        assertEquals(listOf("minimal", "low", "medium", "high", "xhigh", "max"), sol.thinkingLevels)
        assertEquals("minimal", sol.clampThinking("off"))
        assertEquals("xhigh", sol.clampThinking("xhigh"))
    }

    @Test
    fun parseAuthEvent_readsDeviceCodeVerificationUri() {
        val event = JSONObject(
            """
            {
              "type": "device_code",
              "userCode": "ABCD-1234",
              "verificationUri": "https://auth.openai.com/codex/device"
            }
            """.trimIndent(),
        )

        val next = PiBridgeCodec.parseAuthEvent(AuthUiState.Idle, event)
        assertNotNull(next)
        assertEquals("ABCD-1234", next?.userCode)
        assertEquals("https://auth.openai.com/codex/device", next?.openUrl)
    }

    @Test
    fun encodeCustomProviders_serializesEfforts() {
        val encoded = PiBridgeCodec.encodeCustomProviders(
            listOf(
                CustomProviderConfig(
                    id = "custom_1",
                    name = "OpenRouter",
                    baseUrl = "https://openrouter.ai/api/v1",
                    models = listOf(
                        CustomModelEntry(
                            id = "openai/o3",
                            contextWindow = 200_000,
                            supportsImages = true,
                            reasoning = true,
                            efforts = listOf("low", "medium", "high"),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(1, encoded.length())
        val model = encoded.getJSONObject(0).getJSONArray("models").getJSONObject(0)
        assertEquals(3, model.getJSONArray("efforts").length())
        assertEquals("high", model.getJSONArray("efforts").getString(2))
    }

    @Test
    fun parseSessionEvent_mapsStreamingAndLifecycleEvents() {
        val delta = PiBridgeCodec.parseSessionEvent(
            JSONObject("""{"type":"text_delta","session":"s1","delta":"hello"}"""),
        )
        assertEquals(PiSessionEvent.TextDelta("s1", "hello"), delta)

        val idle = PiBridgeCodec.parseSessionEvent(
            JSONObject("""{"type":"idle","session":"s1"}"""),
        )
        assertEquals(PiSessionEvent.Idle("s1"), idle)

        val noSessionErr = PiBridgeCodec.parseSessionEvent(
            JSONObject("""{"type":"error","op":"login","message":"bad"}"""),
        )
        assertFalse(noSessionErr != null)
    }
}
