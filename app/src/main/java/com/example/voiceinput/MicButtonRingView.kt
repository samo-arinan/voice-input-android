package com.example.voiceinput

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.widget.FrameLayout

class MicButtonRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var amplitude: Float = 0f
    private var ringVisible: Boolean = false
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * resources.displayMetrics.density
    }
    private val ringRect = RectF()

    init {
        setWillNotDraw(false)
    }

    fun setAmplitude(level: Float) {
        amplitude = level.coerceIn(0f, 1f)
        ringPaint.color = colorForAmplitude(amplitude)
        invalidate()
    }

    fun showRing() {
        ringVisible = true
        invalidate()
    }

    fun hideRing() {
        ringVisible = false
        amplitude = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!ringVisible) return

        val strokeHalf = ringPaint.strokeWidth / 2
        ringRect.set(strokeHalf, strokeHalf, width - strokeHalf, height - strokeHalf)
        canvas.drawArc(ringRect, 0f, 360f, false, ringPaint)
    }

    companion object {
        private const val COLOR_RED = 0xFFFF5252.toInt()
        private const val COLOR_YELLOW = 0xFFFFC107.toInt()
        private const val COLOR_GREEN = 0xFF4CAF50.toInt()

        fun colorForAmplitude(amplitude: Float): Int {
            val clamped = amplitude.coerceIn(0f, 1f)
            return when {
                clamped <= 0.3f -> COLOR_RED
                clamped <= 0.7f -> {
                    val ratio = (clamped - 0.3f) / 0.4f
                    interpolateColor(COLOR_RED, COLOR_YELLOW, ratio)
                }
                else -> {
                    val ratio = (clamped - 0.7f) / 0.3f
                    interpolateColor(COLOR_YELLOW, COLOR_GREEN, ratio)
                }
            }
        }

        private fun interpolateColor(from: Int, to: Int, ratio: Float): Int {
            val a = 0xFF
            val r = (from.shr(16).and(0xFF)) + ((to.shr(16).and(0xFF)) - (from.shr(16).and(0xFF))) * ratio
            val g = (from.shr(8).and(0xFF)) + ((to.shr(8).and(0xFF)) - (from.shr(8).and(0xFF))) * ratio
            val b = (from.and(0xFF)) + ((to.and(0xFF)) - (from.and(0xFF))) * ratio
            return (a shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
        }
    }
}
