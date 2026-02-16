package com.example.voiceinput

import org.junit.Assert.*
import org.junit.Test

class CommandMatcherTest {

    private val sampleMfcc = arrayOf(floatArrayOf(1f, 2f), floatArrayOf(3f, 4f))
    private val differentMfcc = arrayOf(floatArrayOf(99f, 99f), floatArrayOf(99f, 99f))

    private fun cmd(id: String, threshold: Float = 10f) = VoiceCommand(
        id = id, label = id, text = "/$id\n", threshold = threshold
    )

    @Test
    fun `match returns command when distance below threshold`() {
        val commands = listOf(cmd("exit", threshold = 10f))
        val samples = mapOf("exit" to listOf(sampleMfcc))
        val matcher = CommandMatcher(commands, samples)
        val result = matcher.match(sampleMfcc)
        assertNotNull(result)
        assertEquals("exit", result!!.command.id)
        assertTrue(result.distance < 10f)
    }

    @Test
    fun `match returns null when no commands registered`() {
        val matcher = CommandMatcher(emptyList(), emptyMap())
        val result = matcher.match(sampleMfcc)
        assertNull(result)
    }

    @Test
    fun `match returns null when distance above threshold`() {
        val commands = listOf(cmd("exit", threshold = 0.001f))
        val samples = mapOf("exit" to listOf(differentMfcc))
        val matcher = CommandMatcher(commands, samples)
        val result = matcher.match(sampleMfcc)
        assertNull(result)
    }

    @Test
    fun `match picks closest command among multiple`() {
        val commands = listOf(cmd("exit"), cmd("hello"))
        val samples = mapOf(
            "exit" to listOf(differentMfcc),
            "hello" to listOf(sampleMfcc)
        )
        val matcher = CommandMatcher(commands, samples)
        val result = matcher.match(sampleMfcc)
        assertNotNull(result)
        assertEquals("hello", result!!.command.id)
    }

    @Test
    fun `match uses minimum distance across multiple samples`() {
        val commands = listOf(cmd("exit"))
        val samples = mapOf(
            "exit" to listOf(differentMfcc, sampleMfcc)
        )
        val matcher = CommandMatcher(commands, samples)
        val result = matcher.match(sampleMfcc)
        assertNotNull(result)
        assertTrue(result!!.distance < 1f)
    }

    @Test
    fun `match skips commands with no samples`() {
        val commands = listOf(cmd("exit"))
        val samples = mapOf<String, List<Array<FloatArray>>>()
        val matcher = CommandMatcher(commands, samples)
        val result = matcher.match(sampleMfcc)
        assertNull(result)
    }
}
