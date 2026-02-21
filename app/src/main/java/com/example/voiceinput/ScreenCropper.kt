package com.example.voiceinput

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream

object ScreenCropper {

    fun sanitizeRect(rect: BoundingRect, bitmapWidth: Int, bitmapHeight: Int): BoundingRect? {
        val clamped = BoundingRect(
            rect.left.coerceIn(0, bitmapWidth),
            rect.top.coerceIn(0, bitmapHeight),
            rect.right.coerceIn(0, bitmapWidth),
            rect.bottom.coerceIn(0, bitmapHeight)
        )
        val width = clamped.right - clamped.left
        val height = clamped.bottom - clamped.top
        if (width <= 0 || height <= 0) return null
        return clamped
    }

    fun cropAndEncode(bitmap: Bitmap, rect: BoundingRect): String? {
        val sanitized = sanitizeRect(rect, bitmap.width, bitmap.height) ?: return null
        val width = sanitized.right - sanitized.left
        val height = sanitized.bottom - sanitized.top
        val cropped = Bitmap.createBitmap(bitmap, sanitized.left, sanitized.top, width, height)
        val stream = ByteArrayOutputStream()
        cropped.compress(Bitmap.CompressFormat.PNG, 90, stream)
        if (cropped !== bitmap) cropped.recycle()
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }
}
