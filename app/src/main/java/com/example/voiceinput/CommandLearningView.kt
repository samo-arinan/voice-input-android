package com.example.voiceinput

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.media.MediaPlayer
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

        fun shouldShowPlayButton(sampleCount: Int): Boolean = sampleCount > 0

        fun latestSampleIndex(sampleCount: Int): Int = if (sampleCount > 0) sampleCount - 1 else -1
    }

    var listener: CommandLearningListener? = null
    private var inputBuffer = StringBuilder()
    private var inputDisplay: TextView? = null
    private var commandList: LinearLayout? = null
    private var addButton: View? = null
    private var keyboardContainer: View? = null
    private var commandRepo: VoiceCommandRepository? = null
    private var isKeyboardVisible = false
    private var expandedCommandId: String? = null
    private var mediaPlayer: MediaPlayer? = null
    private var playingCommandId: String? = null

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
        expandedCommandId = null
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
            tag = cmd.id
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
            layoutParams = params
        }

        // === Header row (always visible): [dots] [name] [play] ===
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        // Dot indicators
        val dotRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        for (i in 0 until MAX_SAMPLES) {
            val dot = ImageView(context).apply {
                setImageResource(
                    if (i < cmd.sampleCount) R.drawable.dot_filled else R.drawable.dot_empty
                )
                val size = dp(6)
                val params = LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = dp(3)
                }
                layoutParams = params
            }
            dotRow.addView(dot)
        }
        headerRow.addView(dotRow)

        // Command name
        val nameView = TextView(context).apply {
            text = cmd.label
            setTextColor(COLOR_TEXT_MAIN)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(10), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        headerRow.addView(nameView)

        // Play button (visible only if sampleCount > 0)
        val playBtn = TextView(context).apply {
            text = "\u25B6"  // ▶
            setTextColor(COLOR_ACCENT)
            textSize = 16f
            gravity = Gravity.CENTER
            val size = dp(32)
            layoutParams = LinearLayout.LayoutParams(size, size)
            visibility = if (shouldShowPlayButton(cmd.sampleCount)) View.VISIBLE else View.GONE
            setOnClickListener {
                onPlayTapped(cmd)
            }
        }
        headerRow.addView(playBtn)

        card.addView(headerRow)

        // === Expandable section (hidden by default) ===
        val expandSection = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(8))
            visibility = if (expandedCommandId == cmd.id) View.VISIBLE else View.GONE
            tag = "expandSection"
        }

        // Divider
        val divider = View(context).apply {
            setBackgroundColor(COLOR_BORDER)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            ).apply { bottomMargin = dp(6) }
        }
        expandSection.addView(divider)

        // Send text
        val sendView = TextView(context).apply {
            text = cmd.text.replace("\n", "\\n")
            setTextColor(COLOR_TEXT_SUB)
            textSize = 11f
            letterSpacing = 0.15f
        }
        expandSection.addView(sendView)

        // Button row
        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
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
        expandSection.addView(buttonRow)

        card.addView(expandSection)

        // Tap header to expand/collapse
        headerRow.setOnClickListener {
            val section = card.findViewWithTag<View>("expandSection")
            if (expandedCommandId == cmd.id) {
                expandedCommandId = null
                section?.visibility = View.GONE
            } else {
                collapseAllCards()
                expandedCommandId = cmd.id
                section?.visibility = View.VISIBLE
            }
        }

        return card
    }

    private fun collapseAllCards() {
        expandedCommandId = null
        val list = commandList ?: return
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i)
            child.findViewWithTag<View>("expandSection")?.visibility = View.GONE
        }
    }

    private fun onPlayTapped(cmd: VoiceCommand) {
        if (playingCommandId == cmd.id) {
            stopPlayback()
            return
        }

        stopPlayback()

        val repo = commandRepo ?: return
        val sampleIndex = latestSampleIndex(cmd.sampleCount)
        if (sampleIndex < 0) return
        val file = repo.getSampleFile(cmd.id, sampleIndex)
        if (!file.exists()) return

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                start()
                setOnCompletionListener { stopPlayback() }
            }
            playingCommandId = cmd.id
            updatePlayButtonState(cmd.id, playing = true)
        } catch (e: Exception) {
            stopPlayback()
        }
    }

    private fun stopPlayback() {
        val prevId = playingCommandId
        mediaPlayer?.release()
        mediaPlayer = null
        playingCommandId = null
        if (prevId != null) {
            updatePlayButtonState(prevId, playing = false)
        }
    }

    private fun updatePlayButtonState(commandId: String, playing: Boolean) {
        val list = commandList ?: return
        for (i in 0 until list.childCount) {
            val card = list.getChildAt(i) as? LinearLayout ?: continue
            if (card.tag == commandId) {
                val headerRow = card.getChildAt(0) as? LinearLayout ?: continue
                val playBtn = headerRow.getChildAt(headerRow.childCount - 1) as? TextView ?: continue
                playBtn.text = if (playing) "\u25A0" else "\u25B6"  // ■ or ▶
                break
            }
        }
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

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopPlayback()
    }

    fun animateDotFill(cardIndex: Int, filledCount: Int) {
        val card = commandList?.getChildAt(cardIndex) as? LinearLayout ?: return
        // Header row is child 0, expand section is child 1
        val headerRow = card.getChildAt(0) as? LinearLayout ?: return
        // Dot row is first child of header row
        val dotRow = headerRow.getChildAt(0) as? LinearLayout ?: return
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
