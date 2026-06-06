package com.example.whisperflow.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object SecurityUtils {
    private const val SECURE_PREFS_FILE = "whisperflow_prefs_secure"
    @Volatile
    private var cachedPrefs: SharedPreferences? = null
    private val valueCache = mutableMapOf<String, Any?>()
    @Volatile
    private var isValueCacheReady = false

    fun getEncryptedSharedPreferences(context: Context): SharedPreferences {
        cachedPrefs?.let { return it }

        synchronized(this) {
            cachedPrefs?.let { return it }
            val prefs = createEncryptedSharedPreferences(context)
            warmValueCacheLocked(prefs)
            cachedPrefs = prefs
            return prefs
        }
    }

    fun getString(context: Context, key: String, defaultValue: String): String {
        ensureValueCache(context)
        return synchronized(this) {
            valueCache[key] as? String ?: defaultValue
        }
    }

    fun getBoolean(context: Context, key: String, defaultValue: Boolean): Boolean {
        ensureValueCache(context)
        return synchronized(this) {
            valueCache[key] as? Boolean ?: defaultValue
        }
    }

    fun getInt(context: Context, key: String, defaultValue: Int): Int {
        ensureValueCache(context)
        return synchronized(this) {
            valueCache[key] as? Int ?: defaultValue
        }
    }

    fun putString(context: Context, key: String, value: String) {
        updateCache(key, value)
        getEncryptedSharedPreferences(context).edit().putString(key, value).apply()
    }

    fun putBoolean(context: Context, key: String, value: Boolean) {
        updateCache(key, value)
        getEncryptedSharedPreferences(context).edit().putBoolean(key, value).apply()
    }

    fun putInt(context: Context, key: String, value: Int) {
        updateCache(key, value)
        getEncryptedSharedPreferences(context).edit().putInt(key, value).apply()
    }

    fun remove(context: Context, key: String) {
        synchronized(this) {
            valueCache.remove(key)
        }
        getEncryptedSharedPreferences(context).edit().remove(key).apply()
    }

    fun saveSettings(
        context: Context,
        apiKey: String,
        whisperModel: String,
        interactionMode: String,
        audioSource: Int,
        targetLanguage: String,
        aiEnhancementModel: String
    ) {
        synchronized(this) {
            valueCache["api_key"] = apiKey
            valueCache["whisper_model"] = whisperModel
            valueCache["interaction_mode"] = interactionMode
            valueCache["audio_source"] = audioSource
            valueCache["target_language"] = targetLanguage
            valueCache["ai_enhancement_model"] = aiEnhancementModel
        }
        getEncryptedSharedPreferences(context).edit().apply {
            putString("api_key", apiKey)
            putString("whisper_model", whisperModel)
            putString("interaction_mode", interactionMode)
            putInt("audio_source", audioSource)
            putString("target_language", targetLanguage)
            putString("ai_enhancement_model", aiEnhancementModel)
            apply()
        }
    }

    private fun ensureValueCache(context: Context) {
        if (isValueCacheReady) return
        synchronized(this) {
            if (isValueCacheReady) return
            getEncryptedSharedPreferences(context)
        }
    }

    private fun updateCache(key: String, value: Any?) {
        synchronized(this) {
            valueCache[key] = value
        }
    }

    private fun warmValueCacheLocked(prefs: SharedPreferences) {
        if (isValueCacheReady) return
        valueCache.clear()
        valueCache.putAll(prefs.all)
        isValueCacheReady = true
    }

    private fun createEncryptedSharedPreferences(context: Context): SharedPreferences {
        val securePrefs = try {
            val mainKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context.applicationContext,
                SECURE_PREFS_FILE,
                mainKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e("SecurityUtils", "Failed to create EncryptedSharedPreferences, falling back to standard SharedPreferences", e)
            // Fallback to standard SharedPreferences in case Keystore fails (rare, e.g., on custom broken ROMs)
            return context.applicationContext.getSharedPreferences("whisperflow_prefs", Context.MODE_PRIVATE)
        }

        // Automatic seamless migration from plaintext prefs to secure prefs
        val plainPrefs = context.applicationContext.getSharedPreferences("whisperflow_prefs", Context.MODE_PRIVATE)
        if (plainPrefs.contains("api_key")) {
            try {
                val oldApiKey = plainPrefs.getString("api_key", "") ?: ""
                val oldModel = plainPrefs.getString("whisper_model", "whisper-large-v3") ?: "whisper-large-v3"
                val oldMode = plainPrefs.getString("interaction_mode", "HOLD_TO_TALK") ?: "HOLD_TO_TALK"

                securePrefs.edit().apply {
                    putString("api_key", oldApiKey)
                    putString("whisper_model", oldModel)
                    putString("interaction_mode", oldMode)
                    apply()
                }
                // Clear plaintext prefs to prevent credential recovery from cleartext XML files
                plainPrefs.edit().clear().apply()
                Log.d("SecurityUtils", "Successfully migrated plaintext credentials to hardware-encrypted SharedPreferences.")
            } catch (e: Exception) {
                Log.e("SecurityUtils", "Error during SharedPreferences migration: ${e.message}")
            }
        }
        return securePrefs
    }
}
