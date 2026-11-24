package com.self.zai

import com.self.zai.data.ModelDiscoveryClient
import com.self.zai.update.AppUpdater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZaiUnitTests {

    @Test
    fun normalizeBaseUrl_stripsKnownSuffixesAndAddsHttps() {
        assertEquals(
            "https://api.example.com/v1",
            ModelDiscoveryClient.normalizeBaseUrl("api.example.com/v1/chat/completions/"),
        )
        assertEquals(
            "http://192.168.1.10:8080",
            ModelDiscoveryClient.normalizeBaseUrl("http://192.168.1.10:8080/models"),
        )
    }

    @Test
    fun candidateEndpoints_prefersV1ThenRawBase() {
        val withoutV1 = ModelDiscoveryClient.candidateEndpoints("https://relay.example.com")
        assertEquals(
            listOf(
                "https://relay.example.com/v1" to "https://relay.example.com/v1/models",
                "https://relay.example.com" to "https://relay.example.com/models",
            ),
            withoutV1,
        )

        val withV1 = ModelDiscoveryClient.candidateEndpoints("https://relay.example.com/v1")
        assertEquals(
            listOf("https://relay.example.com/v1" to "https://relay.example.com/v1/models"),
            withV1,
        )

        val zhipuV4 = ModelDiscoveryClient.candidateEndpoints("https://open.bigmodel.cn/api/paas/v4")
        assertEquals(
            listOf("https://open.bigmodel.cn/api/paas/v4" to "https://open.bigmodel.cn/api/paas/v4/models"),
            zhipuV4,
        )

        val gemini = ModelDiscoveryClient.candidateEndpoints("https://generativelanguage.googleapis.com/v1beta/openai")
        assertEquals(
            listOf("https://generativelanguage.googleapis.com/v1beta/openai" to "https://generativelanguage.googleapis.com/v1beta/openai/models"),
            gemini,
        )
    }

    @Test
    fun parseModelsPayload_parsesOpenAiListAndDetectsHints() {
        val payload = """
            {
              "object": "list",
              "data": [
                {"id": "gpt-5.3-codex", "context_length": 256000},
                {"id": "text-embedding-3-large"},
                {"id": "custom-vision", "architecture": {"input_modalities": ["text", "image"]}}
              ]
            }
        """.trimIndent()

        val models = ModelDiscoveryClient.parseModelsPayload(payload)
        assertEquals(3, models.size)

        val codex = models.first { it.id == "gpt-5.3-codex" }
        assertEquals(256000, codex.contextWindow)
        assertEquals(true, codex.supportsImages)
        assertEquals(true, codex.reasoning)

        val emb = models.first { it.id == "text-embedding-3-large" }
        assertFalse(emb.supportsImages ?: true)

        val customVision = models.first { it.id == "custom-vision" }
        assertTrue(customVision.supportsImages == true)
    }

    @Test
    fun isRemoteVersionNewer_comparesSemverProperly() {
        assertTrue(AppUpdater.isRemoteVersionNewer("0.1.0", "v0.1.1"))
        assertTrue(AppUpdater.isRemoteVersionNewer("0.1.0", "0.2.0"))
        assertFalse(AppUpdater.isRemoteVersionNewer("0.1.0", "v0.1.0"))
        assertFalse(AppUpdater.isRemoteVersionNewer("0.2.0", "v0.1.9"))
    }
}
