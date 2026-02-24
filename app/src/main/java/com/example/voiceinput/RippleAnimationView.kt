package com.example.voiceinput

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

enum class RippleState { IDLE, RECORDING, PROCESSING }

class RippleAnimationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val NUM_RINGS = 3
        private const val BASE_RADIUS_DP = 24f
        private const val MAX_EXPANSION_DP = 20f
        private const val BASE_ALPHA = 0.4f

        // Colors (teal palette)
        private const val COLOR_IDLE = 0xFF26A69A.toInt()
        private const val COLOR_RECORDING = 0xFF00897B.toInt()
        private const val COLOR_PROCESSING = 0xFF4DB6AC.toInt()

        fun calculateRippleRadius(amplitude: Float, baseRadius: Float, maxExpansion: Float): Float {
            return baseRadius + maxExpansion * amplitude.coerceIn(0f, 1f)
        }

        fun calculateRippleAlpha(ringIndex: Int, totalRings: Int, baseAlpha: Float): Float {
            return baseAlpha * (1f - ringIndex.toFloat() / totalRings)
        }
    }

    private var state: RippleState = RippleState.IDLE
    private var amplitude: Float = 0f
    private var processingPulse: Float = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var pulseAnimator: ValueAnimator? = null

    fun setState(newState: RippleState) {
        if (state == newState) return
        state = newState
        pulseAnimator?.cancel()
        pulseAnimator = null

        when (newState) {
            RippleState.PROCESSING -> startPulseAnimation()
            else -> {
                processingPulse = 0f
                invalidate()
            }
        }
    }

    fun setAmplitude(rms: Float) {
        amplitude = rms.coerceIn(0f, 1f)
        if (state == RippleState.RECORDING) {
            invalidate()
        }
    }

    private fun startPulseAnimation() {
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1200L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                processingPulse = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val density = resources.displayMetrics.density
        val baseRadius = BASE_RADIUS_DP * density
        val maxExpansion = MAX_EXPANSION_DP * density

        when (state) {
            RippleState.IDLE -> {
                paint.color = COLOR_IDLE
                paint.alpha = 255
                canvas.drawCircle(cx, cy, baseRadius, paint)
            }
            RippleState.RECORDING -> {
                // Draw ripple rings from outermost to innermost
                for (i in (NUM_RINGS - 1) downTo 0) {
                    val ringAmplitude = amplitude * (1f - i.toFloat() / NUM_RINGS)
                    val radius = calculateRippleRadius(ringAmplitude, baseRadius, maxExpansion)
                    val alpha = calculateRippleAlpha(i, NUM_RINGS, BASE_ALPHA)
                    paint.color = COLOR_RECORDING
                    paint.alpha = (alpha * 255).toInt()
                    canvas.drawCircle(cx, cy, radius, paint)
                }
                // Center circle
                paint.color = COLOR_RECORDING
                paint.alpha = 255
                canvas.drawCircle(cx, cy, baseRadius, paint)
            }
            RippleState.PROCESSING -> {
                val pulseRadius = baseRadius + maxExpansion * 0.3f * processingPulse
                paint.color = COLOR_PROCESSING
                paint.alpha = (255 * (0.6f + 0.4f * (1f - processingPulse))).toInt()
                canvas.drawCircle(cx, cy, pulseRadius, paint)
                // Center circle
                paint.color = COLOR_PROCESSING
                paint.alpha = 255
                canvas.drawCircle(cx, cy, baseRadius, paint)
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulseAnimator?.cancel()
        pulseAnimator = null
    }
}
