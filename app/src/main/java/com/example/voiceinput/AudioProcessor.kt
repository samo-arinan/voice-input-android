package com.example.voiceinput

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AudioProcessor {

    private const val SILENCE_RMS_THRESHOLD = 300.0

    fun isSilent(pcmData: ByteArray): Boolean {
        if (pcmData.isEmpty()) return true
        val samples = ShortArray(pcmData.size / 2)
        ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        val sumSquares = samples.sumOf { it.toDouble() * it.toDouble() }
        val rms = kotlin.math.sqrt(sumSquares / samples.size)
        return rms < SILENCE_RMS_THRESHOLD
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
