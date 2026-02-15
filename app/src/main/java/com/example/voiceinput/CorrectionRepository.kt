package com.example.voiceinput

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

class CorrectionRepository(
    private val storageFile: File,
    private val maxEntries: Int = 500
) {
    private val gson = Gson()

    fun save(original: String, corrected: String) {
        val entries = loadAll().toMutableList()
        val existing = entries.find { it.original == original && it.corrected == corrected }
        if (existing != null) {
            existing.frequency++
        } else {
            entries.add(CorrectionEntry(original, corrected))
        }
        if (entries.size > maxEntries) {
            entries.sortBy { it.frequency }
            while (entries.size > maxEntries) {
                entries.removeAt(0)
            }
        }
        persist(entries)
    }

    fun getTopCorrections(limit: Int): List<CorrectionEntry> {
        return loadAll()
            .sortedByDescending { it.frequency }
            .take(limit)
    }

    private fun loadAll(): List<CorrectionEntry> {
        if (!storageFile.exists()) return emptyList()
        return try {
            val json = storageFile.readText()
            if (json.isBlank()) return emptyList()
            val type = object : TypeToken<List<CorrectionEntry>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun persist(entries: List<CorrectionEntry>) {
        storageFile.writeText(gson.toJson(entries))
    }
}
