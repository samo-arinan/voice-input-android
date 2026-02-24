package com.example.voiceinput

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.sqrt

class RealtimeAudioStreamer(
    private val onChunk: (ByteArray) -> Unit
) {
    companion object {
        const val SAMPLE_RATE = 24000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_INTERVAL_MS = 100L

        fun calculateRms(samples: ShortArray): Float {
            if (samples.isEmpty()) return 0f
            var sumSquares = 0.0
            for (sample in samples) {
                val normalized = sample.toFloat() / Short.MAX_VALUE
                sumSquares += normalized * normalized
            }
            return sqrt(sumSquares / samples.size).toFloat()
        }
    }

    @Volatile
    var isStreaming: Boolean = false
        private set

    @Volatile
    var currentRms: Float = 0f
        private set

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null

    fun start(): Boolean {
        if (isStreaming) return false

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

            audioRecord = recorder
            recorder.startRecording()
            isStreaming = true

            val chunkSizeBytes = (SAMPLE_RATE * 2 * CHUNK_INTERVAL_MS / 1000).toInt()

            recordingThread = Thread {
                val buffer = ByteArray(chunkSizeBytes)
                while (isStreaming) {
                    val bytesRead = recorder.read(buffer, 0, buffer.size)
                    if (bytesRead > 0) {
                        val chunk = buffer.copyOf(bytesRead)
                        onChunk(chunk)

                        // Calculate RMS from the chunk
                        val samples = ShortArray(bytesRead / 2)
                        java.nio.ByteBuffer.wrap(chunk)
                            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            .asShortBuffer()
                            .get(samples)
                        currentRms = calculateRms(samples)
                    }
                }
            }.also { it.start() }

            true
        } catch (e: Exception) {
            audioRecord?.release()
            audioRecord = null
            isStreaming = false
            false
        }
    }

    /**
     * Immediately stop the recording loop without blocking.
     * Safe to call from any thread (e.g. WebSocket callback thread).
     */
    fun pauseStreaming() {
        isStreaming = false
    }

    fun stop() {
        if (!isStreaming && recordingThread == null) return
        isStreaming = false

        recordingThread?.join(2000)
        recordingThread = null

        audioRecord?.apply {
            stop()
            release()
        }
        audioRecord = null
        currentRms = 0f
    }
}
