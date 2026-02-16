package com.example.voiceinput

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

interface CommandLearningListener {
    fun onRecordSample(commandId: String, sampleIndex: Int)
    fun onDeleteCommand(commandId: String)
}

class CommandLearningView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    companion object {
        private const val COLOR_BG = 0xFF111418.toInt()
        private const val COLOR_SURFACE = 0xFF1A1F26.toInt()
        private const val COLOR_BORDER = 0xFF2B323C.toInt()
        private const val COLOR_TEXT_MAIN = 0xFFE6EDF3.toInt()
        private const val COLOR_TEXT_SUB = 0xFF8B949E.toInt()
        private const val COLOR_ACCENT = 0xFF6BA4FF.toInt()
        private const val COLOR_DANGER = 0xFFE05D5D.toInt()
        private const val MAX_SAMPLES = 5
    }

    var listener: CommandLearningListener? = null
    private var inputBuffer = StringBuilder()
    private var inputDisplay: TextView? = null
    private var commandList: LinearLayout? = null
    private var addButton: View? = null
    private var keyboardContainer: View? = null
    private var commandRepo: VoiceCommandRepository? = null
    private var isKeyboardVisible = false

    init {
        val view = LayoutInflater.from(context).inflate(R.layout.view_command_learning, this, true)
        inputDisplay = view.findViewById(R.id.inputDisplay)
        commandList = view.findViewById(R.id.commandList)
        addButton = view.findViewById(R.id.addButton)
        keyboardContainer = view.findViewById(R.id.keyboardContainer)

        addButton?.setOnClickListener { onAddTapped() }

        inputDisplay?.setOnClickListener { showKeyboard() }

        keyboardContainer?.findViewById<View>(R.id.keyboardScrim)?.setOnClickListener {
            hideKeyboard()
        }

        val keyboard = view.findViewById<AlphanumericKeyboardView>(R.id.alphaKeyboard)
        keyboard?.listener = object : AlphanumericKeyboardListener {
            override fun onKeyInput(value: String) {
                inputBuffer.append(value)
                updateInputDisplay()
            }
            override fun onBackspace() {
                if (inputBuffer.isNotEmpty()) {
                    inputBuffer.deleteCharAt(inputBuffer.length - 1)
                    updateInputDisplay()
                }
            }
        }

        updateInputDisplay()
    }

    fun setRepository(repo: VoiceCommandRepository) {
        commandRepo = repo
        refreshCommandList()
    }

    private fun updateInputDisplay() {
        inputDisplay?.text = if (inputBuffer.isEmpty()) "" else inputBuffer.toString()
        inputDisplay?.hint = "enter command text..."
    }

    private fun showKeyboard() {
        if (isKeyboardVisible) return
        isKeyboardVisible = true
        keyboardContainer?.let { container ->
            container.visibility = View.VISIBLE
            container.translationY = container.height.toFloat().takeIf { it > 0f } ?: 200f
            container.animate()
                .translationY(0f)
                .setDuration(150)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun hideKeyboard() {
        if (!isKeyboardVisible) return
        isKeyboardVisible = false
        keyboardContainer?.let { container ->
            container.animate()
                .translationY(container.height.toFloat().takeIf { it > 0f } ?: 200f)
                .setDuration(150)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { container.visibility = View.GONE }
                .start()
        }
    }

    private fun onAddTapped() {
        val text = inputBuffer.toString()
        if (text.isEmpty()) return

        commandRepo?.addCommand(text, text)
        inputBuffer.clear()
        updateInputDisplay()
        refreshCommandList()
        hideKeyboard()
    }

    fun refreshCommandList() {
        commandList?.removeAllViews()
        val commands = commandRepo?.getCommands() ?: return

        for ((index, cmd) in commands.withIndex()) {
            val card = buildCommandCard(cmd)
            // Entry animation
            card.alpha = 0f
            card.translationY = 30f
            card.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(150)
                .setStartDelay((index * 50).toLong())
                .setInterpolator(DecelerateInterpolator())
                .start()
            commandList?.addView(card)
        }
    }

    private fun buildCommandCard(cmd: VoiceCommand): LinearLayout {
        val dp = { value: Int -> (value * context.resources.displayMetrics.density).toInt() }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_command_card)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            layoutParams = params
        }

        // Command name
        val nameView = TextView(context).apply {
            text = cmd.label
            setTextColor(COLOR_TEXT_MAIN)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
        }
        card.addView(nameView)

        // Divider
        val divider = View(context).apply {
            setBackgroundColor(COLOR_BORDER)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            }
        }
        card.addView(divider)

        // Send text
        val sendView = TextView(context).apply {
            text = "SEND: /${cmd.text.replace("\n", "\\n")}"
            setTextColor(COLOR_TEXT_SUB)
            textSize = 11f
            letterSpacing = 0.15f
        }
        card.addView(sendView)

        // TRAINING label
        val trainingLabel = TextView(context).apply {
            text = "TRAINING"
            setTextColor(COLOR_TEXT_SUB)
            textSize = 9f
            letterSpacing = 0.2f
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            layoutParams = params
        }
        card.addView(trainingLabel)

        // Dot indicators
        val dotRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
            layoutParams = params
        }
        for (i in 0 until MAX_SAMPLES) {
            val dot = ImageView(context).apply {
                setImageResource(
                    if (i < cmd.sampleCount) R.drawable.dot_filled else R.drawable.dot_empty
                )
                val size = dp(6)
                val params = LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = dp(4)
                }
                layoutParams = params
            }
            dotRow.addView(dot)
        }
        card.addView(dotRow)

        // Button row
        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            layoutParams = params
        }

        val trainBtn = TextView(context).apply {
            text = "TRAIN"
            setTextColor(COLOR_ACCENT)
            textSize = 11f
            letterSpacing = 0.15f
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.btn_outline_accent)
            setPadding(dp(16), dp(6), dp(16), dp(6))
            setOnClickListener {
                // Flash card border
                flashCardBorder(card)
                listener?.onRecordSample(cmd.id, cmd.sampleCount)
            }
        }

        val deleteBtn = TextView(context).apply {
            text = "DELETE"
            setTextColor(COLOR_DANGER)
            textSize = 11f
            letterSpacing = 0.15f
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.btn_outline_danger)
            setPadding(dp(16), dp(6), dp(16), dp(6))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
            layoutParams = params
            setOnClickListener {
                listener?.onDeleteCommand(cmd.id)
            }
        }

        buttonRow.addView(trainBtn)
        buttonRow.addView(deleteBtn)
        card.addView(buttonRow)

        return card
    }

    private fun flashCardBorder(card: View) {
        val bg = card.background
        if (bg is GradientDrawable) {
            val original = COLOR_BORDER
            bg.setStroke((1 * context.resources.displayMetrics.density).toInt(), COLOR_ACCENT)
            card.postDelayed({
                bg.setStroke((1 * context.resources.displayMetrics.density).toInt(), original)
            }, 100)
        }
    }

    fun animateDotFill(cardIndex: Int, filledCount: Int) {
        val card = commandList?.getChildAt(cardIndex) as? LinearLayout ?: return
        // Find the dot row (5th child: name, divider, send, training, dots, buttons)
        val dotRow = card.getChildAt(4) as? LinearLayout ?: return
        for (i in 0 until dotRow.childCount) {
            if (i < filledCount) {
                val dot = dotRow.getChildAt(i) as? ImageView ?: continue
                dot.postDelayed({
                    dot.setImageResource(R.drawable.dot_filled)
                    dot.scaleX = 0f
                    dot.scaleY = 0f
                    dot.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                }, (i * 100).toLong())
            }
        }
    }
}
