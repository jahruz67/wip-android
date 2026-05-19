package com.example.whisperflow.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

object SecurityUtils {
    private const val SECURE_PREFS_FILE = "whisperflow_prefs_secure"

    fun getEncryptedSharedPreferences(context: Context): SharedPreferences {
        val securePrefs = try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                SECURE_PREFS_FILE,
                masterKeyAlias,
                context.applicationContext,
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
