package com.example.voiceinput

import android.content.SharedPreferences

class PreferencesManager(private val prefs: SharedPreferences) {

    companion object {
        private const val KEY_API_KEY = "openai_api_key"
        private const val KEY_WHISPER_MODEL = "whisper_model"
        const val DEFAULT_WHISPER_MODEL = "gpt-4o-transcribe"
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

    fun saveWhisperModel(model: String) {
        prefs.edit().putString(KEY_WHISPER_MODEL, model).apply()
    }

    fun getWhisperModel(): String {
        return prefs.getString(KEY_WHISPER_MODEL, DEFAULT_WHISPER_MODEL) ?: DEFAULT_WHISPER_MODEL
    }
}
