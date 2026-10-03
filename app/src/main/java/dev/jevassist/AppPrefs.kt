package dev.jevassist

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
 * App settings. The Jev API key is encrypted with an AES key that lives in the
 * Android Keystore (it never leaves the phone's secure hardware), and backups are disabled
 * in the manifest, so the key is never copied off the device.
 */
class AppPrefs(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("jevassist", Context.MODE_PRIVATE)

    var apiKey: String?
        get() = prefs.getString(KEY_API, null)?.let { decrypt(it) }
        set(value) {
            if (value.isNullOrBlank()) prefs.edit().remove(KEY_API).apply()
            else prefs.edit().putString(KEY_API, encrypt(value.trim())).apply()
        }

    val hasApiKey: Boolean get() = apiKey != null

    /** Below this Jev confidence, the assistant asks "Did you mean…?" instead of acting. */
    var confidenceThreshold: Float
        get() = prefs.getFloat("threshold", 0.5f)
        set(v) = prefs.edit().putFloat("threshold", v).apply()

    var speakResponses: Boolean
        get() = prefs.getBoolean("speak", true)
        set(v) = prefs.edit().putBoolean("speak", v).apply()

    var showDebug: Boolean
        get() = prefs.getBoolean("debug", true)
        set(v) = prefs.edit().putBoolean("debug", v).apply()

    var model: String
        get() = prefs.getString("model", "jev-latest") ?: "jev-latest"
        set(v) = prefs.edit().putString("model", v.ifBlank { "jev-latest" }).apply()

    /** Remembered so "play X" goes to the app you used last time when you don't name one. */
    var lastMusicPackage: String?
        get() = prefs.getString("music_pkg", null)
        set(v) = prefs.edit().putString("music_pkg", v).apply()

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String? = try {
        val all = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, all.copyOfRange(0, 12)))
        String(cipher.doFinal(all.copyOfRange(12, all.size)), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val ALIAS = "jevassist_api_key"
        private const val KEY_API = "api_key_enc"
    }
}
