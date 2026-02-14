package com.example.voiceinput

import org.junit.Assert.*
import org.junit.Test

class ConversionChunkTest {

    @Test
    fun `isDifferent returns true when raw and converted differ`() {
        val chunk = ConversionChunk(raw = "ギット", converted = "git")
        assertTrue(chunk.isDifferent)
    }

    @Test
    fun `isDifferent returns false when raw and converted are same`() {
        val chunk = ConversionChunk(raw = "こんにちは", converted = "こんにちは")
        assertFalse(chunk.isDifferent)
    }

    @Test
    fun `displayText returns converted by default`() {
        val chunk = ConversionChunk(raw = "ギット", converted = "git")
        assertEquals("git", chunk.displayText)
    }

    @Test
    fun `displayText returns raw when useRaw is true`() {
        val chunk = ConversionChunk(raw = "ギット", converted = "git", useRaw = true)
        assertEquals("ギット", chunk.displayText)
    }
}
