package com.example.voiceinput

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View

class TouchCanvas(context: Context) : View(context) {

    private val points = mutableListOf<Pair<Float, Float>>()
    private val path = Path()
    private var boundingRect: BoundingRect? = null
    var onTraceComplete: ((BoundingRect) -> Unit)? = null

    private val pathPaint = Paint().apply {
        color = Color.argb(180, 74, 222, 128) // green trace
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val rectPaint = Paint().apply {
        color = Color.argb(60, 74, 222, 128) // semi-transparent green fill
        style = Paint.Style.FILL
    }

    private val rectStrokePaint = Paint().apply {
        color = Color.argb(200, 74, 222, 128)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                points.clear()
                path.reset()
                boundingRect = null
                points.add(Pair(event.x, event.y))
                path.moveTo(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                points.add(Pair(event.x, event.y))
                path.lineTo(event.x, event.y)
                boundingRect = BoundingRectCalculator.calculate(points)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                points.add(Pair(event.x, event.y))
                boundingRect = BoundingRectCalculator.calculate(points)
                invalidate()
                boundingRect?.let { onTraceComplete?.invoke(it) }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawPath(path, pathPaint)
        boundingRect?.let { rect ->
            val androidRect = rect.toAndroidRect()
            canvas.drawRect(androidRect, rectPaint)
            canvas.drawRect(androidRect, rectStrokePaint)
        }
    }

    fun getPoints(): List<Pair<Float, Float>> = points.toList()

    fun getBoundingRect(): BoundingRect? = boundingRect
}
