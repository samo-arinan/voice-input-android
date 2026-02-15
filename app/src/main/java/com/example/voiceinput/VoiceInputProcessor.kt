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

    suspend fun stopAndTranscribeOnly(): String? {
        val audioFile = audioRecorder.stop() ?: return null
        try {
            return withContext(Dispatchers.IO) {
                whisperClient.transcribe(audioFile)
            }
        } finally {
            audioFile.delete()
        }
    }

    suspend fun stopAndProcess(): List<ConversionChunk>? {
        val audioFile = audioRecorder.stop() ?: return null

        try {
            val rawText = withContext(Dispatchers.IO) {
                whisperClient.transcribe(audioFile)
            } ?: return null

            val convertedText = withContext(Dispatchers.IO) {
                gptConverter.convert(rawText)
            }

            return TextDiffer.diff(rawText, convertedText)
        } finally {
            audioFile.delete()
        }
    }
}
