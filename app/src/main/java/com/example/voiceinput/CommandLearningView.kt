package com.example.voiceinput

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
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
) : LinearLayout(context, attrs) {

    var listener: CommandLearningListener? = null
    private var inputBuffer = StringBuilder()
    private var inputDisplay: TextView? = null
    private var commandList: LinearLayout? = null
    private var addButton: Button? = null
    private var commandRepo: VoiceCommandRepository? = null
    private var inputMode: InputMode = InputMode.LABEL
    private var pendingLabel: String? = null

    enum class InputMode { LABEL, TEXT }

    init {
        orientation = VERTICAL
        val view = LayoutInflater.from(context).inflate(R.layout.view_command_learning, this, true)
        inputDisplay = view.findViewById(R.id.inputDisplay)
        commandList = view.findViewById(R.id.commandList)
        addButton = view.findViewById(R.id.addButton)

        addButton?.setOnClickListener { onAddTapped() }

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
        val hint = when (inputMode) {
            InputMode.LABEL -> "コマンド名を入力..."
            InputMode.TEXT -> "送信文字列を入力..."
        }
        inputDisplay?.text = if (inputBuffer.isEmpty()) "" else inputBuffer.toString()
        inputDisplay?.hint = hint
    }

    private fun onAddTapped() {
        val input = inputBuffer.toString().trim()
        if (input.isEmpty()) return

        when (inputMode) {
            InputMode.LABEL -> {
                pendingLabel = input
                inputBuffer.clear()
                inputMode = InputMode.TEXT
                updateInputDisplay()
                addButton?.text = "確定"
            }
            InputMode.TEXT -> {
                val label = pendingLabel ?: return
                commandRepo?.addCommand(label, input)
                inputBuffer.clear()
                inputMode = InputMode.LABEL
                pendingLabel = null
                updateInputDisplay()
                addButton?.text = "＋追加"
                refreshCommandList()
            }
        }
    }

    fun refreshCommandList() {
        commandList?.removeAllViews()
        val commands = commandRepo?.getCommands() ?: return

        for (cmd in commands) {
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                setPadding(8, 4, 8, 4)
            }

            val label = TextView(context).apply {
                text = "${cmd.label}  →  ${cmd.text.replace("\n", "\\n")}"
                textSize = 12f
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            }

            val recordBtn = Button(context).apply {
                text = "\uD83D\uDD34 ${cmd.sampleCount}/3"
                textSize = 10f
                setOnClickListener {
                    listener?.onRecordSample(cmd.id, cmd.sampleCount)
                }
            }

            val deleteBtn = Button(context).apply {
                text = "\uD83D\uDDD1"
                textSize = 10f
                setOnClickListener {
                    listener?.onDeleteCommand(cmd.id)
                }
            }

            row.addView(label)
            row.addView(recordBtn)
            row.addView(deleteBtn)
            commandList?.addView(row)
        }
    }
}
