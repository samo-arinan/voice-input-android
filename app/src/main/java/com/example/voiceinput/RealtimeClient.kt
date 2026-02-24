package com.example.voiceinput

import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class RealtimeClient(
    private val httpClient: OkHttpClient,
    private val listener: Listener,
    private val base64Encoder: (ByteArray) -> String = {
        android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP)
    }
) {

    interface Listener {
        fun onTextDelta(text: String)
        fun onTextDone(text: String)
        fun onSpeechStarted()
        fun onSpeechStopped()
        fun onTranscriptionCompleted(transcript: String)
        fun onResponseDone()
        fun onError(message: String)
        fun onSessionReady()
    }

    private val gson = Gson()
    @Volatile
    private var webSocket: WebSocket? = null
    @Volatile
    private var instructions: String = ""

    @Volatile
    var isConnected: Boolean = false
        private set

    fun connect(apiKey: String, model: String) {
        webSocket?.close(1000, "Reconnecting")

        val request = Request.Builder()
            .url("wss://api.openai.com/v1/realtime?model=$model")
            .header("Authorization", "Bearer $apiKey")
            .header("OpenAI-Beta", "realtime=v1")
            .build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                val sessionUpdate = RealtimeEvent.sessionUpdate(
                    instructions = instructions,
                    vadThreshold = 0.5f,
                    silenceDurationMs = 500,
                    transcriptionModel = "whisper-1"
                )
                webSocket.send(gson.toJson(sessionUpdate))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val event = RealtimeEvent.parseServerEvent(text)
                    when (event.type) {
                        "session.updated" -> listener.onSessionReady()
                        "response.output_text.delta" -> listener.onTextDelta(event.delta ?: "")
                        "response.output_text.done" -> listener.onTextDone(event.text ?: "")
                        "input_audio_buffer.speech_started" -> listener.onSpeechStarted()
                        "input_audio_buffer.speech_stopped" -> listener.onSpeechStopped()
                        "conversation.item.input_audio_transcription.completed" ->
                            listener.onTranscriptionCompleted(event.transcript ?: "")
                        "response.done" -> listener.onResponseDone()
                        "error" -> listener.onError(event.errorMessage ?: "Unknown error")
                    }
                } catch (e: Exception) {
                    listener.onError("Failed to process message: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                listener.onError(t.message ?: "Unknown error")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
            }
        })
    }

    fun setInstructions(instructions: String) {
        this.instructions = instructions
    }

    fun sendAudio(pcmData: ByteArray) {
        val base64 = base64Encoder(pcmData)
        val event = RealtimeEvent.audioAppend(base64)
        webSocket?.send(gson.toJson(event))
    }

    fun commitAudio() {
        val event = RealtimeEvent.audioCommit()
        webSocket?.send(gson.toJson(event))
    }

    fun disconnect() {
        val ws = webSocket
        webSocket = null
        isConnected = false
        ws?.close(1000, "Client disconnected")
    }
}
