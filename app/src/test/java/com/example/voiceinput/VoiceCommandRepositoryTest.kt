package com.example.voiceinput

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class VoiceCommandRepositoryTest {

    private lateinit var storageFile: File
    private lateinit var samplesDir: File
    private lateinit var repo: VoiceCommandRepository

    @Before
    fun setUp() {
        storageFile = File.createTempFile("voice_commands_test", ".json")
        samplesDir = File(storageFile.parentFile, "test_samples_${System.nanoTime()}")
        samplesDir.mkdirs()
        repo = VoiceCommandRepository(storageFile, samplesDir)
    }

    @After
    fun tearDown() {
        storageFile.delete()
        samplesDir.deleteRecursively()
    }

    @Test
    fun `addCommand creates a new command`() {
        val cmd = repo.addCommand("exit", "/exit\n")
        assertEquals("exit", cmd.label)
        assertEquals("/exit\n", cmd.text)
        assertEquals(0, cmd.sampleCount)
    }

    @Test
    fun `getCommands returns all commands`() {
        repo.addCommand("exit", "/exit\n")
        repo.addCommand("ls", "ls\n")
        val commands = repo.getCommands()
        assertEquals(2, commands.size)
    }

    @Test
    fun `deleteCommand removes command and sample files`() {
        val cmd = repo.addCommand("exit", "/exit\n")
        File(samplesDir, "${cmd.id}_0.wav").writeText("fake")
        repo.deleteCommand(cmd.id)
        assertTrue(repo.getCommands().isEmpty())
        assertFalse(File(samplesDir, "${cmd.id}_0.wav").exists())
    }

    @Test
    fun `updateSampleCount updates the count`() {
        val cmd = repo.addCommand("exit", "/exit\n")
        repo.updateSampleCount(cmd.id, 3)
        assertEquals(3, repo.getCommands().first().sampleCount)
    }

    @Test
    fun `data persists across instances`() {
        repo.addCommand("exit", "/exit\n")
        val repo2 = VoiceCommandRepository(storageFile, samplesDir)
        assertEquals(1, repo2.getCommands().size)
    }

    @Test
    fun `handles empty file gracefully`() {
        storageFile.delete()
        val repo2 = VoiceCommandRepository(storageFile, samplesDir)
        assertTrue(repo2.getCommands().isEmpty())
    }

    @Test
    fun `getSampleFile returns correct path`() {
        val file = repo.getSampleFile("exit", 2)
        assertEquals("exit_2.wav", file.name)
        assertEquals(samplesDir, file.parentFile)
    }

    @Test
    fun `getMfccCacheFile returns correct path`() {
        val file = repo.getMfccCacheFile("exit", 1)
        assertEquals("exit_1.mfcc", file.name)
        assertEquals(samplesDir, file.parentFile)
    }

    @Test
    fun `saveMfccCache and loadMfccCache roundtrip`() {
        val mfcc = arrayOf(floatArrayOf(1.5f, 2.5f), floatArrayOf(3.5f, 4.5f))
        repo.saveMfccCache("exit", 0, mfcc)
        val loaded = repo.loadMfccCache("exit", 0)
        assertNotNull(loaded)
        assertEquals(2, loaded!!.size)
        assertEquals(1.5f, loaded[0][0], 0.001f)
        assertEquals(4.5f, loaded[1][1], 0.001f)
    }

    @Test
    fun `loadMfccCache returns null when no cache`() {
        val loaded = repo.loadMfccCache("nonexistent", 0)
        assertNull(loaded)
    }

    @Test
    fun `loadAllMfccs loads cached MFCCs for all samples`() {
        val cmd = repo.addCommand("exit", "/exit\n")
        repo.updateSampleCount(cmd.id, 2)
        val mfcc0 = arrayOf(floatArrayOf(1f, 2f))
        val mfcc1 = arrayOf(floatArrayOf(3f, 4f))
        repo.saveMfccCache(cmd.id, 0, mfcc0)
        repo.saveMfccCache(cmd.id, 1, mfcc1)
        val all = repo.loadAllMfccs()
        assertEquals(1, all.size)
        assertEquals(2, all[cmd.id]!!.size)
    }

    @Test
    fun `can record up to 5 samples`() {
        val cmd = repo.addCommand("exit", "/exit\n")
        for (i in 0 until 5) {
            repo.getSampleFile(cmd.id, i).writeText("fake$i")
            repo.updateSampleCount(cmd.id, i + 1)
        }
        assertEquals(5, repo.getCommands().first().sampleCount)
        for (i in 0 until 5) {
            assertTrue(repo.getSampleFile(cmd.id, i).exists())
        }
    }

    @Test
    fun `deleteCommand also removes mfcc cache files`() {
        val cmd = repo.addCommand("exit", "/exit\n")
        repo.saveMfccCache(cmd.id, 0, arrayOf(floatArrayOf(1f)))
        repo.deleteCommand(cmd.id)
        assertNull(repo.loadMfccCache(cmd.id, 0))
    }
}
