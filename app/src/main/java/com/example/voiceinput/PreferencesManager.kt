package com.example.voiceinput

import android.content.SharedPreferences

class PreferencesManager(private val prefs: SharedPreferences) {

    companion object {
        private const val KEY_API_KEY = "openai_api_key"
    }

    fun saveApiKey(apiKey: String) {
        prefs.edit().putString(KEY_API_KEY, apiKey).apply()
    }

    fun getApiKey(): String? {
        return prefs.getString(KEY_API_KEY, null)
    }

    fun hasApiKey(): Boolean {
        return !getApiKey().isNullOrBlank()
    }
}
