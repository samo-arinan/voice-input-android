package com.example.voiceinput

data class VoiceCommand(
    val id: String,
    val label: String,
    val text: String,
    val auto: Boolean = false,
    val threshold: Float = 15.0f,
    var sampleCount: Int = 0,
    val enabled: Boolean = true
)
