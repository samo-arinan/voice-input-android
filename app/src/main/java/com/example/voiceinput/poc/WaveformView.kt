package com.example.voiceinput.poc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val waveformPaint = Paint().apply {
        color = Color.parseColor("#4CAF50")
        strokeWidth = 1.5f
        isAntiAlias = true
    }

    private val centerLinePaint = Paint().apply {
        color = Color.parseColor("#333333")
        strokeWidth = 1f
    }

    internal val buffer = WaveformBuffer(maxSamples = 3 * 16000) // 3 seconds at 16kHz

    fun pushSamples(newSamples: ShortArray) {
        buffer.pushSamples(newSamples)
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val centerY = h / 2f

        canvas.drawColor(Color.parseColor("#1A1A1A"))
        canvas.drawLine(0f, centerY, w, centerY, centerLinePaint)

        if (buffer.sampleCount == 0) return

        val displayCount = buffer.sampleCount.coerceAtMost(buffer.maxSamples)
        val samplesPerPixel = (displayCount / w).toInt().coerceAtLeast(1)

        for (x in 0 until w.toInt()) {
            val sampleOffset = (x * displayCount / w).toInt()
            val rangeEnd = (sampleOffset + samplesPerPixel - 1).coerceAtMost(displayCount - 1)
            val (min, max) = buffer.getMinMax(sampleOffset, rangeEnd)

            val yMin = centerY - (max.toFloat() / 32768f) * centerY
            val yMax = centerY - (min.toFloat() / 32768f) * centerY
            canvas.drawLine(x.toFloat(), yMin, x.toFloat(), yMax, waveformPaint)
        }
    }
}
