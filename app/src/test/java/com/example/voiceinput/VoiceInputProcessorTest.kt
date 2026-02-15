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
    fun `stopAndProcess returns chunks with diff on success`() = runTest {
        val audioFile = mockk<File>()
        every { audioRecorder.stop() } returns audioFile
        every { whisperClient.transcribe(audioFile, any()) } returns "ギットステータスを確認して"
        every { gptConverter.convert("ギットステータスを確認して") } returns "git statusを確認して"
        every { audioFile.delete() } returns true

        val result = processor.stopAndProcess()

        assertNotNull(result)
        assertTrue(result!!.size >= 2)
        val unchanged = result.filter { !it.isDifferent }
        assertTrue(unchanged.any { it.raw.contains("を確認して") })
        verify { audioFile.delete() }
    }

    @Test
    fun `stopAndProcess returns single chunk when texts are identical`() = runTest {
        val audioFile = mockk<File>()
        every { audioRecorder.stop() } returns audioFile
        every { whisperClient.transcribe(audioFile, any()) } returns "こんにちは"
        every { gptConverter.convert("こんにちは") } returns "こんにちは"
        every { audioFile.delete() } returns true

        val result = processor.stopAndProcess()

        assertEquals(1, result!!.size)
        assertFalse(result[0].isDifferent)
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
        every { whisperClient.transcribe(audioFile, any()) } returns null
        every { audioFile.delete() } returns true

        val result = processor.stopAndProcess()

        assertNull(result)
        verify { audioFile.delete() }
    }

    @Test
    fun `stopAndTranscribeOnly returns raw text without GPT conversion`() = runTest {
        val audioFile = mockk<File>()
        every { audioRecorder.stop() } returns audioFile
        every { whisperClient.transcribe(audioFile, any()) } returns "こんにちは"
        every { audioFile.delete() } returns true

        val result = processor.stopAndTranscribeOnly()

        assertEquals("こんにちは", result)
        verify(exactly = 0) { gptConverter.convert(any()) }
        verify { audioFile.delete() }
    }

    @Test
    fun `stopAndTranscribeOnly returns null when recorder returns null`() = runTest {
        every { audioRecorder.stop() } returns null

        val result = processor.stopAndTranscribeOnly()

        assertNull(result)
    }

    @Test
    fun `stopAndTranscribeOnly returns null when transcription fails`() = runTest {
        val audioFile = mockk<File>()
        every { audioRecorder.stop() } returns audioFile
        every { whisperClient.transcribe(audioFile, any()) } returns null
        every { audioFile.delete() } returns true

        val result = processor.stopAndTranscribeOnly()

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

    @Test
    fun `stopAndProcess passes context to whisper`() = runTest {
        val audioFile = mockk<File>()
        every { audioRecorder.stop() } returns audioFile
        every { whisperClient.transcribe(audioFile, "既存テキスト") } returns "変換前"
        every { gptConverter.convert("変換前") } returns "変換後"
        every { audioFile.delete() } returns true

        val result = processor.stopAndProcess(context = "既存テキスト")

        assertNotNull(result)
        verify { whisperClient.transcribe(audioFile, "既存テキスト") }
    }

    @Test
    fun `stopAndProcess uses convertWithHistory when corrections provided`() = runTest {
        val audioFile = mockk<File>()
        every { audioRecorder.stop() } returns audioFile
        every { whisperClient.transcribe(audioFile, any()) } returns "おはよう御座います"
        val corrections = listOf(CorrectionEntry("おはよう御座います", "おはようございます", 5))
        every { gptConverter.convertWithHistory("おはよう御座います", corrections) } returns "おはようございます"
        every { audioFile.delete() } returns true

        val result = processor.stopAndProcess(corrections = corrections)

        assertNotNull(result)
        verify { gptConverter.convertWithHistory("おはよう御座います", corrections) }
        verify(exactly = 0) { gptConverter.convert(any()) }
    }

    @Test
    fun `stopAndProcess with null corrections uses convert`() = runTest {
        val audioFile = mockk<File>()
        every { audioRecorder.stop() } returns audioFile
        every { whisperClient.transcribe(audioFile, any()) } returns "テスト"
        every { gptConverter.convert("テスト") } returns "テスト"
        every { audioFile.delete() } returns true

        val result = processor.stopAndProcess()

        assertNotNull(result)
        verify { gptConverter.convert("テスト") }
    }

    @Test
    fun `stopAndTranscribeOnly passes context to whisper`() = runTest {
        val audioFile = mockk<File>()
        every { audioRecorder.stop() } returns audioFile
        every { whisperClient.transcribe(audioFile, "文脈") } returns "結果"
        every { audioFile.delete() } returns true

        val result = processor.stopAndTranscribeOnly(context = "文脈")

        assertEquals("結果", result)
        verify { whisperClient.transcribe(audioFile, "文脈") }
    }
}
