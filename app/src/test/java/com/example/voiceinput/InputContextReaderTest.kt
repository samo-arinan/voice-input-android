package com.example.voiceinput

import org.junit.Assert.*
import org.junit.Test

class InputContextReaderTest {

    @Test
    fun `formatContextDebug shows text when available`() {
        val result = InputContextReader.formatContextDebug("Hello world")
        assertEquals("CTX[11]: Hello world", result)
    }

    @Test
    fun `formatContextDebug shows empty message for null`() {
        val result = InputContextReader.formatContextDebug(null)
        assertEquals("CTX: (empty)", result)
    }

    @Test
    fun `formatContextDebug shows empty message for blank string`() {
        val result = InputContextReader.formatContextDebug("")
        assertEquals("CTX: (empty)", result)
    }

    @Test
    fun `formatContextDebug truncates long text`() {
        val longText = "a".repeat(200)
        val result = InputContextReader.formatContextDebug(longText)
        assertTrue(result.startsWith("CTX[200]: "))
        assertTrue(result.endsWith("..."))
        // 100 chars + "..."
        assertEquals("CTX[200]: ${"a".repeat(100)}...", result)
    }

    @Test
    fun `formatContextDebug does not truncate 100 char text`() {
        val text = "b".repeat(100)
        val result = InputContextReader.formatContextDebug(text)
        assertEquals("CTX[100]: ${"b".repeat(100)}", result)
    }
}
