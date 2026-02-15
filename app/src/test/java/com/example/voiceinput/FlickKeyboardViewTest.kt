package com.example.voiceinput

import org.junit.Assert.*
import org.junit.Test

class FlickKeyboardViewTest {

    @Test
    fun `resolveFlick center tap on A-row returns あ`() {
        val result = FlickResolver.resolve("あ", FlickDirection.CENTER)
        assertEquals("あ", result)
    }

    @Test
    fun `resolveFlick up on A-row returns い`() {
        val result = FlickResolver.resolve("あ", FlickDirection.UP)
        assertEquals("い", result)
    }

    @Test
    fun `resolveFlick left on A-row returns う`() {
        val result = FlickResolver.resolve("あ", FlickDirection.LEFT)
        assertEquals("う", result)
    }

    @Test
    fun `resolveFlick down on A-row returns え`() {
        val result = FlickResolver.resolve("あ", FlickDirection.DOWN)
        assertEquals("え", result)
    }

    @Test
    fun `resolveFlick right on A-row returns お`() {
        val result = FlickResolver.resolve("あ", FlickDirection.RIGHT)
        assertEquals("お", result)
    }

    @Test
    fun `resolveFlick center on KA-row returns か`() {
        val result = FlickResolver.resolve("か", FlickDirection.CENTER)
        assertEquals("か", result)
    }

    @Test
    fun `resolveFlick up on KA-row returns き`() {
        val result = FlickResolver.resolve("か", FlickDirection.UP)
        assertEquals("き", result)
    }

    @Test
    fun `resolveFlick covers all rows`() {
        val rows = listOf("あ", "か", "さ", "た", "な", "は", "ま", "や", "ら", "わ")
        for (row in rows) {
            for (dir in FlickDirection.values()) {
                val result = FlickResolver.resolve(row, dir)
                assertNotNull("Row $row direction $dir should return a value", result)
                assertTrue("Result should not be empty", result!!.isNotEmpty())
            }
        }
    }

    @Test
    fun `resolveFlick YA-row has only 3 chars`() {
        assertEquals("や", FlickResolver.resolve("や", FlickDirection.CENTER))
        assertEquals("ゆ", FlickResolver.resolve("や", FlickDirection.LEFT))
        assertEquals("よ", FlickResolver.resolve("や", FlickDirection.RIGHT))
        assertEquals("や", FlickResolver.resolve("や", FlickDirection.UP))
        assertEquals("や", FlickResolver.resolve("や", FlickDirection.DOWN))
    }

    @Test
    fun `resolveFlick WA-row`() {
        assertEquals("わ", FlickResolver.resolve("わ", FlickDirection.CENTER))
        assertEquals("を", FlickResolver.resolve("わ", FlickDirection.LEFT))
        assertEquals("ん", FlickResolver.resolve("わ", FlickDirection.RIGHT))
    }

    @Test
    fun `detectFlickDirection with small movement returns CENTER`() {
        val dir = FlickResolver.detectDirection(0f, 0f, 5f, 3f)
        assertEquals(FlickDirection.CENTER, dir)
    }

    @Test
    fun `detectFlickDirection upward returns UP`() {
        val dir = FlickResolver.detectDirection(100f, 100f, 100f, 30f)
        assertEquals(FlickDirection.UP, dir)
    }

    @Test
    fun `detectFlickDirection downward returns DOWN`() {
        val dir = FlickResolver.detectDirection(100f, 100f, 100f, 170f)
        assertEquals(FlickDirection.DOWN, dir)
    }

    @Test
    fun `detectFlickDirection leftward returns LEFT`() {
        val dir = FlickResolver.detectDirection(100f, 100f, 30f, 100f)
        assertEquals(FlickDirection.LEFT, dir)
    }

    @Test
    fun `detectFlickDirection rightward returns RIGHT`() {
        val dir = FlickResolver.detectDirection(100f, 100f, 170f, 100f)
        assertEquals(FlickDirection.RIGHT, dir)
    }
}
