package com.self.zai

import com.self.zai.data.AttachmentManager
import com.self.zai.data.ModelDiscoveryClient
import com.self.zai.model.AttachmentKind
import com.self.zai.model.ChatAttachment
import com.self.zai.model.PendingAttachment
import com.self.zai.model.PromptImage
import com.self.zai.permission.DevicePermissionHelper
import com.self.zai.update.AppUpdater
import com.self.zai.update.PiHotUpdateVerifier
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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

    @Test
    fun accessibilityRestorePlan_matchesFullAndShortNames_andTogglesOffPreservingOtherServices() {
        val full = "com.self.zai/com.self.zai.phone.ZaiAccessibilityService"
        val short = "com.self.zai/.phone.ZaiAccessibilityService"

        // Empty / null settings
        val fromNull = DevicePermissionHelper.buildAccessibilityRestorePlan("null", full, short)
        assertFalse(fromNull.alreadyPresent)
        assertEquals("", fromNull.withoutSelf)
        assertEquals(full, fromNull.withSelf)
        assertFalse(DevicePermissionHelper.isComponentEnabledInAccessibilityList("null", full, short))

        // Already enabled using short component name alongside other services
        val existingShort = "com.foo.app/.MyService:$short:com.bar.app/com.bar.app.OtherService"
        assertTrue(DevicePermissionHelper.isComponentEnabledInAccessibilityList(existingShort, full, short))
        val fromShort = DevicePermissionHelper.buildAccessibilityRestorePlan(existingShort, full, short)
        assertTrue(fromShort.alreadyPresent)
        assertEquals("com.foo.app/.MyService:com.bar.app/com.bar.app.OtherService", fromShort.withoutSelf)
        assertEquals("com.foo.app/.MyService:com.bar.app/com.bar.app.OtherService:$full", fromShort.withSelf)

        // Already enabled as the sole service (unbound zombie case) -> withoutSelf must be empty so toggle rebinds
        val fromSolo = DevicePermissionHelper.buildAccessibilityRestorePlan(full, full, short)
        assertTrue(fromSolo.alreadyPresent)
        assertEquals("", fromSolo.withoutSelf)
        assertEquals(full, fromSolo.withSelf)

        // Deduplicates if both short and full somehow coexist
        val fromDup = DevicePermissionHelper.buildAccessibilityRestorePlan("$short:$full", full, short)
        assertTrue(fromDup.alreadyPresent)
        assertEquals("", fromDup.withoutSelf)
        assertEquals(full, fromDup.withSelf)
    }

    @Test
    fun attachmentManager_classifiesFiles_extractsOfficeXml_andBuildsMultimodalPrompts() {
        assertTrue(AttachmentManager.isImageFile("IMG_001.HEIC", "application/octet-stream"))
        assertTrue(AttachmentManager.isPdfFile("invoice.pdf", null))
        assertEquals("docx", AttachmentManager.officeDocumentType("report.docx", null))
        assertEquals("xlsx", AttachmentManager.officeDocumentType("budget.xlsx", null))
        assertEquals("pptx", AttachmentManager.officeDocumentType("deck.pptx", null))
        assertTrue(AttachmentManager.isSupportedTextFile("MainActivity.kt", "application/octet-stream"))
        assertTrue(AttachmentManager.looksLikeUtf8Text("你好 Zai\nline 2".toByteArray(Charsets.UTF_8)))
        assertFalse(AttachmentManager.looksLikeUtf8Text(byteArrayOf(0x50, 0x4B, 0x00, 0x04)))

        // Build synthetic .docx in memory and verify XML paragraph & entity extraction
        val docxBytes = ByteArrayOutputStream().apply {
            ZipOutputStream(this).use { zos ->
                zos.putNextEntry(ZipEntry("word/document.xml"))
                zos.write(
                    """
                    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                      <w:body>
                        <w:p><w:r><w:t>项目周报 &amp; 计划</w:t></w:r></w:p>
                        <w:p><w:r><w:t>完成度：100%</w:t></w:r></w:p>
                      </w:body>
                    </w:document>
                    """.trimIndent().toByteArray(Charsets.UTF_8),
                )
                zos.closeEntry()
            }
        }.toByteArray()
        val (docxText, docxTruncated) = AttachmentManager.extractOfficeXmlText(docxBytes, "docx")
        assertFalse(docxTruncated)
        assertEquals("项目周报 & 计划\n完成度：100%", docxText)

        // Build synthetic .xlsx in memory and verify shared strings + inline numbers
        val xlsxBytes = ByteArrayOutputStream().apply {
            ZipOutputStream(this).use { zos ->
                zos.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
                zos.write("<sst><si><t>项目</t></si><si><t>预算</t></si><si><t>云服务</t></si></sst>".toByteArray())
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
                zos.write(
                    ("<worksheet><sheetData>" +
                        "<row><c t=\"s\"><v>0</v></c><c t=\"s\"><v>1</v></c></row>" +
                        "<row><c t=\"s\"><v>2</v></c><c><v>1280</v></c></row>" +
                        "</sheetData></worksheet>").toByteArray(Charsets.UTF_8),
                )
                zos.closeEntry()
            }
        }.toByteArray()
        val (xlsxText, _) = AttachmentManager.extractOfficeXmlText(xlsxBytes, "xlsx")
        assertTrue(xlsxText.contains("项目\t预算"))
        assertTrue(xlsxText.contains("云服务\t1280"))

        // Prompt formatting + title fallback for attachment-only messages
        val textFile = PendingAttachment(
            attachment = ChatAttachment(
                id = "att_1",
                kind = AttachmentKind.FILE,
                displayName = "notes.md",
                mimeType = "text/markdown",
                sizeBytes = 2048,
                metaLabel = "2.0 KB",
            ),
            extractedText = "# Todo\n- Buy milk",
        )
        val photo = PendingAttachment(
            attachment = ChatAttachment(
                id = "att_2",
                kind = AttachmentKind.IMAGE,
                displayName = "shot.jpg",
                mimeType = "image/jpeg",
                sizeBytes = 1024,
            ),
            promptImages = listOf(PromptImage("ZmFrZQ==", "image/jpeg")),
        )
        val builtPrompt = AttachmentManager.buildPromptTextWithAttachments("", listOf(textFile, photo))
        assertTrue(builtPrompt.contains("[附件文件：notes.md (2.0 KB)]"))
        assertTrue(builtPrompt.contains("# Todo\n- Buy milk"))
        assertTrue(builtPrompt.contains("请查看并分析以上附件内容"))

        assertEquals("图片分析", AttachmentManager.buildSessionTitleFromPrompt("", listOf(photo.attachment)))
        assertEquals("notes.md", AttachmentManager.buildSessionTitleFromPrompt("", listOf(textFile.attachment)))
        assertEquals("帮我总结", AttachmentManager.buildSessionTitleFromPrompt("帮我总结", listOf(textFile.attachment)))
    }
}
