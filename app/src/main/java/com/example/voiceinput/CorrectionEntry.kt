package com.example.voiceinput

data class CorrectionEntry(
    val original: String,
    val corrected: String,
    var frequency: Int = 1
)
