package com.example.voiceinput

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioProcessorTest {

    private lateinit var outputDir: File

    @Before
    fun setUp() {
        outputDir = File(System.getProperty("java.io.tmpdir"), "audio_processor_test")
        outputDir.mkdirs()
    }

    @Test
    fun `encodeWav creates valid WAV file from PCM data`() {
        // 1秒の無音 PCM (16kHz, 16bit, mono = 32000 bytes)
        val pcmData = ByteArray(32000)
        val outputFile = File(outputDir, "test.wav")

        AudioProcessor.encodeWav(pcmData, sampleRate = 16000, outputFile = outputFile)

        assertTrue(outputFile.exists())
        // WAV header = 44 bytes + PCM data
        assertEquals(32044L, outputFile.length())

        // Verify WAV header
        val header = outputFile.inputStream().use { it.readNBytes(44) }
        val riff = String(header, 0, 4)
        val wave = String(header, 8, 4)
        assertEquals("RIFF", riff)
        assertEquals("WAVE", wave)

        // Verify sample rate in header (offset 24, little-endian)
        val sampleRate = ByteBuffer.wrap(header, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(16000, sampleRate)
    }

    @Test
    fun `isSilent returns true for digital silence`() {
        val pcmData = ByteArray(32000) // all zeros
        assertTrue(AudioProcessor.isSilent(pcmData))
    }

    @Test
    fun `isSilent returns true for low ambient noise`() {
        // RMS ≈ 100 (ambient noise level)
        val samples = ShortArray(16000) { i ->
            (100 * kotlin.math.sin(2.0 * Math.PI * 60.0 * i / 16000)).toInt().toShort()
        }
        val pcmData = ByteArray(samples.size * 2)
        ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples)
        assertTrue(AudioProcessor.isSilent(pcmData))
    }

    @Test
    fun `isSilent returns false for speech level audio`() {
        // RMS ≈ 2000 (speech)
        val samples = ShortArray(16000) { i ->
            (3000 * kotlin.math.sin(2.0 * Math.PI * 440.0 * i / 16000)).toInt().toShort()
        }
        val pcmData = ByteArray(samples.size * 2)
        ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples)
        assertFalse(AudioProcessor.isSilent(pcmData))
    }

    @Test
    fun `isSilent returns true for empty data`() {
        assertTrue(AudioProcessor.isSilent(ByteArray(0)))
    }
}
