package com.example.voiceinput.poc

import org.junit.Assert.*
import org.junit.Test

class RmsGraphBufferTest {

    @Test
    fun `new buffer is empty`() {
        val buffer = RmsGraphBuffer()
        assertEquals(0, buffer.size())
        assertTrue(buffer.entries().isEmpty())
    }

    @Test
    fun `addRms stores entry`() {
        val buffer = RmsGraphBuffer()
        buffer.addRms(500.0, isPeak = false)
        assertEquals(1, buffer.size())
        val entry = buffer.entries().first()
        assertEquals(500.0, entry.rms, 0.01)
        assertFalse(entry.isPeak)
    }

    @Test
    fun `addRms stores peak entry`() {
        val buffer = RmsGraphBuffer()
        buffer.addRms(1000.0, isPeak = true)
        val entry = buffer.entries().first()
        assertTrue(entry.isPeak)
    }

    @Test
    fun `addRms enforces max entries limit`() {
        val buffer = RmsGraphBuffer(maxEntries = 5)
        repeat(10) { buffer.addRms(it.toDouble(), isPeak = false) }
        assertEquals(5, buffer.size())
        // Oldest entries should be removed
        val entries = buffer.entries()
        assertEquals(5.0, entries.first().rms, 0.01)
        assertEquals(9.0, entries.last().rms, 0.01)
    }

    @Test
    fun `maxRms tracks the highest seen value`() {
        val buffer = RmsGraphBuffer()
        // Values below default (1000) don't change maxRms
        buffer.addRms(100.0, isPeak = false)
        assertEquals(1000.0, buffer.maxRms(), 0.01)
        // Value above default updates maxRms
        buffer.addRms(2000.0, isPeak = false)
        assertEquals(2000.0, buffer.maxRms(), 0.01)
        // Lower value doesn't decrease maxRms
        buffer.addRms(500.0, isPeak = false)
        assertEquals(2000.0, buffer.maxRms(), 0.01)
        // Even higher value updates maxRms
        buffer.addRms(3000.0, isPeak = false)
        assertEquals(3000.0, buffer.maxRms(), 0.01)
    }

    @Test
    fun `maxRms defaults to 1000`() {
        val buffer = RmsGraphBuffer()
        assertEquals(1000.0, buffer.maxRms(), 0.01)
    }

    @Test
    fun `maxRms does not decrease when old entries are removed`() {
        val buffer = RmsGraphBuffer(maxEntries = 3)
        buffer.addRms(5000.0, isPeak = false)
        buffer.addRms(100.0, isPeak = false)
        buffer.addRms(100.0, isPeak = false)
        buffer.addRms(100.0, isPeak = false) // pushes 5000 out
        assertEquals(5000.0, buffer.maxRms(), 0.01) // still tracks peak
    }

    @Test
    fun `entries returns snapshot in insertion order`() {
        val buffer = RmsGraphBuffer()
        buffer.addRms(10.0, isPeak = false)
        buffer.addRms(20.0, isPeak = true)
        buffer.addRms(30.0, isPeak = false)
        val entries = buffer.entries()
        assertEquals(3, entries.size)
        assertEquals(10.0, entries[0].rms, 0.01)
        assertEquals(20.0, entries[1].rms, 0.01)
        assertEquals(30.0, entries[2].rms, 0.01)
        assertTrue(entries[1].isPeak)
    }

    @Test
    fun `entry has timestamp`() {
        val before = System.currentTimeMillis()
        val buffer = RmsGraphBuffer()
        buffer.addRms(100.0, isPeak = false)
        val after = System.currentTimeMillis()
        val entry = buffer.entries().first()
        assertTrue(entry.timestamp in before..after)
    }
}
