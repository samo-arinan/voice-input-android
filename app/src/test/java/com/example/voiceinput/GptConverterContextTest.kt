package com.example.voiceinput

import org.junit.Assert.*
import org.junit.Test

class GptConverterContextTest {

    @Test
    fun `buildUserMessage without context returns raw text only`() {
        val result = GptConverter.buildUserMessage("こんにちは", null)
        assertEquals("こんにちは", result)
    }

    @Test
    fun `buildUserMessage with context includes terminal section`() {
        val result = GptConverter.buildUserMessage("こんにちは", "$ ls\nfile1 file2")
        assertTrue(result.contains("[端末コンテキスト]"))
        assertTrue(result.contains("$ ls\nfile1 file2"))
        assertTrue(result.contains("[音声入力テキスト]"))
        assertTrue(result.contains("こんにちは"))
    }

    @Test
    fun `buildUserMessage with blank context returns raw text only`() {
        val result = GptConverter.buildUserMessage("こんにちは", "  ")
        assertEquals("こんにちは", result)
    }
}
