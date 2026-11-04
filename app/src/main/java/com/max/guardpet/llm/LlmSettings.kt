package com.max.guardpet.llm

import android.content.SharedPreferences

// Single source of truth for the opt-in gate. Features 1-3 read this, never the raw keys.
object LlmSettings {
    const val PREFS = "guardpet"
    const val KEY_OPT_IN = "llm_opt_in"
    const val KEY_API_KEY = "llm_api_key"
    const val KEY_PRIVACY_OK = "llm_privacy_ok"
    const val KEY_LANG = "lang"

    fun isOptedIn(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_OPT_IN, false) && !prefs.getString(KEY_API_KEY, null).isNullOrBlank()

    // Decrypts on read. A legacy plaintext value is encrypted in place on first
    // read; if encryption fails the plaintext is still returned (no worse than before).
    // A stored value that no longer decrypts returns null -> scripted fallback.
    fun apiKey(prefs: SharedPreferences): String? {
        val stored = prefs.getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() } ?: return null
        if (KeystoreCrypto.isEncrypted(stored)) return KeystoreCrypto.decrypt(stored)
        migrateApiKey(prefs)
        return stored
    }

    // Returns true when the key was stored encrypted.
    fun saveApiKey(prefs: SharedPreferences, key: String): Boolean {
        val encrypted = KeystoreCrypto.encrypt(key)
        prefs.edit().putString(KEY_API_KEY, encrypted ?: key).apply()
        return encrypted != null
    }

    // Eager migration for keys saved before encryption existed. Idempotent.
    fun migrateApiKey(prefs: SharedPreferences) {
        val stored = prefs.getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() } ?: return
        if (KeystoreCrypto.isEncrypted(stored)) return
        KeystoreCrypto.encrypt(stored)?.let { prefs.edit().putString(KEY_API_KEY, it).apply() }
    }

    // Null inner -> SafeLlmClient returns null -> caller uses scripted copy.
    fun safeClient(prefs: SharedPreferences): SafeLlmClient =
        LlmClientFactory.safeClientFor(if (isOptedIn(prefs)) apiKey(prefs) else null)
}
