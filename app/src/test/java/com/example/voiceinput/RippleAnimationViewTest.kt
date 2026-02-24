package com.example.voiceinput

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RippleAnimationViewTest {

    @Test
    fun calculateRippleRadius_at_zero_amplitude() {
        val radius = RippleAnimationView.calculateRippleRadius(0f, 24f, 20f)
        assertEquals(24f, radius, 0.1f)
    }

    @Test
    fun calculateRippleRadius_at_max_amplitude() {
        val radius = RippleAnimationView.calculateRippleRadius(1f, 24f, 20f)
        assertEquals(44f, radius, 0.1f)
    }

    @Test
    fun calculateRippleRadius_at_half_amplitude() {
        val radius = RippleAnimationView.calculateRippleRadius(0.5f, 24f, 20f)
        assertEquals(34f, radius, 0.1f)
    }

    @Test
    fun calculateRippleAlpha_decreases_with_index() {
        val a0 = RippleAnimationView.calculateRippleAlpha(0, 3, 0.4f)
        val a1 = RippleAnimationView.calculateRippleAlpha(1, 3, 0.4f)
        val a2 = RippleAnimationView.calculateRippleAlpha(2, 3, 0.4f)
        assertTrue(a0 > a1)
        assertTrue(a1 > a2)
        assertTrue(a2 > 0f)
    }

    @Test
    fun calculateRippleRadius_clamps_amplitude_above_1() {
        val radius = RippleAnimationView.calculateRippleRadius(2f, 24f, 20f)
        assertEquals(44f, radius, 0.1f) // same as 1.0
    }

    @Test
    fun calculateRippleRadius_clamps_amplitude_below_0() {
        val radius = RippleAnimationView.calculateRippleRadius(-0.5f, 24f, 20f)
        assertEquals(24f, radius, 0.1f) // same as 0.0
    }
}
