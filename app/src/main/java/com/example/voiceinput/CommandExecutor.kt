package com.example.voiceinput

import android.view.KeyEvent
import android.view.inputmethod.InputConnection

object CommandExecutor {

    sealed class Action {
        data class Text(val value: String) : Action()
        data object Enter : Action()
    }

    fun parseActions(text: String): List<Action> {
        if (text.isEmpty()) return emptyList()
        val actions = mutableListOf<Action>()
        val buffer = StringBuilder()
        for (ch in text) {
            if (ch == '\n') {
                if (buffer.isNotEmpty()) {
                    actions.add(Action.Text(buffer.toString()))
                    buffer.clear()
                }
                actions.add(Action.Enter)
            } else {
                buffer.append(ch)
            }
        }
        if (buffer.isNotEmpty()) {
            actions.add(Action.Text(buffer.toString()))
        }
        return actions
    }

    fun execute(text: String, ic: InputConnection) {
        for (action in parseActions(text)) {
            when (action) {
                is Action.Text -> ic.commitText(action.value, 1)
                is Action.Enter -> {
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                }
            }
        }
    }
}
