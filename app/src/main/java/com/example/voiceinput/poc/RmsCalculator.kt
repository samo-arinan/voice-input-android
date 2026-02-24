package com.example.voiceinput.poc

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.LinkedList

class RmsCalculator(
    private val maxHistorySize: Int = 50,
    private val peakThresholdMultiplier: Double = 3.0
) {
    private val rmsHistory = LinkedList<Double>()

    constructor(historySize: Int) : this(maxHistorySize = historySize)

    fun calculateRms(pcmData: ByteArray): Double {
        if (pcmData.size < 2) return 0.0
        val samples = ShortArray(pcmData.size / 2)
        ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        val sumSquares = samples.sumOf { it.toDouble() * it.toDouble() }
        return kotlin.math.sqrt(sumSquares / samples.size)
    }

    fun addRms(rms: Double) {
        rmsHistory.addLast(rms)
        if (rmsHistory.size > maxHistorySize) {
            rmsHistory.removeFirst()
        }
    }

    fun detectPeak(currentRms: Double): Boolean {
        if (rmsHistory.size < 3) return false
        val average = rmsHistory.average()
        if (average < 1.0) return false
        return currentRms > average * peakThresholdMultiplier
    }

    fun historySize(): Int = rmsHistory.size
}
