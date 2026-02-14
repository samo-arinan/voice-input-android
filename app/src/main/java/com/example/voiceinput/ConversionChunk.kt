package com.example.voiceinput

data class ConversionChunk(
    val raw: String,
    val converted: String,
    var useRaw: Boolean = false
) {
    val isDifferent: Boolean
        get() = raw != converted

    val displayText: String
        get() = if (useRaw) raw else converted
}
