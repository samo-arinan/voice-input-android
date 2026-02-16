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
}
