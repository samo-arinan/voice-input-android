package com.example.voiceinput

import org.junit.Assert.*
import org.junit.Test

class VoiceCommandTest {

    @Test
    fun `default values are correct`() {
        val cmd = VoiceCommand(id = "test", label = "test", text = "test\n")
        assertFalse(cmd.auto)
        assertEquals(0.95f, cmd.threshold)
        assertEquals(0, cmd.sampleCount)
        assertTrue(cmd.enabled)
    }

    @Test
    fun `custom values are preserved`() {
        val cmd = VoiceCommand(
            id = "exit", label = "exit", text = "/exit\n",
            auto = false, threshold = 0.98f, sampleCount = 3, enabled = true
        )
        assertEquals("exit", cmd.id)
        assertEquals("/exit\n", cmd.text)
        assertEquals(0.98f, cmd.threshold)
        assertEquals(3, cmd.sampleCount)
    }
}
