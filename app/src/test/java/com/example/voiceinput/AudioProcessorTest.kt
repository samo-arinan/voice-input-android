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
    fun `normalizeRms amplifies quiet audio to target level`() {
        // 小声をシミュレート: 振幅 ±500 程度（16bit最大は ±32767）
        val samples = ShortArray(16000) { i ->
            (500 * kotlin.math.sin(2.0 * Math.PI * 440.0 * i / 16000)).toInt().toShort()
        }
        val pcmData = ByteArray(samples.size * 2)
        ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples)

        val originalRms = calculateRms(pcmData)

        val normalized = AudioProcessor.normalizeRms(pcmData, targetRmsDb = -18.0)
        val normalizedRms = calculateRms(normalized)

        // 正規化後のRMSが元より大きいこと
        assertTrue("RMS should increase: original=$originalRms, normalized=$normalizedRms",
            normalizedRms > originalRms * 2)
        // クリッピングしていないこと
        val normalizedSamples = ShortArray(normalized.size / 2)
        ByteBuffer.wrap(normalized).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(normalizedSamples)
        val maxSample = normalizedSamples.maxOf { kotlin.math.abs(it.toInt()) }
        assertTrue("Should not clip: max=$maxSample", maxSample <= 32767)
    }

    @Test
    fun `normalizeRms does not amplify already loud audio`() {
        // 大きな音: 振幅 ±20000
        val samples = ShortArray(16000) { i ->
            (20000 * kotlin.math.sin(2.0 * Math.PI * 440.0 * i / 16000)).toInt().toShort()
        }
        val pcmData = ByteArray(samples.size * 2)
        ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples)

        val originalRms = calculateRms(pcmData)
        val normalized = AudioProcessor.normalizeRms(pcmData, targetRmsDb = -18.0)
        val normalizedRms = calculateRms(normalized)

        // 既に十分大きいので変化は小さい（±50%以内）
        val ratio = normalizedRms / originalRms.toDouble()
        assertTrue("Should stay similar: ratio=$ratio", ratio in 0.5..1.5)
    }

    private fun calculateRms(pcmData: ByteArray): Double {
        val samples = ShortArray(pcmData.size / 2)
        ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        val sumSquares = samples.sumOf { it.toDouble() * it.toDouble() }
        return kotlin.math.sqrt(sumSquares / samples.size)
    }
}
