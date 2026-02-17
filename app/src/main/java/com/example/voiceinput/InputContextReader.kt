package com.example.voiceinput

object InputContextReader {

    private const val MAX_DISPLAY_LENGTH = 100

    fun formatContextDebug(text: CharSequence?): String {
        if (text.isNullOrEmpty()) return "CTX: (empty)"
        val len = text.length
        val display = if (len > MAX_DISPLAY_LENGTH) {
            text.substring(0, MAX_DISPLAY_LENGTH) + "..."
        } else {
            text.toString()
        }
        return "CTX[$len]: $display"
    }
}
