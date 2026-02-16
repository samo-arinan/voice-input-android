package com.example.voiceinput

import java.nio.ByteBuffer
import java.nio.ByteOrder

object MfccExtractor {

    const val SAMPLE_RATE = 16000
    const val FRAME_SIZE = 400    // 25ms at 16kHz
    const val STRIDE = 160        // 10ms at 16kHz
    const val NUM_MFCC = 13
    const val FFT_SIZE = 512
    const val NUM_MEL_FILTERS = 26
    const val MEL_LOW_FREQ = 300.0
    const val MEL_HIGH_FREQ = 8000.0

    fun parsePcm(wavBytes: ByteArray): FloatArray {
        val pcmData = wavBytes.copyOfRange(44, wavBytes.size)
        val shortBuf = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = ShortArray(shortBuf.remaining())
        shortBuf.get(samples)
        return FloatArray(samples.size) { samples[it].toFloat() }
    }

    fun frameSignal(signal: FloatArray, frameSize: Int = FRAME_SIZE, stride: Int = STRIDE): List<FloatArray> {
        val frames = mutableListOf<FloatArray>()
        var start = 0
        while (start + frameSize <= signal.size) {
            frames.add(signal.copyOfRange(start, start + frameSize))
            start += stride
        }
        return frames
    }
}
