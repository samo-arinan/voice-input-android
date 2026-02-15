package com.example.voiceinput

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AudioProcessor {

    fun normalizeRms(pcmData: ByteArray, targetRmsDb: Double = -18.0): ByteArray {
        if (pcmData.isEmpty()) return pcmData

        val samples = ShortArray(pcmData.size / 2)
        ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)

        // Calculate current RMS
        val sumSquares = samples.sumOf { it.toDouble() * it.toDouble() }
        val currentRms = kotlin.math.sqrt(sumSquares / samples.size)
        if (currentRms < 1.0) return pcmData // silence

        // Target RMS in linear scale (relative to 16-bit max)
        val targetRms = 32767.0 * Math.pow(10.0, targetRmsDb / 20.0)
        val gain = targetRms / currentRms

        // Only amplify, never attenuate; cap gain to prevent extreme amplification
        val cappedGain = gain.coerceIn(1.0, 20.0)

        // Apply gain with clipping protection
        val output = ShortArray(samples.size) { i ->
            (samples[i] * cappedGain).toInt().coerceIn(-32768, 32767).toShort()
        }

        val result = ByteArray(output.size * 2)
        ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(output)
        return result
    }

    fun encodeWav(pcmData: ByteArray, sampleRate: Int, outputFile: File) {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcmData.size
        val totalSize = 36 + dataSize

        FileOutputStream(outputFile).use { out ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            // RIFF header
            header.put("RIFF".toByteArray())
            header.putInt(totalSize)
            header.put("WAVE".toByteArray())
            // fmt chunk
            header.put("fmt ".toByteArray())
            header.putInt(16) // chunk size
            header.putShort(1) // PCM format
            header.putShort(channels.toShort())
            header.putInt(sampleRate)
            header.putInt(byteRate)
            header.putShort(blockAlign.toShort())
            header.putShort(bitsPerSample.toShort())
            // data chunk
            header.put("data".toByteArray())
            header.putInt(dataSize)

            out.write(header.array())
            out.write(pcmData)
        }
    }
}
