package com.self.zai

import com.self.zai.data.ModelDiscoveryClient
import com.self.zai.update.AppUpdater
import com.self.zai.update.PiHotUpdateVerifier
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

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
    fun parseModelsPayload_extractsProviderReasoningEfforts() {
        val payload = """
            {
              "data": [
                {
                  "id": "custom-reasoner",
                  "context_length": 200000,
                  "reasoning_efforts": ["low", "medium", "high", "max"]
                }
              ]
            }
        """.trimIndent()

        val models = ModelDiscoveryClient.parseModelsPayload(payload)
        assertEquals(1, models.size)
        assertEquals(listOf("low", "medium", "high", "max"), models.first().efforts)
        assertTrue(models.first().reasoning == true)
    }

    @Test
    fun isRemoteVersionNewer_comparesSemverProperly() {
        assertTrue(AppUpdater.isRemoteVersionNewer("0.1.0", "v0.1.1"))
        assertTrue(AppUpdater.isRemoteVersionNewer("0.1.0", "0.2.0"))
        assertFalse(AppUpdater.isRemoteVersionNewer("0.1.0", "v0.1.0"))
        assertFalse(AppUpdater.isRemoteVersionNewer("0.2.0", "v0.1.9"))
    }

    @Test
    fun shouldDiscardHotUpdate_evictsOlderOrEqualHotBundlesAfterApkUpgrade() {
        assertTrue(AppUpdater.shouldDiscardHotUpdate("0.1.10", "v0.1.9"))
        assertTrue(AppUpdater.shouldDiscardHotUpdate("0.1.10", "v0.1.10"))
        assertTrue(AppUpdater.shouldDiscardHotUpdate("0.1.10", ""))
        assertFalse(AppUpdater.shouldDiscardHotUpdate("0.1.10", "v0.1.11"))
    }

    @Test
    fun piHotUpdateVerifier_acceptsValidEcdsaManifest_andRejectsTamperedOrWrongTag() {
        val kpg = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }
        val pair = kpg.generateKeyPair()
        val pubB64 = Base64.getEncoder().encodeToString(pair.public.encoded)

        val bundleText = buildString {
            append("// ZaiHost ZaiBridge\n")
            repeat(1_200) { append("var x$it = $it;\n") }
        }
        val bundleBytes = bundleText.toByteArray(Charsets.UTF_8)
        val sha256 = PiHotUpdateVerifier.sha256Hex(bundleBytes)
        val tag = "v0.2.6"
        val size = bundleBytes.size.toLong()
        val payloadRaw = JSONObject()
            .put("tag", tag)
            .put("sha256", sha256)
            .put("size", size)
            .put("issuedAt", "2026-11-25T14:00:00Z")
            .toString()

        val sigBytes = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(payloadRaw.toByteArray(Charsets.UTF_8))
            sign()
        }
        val sigB64 = Base64.getEncoder().encodeToString(sigBytes)

        val manifest = JSONObject()
            .put("payload", payloadRaw)
            .put("signature", sigB64)
            .toString()

        // Valid signed bundle passes
        val ok = PiHotUpdateVerifier.verifyBundle(
            manifestJson = manifest,
            bundleBytes = bundleBytes,
            expectedTag = "v0.2.6",
            publicKeySpkiBase64 = pubB64,
        )
        assertTrue("Expected valid signed bundle to verify: ${ok.exceptionOrNull()}", ok.isSuccess)
        assertEquals("v0.2.6", ok.getOrThrow().tag)

        // Tampered 1 byte in bundle fails
        val tamperedBytes = bundleBytes.copyOf().also { it[it.lastIndex] = 'Z'.code.toByte() }
        assertTrue(
            PiHotUpdateVerifier.verifyBundle(manifest, tamperedBytes, "v0.2.6", pubB64).isFailure,
        )

        // Mismatched expected tag fails (prevents downgrade/tag swap)
        assertTrue(
            PiHotUpdateVerifier.verifyBundle(manifest, bundleBytes, "v0.2.7", pubB64).isFailure,
        )

        // Wrong public key fails
        val otherPub = Base64.getEncoder().encodeToString(kpg.generateKeyPair().public.encoded)
        assertTrue(
            PiHotUpdateVerifier.verifyBundle(manifest, bundleBytes, "v0.2.6", otherPub).isFailure,
        )
    }
}
