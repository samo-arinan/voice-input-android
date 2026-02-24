package com.example.voiceinput

import org.junit.Assert.*
import org.junit.Test

class RealtimeAudioStreamerTest {

    @Test
    fun `SAMPLE_RATE is 24000`() {
        assertEquals(24000, RealtimeAudioStreamer.SAMPLE_RATE)
    }

    @Test
    fun `calculateRms for silence returns zero`() {
        val silence = ShortArray(1024) { 0 }
        assertEquals(0f, RealtimeAudioStreamer.calculateRms(silence), 0.0001f)
    }

    @Test
    fun `calculateRms for loud signal returns greater than 0_3`() {
        // Signal at ~70% of max amplitude
        val loud = ShortArray(1024) { (Short.MAX_VALUE * 0.7).toInt().toShort() }
        val rms = RealtimeAudioStreamer.calculateRms(loud)
        assertTrue("RMS $rms should be > 0.3", rms > 0.3f)
    }

    @Test
    fun `calculateRms normalizes to 0-1 range`() {
        // Max amplitude signal
        val maxSignal = ShortArray(1024) { Short.MAX_VALUE }
        val rms = RealtimeAudioStreamer.calculateRms(maxSignal)
        assertTrue("RMS $rms should be <= 1.0", rms <= 1.0f)
        assertTrue("RMS $rms should be > 0.0", rms > 0.0f)
    }

    @Test
    fun `initial isStreaming is false`() {
        val streamer = RealtimeAudioStreamer(onChunk = {})
        assertFalse(streamer.isStreaming)
    }
}
