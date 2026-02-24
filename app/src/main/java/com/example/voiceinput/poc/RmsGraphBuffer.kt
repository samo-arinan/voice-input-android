package com.example.voiceinput.poc

class RmsGraphBuffer(val maxEntries: Int = 300) {

    data class RmsEntry(val rms: Double, val isPeak: Boolean, val timestamp: Long)

    private val entries = ArrayDeque<RmsEntry>(maxEntries)
    private var maxRms = 1000.0

    fun addRms(rms: Double, isPeak: Boolean) {
        entries.addLast(RmsEntry(rms, isPeak, System.currentTimeMillis()))
        if (entries.size > maxEntries) entries.removeFirst()
        if (rms > maxRms) maxRms = rms
    }

    fun entries(): List<RmsEntry> = entries.toList()

    fun size(): Int = entries.size

    fun maxRms(): Double = maxRms
}
