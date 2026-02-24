package com.example.voiceinput

import org.junit.Assert.*
import org.junit.Test

class UndoManagerTest {

    @Test
    fun `initial state has canUndo false and lastCommittedText null`() {
        val manager = UndoManager()
        assertFalse(manager.canUndo)
        assertNull(manager.lastCommittedText)
        assertEquals(0, manager.lastCommittedLength)
    }

    @Test
    fun `recordCommit enables undo`() {
        val manager = UndoManager()
        manager.recordCommit("hello", 5)
        assertTrue(manager.canUndo)
        assertEquals("hello", manager.lastCommittedText)
        assertEquals(5, manager.lastCommittedLength)
    }

    @Test
    fun `undo returns committed length and clears state`() {
        val manager = UndoManager()
        manager.recordCommit("hello", 5)
        val length = manager.undo()
        assertEquals(5, length)
        assertFalse(manager.canUndo)
        assertNull(manager.lastCommittedText)
        assertEquals(0, manager.lastCommittedLength)
    }

    @Test
    fun `undo when nothing to undo returns 0`() {
        val manager = UndoManager()
        val length = manager.undo()
        assertEquals(0, length)
    }

    @Test
    fun `clear resets state`() {
        val manager = UndoManager()
        manager.recordCommit("hello", 5)
        manager.clear()
        assertFalse(manager.canUndo)
        assertNull(manager.lastCommittedText)
        assertEquals(0, manager.lastCommittedLength)
    }

    @Test
    fun `new commit replaces previous`() {
        val manager = UndoManager()
        manager.recordCommit("hello", 5)
        manager.recordCommit("world!", 6)
        assertTrue(manager.canUndo)
        assertEquals("world!", manager.lastCommittedText)
        assertEquals(6, manager.lastCommittedLength)
    }
}
