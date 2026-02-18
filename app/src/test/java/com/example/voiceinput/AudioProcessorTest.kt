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

    @Test
    fun `compress reduces dynamic range`() {
        // ダイナミックレンジが広い信号: 前半小さい、後半大きい
        val samples = ShortArray(16000) { i ->
            val amplitude = if (i < 8000) 500 else 15000
            (amplitude * kotlin.math.sin(2.0 * Math.PI * 440.0 * i / 16000)).toInt().toShort()
        }
        val pcmData = ByteArray(samples.size * 2)
        ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples)

        val compressed = AudioProcessor.compress(pcmData)
        val compressedSamples = ShortArray(compressed.size / 2)
        ByteBuffer.wrap(compressed).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(compressedSamples)

        // 前半と後半のRMSの比率が、元より小さくなっていること（＝ダイナミックレンジ圧縮）
        val origQuietRms = rmsOfRange(samples, 2000, 6000) // 安定区間
        val origLoudRms = rmsOfRange(samples, 10000, 14000)
        val compQuietRms = rmsOfRange(compressedSamples, 2000, 6000)
        val compLoudRms = rmsOfRange(compressedSamples, 10000, 14000)

        val origRatio = origLoudRms / origQuietRms
        val compRatio = compLoudRms / compQuietRms

        assertTrue("Dynamic range should be reduced: origRatio=$origRatio, compRatio=$compRatio",
            compRatio < origRatio)
    }

    private fun rmsOfRange(samples: ShortArray, from: Int, to: Int): Double {
        val slice = samples.sliceArray(from until to)
        val sumSquares = slice.sumOf { it.toDouble() * it.toDouble() }
        return kotlin.math.sqrt(sumSquares / slice.size)
    }

    @Test
    fun `processForWhisper applies normalization and compression then outputs WAV`() {
        // 小声の信号
        val samples = ShortArray(16000) { i ->
            (300 * kotlin.math.sin(2.0 * Math.PI * 440.0 * i / 16000)).toInt().toShort()
        }
        val pcmData = ByteArray(samples.size * 2)
        ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples)

        val outputFile = File(outputDir, "processed.wav")
        AudioProcessor.processForWhisper(pcmData, sampleRate = 16000, outputFile = outputFile)

        assertTrue(outputFile.exists())
        // WAV header (44) + processed PCM data (32000)
        assertEquals(32044L, outputFile.length())

        // 処理済みPCMのRMSが元より大きいこと
        val wavData = outputFile.readBytes()
        val processedPcm = wavData.copyOfRange(44, wavData.size)
        val processedRms = calculateRms(processedPcm)
        val originalRms = calculateRms(pcmData)
        assertTrue("Processed should be louder: orig=$originalRms, proc=$processedRms",
            processedRms > originalRms * 2)
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

    private fun calculateRms(pcmData: ByteArray): Double {
        val samples = ShortArray(pcmData.size / 2)
        ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        val sumSquares = samples.sumOf { it.toDouble() * it.toDouble() }
        return kotlin.math.sqrt(sumSquares / samples.size)
    }
}
