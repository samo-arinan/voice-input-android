package com.example.voiceinput

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

class VoiceCommandRepository(
    private val storageFile: File,
    private val samplesDir: File
) {
    private val gson = Gson()

    init {
        if (!samplesDir.exists()) samplesDir.mkdirs()
    }

    @Synchronized
    fun getCommands(): List<VoiceCommand> {
        if (!storageFile.exists()) return emptyList()
        return try {
            val json = storageFile.readText()
            if (json.isBlank()) return emptyList()
            val type = object : TypeToken<List<VoiceCommand>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun addCommand(label: String, text: String): VoiceCommand {
        val commands = getCommands().toMutableList()
        val id = label.lowercase().replace(Regex("[^a-z0-9]"), "_")
        val cmd = VoiceCommand(id = id, label = label, text = text)
        commands.add(cmd)
        persist(commands)
        return cmd
    }

    @Synchronized
    fun deleteCommand(id: String) {
        val commands = getCommands().toMutableList()
        commands.removeAll { it.id == id }
        persist(commands)
        samplesDir.listFiles()?.filter { it.name.startsWith("${id}_") }?.forEach { it.delete() }
    }

    @Synchronized
    fun updateSampleCount(id: String, count: Int) {
        val commands = getCommands().toMutableList()
        val index = commands.indexOfFirst { it.id == id }
        if (index >= 0) {
            commands[index] = commands[index].copy(sampleCount = count)
            persist(commands)
        }
    }

    fun getSampleFile(commandId: String, index: Int): File {
        return File(samplesDir, "${commandId}_${index}.wav")
    }

    private fun persist(commands: List<VoiceCommand>) {
        storageFile.writeText(gson.toJson(commands))
    }
}
