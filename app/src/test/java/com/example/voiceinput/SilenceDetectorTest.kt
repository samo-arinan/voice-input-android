// SilenceDetectorTest.kt
package com.example.voiceinput

import org.junit.Assert.*
import org.junit.Test

class SilenceDetectorTest {

    @Test
    fun `detects silence in zero-amplitude buffer`() {
        val silentBuffer = ByteArray(3200) // 100ms at 16kHz 16-bit mono
        val detector = SilenceDetector(thresholdRms = 200.0, silenceDurationMs = 100)

        val result = detector.feed(silentBuffer, silentBuffer.size)

        assertTrue(result)
    }

    @Test
    fun `does not detect silence in loud buffer`() {
        // Fill with high-amplitude samples (16-bit little-endian)
        val loudBuffer = ByteArray(3200)
        for (i in loudBuffer.indices step 2) {
            loudBuffer[i] = 0x00       // low byte
            loudBuffer[i + 1] = 0x40   // high byte → ~16384 amplitude
        }
        val detector = SilenceDetector(thresholdRms = 200.0, silenceDurationMs = 100)

        val result = detector.feed(loudBuffer, loudBuffer.size)

        assertFalse(result)
    }

    @Test
    fun `requires sustained silence for specified duration`() {
        val silentBuffer = ByteArray(1600) // 50ms
        val detector = SilenceDetector(thresholdRms = 200.0, silenceDurationMs = 1500)

        // 50ms of silence is not enough for 1500ms threshold
        val result = detector.feed(silentBuffer, silentBuffer.size)

        assertFalse(result)
    }

    @Test
    fun `resets silence timer on loud input`() {
        val detector = SilenceDetector(thresholdRms = 200.0, silenceDurationMs = 100)

        // Feed 200ms of silence (enough for 100ms threshold)
        val silentBuffer = ByteArray(6400)
        detector.feed(silentBuffer, silentBuffer.size)

        // Feed loud buffer → resets timer
        val loudBuffer = ByteArray(3200)
        for (i in loudBuffer.indices step 2) {
            loudBuffer[i + 1] = 0x40
        }
        detector.feed(loudBuffer, loudBuffer.size)

        // Feed only 50ms of silence → not enough after reset
        val shortSilence = ByteArray(1600)
        val result = detector.feed(shortSilence, shortSilence.size)

        assertFalse(result)
    }

    @Test
    fun `reset clears state`() {
        val detector = SilenceDetector(thresholdRms = 200.0, silenceDurationMs = 100)

        // Accumulate silence
        val silentBuffer = ByteArray(6400) // 200ms
        detector.feed(silentBuffer, silentBuffer.size)

        detector.reset()

        // After reset, 50ms is not enough
        val shortSilence = ByteArray(1600)
        val result = detector.feed(shortSilence, shortSilence.size)

        assertFalse(result)
    }
}
