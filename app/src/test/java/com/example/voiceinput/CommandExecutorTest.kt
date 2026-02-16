package com.example.voiceinput

import org.junit.Assert.*
import org.junit.Test

class CommandExecutorTest {

    @Test
    fun `parseActions splits text and newlines`() {
        val actions = CommandExecutor.parseActions("hello\nworld\n")
        assertEquals(
            listOf(
                CommandExecutor.Action.Text("hello"),
                CommandExecutor.Action.Enter,
                CommandExecutor.Action.Text("world"),
                CommandExecutor.Action.Enter
            ),
            actions
        )
    }

    @Test
    fun `parseActions handles text without newlines`() {
        val actions = CommandExecutor.parseActions("hello")
        assertEquals(listOf(CommandExecutor.Action.Text("hello")), actions)
    }

    @Test
    fun `parseActions handles only newline`() {
        val actions = CommandExecutor.parseActions("\n")
        assertEquals(listOf(CommandExecutor.Action.Enter), actions)
    }

    @Test
    fun `parseActions handles empty text`() {
        val actions = CommandExecutor.parseActions("")
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `parseActions handles multiple consecutive newlines`() {
        val actions = CommandExecutor.parseActions("\n\n")
        assertEquals(listOf(CommandExecutor.Action.Enter, CommandExecutor.Action.Enter), actions)
    }
}
