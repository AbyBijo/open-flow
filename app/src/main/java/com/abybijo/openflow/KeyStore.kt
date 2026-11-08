package com.abybijo.openflow

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * The user's own Groq API key, stored only on this phone in
 * EncryptedSharedPreferences. It is never logged and never sent
 * anywhere except to api.groq.com.
 */
object KeyStore {
    private const val PREFS_NAME = "openflow_keys"
    private const val KEY_GROQ = "groq_api_key"

    private fun prefs(context: Context): SharedPreferences? {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            null
        }
    }

    fun getGroqKey(context: Context): String? {
        return try {
            prefs(context)?.getString(KEY_GROQ, null)?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    fun hasGroqKey(context: Context): Boolean = getGroqKey(context) != null

    fun saveGroqKey(context: Context, key: String) {
        prefs(context)?.edit()?.putString(KEY_GROQ, key.trim())?.apply()
    }

    fun clearGroqKey(context: Context) {
        prefs(context)?.edit()?.remove(KEY_GROQ)?.apply()
    }

    /** Masked form for display, e.g. "gsk_............" — never the real key. */
    fun maskedKey(context: Context): String {
        val key = getGroqKey(context) ?: return ""
        return if (key.length > 4) key.take(4) + ".".repeat(12) else "gsk_............"
    }
}
