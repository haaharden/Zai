package com.self.zai.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores sensitive tokens (ChatGPT OAuth credentials, custom API keys) encrypted via Android Keystore AES-256-GCM.
 */
class SecureStore(context: Context) : CredentialVault {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun putString(key: String, value: String) {
        runCatching {
            val encrypted = encrypt(value.toByteArray(Charsets.UTF_8))
            prefs.edit().putString(key, encrypted).apply()
        }
    }

    override fun getString(key: String): String? {
        val raw = prefs.getString(key, null) ?: return null
        return runCatching {
            String(decrypt(raw), Charsets.UTF_8)
        }.getOrNull()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun putProviderApiKey(providerId: String, apiKey: String) {
        putString("api_key_$providerId", apiKey)
    }

    override fun getProviderApiKey(providerId: String): String? = getString("api_key_$providerId")

    override fun removeProviderApiKey(providerId: String) = remove("api_key_$providerId")

    override fun getAllProviderApiKeys(providerIds: Collection<String>): Map<String, String> =
        buildMap {
            for (id in providerIds) {
                val key = getProviderApiKey(id)
                if (!key.isNullOrBlank()) put(id, key)
            }
        }

    override fun savePiCredentialsJson(json: String) = putString(KEY_PI_CREDS, json)

    override fun loadPiCredentialsJson(): String? = getString(KEY_PI_CREDS)

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = ks.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun encrypt(plain: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plain)
        val combined = ByteArray(1 + iv.size + cipherText.size)
        combined[0] = iv.size.toByte()
        System.arraycopy(iv, 0, combined, 1, iv.size)
        System.arraycopy(cipherText, 0, combined, 1 + iv.size, cipherText.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): ByteArray {
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        val ivLen = combined[0].toInt() and 0xFF
        val iv = combined.copyOfRange(1, 1 + ivLen)
        val cipherText = combined.copyOfRange(1 + ivLen, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(cipherText)
    }

    companion object {
        private const val PREFS_NAME = "zai_secure_store"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "zai_master_aes_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_PI_CREDS = "pi_credentials_json"
    }
}
