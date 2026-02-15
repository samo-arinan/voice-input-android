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

    fun compress(
        pcmData: ByteArray,
        thresholdDb: Double = -30.0,
        ratio: Double = 2.5,
        attackMs: Double = 10.0,
        releaseMs: Double = 80.0,
        sampleRate: Int = 16000
    ): ByteArray {
        if (pcmData.isEmpty()) return pcmData

        val samples = ShortArray(pcmData.size / 2)
        ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)

        val thresholdLinear = 32767.0 * Math.pow(10.0, thresholdDb / 20.0)
        val attackCoeff = kotlin.math.exp(-1.0 / (attackMs * sampleRate / 1000.0))
        val releaseCoeff = kotlin.math.exp(-1.0 / (releaseMs * sampleRate / 1000.0))

        var envelope = 0.0
        val output = ShortArray(samples.size)

        for (i in samples.indices) {
            val inputAbs = kotlin.math.abs(samples[i].toDouble())

            // Envelope follower
            envelope = if (inputAbs > envelope) {
                attackCoeff * envelope + (1.0 - attackCoeff) * inputAbs
            } else {
                releaseCoeff * envelope + (1.0 - releaseCoeff) * inputAbs
            }

            // Compute gain reduction
            val gain = if (envelope > thresholdLinear) {
                val overDb = 20.0 * kotlin.math.log10(envelope / thresholdLinear)
                val reducedDb = overDb * (1.0 - 1.0 / ratio)
                Math.pow(10.0, -reducedDb / 20.0)
            } else {
                1.0
            }

            output[i] = (samples[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
        }

        val result = ByteArray(output.size * 2)
        ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(output)
        return result
    }

    fun processForWhisper(pcmData: ByteArray, sampleRate: Int, outputFile: File) {
        val normalized = normalizeRms(pcmData)
        val compressed = compress(normalized, sampleRate = sampleRate)
        encodeWav(compressed, sampleRate, outputFile)
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
