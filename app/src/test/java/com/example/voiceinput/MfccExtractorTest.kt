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

    @Test
    fun `hammingWindow applies correct windowing`() {
        val frame = FloatArray(4) { 1.0f }
        val windowed = MfccExtractor.applyHammingWindow(frame)
        assertEquals(0.08f, windowed[0], 0.01f)
        assertEquals(0.77f, windowed[1], 0.01f)
    }

    @Test
    fun `fft produces power spectrum of correct size`() {
        val frame = FloatArray(512) { 0f }
        frame[0] = 1.0f
        val power = MfccExtractor.powerSpectrum(frame, 512)
        assertEquals(257, power.size)
        val expected = 1.0f / 512f
        assertEquals(expected, power[0], 0.001f)
        assertEquals(expected, power[1], 0.001f)
    }

    @Test
    fun `extract produces correct dimensions`() {
        val samples = ShortArray(16000) { 0 }
        val wav = createWavBytes(samples)
        val mfcc = MfccExtractor.extract(wav)
        assertEquals(98, mfcc.size)
        assertEquals(13, mfcc[0].size)
    }

    @Test
    fun `extract of sine wave produces non-zero MFCCs`() {
        val samples = ShortArray(16000) { i ->
            (16000 * kotlin.math.sin(2.0 * Math.PI * 440.0 * i / 16000.0)).toInt().toShort()
        }
        val wav = createWavBytes(samples)
        val mfcc = MfccExtractor.extract(wav)
        val midFrame = mfcc[mfcc.size / 2]
        val energy = midFrame.map { it * it }.sum()
        assertTrue("MFCC energy should be non-trivial", energy > 1.0f)
    }
}
