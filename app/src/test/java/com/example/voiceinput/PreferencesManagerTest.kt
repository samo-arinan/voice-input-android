package com.example.voiceinput

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PreferencesManagerTest {

    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var manager: PreferencesManager

    @Before
    fun setUp() {
        sharedPreferences = mockk()
        editor = mockk(relaxed = true)
        every { sharedPreferences.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.putInt(any(), any()) } returns editor
        every { editor.putBoolean(any(), any()) } returns editor
        manager = PreferencesManager(sharedPreferences)
    }

    @Test
    fun `saveApiKey stores key in preferences`() {
        manager.saveApiKey("sk-test-key-123")
        verify { editor.putString("openai_api_key", "sk-test-key-123") }
        verify { editor.apply() }
    }

    @Test
    fun `getApiKey returns stored key`() {
        every { sharedPreferences.getString("openai_api_key", null) } returns "sk-test-key-123"
        assertEquals("sk-test-key-123", manager.getApiKey())
    }

    @Test
    fun `getApiKey returns null when no key stored`() {
        every { sharedPreferences.getString("openai_api_key", null) } returns null
        assertNull(manager.getApiKey())
    }

    @Test
    fun `hasApiKey returns true when key exists`() {
        every { sharedPreferences.getString("openai_api_key", null) } returns "sk-test-key-123"
        assertTrue(manager.hasApiKey())
    }

    @Test
    fun `hasApiKey returns false when key is null`() {
        every { sharedPreferences.getString("openai_api_key", null) } returns null
        assertFalse(manager.hasApiKey())
    }

    @Test
    fun `saveWhisperModel stores model in preferences`() {
        manager.saveWhisperModel("whisper-1")
        verify { editor.putString("whisper_model", "whisper-1") }
        verify { editor.apply() }
    }

    @Test
    fun `getWhisperModel returns stored model`() {
        every { sharedPreferences.getString("whisper_model", "gpt-4o-transcribe") } returns "whisper-1"
        assertEquals("whisper-1", manager.getWhisperModel())
    }

    @Test
    fun `getWhisperModel returns default when not set`() {
        every { sharedPreferences.getString("whisper_model", "gpt-4o-transcribe") } returns "gpt-4o-transcribe"
        assertEquals("gpt-4o-transcribe", manager.getWhisperModel())
    }

    // SSH config tests

    @Test
    fun `saveSshConfig stores all SSH fields`() {
        every { sharedPreferences.getString("ssh_host", null) } returns "192.168.1.100"
        every { sharedPreferences.getInt("ssh_port", 22) } returns 22
        every { sharedPreferences.getString("ssh_username", null) } returns "user"
        every { sharedPreferences.getString("ssh_private_key", null) } returns "-----BEGIN RSA PRIVATE KEY-----\ntest\n-----END RSA PRIVATE KEY-----"
        every { sharedPreferences.getBoolean("ssh_context_enabled", false) } returns true

        manager.saveSshHost("192.168.1.100")
        manager.saveSshPort(22)
        manager.saveSshUsername("user")
        manager.saveSshPrivateKey("-----BEGIN RSA PRIVATE KEY-----\ntest\n-----END RSA PRIVATE KEY-----")
        manager.saveSshContextEnabled(true)

        assertEquals("192.168.1.100", manager.getSshHost())
        assertEquals(22, manager.getSshPort())
        assertEquals("user", manager.getSshUsername())
        assertEquals("-----BEGIN RSA PRIVATE KEY-----\ntest\n-----END RSA PRIVATE KEY-----", manager.getSshPrivateKey())
        assertTrue(manager.isSshContextEnabled())
    }

    @Test
    fun `SSH defaults are correct`() {
        every { sharedPreferences.getString("ssh_host", null) } returns null
        every { sharedPreferences.getInt("ssh_port", 22) } returns 22
        every { sharedPreferences.getString("ssh_username", null) } returns null
        every { sharedPreferences.getString("ssh_private_key", null) } returns null
        every { sharedPreferences.getBoolean("ssh_context_enabled", false) } returns false

        assertNull(manager.getSshHost())
        assertEquals(22, manager.getSshPort())
        assertNull(manager.getSshUsername())
        assertNull(manager.getSshPrivateKey())
        assertFalse(manager.isSshContextEnabled())
    }

    @Test
    fun `isSshConfigured returns true when all fields set`() {
        every { sharedPreferences.getBoolean("ssh_context_enabled", false) } returns true
        every { sharedPreferences.getString("ssh_host", null) } returns "host"
        every { sharedPreferences.getString("ssh_username", null) } returns "user"
        every { sharedPreferences.getString("ssh_private_key", null) } returns "key"

        manager.saveSshHost("host")
        manager.saveSshUsername("user")
        manager.saveSshPrivateKey("key")
        manager.saveSshContextEnabled(true)
        assertTrue(manager.isSshConfigured())
    }

    @Test
    fun `isSshConfigured returns false when host missing`() {
        every { sharedPreferences.getBoolean("ssh_context_enabled", false) } returns true
        every { sharedPreferences.getString("ssh_host", null) } returns null
        every { sharedPreferences.getString("ssh_username", null) } returns "user"
        every { sharedPreferences.getString("ssh_private_key", null) } returns "key"

        manager.saveSshUsername("user")
        manager.saveSshPrivateKey("key")
        manager.saveSshContextEnabled(true)
        assertFalse(manager.isSshConfigured())
    }

    @Test
    fun `save and get ntfy topic`() {
        every { sharedPreferences.getString("ntfy_topic", "") } returns "my-voice-input"
        manager.saveNtfyTopic("my-voice-input")
        verify { editor.putString("ntfy_topic", "my-voice-input") }
        verify { editor.apply() }
        assertEquals("my-voice-input", manager.getNtfyTopic())
    }

    @Test
    fun `ntfy topic defaults to empty string`() {
        every { sharedPreferences.getString("ntfy_topic", "") } returns ""
        assertEquals("", manager.getNtfyTopic())
    }
}
