package com.example.voiceinput

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VoiceInputProcessor(
    private val audioRecorder: AudioRecorder,
    private val whisperClient: WhisperClient,
    private val gptConverter: GptConverter
) {
    val isRecording: Boolean
        get() = audioRecorder.isRecording

    fun startRecording(): Boolean {
        return audioRecorder.start()
    }

    suspend fun stopAndProcess(): List<ConversionChunk>? {
        val audioFile = audioRecorder.stop() ?: return null

        try {
            val rawText = withContext(Dispatchers.IO) {
                whisperClient.transcribe(audioFile)
            } ?: return null

            return withContext(Dispatchers.IO) {
                gptConverter.convertToChunks(rawText)
            }
        } finally {
            audioFile.delete()
        }
    }
}
