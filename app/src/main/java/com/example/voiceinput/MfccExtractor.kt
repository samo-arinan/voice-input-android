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

    fun applyHammingWindow(frame: FloatArray): FloatArray {
        val n = frame.size
        return FloatArray(n) { i ->
            frame[i] * (0.54f - 0.46f * kotlin.math.cos(2.0 * Math.PI * i / (n - 1)).toFloat())
        }
    }

    fun applyPreEmphasis(signal: FloatArray, coeff: Float = 0.97f): FloatArray {
        val result = FloatArray(signal.size)
        result[0] = signal[0]
        for (i in 1 until signal.size) {
            result[i] = signal[i] - coeff * signal[i - 1]
        }
        return result
    }

    fun powerSpectrum(frame: FloatArray, fftSize: Int = FFT_SIZE): FloatArray {
        val padded = FloatArray(fftSize)
        frame.copyInto(padded, 0, 0, minOf(frame.size, fftSize))
        val real = padded.copyOf()
        val imag = FloatArray(fftSize)
        fft(real, imag)
        val halfSize = fftSize / 2 + 1
        return FloatArray(halfSize) { k ->
            (real[k] * real[k] + imag[k] * imag[k]) / fftSize.toFloat()
        }
    }

    private fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        var j = 0
        for (i in 0 until n) {
            if (i < j) {
                var temp = real[i]; real[i] = real[j]; real[j] = temp
                temp = imag[i]; imag[i] = imag[j]; imag[j] = temp
            }
            var m = n / 2
            while (m >= 1 && j >= m) { j -= m; m /= 2 }
            j += m
        }
        var step = 1
        while (step < n) {
            val halfStep = step
            step *= 2
            val angleStep = -Math.PI / halfStep
            for (k in 0 until halfStep) {
                val angle = k * angleStep
                val wr = kotlin.math.cos(angle).toFloat()
                val wi = kotlin.math.sin(angle).toFloat()
                var i = k
                while (i < n) {
                    val j2 = i + halfStep
                    val tr = wr * real[j2] - wi * imag[j2]
                    val ti = wr * imag[j2] + wi * real[j2]
                    real[j2] = real[i] - tr
                    imag[j2] = imag[i] - ti
                    real[i] += tr
                    imag[i] += ti
                    i += step
                }
            }
        }
    }

    private fun melFilterbank(powerSpec: FloatArray): FloatArray {
        val numBins = powerSpec.size
        fun hzToMel(hz: Double) = 2595.0 * kotlin.math.log10(1.0 + hz / 700.0)
        fun melToHz(mel: Double) = 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0)

        val melLow = hzToMel(MEL_LOW_FREQ)
        val melHigh = hzToMel(MEL_HIGH_FREQ)
        val melPoints = DoubleArray(NUM_MEL_FILTERS + 2) { i ->
            melLow + i * (melHigh - melLow) / (NUM_MEL_FILTERS + 1)
        }
        val binPoints = IntArray(melPoints.size) { i ->
            ((melToHz(melPoints[i]) * FFT_SIZE / SAMPLE_RATE).toInt()).coerceIn(0, numBins - 1)
        }

        return FloatArray(NUM_MEL_FILTERS) { m ->
            var sum = 0.0f
            val left = binPoints[m]
            val center = binPoints[m + 1]
            val right = binPoints[m + 2]
            for (k in left until center) {
                if (center > left) {
                    sum += powerSpec[k] * (k - left).toFloat() / (center - left)
                }
            }
            for (k in center until right) {
                if (right > center) {
                    sum += powerSpec[k] * (right - k).toFloat() / (right - center)
                }
            }
            sum
        }
    }

    private fun dct(input: FloatArray, numCoeffs: Int = NUM_MFCC): FloatArray {
        val n = input.size
        return FloatArray(numCoeffs) { k ->
            var sum = 0.0f
            for (i in 0 until n) {
                sum += input[i] * kotlin.math.cos(Math.PI * k * (2 * i + 1) / (2.0 * n)).toFloat()
            }
            sum
        }
    }

    fun extract(wavBytes: ByteArray): Array<FloatArray> {
        val rawSignal = parsePcm(wavBytes)
        val signal = applyPreEmphasis(rawSignal)
        val frames = frameSignal(signal)

        return Array(frames.size) { i ->
            val windowed = applyHammingWindow(frames[i])
            val power = powerSpectrum(windowed)
            val melEnergies = melFilterbank(power)
            val logMel = FloatArray(melEnergies.size) { j ->
                kotlin.math.ln(melEnergies[j].coerceAtLeast(1e-10f))
            }
            dct(logMel)
        }
    }
}
