package com.example.voiceinput

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VoiceInputProcessor(
    private val audioRecorder: AudioRecorder,
    private val whisperClient: WhisperClient,
    val gptConverter: GptConverter
) {
    val isRecording: Boolean
        get() = audioRecorder.isRecording

    fun startRecording(): Boolean {
        return audioRecorder.start()
    }

    fun stopRecording(): File? {
        return audioRecorder.stop()
    }

    suspend fun processAudioFile(
        audioFile: File,
        context: String? = null,
        terminalContext: String? = null,
        corrections: List<CorrectionEntry>? = null
    ): List<ConversionChunk>? {
        try {
            val rawText = withContext(Dispatchers.IO) {
                whisperClient.transcribe(audioFile, prompt = context)
            } ?: return null

            val convertedText = withContext(Dispatchers.IO) {
                if (corrections != null) {
                    gptConverter.convertWithHistory(rawText, corrections, terminalContext)
                } else {
                    gptConverter.convert(rawText)
                }
            }

            return TextDiffer.diff(rawText, convertedText)
        } finally {
            audioFile.delete()
        }
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

    suspend fun stopAndProcess(
        context: String? = null,
        corrections: List<CorrectionEntry>? = null
    ): List<ConversionChunk>? {
        val audioFile = audioRecorder.stop() ?: return null

        try {
            val rawText = withContext(Dispatchers.IO) {
                whisperClient.transcribe(audioFile, prompt = context)
            } ?: return null

            val convertedText = withContext(Dispatchers.IO) {
                if (corrections != null) {
                    gptConverter.convertWithHistory(rawText, corrections)
                } else {
                    gptConverter.convert(rawText)
                }
            }

            return TextDiffer.diff(rawText, convertedText)
        } finally {
            audioFile.delete()
        }
    }
}
