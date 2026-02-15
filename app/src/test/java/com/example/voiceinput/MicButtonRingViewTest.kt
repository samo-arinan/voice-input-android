package com.example.voiceinput

import org.junit.Assert.*
import org.junit.Test

class MicButtonRingViewTest {

    @Test
    fun `colorForAmplitude returns red for 0`() {
        val color = MicButtonRingView.colorForAmplitude(0.0f)
        assertEquals(0xFFFF5252.toInt(), color)
    }

    @Test
    fun `colorForAmplitude returns red for low amplitude`() {
        val color = MicButtonRingView.colorForAmplitude(0.2f)
        assertEquals(0xFFFF5252.toInt(), color)
    }

    @Test
    fun `colorForAmplitude returns yellow for mid amplitude`() {
        val color = MicButtonRingView.colorForAmplitude(0.5f)
        // Halfway between red and yellow
        // Red #FF5252 -> Yellow #FFC107
        // At 0.5: ratio = (0.5 - 0.3) / (0.7 - 0.3) = 0.5
        // R: 255->255, G: 82->193, B: 82->7
        // G: 82 + (193-82)*0.5 = 137, B: 82 + (7-82)*0.5 = 44
        val r = color.shr(16).and(0xFF)
        val g = color.shr(8).and(0xFF)
        assertEquals(255, r)
        assertTrue("Green should be between 82 and 193, was $g", g in 100..170)
    }

    @Test
    fun `colorForAmplitude returns green for 1`() {
        val color = MicButtonRingView.colorForAmplitude(1.0f)
        assertEquals(0xFF4CAF50.toInt(), color)
    }

    @Test
    fun `colorForAmplitude clamps below 0`() {
        val color = MicButtonRingView.colorForAmplitude(-0.5f)
        assertEquals(0xFFFF5252.toInt(), color)
    }

    @Test
    fun `colorForAmplitude clamps above 1`() {
        val color = MicButtonRingView.colorForAmplitude(1.5f)
        assertEquals(0xFF4CAF50.toInt(), color)
    }

    @Test
    fun `colorForAmplitude returns yellow at 0_7 boundary`() {
        val color = MicButtonRingView.colorForAmplitude(0.7f)
        assertEquals(0xFFFFC107.toInt(), color)
    }
}
