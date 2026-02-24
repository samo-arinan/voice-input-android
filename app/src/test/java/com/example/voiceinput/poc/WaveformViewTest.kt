package com.example.voiceinput.poc

import org.junit.Assert.*
import org.junit.Test

class WaveformViewTest {

    @Test
    fun `pushSamples stores samples in ring buffer`() {
        val buffer = WaveformBuffer(maxSamples = 10)
        buffer.pushSamples(shortArrayOf(100, 200, 300))
        assertEquals(3, buffer.sampleCount)
        assertEquals(100.toShort(), buffer.getSample(0))
        assertEquals(200.toShort(), buffer.getSample(1))
        assertEquals(300.toShort(), buffer.getSample(2))
    }

    @Test
    fun `pushSamples wraps around when exceeding maxSamples`() {
        val buffer = WaveformBuffer(maxSamples = 5)
        buffer.pushSamples(shortArrayOf(1, 2, 3, 4, 5))
        buffer.pushSamples(shortArrayOf(6, 7))
        // Ring buffer should have overwritten oldest: now contains 3,4,5,6,7
        assertEquals(5, buffer.sampleCount)
        // startIndex should be 2, so logical index 0 = physical index 2
        assertEquals(3.toShort(), buffer.getSample(0))
        assertEquals(7.toShort(), buffer.getSample(4))
    }

    @Test
    fun `sampleCount does not exceed maxSamples`() {
        val buffer = WaveformBuffer(maxSamples = 5)
        buffer.pushSamples(shortArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        assertEquals(5, buffer.sampleCount)
    }

    @Test
    fun `getSample returns correct value after wrap`() {
        val buffer = WaveformBuffer(maxSamples = 4)
        buffer.pushSamples(shortArrayOf(10, 20, 30, 40))
        buffer.pushSamples(shortArrayOf(50)) // overwrites 10
        // Logical order: 20, 30, 40, 50
        assertEquals(20.toShort(), buffer.getSample(0))
        assertEquals(50.toShort(), buffer.getSample(3))
    }

    @Test
    fun `empty buffer has zero sampleCount`() {
        val buffer = WaveformBuffer(maxSamples = 10)
        assertEquals(0, buffer.sampleCount)
    }

    @Test
    fun `getMinMax returns min and max over range`() {
        val buffer = WaveformBuffer(maxSamples = 10)
        buffer.pushSamples(shortArrayOf(100, -200, 300, -50, 250))
        val result: Pair<Short, Short> = buffer.getMinMax(1, 3) // indices 1,2,3 -> -200, 300, -50
        assertEquals((-200).toShort(), result.first)
        assertEquals(300.toShort(), result.second)
    }

    @Test
    fun `getMinMax works after wrap`() {
        val buffer = WaveformBuffer(maxSamples = 4)
        buffer.pushSamples(shortArrayOf(10, 20, 30, 40))
        buffer.pushSamples(shortArrayOf(50, 60)) // now: 30, 40, 50, 60
        val result: Pair<Short, Short> = buffer.getMinMax(0, 3) // all 4 values
        assertEquals(30.toShort(), result.first)
        assertEquals(60.toShort(), result.second)
    }
}
