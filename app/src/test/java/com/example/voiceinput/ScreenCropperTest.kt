package com.example.voiceinput

import org.junit.Assert.*
import org.junit.Test

class ScreenCropperTest {

    @Test
    fun `sanitize clamps rect to bitmap bounds`() {
        val rect = BoundingRect(-10, -20, 2000, 3000)
        val result = ScreenCropper.sanitizeRect(rect, 1080, 2340)

        assertNotNull(result)
        assertEquals(0, result!!.left)
        assertEquals(0, result.top)
        assertEquals(1080, result.right)
        assertEquals(2340, result.bottom)
    }

    @Test
    fun `sanitize returns null for zero-area rect`() {
        val rect = BoundingRect(100, 100, 100, 100)
        val result = ScreenCropper.sanitizeRect(rect, 1080, 2340)

        assertNull(result)
    }

    @Test
    fun `sanitize returns null for inverted rect`() {
        val rect = BoundingRect(200, 200, 100, 100)
        val result = ScreenCropper.sanitizeRect(rect, 1080, 2340)

        assertNull(result)
    }

    @Test
    fun `sanitize preserves valid rect`() {
        val rect = BoundingRect(100, 200, 500, 600)
        val result = ScreenCropper.sanitizeRect(rect, 1080, 2340)

        assertNotNull(result)
        assertEquals(100, result!!.left)
        assertEquals(200, result.top)
        assertEquals(500, result.right)
        assertEquals(600, result.bottom)
    }
}
