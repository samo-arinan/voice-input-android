package com.example.voiceinput

import android.content.Context
import android.util.AttributeSet
import android.widget.Button
import android.widget.GridLayout

data class KeyDef(val display: String, val value: String)

interface AlphanumericKeyboardListener {
    fun onKeyInput(value: String)
    fun onBackspace()
}

class AlphanumericKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GridLayout(context, attrs) {

    var listener: AlphanumericKeyboardListener? = null

    companion object {
        val KEY_ROWS = listOf(
            listOf(
                KeyDef("q","q"), KeyDef("w","w"), KeyDef("e","e"), KeyDef("r","r"),
                KeyDef("t","t"), KeyDef("y","y"), KeyDef("u","u"), KeyDef("i","i"),
                KeyDef("o","o"), KeyDef("p","p")
            ),
            listOf(
                KeyDef("a","a"), KeyDef("s","s"), KeyDef("d","d"), KeyDef("f","f"),
                KeyDef("g","g"), KeyDef("h","h"), KeyDef("j","j"), KeyDef("k","k"),
                KeyDef("l","l"), KeyDef("/","/")
            ),
            listOf(
                KeyDef("z","z"), KeyDef("x","x"), KeyDef("c","c"), KeyDef("v","v"),
                KeyDef("b","b"), KeyDef("n","n"), KeyDef("m","m"), KeyDef("-","-"),
                KeyDef(".","."), KeyDef("\\","\\")
            ),
            listOf(
                KeyDef("0","0"), KeyDef("1","1"), KeyDef("2","2"), KeyDef("3","3"),
                KeyDef("4","4"), KeyDef("5","5"), KeyDef("6","6"), KeyDef("7","7"),
                KeyDef("8","8"), KeyDef("9","9")
            ),
            listOf(
                KeyDef("␣"," "), KeyDef("⏎","\n"), KeyDef("⌫","BACKSPACE")
            )
        )
    }

    init {
        columnCount = 10
        buildKeyboard()
    }

    private fun buildKeyboard() {
        removeAllViews()
        for (row in KEY_ROWS) {
            for (key in row) {
                addKeyButton(key, if (row == KEY_ROWS.last()) calcSpan(key, row) else 1)
            }
        }
    }

    private fun calcSpan(key: KeyDef, row: List<KeyDef>): Int {
        return 10 / row.size
    }

    private fun addKeyButton(key: KeyDef, span: Int) {
        val btn = Button(context).apply {
            text = key.display
            textSize = 12f
            setOnClickListener {
                if (key.value == "BACKSPACE") {
                    listener?.onBackspace()
                } else {
                    listener?.onKeyInput(key.value)
                }
            }
        }
        val params = LayoutParams(spec(UNDEFINED, span.toFloat()), spec(UNDEFINED, 1f)).apply {
            width = 0
            height = LayoutParams.WRAP_CONTENT
        }
        addView(btn, params)
    }
}
