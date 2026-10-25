package com.ultrapuika.halight.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.ultrapuika.halight.core.HaClient
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the Home Assistant base URL and access token.
 *
 * The token is encrypted with AES-256-GCM using a non-exportable key held in the Android
 * Keystore (hardware-backed on most phones). Only the ciphertext is written to app-private
 * SharedPreferences, which are excluded from cloud backup and device transfer. The key does
 * not require user authentication, because the widget must work in the background.
 */
class CredentialStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val baseUrl: String? get() = prefs.getString(KEY_URL, null)

    val hasToken: Boolean get() = prefs.contains(KEY_TOKEN)

    val isConfigured: Boolean get() = baseUrl != null && hasToken

    /** Decrypted token, or null if none is stored or the Keystore key is gone (e.g. after restore). */
    fun token(): String? {
        val stored = prefs.getString(KEY_TOKEN, null) ?: return null
        return try {
            TokenCipher.decrypt(stored)
        } catch (e: Exception) {
            null
        }
    }

    /** Saves the URL and, when [token] is non-null, replaces the stored token. */
    fun save(baseUrl: String, token: String?) {
        val editor = prefs.edit().putString(KEY_URL, baseUrl)
        if (token != null) editor.putString(KEY_TOKEN, TokenCipher.encrypt(token))
        editor.apply()
    }

    fun client(): HaClient? {
        val url = baseUrl ?: return null
        val token = token() ?: return null
        return try {
            HaClient(url, token)
        } catch (e: Exception) {
            null
        }
    }

    private object TokenCipher {
        private const val ALIAS = "ha_token_key_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128

        private fun key(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            return generator.generateKey()
        }

        fun encrypt(plain: String): String {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            check(iv.size == IV_BYTES)
            val out = iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            return Base64.encodeToString(out, Base64.NO_WRAP)
        }

        fun decrypt(stored: String): String {
            val data = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES))
            return String(cipher.doFinal(data, IV_BYTES, data.size - IV_BYTES), Charsets.UTF_8)
        }
    }

    private companion object {
        const val PREFS = "credentials"
        const val KEY_URL = "base_url"
        const val KEY_TOKEN = "token_enc"
    }
}
