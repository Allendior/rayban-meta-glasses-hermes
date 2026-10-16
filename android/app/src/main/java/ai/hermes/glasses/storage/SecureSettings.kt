package ai.hermes.glasses.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import ai.hermes.glasses.voice.ReplyMode
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureSettings(context: Context) {
    private val preferences = context.getSharedPreferences("hermes_settings", Context.MODE_PRIVATE)

    var bridgeUrl: String
        get() = preferences.getString(URL_KEY, DEFAULT_URL) ?: DEFAULT_URL
        set(value) = preferences.edit { putString(URL_KEY, value) }

    var language: String
        get() = preferences.getString(LANGUAGE_KEY, "en") ?: "en"
        set(value) = preferences.edit { putString(LANGUAGE_KEY, value) }

    var replyMode: ReplyMode
        get() = ReplyMode.fromStored(preferences.getString(REPLY_MODE_KEY, null))
        set(value) = preferences.edit { putString(REPLY_MODE_KEY, value.storedValue) }

    var sessionId: String?
        get() = preferences.getString(SESSION_KEY, null)
        set(value) = preferences.edit { putString(SESSION_KEY, value) }

    fun saveApiKey(value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        preferences.edit {
            putString(API_KEY, Base64.encodeToString(cipher.doFinal(value.toByteArray()), Base64.NO_WRAP))
            putString(API_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
        }
    }

    fun loadApiKey(): String = try {
        val encrypted = preferences.getString(API_KEY, null) ?: return ""
        val iv = preferences.getString(API_IV, null) ?: return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
        )
        String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)))
    } catch (_: Exception) {
        ""
    }

    fun clearSession() {
        sessionId = null
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        // No default: each owner must enter their own Mac's Tailscale IPv4
        // address (find it with `tailscale ip -4` on the Mac). Shipping a
        // real address here would point every install at the maintainer's
        // machine.
        const val DEFAULT_URL = ""
        private const val URL_KEY = "bridge_url"
        private const val LANGUAGE_KEY = "language"
        private const val REPLY_MODE_KEY = "reply_mode"
        private const val SESSION_KEY = "session_id"
        private const val API_KEY = "api_key_ciphertext"
        private const val API_IV = "api_key_iv"
        private const val KEY_ALIAS = "hermes_bridge_api_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
