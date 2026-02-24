package com.example.voiceinput.poc

/**
 * Ring buffer for PCM sample storage. Extracted from WaveformView
 * for testability without Android framework dependencies.
 */
class WaveformBuffer(val maxSamples: Int) {

    private val samples = ShortArray(maxSamples)
    private var writeIndex = 0
    var sampleCount = 0
        private set

    val startIndex: Int
        get() = if (sampleCount >= maxSamples) writeIndex else 0

    fun pushSamples(newSamples: ShortArray) {
        for (s in newSamples) {
            samples[writeIndex] = s
            writeIndex = (writeIndex + 1) % maxSamples
            if (sampleCount < maxSamples) sampleCount++
        }
    }

    /**
     * Get sample at logical index (0 = oldest visible sample).
     */
    fun getSample(logicalIndex: Int): Short {
        val physicalIndex = (startIndex + logicalIndex) % maxSamples
        return samples[physicalIndex]
    }

    /**
     * Get min and max sample values over the logical index range [fromIndex, toIndex] inclusive.
     */
    fun getMinMax(fromIndex: Int, toIndex: Int): Pair<Short, Short> {
        var min = getSample(fromIndex)
        var max = min
        for (i in fromIndex + 1..toIndex) {
            val s = getSample(i)
            if (s < min) min = s
            if (s > max) max = s
        }
        return Pair(min, max)
    }
}
