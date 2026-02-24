package com.example.voiceinput

import com.google.gson.Gson
import com.google.gson.JsonObject

data class ServerEvent(
    val type: String,
    val delta: String? = null,
    val text: String? = null,
    val transcript: String? = null,
    val audioStartMs: Int? = null,
    val audioEndMs: Int? = null,
    val errorType: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null
)

object RealtimeEvent {

    private val gson = Gson()

    fun sessionUpdate(
        instructions: String,
        vadThreshold: Float,
        silenceDurationMs: Int,
        transcriptionModel: String
    ): Map<String, Any> {
        return mapOf(
            "type" to "session.update",
            "session" to mapOf(
                "instructions" to instructions,
                "output_modalities" to listOf("text"),
                "turn_detection" to mapOf(
                    "type" to "server_vad",
                    "threshold" to vadThreshold,
                    "silence_duration_ms" to silenceDurationMs
                ),
                "input_audio_transcription" to mapOf(
                    "model" to transcriptionModel
                )
            )
        )
    }

    fun audioAppend(base64Audio: String): Map<String, Any> {
        return mapOf(
            "type" to "input_audio_buffer.append",
            "audio" to base64Audio
        )
    }

    fun audioCommit(): Map<String, Any> {
        return mapOf(
            "type" to "input_audio_buffer.commit"
        )
    }

    fun parseServerEvent(json: String): ServerEvent {
        val obj = gson.fromJson(json, JsonObject::class.java)
        val type = obj.get("type")?.asString ?: "unknown"

        return when (type) {
            "response.output_text.delta",
            "conversation.item.input_audio_transcription.delta" -> {
                ServerEvent(
                    type = type,
                    delta = obj.get("delta")?.asString
                )
            }

            "response.output_text.done" -> {
                ServerEvent(
                    type = type,
                    text = obj.get("text")?.asString
                )
            }

            "conversation.item.input_audio_transcription.completed" -> {
                ServerEvent(
                    type = type,
                    transcript = obj.get("transcript")?.asString
                )
            }

            "input_audio_buffer.speech_started" -> {
                ServerEvent(
                    type = type,
                    audioStartMs = obj.get("audio_start_ms")?.asInt
                )
            }

            "input_audio_buffer.speech_stopped" -> {
                ServerEvent(
                    type = type,
                    audioEndMs = obj.get("audio_end_ms")?.asInt
                )
            }

            "error" -> {
                val error = obj.getAsJsonObject("error")
                ServerEvent(
                    type = type,
                    errorType = error?.get("type")?.asString,
                    errorCode = if (error?.has("code") == true && !error.get("code").isJsonNull) error.get("code").asString else null,
                    errorMessage = if (error?.has("message") == true && !error.get("message").isJsonNull) error.get("message").asString else null
                )
            }

            else -> ServerEvent(type = type)
        }
    }
}
