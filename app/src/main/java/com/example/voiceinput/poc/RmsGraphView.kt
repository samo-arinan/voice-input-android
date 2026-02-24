package com.example.voiceinput.poc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

class RmsGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val linePaint = Paint().apply {
        color = Color.parseColor("#2196F3")
        strokeWidth = 2f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val peakPaint = Paint().apply {
        color = Color.parseColor("#F44336")
        strokeWidth = 6f
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 28f
        isAntiAlias = true
    }

    private val buffer = RmsGraphBuffer()

    fun addRms(rms: Double, isPeak: Boolean) {
        buffer.addRms(rms, isPeak)
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        canvas.drawColor(Color.parseColor("#1A1A1A"))

        val entryList = buffer.entries()
        if (entryList.isEmpty()) return

        val maxEntries = 300
        val maxRms = buffer.maxRms()
        val path = Path()
        val stepX = w / maxEntries

        for (i in entryList.indices) {
            val x = i * stepX
            val y = h - (entryList[i].rms / maxRms * h * 0.9).toFloat()

            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)

            if (entryList[i].isPeak) {
                canvas.drawCircle(x, y, 6f, peakPaint)
            }
        }
        canvas.drawPath(path, linePaint)

        // Current RMS value text
        val lastRms = entryList.last().rms
        canvas.drawText("RMS: %.0f".format(lastRms), 10f, 30f, textPaint)
    }
}
