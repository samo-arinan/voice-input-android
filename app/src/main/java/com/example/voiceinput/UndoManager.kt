package com.example.voiceinput

class UndoManager {
    var lastCommittedText: String? = null
        private set
    var lastCommittedLength: Int = 0
        private set

    val canUndo: Boolean
        get() = lastCommittedText != null

    fun recordCommit(text: String, length: Int) {
        lastCommittedText = text
        lastCommittedLength = length
    }

    fun undo(): Int {
        if (!canUndo) return 0
        val length = lastCommittedLength
        clear()
        return length
    }

    fun clear() {
        lastCommittedText = null
        lastCommittedLength = 0
    }
}
