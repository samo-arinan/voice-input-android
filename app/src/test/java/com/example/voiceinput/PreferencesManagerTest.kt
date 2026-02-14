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
}
