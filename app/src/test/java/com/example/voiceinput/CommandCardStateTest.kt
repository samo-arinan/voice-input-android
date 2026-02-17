package com.example.voiceinput

import org.junit.Assert.*
import org.junit.Test

class CommandCardStateTest {

    @Test
    fun `play button visible when sampleCount greater than zero`() {
        assertTrue(CommandLearningView.shouldShowPlayButton(sampleCount = 1))
        assertTrue(CommandLearningView.shouldShowPlayButton(sampleCount = 5))
    }

    @Test
    fun `play button hidden when sampleCount is zero`() {
        assertFalse(CommandLearningView.shouldShowPlayButton(sampleCount = 0))
    }

    @Test
    fun `latest sample index is sampleCount minus one`() {
        assertEquals(0, CommandLearningView.latestSampleIndex(sampleCount = 1))
        assertEquals(4, CommandLearningView.latestSampleIndex(sampleCount = 5))
    }

    @Test
    fun `latest sample index returns negative one when no samples`() {
        assertEquals(-1, CommandLearningView.latestSampleIndex(sampleCount = 0))
    }
}
