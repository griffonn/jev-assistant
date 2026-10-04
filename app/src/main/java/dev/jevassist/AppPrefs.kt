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

    /** "Hey Jev" background listening is switched on. */
    var wakeEnabled: Boolean
        get() = prefs.getBoolean("wake_enabled", false)
        set(v) = prefs.edit().putBoolean("wake_enabled", v).apply()

    /**
     * The phrase the offline recognizer listens for. It must be ordinary English words the
     * speech model knows; "jev" isn't one, so the default is "hey jeff", which sounds the same.
     */
    var wakePhrase: String
        get() = prefs.getString("wake_phrase", DEFAULT_WAKE) ?: DEFAULT_WAKE
        set(v) = prefs.edit().putString("wake_phrase",
            TextTools.normalize(v).ifBlank { DEFAULT_WAKE }).apply()

    /** Tap WhatsApp's Send button automatically after you confirm (needs the accessibility service). */
    var whatsappAutoSend: Boolean
        get() = prefs.getBoolean("wa_autosend", true)
        set(v) = prefs.edit().putBoolean("wa_autosend", v).apply()

    /** Agent mode: let Jev operate arbitrary apps by reading the screen and tapping. Off by default. */
    var agentEnabled: Boolean
        get() = prefs.getBoolean("agent_enabled", false)
        set(v) = prefs.edit().putBoolean("agent_enabled", v).apply()

    /** Ask before every tap/typing (handy while trying it out). Risky taps always ask regardless. */
    var agentConfirmEveryStep: Boolean
        get() = prefs.getBoolean("agent_confirm_all", false)
        set(v) = prefs.edit().putBoolean("agent_confirm_all", v).apply()

    /** Comma-separated app names the agent must never operate (e.g. banking apps). */
    var agentBlocklist: String
        get() = prefs.getString("agent_block", "") ?: ""
        set(v) = prefs.edit().putString("agent_block", v.trim()).apply()

    fun isAgentBlocked(pkg: String, label: String): Boolean {
        val l = label.lowercase()
        return agentBlocklist.split(',').map { it.trim().lowercase() }.filter { it.length >= 2 }
            .any { it == pkg.lowercase() || l.contains(it) }
    }

    /** Length of a reminder's calendar event. */
    var reminderEventMinutes: Int
        get() = prefs.getInt("rem_minutes", 5)
        set(v) = prefs.edit().putInt("rem_minutes", v.coerceIn(1, 120)).apply()

    /** Keep the on-device interaction log (Settings → View logs). */
    var keepLogs: Boolean
        get() = prefs.getBoolean("keep_logs", true)
        set(v) = prefs.edit().putBoolean("keep_logs", v).apply()

    /** Close the assistant panel by itself after answering (otherwise it stays until you tap outside). */
    var autoClose: Boolean
        get() = prefs.getBoolean("auto_close", false)
        set(v) = prefs.edit().putBoolean("auto_close", v).apply()

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
        const val DEFAULT_WAKE = "hey jeff"
    }
}
