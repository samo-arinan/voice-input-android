package com.example.voiceinput

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class AudioRecorderTest {

    private lateinit var outputDir: File

    @Before
    fun setUp() {
        outputDir = File(System.getProperty("java.io.tmpdir"), "voice_input_test")
        outputDir.mkdirs()
    }

    @Test
    fun `getOutputFile returns file in specified directory with m4a extension`() {
        val recorder = AudioRecorder(outputDir)
        val file = recorder.getOutputFile()
        assertEquals(outputDir, file.parentFile)
        assertTrue(file.name.endsWith(".m4a"))
    }

    @Test
    fun `isRecording returns false initially`() {
        val recorder = AudioRecorder(outputDir)
        assertFalse(recorder.isRecording)
    }
}
