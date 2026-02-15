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

    fun getAmplitude(): Int {
        return audioRecorder.getAmplitude()
    }

    fun startRecording(): Boolean {
        return audioRecorder.start()
    }

    suspend fun stopAndTranscribeOnly(context: String? = null): String? {
        val audioFile = audioRecorder.stop() ?: return null
        try {
            return withContext(Dispatchers.IO) {
                whisperClient.transcribe(audioFile, prompt = context)
            }
        } finally {
            audioFile.delete()
        }
    }

    suspend fun stopAndProcess(context: String? = null): List<ConversionChunk>? {
        val audioFile = audioRecorder.stop() ?: return null

        try {
            val rawText = withContext(Dispatchers.IO) {
                whisperClient.transcribe(audioFile, prompt = context)
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
