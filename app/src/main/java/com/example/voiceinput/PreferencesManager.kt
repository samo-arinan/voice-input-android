package com.example.voiceinput

import android.content.SharedPreferences

class PreferencesManager(private val prefs: SharedPreferences) {

    companion object {
        private const val KEY_API_KEY = "openai_api_key"
        private const val KEY_WHISPER_MODEL = "whisper_model"
        const val DEFAULT_WHISPER_MODEL = "gpt-4o-transcribe"
        private const val KEY_SSH_HOST = "ssh_host"
        private const val KEY_SSH_PORT = "ssh_port"
        private const val KEY_SSH_USERNAME = "ssh_username"
        private const val KEY_SSH_PRIVATE_KEY = "ssh_private_key"
        private const val KEY_SSH_CONTEXT_ENABLED = "ssh_context_enabled"
        private const val KEY_SSH_TMUX_SESSION = "ssh_tmux_session"
        const val DEFAULT_SSH_PORT = 22
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

    fun saveSshHost(host: String) { prefs.edit().putString(KEY_SSH_HOST, host).apply() }
    fun getSshHost(): String? = prefs.getString(KEY_SSH_HOST, null)

    fun saveSshPort(port: Int) { prefs.edit().putInt(KEY_SSH_PORT, port).apply() }
    fun getSshPort(): Int = prefs.getInt(KEY_SSH_PORT, DEFAULT_SSH_PORT)

    fun saveSshUsername(username: String) { prefs.edit().putString(KEY_SSH_USERNAME, username).apply() }
    fun getSshUsername(): String? = prefs.getString(KEY_SSH_USERNAME, null)

    fun saveSshPrivateKey(key: String) { prefs.edit().putString(KEY_SSH_PRIVATE_KEY, key).apply() }
    fun getSshPrivateKey(): String? = prefs.getString(KEY_SSH_PRIVATE_KEY, null)

    fun saveSshContextEnabled(enabled: Boolean) { prefs.edit().putBoolean(KEY_SSH_CONTEXT_ENABLED, enabled).apply() }
    fun isSshContextEnabled(): Boolean = prefs.getBoolean(KEY_SSH_CONTEXT_ENABLED, false)

    fun saveSshTmuxSession(session: String) { prefs.edit().putString(KEY_SSH_TMUX_SESSION, session).apply() }
    fun getSshTmuxSession(): String = prefs.getString(KEY_SSH_TMUX_SESSION, "") ?: ""

    fun isSshConfigured(): Boolean {
        return isSshContextEnabled()
            && !getSshHost().isNullOrBlank()
            && !getSshUsername().isNullOrBlank()
            && !getSshPrivateKey().isNullOrBlank()
    }
}
