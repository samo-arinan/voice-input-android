package com.example.voiceinput

import android.graphics.Rect

/**
 * Pure-data bounding rectangle, free of Android framework dependencies.
 * Use [toAndroidRect] at the boundary when android.graphics.Rect is needed.
 */
data class BoundingRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    fun toAndroidRect(): Rect = Rect(left, top, right, bottom)
}

object BoundingRectCalculator {

    fun calculate(points: List<Pair<Float, Float>>): BoundingRect {
        if (points.isEmpty()) return BoundingRect(0, 0, 0, 0)

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE

        for ((x, y) in points) {
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }

        val left = minX.toInt()
        val top = minY.toInt()
        val right = maxOf(maxX.toInt(), left + 1)
        val bottom = maxOf(maxY.toInt(), top + 1)

        return BoundingRect(left, top, right, bottom)
    }

    fun calculateClamped(points: List<Pair<Float, Float>>, bitmapWidth: Int, bitmapHeight: Int): BoundingRect {
        val rect = calculate(points)
        return BoundingRect(
            rect.left.coerceIn(0, bitmapWidth),
            rect.top.coerceIn(0, bitmapHeight),
            rect.right.coerceIn(0, bitmapWidth),
            rect.bottom.coerceIn(0, bitmapHeight)
        )
    }
}
