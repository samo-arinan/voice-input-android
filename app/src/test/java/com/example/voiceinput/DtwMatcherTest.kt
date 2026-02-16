package com.example.voiceinput

import org.junit.Assert.*
import org.junit.Test

class DtwMatcherTest {

    @Test
    fun `dtw distance of identical sequences is zero`() {
        val seq = arrayOf(floatArrayOf(1f, 2f, 3f), floatArrayOf(4f, 5f, 6f))
        val dist = DtwMatcher.dtwDistance(seq, seq)
        assertEquals(0.0f, dist, 0.001f)
    }

    @Test
    fun `dtw distance of different sequences is positive`() {
        val a = arrayOf(floatArrayOf(0f, 0f), floatArrayOf(0f, 0f))
        val b = arrayOf(floatArrayOf(1f, 1f), floatArrayOf(1f, 1f))
        val dist = DtwMatcher.dtwDistance(a, b)
        assertTrue(dist > 0)
    }

    @Test
    fun `dtw handles time-stretched sequences`() {
        val short = arrayOf(floatArrayOf(1f), floatArrayOf(2f), floatArrayOf(3f))
        val long = arrayOf(
            floatArrayOf(1f), floatArrayOf(1f),
            floatArrayOf(2f), floatArrayOf(2f),
            floatArrayOf(3f), floatArrayOf(3f)
        )
        val distStretched = DtwMatcher.dtwDistance(short, long)
        val different = arrayOf(floatArrayOf(9f), floatArrayOf(8f), floatArrayOf(7f))
        val distDifferent = DtwMatcher.dtwDistance(short, different)
        assertTrue("Stretched should be closer than different",
            distStretched < distDifferent)
    }

    @Test
    fun `dtw is symmetric`() {
        val a = arrayOf(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f))
        val b = arrayOf(floatArrayOf(0f, 1f), floatArrayOf(1f, 0f), floatArrayOf(0f, 0f))
        val dAB = DtwMatcher.dtwDistance(a, b)
        val dBA = DtwMatcher.dtwDistance(b, a)
        assertEquals(dAB, dBA, 0.001f)
    }
}
