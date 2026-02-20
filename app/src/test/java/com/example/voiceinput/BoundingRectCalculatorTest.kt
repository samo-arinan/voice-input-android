package com.example.voiceinput

import org.junit.Assert.*
import org.junit.Test

class BoundingRectCalculatorTest {

    @Test
    fun `single point returns 1x1 rect`() {
        val points = listOf(Pair(100f, 200f))
        val rect = BoundingRectCalculator.calculate(points)
        assertEquals(100, rect.left)
        assertEquals(200, rect.top)
        assertEquals(101, rect.right)
        assertEquals(201, rect.bottom)
    }

    @Test
    fun `multiple points returns bounding rect`() {
        val points = listOf(
            Pair(50f, 100f),
            Pair(200f, 150f),
            Pair(100f, 300f)
        )
        val rect = BoundingRectCalculator.calculate(points)
        assertEquals(50, rect.left)
        assertEquals(100, rect.top)
        assertEquals(200, rect.right)
        assertEquals(300, rect.bottom)
    }

    @Test
    fun `empty points returns zero rect`() {
        val rect = BoundingRectCalculator.calculate(emptyList())
        assertEquals(0, rect.left)
        assertEquals(0, rect.top)
        assertEquals(0, rect.right)
        assertEquals(0, rect.bottom)
    }

    @Test
    fun `negative coordinates are calculated correctly`() {
        val points = listOf(Pair(-10f, -20f))
        val rect = BoundingRectCalculator.calculate(points)
        assertEquals(-10, rect.left)
        assertEquals(-20, rect.top)
        assertEquals(-9, rect.right)
        assertEquals(-19, rect.bottom)
    }

    @Test
    fun `clamps to bitmap bounds`() {
        val points = listOf(Pair(-10f, -20f), Pair(2000f, 3000f))
        val rect = BoundingRectCalculator.calculateClamped(points, 1080, 2340)
        assertEquals(0, rect.left)
        assertEquals(0, rect.top)
        assertEquals(1080, rect.right)
        assertEquals(2340, rect.bottom)
    }
}
