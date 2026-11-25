package com.self.zai

import com.self.zai.model.AuthUiState
import com.self.zai.model.CompactionMode
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

    @Test
    fun parseProviderQuota_andContextEvents_decodeWindowsAndCountdownCleanly() {
        val now = 1_800_000_000_000L
        val quotaJson = JSONObject(
            """
            {
              "providerId": "openai-codex",
              "planType": "pro",
              "primary": {
                "usedPercent": 16,
                "remainingPercent": 84,
                "windowMinutes": 300,
                "resetAtMs": ${now + 135 * 60_000L}
              },
              "secondary": {
                "usedPercent": 38,
                "remainingPercent": 62,
                "windowMinutes": 10080,
                "resetAtMs": ${now + 3 * 24 * 3600_000L}
              },
              "limitReached": false,
              "updatedAtMs": $now
            }
            """.trimIndent(),
        )
        val parsed = PiBridgeCodec.parseProviderQuota("openai-codex", quotaJson)
        assertNotNull(parsed)
        assertEquals("Pro", parsed!!.planDisplayLabel())
        val pWin = parsed.primary!!
        assertEquals(84, pWin.remainingPercent)
        assertEquals("5h", pWin.windowLabel())
        assertEquals("2h15m后重置", pWin.formatResetCountdown(now))
        val sWin = parsed.secondary!!
        assertEquals(62, sWin.remainingPercent)
        assertEquals("周", sWin.windowLabel())
        assertEquals("3天后重置", sWin.formatResetCountdown(now))

        val usageEv = PiBridgeCodec.parseSessionEvent(
            JSONObject(
                """
                {
                  "type": "context_usage",
                  "session": "s1",
                  "estimatedTokens": 92000,
                  "contextWindow": 500000,
                  "percent": 18,
                  "archivedBlocks": 2,
                  "mode": "acp",
                  "thresholdPercent": 75
                }
                """.trimIndent(),
            ),
        ) as PiSessionEvent.ContextUsage
        assertEquals(18, usageEv.usage.percent)
        assertEquals(2, usageEv.usage.archivedBlocks)
        assertEquals(CompactionMode.ACP, usageEv.usage.mode)

        val compactEv = PiBridgeCodec.parseSessionEvent(
            JSONObject(
                """
                {
                  "type": "context_compacted",
                  "session": "s1",
                  "blockId": "b1",
                  "foldedCount": 8,
                  "beforeTokens": 120000,
                  "afterTokens": 30000,
                  "percent": 24
                }
                """.trimIndent(),
            ),
        ) as PiSessionEvent.ContextCompacted
        assertEquals("b1", compactEv.blockId)
        assertEquals(8, compactEv.foldedCount)
        assertEquals(24, compactEv.percent)
    }
}
