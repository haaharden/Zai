package com.self.zai.update

import org.json.JSONObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

data class VerifiedPiManifest(
    val tag: String,
    val sha256Hex: String,
    val sizeBytes: Long,
    val issuedAt: String,
)

/**
 * Cryptographic verifier for hot-updated Pi JS kernels (`zai-pi-host.js`).
 *
 * Every hot-update must carry a companion signed manifest (`zai-pi-host.manifest.json`) signed by
 * the trusted release ECDSA P-256 private key whose SPKI public key is embedded inside the signed APK.
 * Uses pure JCA (`SHA256withECDSA` + `SHA-256`), supported natively on Android API 29+ and JVM 17.
 */
object PiHotUpdateVerifier {

    const val MANIFEST_ASSET_NAME = "zai-pi-host.manifest.json"

    // Trusted ECDSA P-256 SPKI public key corresponding to `keystore/pi-hot-signing-private.pem`.
    const val DEFAULT_PUBLIC_KEY_SPKI_B64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE5kc7euXRMHJ6NNJviDNDiedn5v4oth+rsWHSLaiY/kogWZy50Xt8PguX/DCvFy30rCEuOgAE8+65WZQro/0Uhg=="

    fun verifyBundle(
        manifestJson: String,
        bundleBytes: ByteArray,
        expectedTag: String,
        publicKeySpkiBase64: String = DEFAULT_PUBLIC_KEY_SPKI_B64,
    ): Result<VerifiedPiManifest> = runCatching {
        require(manifestJson.isNotBlank()) { "签名清单为空，已拒绝加载" }
        require(bundleBytes.size > 10_000) { "下载的 Pi 内核文件不完整" }

        val envelope = JSONObject(manifestJson)
        val payloadRaw = envelope.optString("payload")
        val signatureB64 = envelope.optString("signature").trim()
        require(payloadRaw.isNotBlank() && signatureB64.isNotBlank()) {
            "签名清单格式无效：缺少 payload 或 signature"
        }

        val pubBytes = Base64.getDecoder().decode(publicKeySpkiBase64.trim())
        val pubKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(pubBytes))
        val sigBytes = Base64.getDecoder().decode(signatureB64)

        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(pubKey)
        verifier.update(payloadRaw.toByteArray(Charsets.UTF_8))
        require(verifier.verify(sigBytes)) { "Pi 内核发布者签名验证失败，已拒绝加载" }

        val payload = JSONObject(payloadRaw)
        val manifestTag = payload.optString("tag").trim()
        val expectedSha = payload.optString("sha256").trim().lowercase()
        val expectedSize = payload.optLong("size", -1L)
        val issuedAt = payload.optString("issuedAt").trim()

        require(manifestTag.isNotEmpty() && expectedSha.length == 64) {
            "签名清单缺少版本或摘要字段"
        }

        if (expectedTag.isNotBlank()) {
            val normExpected = expectedTag.trim().removePrefix("v").removePrefix("V")
            val normManifest = manifestTag.removePrefix("v").removePrefix("V")
            require(normExpected.equals(normManifest, ignoreCase = true)) {
                "签名清单版本 ($manifestTag) 与目标版本 ($expectedTag) 不匹配"
            }
        }

        if (expectedSize > 0) {
            require(bundleBytes.size.toLong() == expectedSize) {
                "Pi 内核文件大小校验不匹配（预期 $expectedSize，实际 ${bundleBytes.size}）"
            }
        }

        val actualSha = sha256Hex(bundleBytes)
        require(
            MessageDigest.isEqual(
                actualSha.toByteArray(Charsets.US_ASCII),
                expectedSha.toByteArray(Charsets.US_ASCII),
            ),
        ) {
            "Pi 内核 SHA-256 摘要不匹配，文件可能已被篡改"
        }

        val textSample = String(bundleBytes, Charsets.UTF_8)
        require(textSample.contains("ZaiHost") && textSample.contains("ZaiBridge")) {
            "Pi 内核结构校验失败：缺少宿主桥接入口"
        }

        VerifiedPiManifest(
            tag = manifestTag,
            sha256Hex = actualSha,
            sizeBytes = bundleBytes.size.toLong(),
            issuedAt = issuedAt,
        )
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }
}
