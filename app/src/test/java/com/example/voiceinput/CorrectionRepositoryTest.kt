package com.example.voiceinput

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class CorrectionRepositoryTest {

    private lateinit var storageFile: File
    private lateinit var repo: CorrectionRepository

    @Before
    fun setUp() {
        storageFile = File.createTempFile("corrections_test", ".json")
        repo = CorrectionRepository(storageFile)
    }

    @After
    fun tearDown() {
        storageFile.delete()
    }

    @Test
    fun `save stores a correction entry`() {
        repo.save("おはよう御座います", "おはようございます")
        val corrections = repo.getTopCorrections(10)
        assertEquals(1, corrections.size)
        assertEquals("おはよう御座います", corrections[0].original)
        assertEquals("おはようございます", corrections[0].corrected)
    }

    @Test
    fun `save increments frequency for duplicate pair`() {
        repo.save("御座います", "ございます")
        repo.save("御座います", "ございます")
        repo.save("御座います", "ございます")
        val corrections = repo.getTopCorrections(10)
        assertEquals(1, corrections.size)
        assertEquals(3, corrections[0].frequency)
    }

    @Test
    fun `getTopCorrections returns sorted by frequency descending`() {
        repo.save("A", "a")
        repo.save("B", "b")
        repo.save("B", "b")
        repo.save("B", "b")
        repo.save("C", "c")
        repo.save("C", "c")
        val corrections = repo.getTopCorrections(10)
        assertEquals("B", corrections[0].original)
        assertEquals("C", corrections[1].original)
        assertEquals("A", corrections[2].original)
    }

    @Test
    fun `getTopCorrections respects limit`() {
        for (i in 1..10) {
            repo.save("orig$i", "corr$i")
        }
        val corrections = repo.getTopCorrections(3)
        assertEquals(3, corrections.size)
    }

    @Test
    fun `cleanup removes lowest frequency entries when exceeding max`() {
        val smallRepo = CorrectionRepository(storageFile, maxEntries = 3)
        smallRepo.save("A", "a")
        smallRepo.save("B", "b")
        smallRepo.save("B", "b")
        smallRepo.save("C", "c")
        smallRepo.save("D", "d")

        val corrections = smallRepo.getTopCorrections(10)
        assertTrue(corrections.size <= 3)
        assertTrue(corrections.any { it.original == "B" })
    }

    @Test
    fun `data persists across instances`() {
        repo.save("テスト", "test")
        val repo2 = CorrectionRepository(storageFile)
        val corrections = repo2.getTopCorrections(10)
        assertEquals(1, corrections.size)
        assertEquals("テスト", corrections[0].original)
    }

    @Test
    fun `handles empty or missing file gracefully`() {
        storageFile.delete()
        val repo2 = CorrectionRepository(storageFile)
        val corrections = repo2.getTopCorrections(10)
        assertTrue(corrections.isEmpty())
    }
}
