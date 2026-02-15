package com.example.voiceinput

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.Button
import android.widget.GridLayout
import kotlin.math.abs

enum class FlickDirection {
    CENTER, UP, DOWN, LEFT, RIGHT
}

object FlickResolver {

    // Array order: CENTER, UP, LEFT, DOWN, RIGHT
    private val KANA_MAP = mapOf(
        "あ" to arrayOf("あ", "い", "う", "え", "お"),
        "か" to arrayOf("か", "き", "く", "け", "こ"),
        "さ" to arrayOf("さ", "し", "す", "せ", "そ"),
        "た" to arrayOf("た", "ち", "つ", "て", "と"),
        "な" to arrayOf("な", "に", "ぬ", "ね", "の"),
        "は" to arrayOf("は", "ひ", "ふ", "へ", "ほ"),
        "ま" to arrayOf("ま", "み", "む", "め", "も"),
        "や" to arrayOf("や", "や", "ゆ", "や", "よ"),
        "ら" to arrayOf("ら", "り", "る", "れ", "ろ"),
        "わ" to arrayOf("わ", "わ", "を", "わ", "ん")
    )

    private val DIR_INDEX = mapOf(
        FlickDirection.CENTER to 0,
        FlickDirection.UP to 1,
        FlickDirection.LEFT to 2,
        FlickDirection.DOWN to 3,
        FlickDirection.RIGHT to 4
    )

    fun resolve(rowKey: String, direction: FlickDirection): String? {
        val row = KANA_MAP[rowKey] ?: return null
        return row[DIR_INDEX[direction]!!]
    }

    fun detectDirection(startX: Float, startY: Float, endX: Float, endY: Float): FlickDirection {
        val dx = endX - startX
        val dy = endY - startY
        val threshold = 30f

        if (abs(dx) < threshold && abs(dy) < threshold) return FlickDirection.CENTER

        return if (abs(dx) > abs(dy)) {
            if (dx > 0) FlickDirection.RIGHT else FlickDirection.LEFT
        } else {
            if (dy > 0) FlickDirection.DOWN else FlickDirection.UP
        }
    }
}

interface FlickKeyboardListener {
    fun onCharacterInput(char: String)
    fun onBackspace()
    fun onConvert()
    fun onConfirm()
}

class FlickKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GridLayout(context, attrs) {

    var listener: FlickKeyboardListener? = null

    private val keys = listOf("あ", "か", "さ", "た", "な", "は", "ま", "や", "ら", "わ")

    init {
        columnCount = 5
        buildKeyboard()
    }

    private fun buildKeyboard() {
        removeAllViews()

        // Row 1: あ か さ た な
        for (i in 0 until 5) {
            addFlickKey(keys[i])
        }
        // Row 2: は ま や ら わ
        for (i in 5 until 10) {
            addFlickKey(keys[i])
        }
        // Row 3: 🎤 ⌫ 変換 確定
        addActionButton("、", 1) { listener?.onCharacterInput("、") }
        addActionButton("⌫", 1) { listener?.onBackspace() }
        addActionButton("変換", 2) { listener?.onConvert() }
        addActionButton("確定", 1) { listener?.onConfirm() }
    }

    private fun addFlickKey(rowKey: String) {
        val btn = Button(context).apply {
            text = rowKey
            textSize = 18f
            var startX = 0f
            var startY = 0f
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val dir = FlickResolver.detectDirection(startX, startY, event.rawX, event.rawY)
                        val char = FlickResolver.resolve(rowKey, dir)
                        if (char != null) {
                            listener?.onCharacterInput(char)
                        }
                        true
                    }
                    else -> false
                }
            }
        }
        val params = LayoutParams(spec(UNDEFINED, 1f), spec(UNDEFINED, 1f)).apply {
            width = 0
            height = LayoutParams.WRAP_CONTENT
        }
        addView(btn, params)
    }

    private fun addActionButton(label: String, span: Int, action: () -> Unit) {
        val btn = Button(context).apply {
            text = label
            textSize = 14f
            setOnClickListener { action() }
        }
        val params = LayoutParams(spec(UNDEFINED, span.toFloat()), spec(UNDEFINED, 1f)).apply {
            width = 0
            height = LayoutParams.WRAP_CONTENT
        }
        addView(btn, params)
    }
}
