package com.example.voiceinput

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

interface TmuxViewListener {
    fun onSendKeys(key: String)
    fun onRequestRefresh(callback: (String?) -> Unit)
}

class TmuxView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    companion object {
        private const val POLL_INTERVAL_MS = 2000L
        private const val COLOR_KEY_BG = 0xFF1A1F26.toInt()
        private const val COLOR_KEY_TEXT = 0xFF8B949E.toInt()
        private const val COLOR_KEY_ACCENT = 0xFF6BA4FF.toInt()
        private const val COLOR_BORDER = 0xFF2B323C.toInt()

        val KEYS = listOf(
            "\u2191" to "Up",      // ↑
            "\u2193" to "Down",    // ↓
            "0" to "0", "1" to "1", "2" to "2", "3" to "3", "4" to "4",
            "5" to "5", "6" to "6", "7" to "7", "8" to "8", "9" to "9",
            "\u232B" to "BSpace",  // ⌫
            "\u23CE" to "Enter"    // ⏎
        )
    }

    var listener: TmuxViewListener? = null
    private var tmuxOutput: TextView? = null
    private var tmuxScrollView: ScrollView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var polling = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!polling) return
            listener?.onRequestRefresh { text ->
                post {
                    tmuxOutput?.text = text ?: ""
                    tmuxScrollView?.fullScroll(ScrollView.FOCUS_DOWN)
                }
            }
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    init {
        val view = LayoutInflater.from(context).inflate(R.layout.view_tmux, this, true)
        tmuxOutput = view.findViewById(R.id.tmuxOutput)
        tmuxScrollView = view.findViewById(R.id.tmuxScrollView)

        val keyboard = view.findViewById<LinearLayout>(R.id.tmuxKeyboard)
        buildKeyboard(keyboard)
    }

    private fun buildKeyboard(container: LinearLayout) {
        val dp = { value: Int -> (value * context.resources.displayMetrics.density).toInt() }

        for ((label, key) in KEYS) {
            val btn = TextView(context).apply {
                text = label
                setTextColor(if (key == "Enter") COLOR_KEY_ACCENT else COLOR_KEY_TEXT)
                textSize = 11f
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(8))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { listener?.onSendKeys(key) }
            }
            container.addView(btn)
        }
    }

    fun startPolling() {
        if (polling) return
        polling = true
        handler.post(pollRunnable)
    }

    fun stopPolling() {
        polling = false
        handler.removeCallbacks(pollRunnable)
    }

    fun updateOutput(text: String?) {
        tmuxOutput?.text = text ?: ""
        tmuxScrollView?.fullScroll(ScrollView.FOCUS_DOWN)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopPolling()
    }
}
