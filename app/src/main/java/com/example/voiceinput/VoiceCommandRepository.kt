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

    fun getMfccCacheFile(commandId: String, index: Int): File {
        return File(samplesDir, "${commandId}_${index}.mfcc")
    }

    fun saveMfccCache(commandId: String, index: Int, mfcc: Array<FloatArray>) {
        val file = getMfccCacheFile(commandId, index)
        val buffer = java.nio.ByteBuffer.allocate(8 + mfcc.sumOf { it.size * 4 })
        buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(mfcc.size)
        buffer.putInt(if (mfcc.isNotEmpty()) mfcc[0].size else 0)
        for (frame in mfcc) {
            for (value in frame) {
                buffer.putFloat(value)
            }
        }
        file.writeBytes(buffer.array())
    }

    fun loadMfccCache(commandId: String, index: Int): Array<FloatArray>? {
        val file = getMfccCacheFile(commandId, index)
        if (!file.exists()) return null
        return try {
            val bytes = file.readBytes()
            val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val numFrames = buffer.getInt()
            val dimensions = buffer.getInt()
            Array(numFrames) { FloatArray(dimensions) { buffer.getFloat() } }
        } catch (e: Exception) {
            null
        }
    }

    fun loadAllMfccs(): Map<String, List<Array<FloatArray>>> {
        val result = mutableMapOf<String, MutableList<Array<FloatArray>>>()
        for (cmd in getCommands()) {
            val samples = mutableListOf<Array<FloatArray>>()
            for (i in 0 until cmd.sampleCount) {
                val mfcc = loadMfccCache(cmd.id, i)
                if (mfcc != null) samples.add(mfcc)
            }
            if (samples.isNotEmpty()) {
                result[cmd.id] = samples
            }
        }
        return result
    }

    private fun persist(commands: List<VoiceCommand>) {
        storageFile.writeText(gson.toJson(commands))
    }
}
