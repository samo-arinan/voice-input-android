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
    fun `extractWhisperContext always returns null`() {
        assertNull(SshContextProvider.extractWhisperContext("some text"))
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
    fun `reformatPemKey fixes key with missing newlines`() {
        val header = "-----BEGIN RSA PRIVATE KEY-----"
        val footer = "-----END RSA PRIVATE KEY-----"
        val body = "ABCD" .repeat(20) // 80 chars, no newlines
        val mangled = "$header\n$body$footer"
        val result = SshContextProvider.reformatPemKey(mangled)
        assertTrue(result.startsWith("$header\n"))
        assertTrue(result.endsWith("\n$footer"))
        // Body should be split into 64-char lines
        val lines = result.split("\n")
        assertEquals(header, lines.first())
        assertEquals(footer, lines.last())
        // Middle lines should be max 64 chars
        lines.drop(1).dropLast(1).forEach { line ->
            assertTrue("Line too long: ${line.length}", line.length <= 64)
        }
    }

    @Test
    fun `reformatPemKey preserves already formatted key`() {
        val key = "-----BEGIN RSA PRIVATE KEY-----\n" +
            "ABCD".repeat(16) + "\n" + // 64 chars
            "EFGH".repeat(16) + "\n" + // 64 chars
            "-----END RSA PRIVATE KEY-----"
        val result = SshContextProvider.reformatPemKey(key)
        assertEquals(key, result)
    }

    @Test
    fun `reformatPemKey handles OPENSSH format`() {
        val key = "-----BEGIN OPENSSH PRIVATE KEY-----\n" +
            "ABCD".repeat(30) + // 120 chars, no newlines
            "\n-----END OPENSSH PRIVATE KEY-----"
        val result = SshContextProvider.reformatPemKey(key)
        assertTrue(result.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----\n"))
        assertTrue(result.endsWith("\n-----END OPENSSH PRIVATE KEY-----"))
    }

    @Test
    fun `buildCommand without session has no target`() {
        val cmd = SshContextProvider.buildCommand("")
        assertTrue(cmd.contains("tmux capture-pane -p -S -80"))
        assertFalse(cmd.contains(" -t "))
    }

    @Test
    fun `buildCommand with session includes target`() {
        val cmd = SshContextProvider.buildCommand("dev")
        assertTrue(cmd.contains("tmux capture-pane -t dev -p -S -80"))
    }

    @Test
    fun `buildCommand with blank session has no target`() {
        val cmd = SshContextProvider.buildCommand("  ")
        assertFalse(cmd.contains(" -t "))
    }
}
