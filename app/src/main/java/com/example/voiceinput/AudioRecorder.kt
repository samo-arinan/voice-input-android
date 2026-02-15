package com.example.voiceinput

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.io.File

class AudioRecorder(private val outputDir: File) {

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var pcmBuffer: ByteArrayOutputStream? = null
    private val bufferLock = Any()
    @Volatile
    var isRecording: Boolean = false
        private set

    companion object {
        const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    fun getOutputFile(): File {
        val fileName = "voice_${System.currentTimeMillis()}.wav"
        return File(outputDir, fileName)
    }

    fun start(): Boolean {
        if (isRecording) return false

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
            return false
        }

        return try {
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                return false
            }

            pcmBuffer = ByteArrayOutputStream()
            audioRecord = recorder
            recorder.startRecording()
            isRecording = true

            recordingThread = Thread {
                val buffer = ByteArray(bufferSize)
                while (isRecording) {
                    val bytesRead = recorder.read(buffer, 0, buffer.size)
                    if (bytesRead > 0) {
                        synchronized(bufferLock) {
                            pcmBuffer?.write(buffer, 0, bytesRead)
                        }
                    }
                }
            }.also { it.start() }

            true
        } catch (e: Exception) {
            audioRecord?.release()
            audioRecord = null
            pcmBuffer = null
            false
        }
    }

    fun stop(): File? {
        if (!isRecording) return null
        isRecording = false

        recordingThread?.join(2000)
        recordingThread = null

        audioRecord?.apply {
            stop()
            release()
        }
        audioRecord = null

        val pcmData: ByteArray
        synchronized(bufferLock) {
            pcmData = pcmBuffer?.toByteArray() ?: return null
            pcmBuffer = null
        }

        if (pcmData.isEmpty()) return null

        val outputFile = getOutputFile()
        AudioProcessor.processForWhisper(pcmData, SAMPLE_RATE, outputFile)
        return outputFile
    }

}
