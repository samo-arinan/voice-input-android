package com.example.voiceinput

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MfccExtractorTest {

    private fun createWavBytes(samples: ShortArray, sampleRate: Int = 16000): ByteArray {
        val pcmData = ByteArray(samples.size * 2)
        ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + pcmData.size)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray())
        header.putInt(pcmData.size)
        return header.array() + pcmData
    }

    @Test
    fun `parsePcm extracts samples from WAV bytes`() {
        val samples = shortArrayOf(100, 200, -100, -200)
        val wav = createWavBytes(samples)
        val result = MfccExtractor.parsePcm(wav)
        assertEquals(4, result.size)
        assertEquals(100.0f, result[0], 0.01f)
        assertEquals(-200.0f, result[3], 0.01f)
    }

    @Test
    fun `frameSignal splits into overlapping frames`() {
        val signal = FloatArray(800) { 1.0f }
        val frames = MfccExtractor.frameSignal(signal, frameSize = 400, stride = 160)
        assertEquals(3, frames.size)
        assertEquals(400, frames[0].size)
    }
}
