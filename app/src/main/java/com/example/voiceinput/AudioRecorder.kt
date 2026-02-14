package com.example.voiceinput

import android.media.MediaRecorder
import java.io.File

class AudioRecorder(private val outputDir: File) {

    private var mediaRecorder: MediaRecorder? = null
    private var currentFile: File? = null
    var isRecording: Boolean = false
        private set

    fun getOutputFile(): File {
        val fileName = "voice_${System.currentTimeMillis()}.m4a"
        return File(outputDir, fileName)
    }

    fun start(): Boolean {
        if (isRecording) return false
        val file = getOutputFile()
        currentFile = file

        return try {
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setAudioChannels(1)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            isRecording = true
            true
        } catch (e: Exception) {
            mediaRecorder?.release()
            mediaRecorder = null
            currentFile = null
            false
        }
    }

    fun stop(): File? {
        if (!isRecording) return null
        mediaRecorder?.apply {
            stop()
            release()
        }
        mediaRecorder = null
        isRecording = false
        return currentFile
    }
}
