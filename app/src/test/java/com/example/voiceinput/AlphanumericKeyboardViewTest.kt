package com.example.voiceinput

import org.junit.Assert.*
import org.junit.Test

class AlphanumericKeyboardViewTest {

    @Test
    fun `KEY_ROWS contains all lowercase letters`() {
        val allKeys = AlphanumericKeyboardView.KEY_ROWS.flatten().map { it.display }
        for (c in 'a'..'z') {
            assertTrue("Missing key: $c", allKeys.contains(c.toString()))
        }
    }

    @Test
    fun `KEY_ROWS contains digits`() {
        val allKeys = AlphanumericKeyboardView.KEY_ROWS.flatten().map { it.display }
        for (c in '0'..'9') {
            assertTrue("Missing key: $c", allKeys.contains(c.toString()))
        }
    }

    @Test
    fun `KEY_ROWS contains special keys`() {
        val allKeys = AlphanumericKeyboardView.KEY_ROWS.flatten().map { it.display }
        assertTrue("Missing /", allKeys.contains("/"))
        assertTrue("Missing space", allKeys.contains("␣"))
        assertTrue("Missing backspace", allKeys.contains("⌫"))
        assertTrue("Missing newline", allKeys.contains("⏎"))
    }
}
