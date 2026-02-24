package com.example.voiceinput.poc

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RmsCalculatorTest {

    @Test
    fun `calculateRms returns zero for silence`() {
        val calc = RmsCalculator()
        val pcm = ByteArray(320) // 10ms at 16kHz, 16bit
        val rms = calc.calculateRms(pcm)
        assertEquals(0.0, rms, 0.01)
    }

    @Test
    fun `calculateRms returns correct value for known signal`() {
        val calc = RmsCalculator()
        // All samples = 1000 -> RMS = 1000
        val samples = ShortArray(160) { 1000 }
        val pcm = shortsToBytes(samples)
        val rms = calc.calculateRms(pcm)
        assertEquals(1000.0, rms, 1.0)
    }

    @Test
    fun `calculateRms returns correct value for sine wave`() {
        val calc = RmsCalculator()
        val amplitude = 10000
        val samples = ShortArray(160) { i ->
            (amplitude * kotlin.math.sin(2.0 * Math.PI * i / 160)).toInt().toShort()
        }
        val pcm = shortsToBytes(samples)
        val rms = calc.calculateRms(pcm)
        // RMS of sine wave = amplitude / sqrt(2) ~= 7071
        assertEquals(amplitude / kotlin.math.sqrt(2.0), rms, 200.0)
    }

    @Test
    fun `detectPeak returns true when rms exceeds threshold times average`() {
        val calc = RmsCalculator()
        // Feed quiet history
        repeat(10) { calc.addRms(100.0) }
        // Spike
        assertTrue(calc.detectPeak(500.0))
    }

    @Test
    fun `detectPeak returns false for normal values`() {
        val calc = RmsCalculator()
        repeat(10) { calc.addRms(100.0) }
        assertFalse(calc.detectPeak(120.0))
    }

    @Test
    fun `detectPeak returns false when no history`() {
        val calc = RmsCalculator()
        assertFalse(calc.detectPeak(500.0))
    }

    @Test
    fun `addRms maintains sliding window`() {
        val calc = RmsCalculator(historySize = 5)
        repeat(10) { calc.addRms(100.0) }
        // Should only keep last 5
        assertEquals(5, calc.historySize())
    }

    private fun shortsToBytes(samples: ShortArray): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples)
        return bytes
    }
}
