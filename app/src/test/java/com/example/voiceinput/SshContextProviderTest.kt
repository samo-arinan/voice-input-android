package com.example.voiceinput

import org.junit.Assert.*
import org.junit.Test

class SshContextProviderTest {

    @Test
    fun `parseOutput trims trailing blank lines`() {
        val raw = "line1\nline2\n\n\n"
        val result = SshContextProvider.parseOutput(raw)
        assertEquals("line1\nline2", result)
    }

    @Test
    fun `parseOutput returns null for blank output`() {
        assertNull(SshContextProvider.parseOutput(""))
        assertNull(SshContextProvider.parseOutput("\n\n"))
    }

    @Test
    fun `parseOutput preserves internal blank lines`() {
        val raw = "line1\n\nline3\n"
        val result = SshContextProvider.parseOutput(raw)
        assertEquals("line1\n\nline3", result)
    }

    @Test
    fun `extractWhisperContext returns last 20 lines`() {
        val lines = (1..50).map { "line$it" }.joinToString("\n")
        val result = SshContextProvider.extractWhisperContext(lines)
        val resultLines = result!!.split("\n")
        assertEquals(20, resultLines.size)
        assertEquals("line31", resultLines.first())
        assertEquals("line50", resultLines.last())
    }

    @Test
    fun `extractWhisperContext returns all if under 20 lines`() {
        val text = "line1\nline2\nline3"
        val result = SshContextProvider.extractWhisperContext(text)
        assertEquals(text, result)
    }

    @Test
    fun `extractWhisperContext returns null for null input`() {
        assertNull(SshContextProvider.extractWhisperContext(null))
    }

    @Test
    fun `extractGptContext returns full text`() {
        val text = "line1\nline2"
        assertEquals(text, SshContextProvider.extractGptContext(text))
    }

    @Test
    fun `extractGptContext returns null for null input`() {
        assertNull(SshContextProvider.extractGptContext(null))
    }

    @Test
    fun `buildCommand without session has no target`() {
        assertEquals("tmux capture-pane -p -S -80", SshContextProvider.buildCommand(""))
    }

    @Test
    fun `buildCommand with session includes target`() {
        assertEquals("tmux capture-pane -t dev -p -S -80", SshContextProvider.buildCommand("dev"))
    }

    @Test
    fun `buildCommand with blank session has no target`() {
        assertEquals("tmux capture-pane -p -S -80", SshContextProvider.buildCommand("  "))
    }
}
