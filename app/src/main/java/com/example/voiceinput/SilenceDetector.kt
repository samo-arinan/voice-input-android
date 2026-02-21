// SilenceDetector.kt
package com.example.voiceinput

import kotlin.math.sqrt

class SilenceDetector(
    private val thresholdRms: Double = 200.0,
    private val silenceDurationMs: Long = 1500,
    private val sampleRate: Int = 16000
) {
    private var silentMs: Long = 0

    fun feed(buffer: ByteArray, bytesRead: Int): Boolean {
        val rms = calculateRms(buffer, bytesRead)
        val durationMs = (bytesRead / 2) * 1000L / sampleRate

        if (rms < thresholdRms) {
            silentMs += durationMs
        } else {
            silentMs = 0
        }

        return silentMs >= silenceDurationMs
    }

    fun reset() {
        silentMs = 0
    }

    private fun calculateRms(buffer: ByteArray, bytesRead: Int): Double {
        var sumSquares = 0.0
        val sampleCount = bytesRead / 2

        if (sampleCount == 0) return 0.0

        for (i in 0 until bytesRead step 2) {
            if (i + 1 >= bytesRead) break
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            val signed = if (sample > 32767) sample - 65536 else sample
            sumSquares += signed.toDouble() * signed.toDouble()
        }

        return sqrt(sumSquares / sampleCount)
    }
}
