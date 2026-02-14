package com.example.voiceinput

import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class VoiceInputProcessorTest {

    private lateinit var audioRecorder: AudioRecorder
    private lateinit var whisperClient: WhisperClient
    private lateinit var gptConverter: GptConverter
    private lateinit var processor: VoiceInputProcessor

    @Before
    fun setUp() {
        audioRecorder = mockk(relaxed = true)
        whisperClient = mockk()
        gptConverter = mockk()
        processor = VoiceInputProcessor(audioRecorder, whisperClient, gptConverter)
    }

    @Test
    fun `startRecording delegates to audioRecorder`() {
        every { audioRecorder.start() } returns true
        val result = processor.startRecording()
        assertTrue(result)
        verify { audioRecorder.start() }
    }

    @Test
    fun `startRecording returns false when recorder fails`() {
        every { audioRecorder.start() } returns false
        val result = processor.startRecording()
        assertFalse(result)
    }

    @Test
    fun `stopAndProcess returns chunks on success`() = runTest {
        val audioFile = mockk<File>()
        val chunks = listOf(
            ConversionChunk("ギット", "git"),
            ConversionChunk("ステータス", "status")
        )
        every { audioRecorder.stop() } returns audioFile
        every { whisperClient.transcribe(audioFile) } returns "ギットステータス"
        every { gptConverter.convertToChunks("ギットステータス") } returns chunks
        every { audioFile.delete() } returns true

        val result = processor.stopAndProcess()

        assertEquals(2, result!!.size)
        assertEquals("git", result[0].converted)
        assertEquals("status", result[1].converted)
        verify { audioFile.delete() }
    }

    @Test
    fun `stopAndProcess returns null when recorder returns null`() = runTest {
        every { audioRecorder.stop() } returns null

        val result = processor.stopAndProcess()

        assertNull(result)
    }

    @Test
    fun `stopAndProcess returns null when transcription fails`() = runTest {
        val audioFile = mockk<File>()
        every { audioRecorder.stop() } returns audioFile
        every { whisperClient.transcribe(audioFile) } returns null
        every { audioFile.delete() } returns true

        val result = processor.stopAndProcess()

        assertNull(result)
        verify { audioFile.delete() }
    }

    @Test
    fun `isRecording delegates to audioRecorder`() {
        every { audioRecorder.isRecording } returns true
        assertTrue(processor.isRecording)
        every { audioRecorder.isRecording } returns false
        assertFalse(processor.isRecording)
    }
}
