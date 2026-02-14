package com.example.voiceinput

import org.junit.Assert.*
import org.junit.Test

class TextDifferTest {

    @Test
    fun `identical texts return single unchanged chunk`() {
        val chunks = TextDiffer.diff("こんにちは", "こんにちは")
        assertEquals(1, chunks.size)
        assertEquals("こんにちは", chunks[0].raw)
        assertEquals("こんにちは", chunks[0].converted)
        assertFalse(chunks[0].isDifferent)
    }

    @Test
    fun `completely different texts return single changed chunk`() {
        val chunks = TextDiffer.diff("abc", "xyz")
        assertEquals(1, chunks.size)
        assertEquals("abc", chunks[0].raw)
        assertEquals("xyz", chunks[0].converted)
        assertTrue(chunks[0].isDifferent)
    }

    @Test
    fun `shared suffix splits into changed and unchanged`() {
        // "ギットステータスを確認して" → "git statusを確認して"
        val chunks = TextDiffer.diff("ギットステータスを確認して", "git statusを確認して")
        assertEquals(2, chunks.size)
        assertEquals("ギットステータス", chunks[0].raw)
        assertEquals("git status", chunks[0].converted)
        assertTrue(chunks[0].isDifferent)
        assertEquals("を確認して", chunks[1].raw)
        assertEquals("を確認して", chunks[1].converted)
        assertFalse(chunks[1].isDifferent)
    }

    @Test
    fun `shared prefix splits into unchanged and changed`() {
        // "今日はいい天気" → "今日はgood weather"
        val chunks = TextDiffer.diff("今日はいい天気", "今日はgood weather")
        assertEquals(2, chunks.size)
        assertEquals("今日は", chunks[0].raw)
        assertEquals("今日は", chunks[0].converted)
        assertFalse(chunks[0].isDifferent)
        assertEquals("いい天気", chunks[1].raw)
        assertEquals("good weather", chunks[1].converted)
        assertTrue(chunks[1].isDifferent)
    }

    @Test
    fun `shared prefix and suffix with middle change`() {
        // "今日はギットステータスを確認して" → "今日はgit statusを確認して"
        val chunks = TextDiffer.diff("今日はギットステータスを確認して", "今日はgit statusを確認して")
        assertEquals(3, chunks.size)
        assertEquals("今日は", chunks[0].raw)
        assertEquals("今日は", chunks[0].converted)
        assertFalse(chunks[0].isDifferent)
        assertEquals("ギットステータス", chunks[1].raw)
        assertEquals("git status", chunks[1].converted)
        assertTrue(chunks[1].isDifferent)
        assertEquals("を確認して", chunks[2].raw)
        assertEquals("を確認して", chunks[2].converted)
        assertFalse(chunks[2].isDifferent)
    }

    @Test
    fun `empty raw text`() {
        val chunks = TextDiffer.diff("", "hello")
        assertEquals(1, chunks.size)
        assertEquals("", chunks[0].raw)
        assertEquals("hello", chunks[0].converted)
    }

    @Test
    fun `empty converted text`() {
        val chunks = TextDiffer.diff("hello", "")
        assertEquals(1, chunks.size)
        assertEquals("hello", chunks[0].raw)
        assertEquals("", chunks[0].converted)
    }

    @Test
    fun `multiple changes with common parts`() {
        // "エーピーアイキーをせっていする" → "APIキーを設定する"
        // common: キーを, る
        val chunks = TextDiffer.diff("エーピーアイキーをせっていする", "APIキーを設定する")
        // Should have alternating changed/unchanged sections
        assertTrue(chunks.size >= 2)
        // The "キーを" part should be common
        val unchangedParts = chunks.filter { !it.isDifferent }
        assertTrue(unchangedParts.any { it.raw.contains("キーを") })
    }
}
