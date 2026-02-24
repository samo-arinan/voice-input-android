package com.example.voiceinput

import android.util.Log
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

    companion object {
        private const val TAG = "RealtimeClient"
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
                Log.d(TAG, "WebSocket opened")
                isConnected = true
                val sessionUpdate = RealtimeEvent.sessionUpdate(
                    instructions = instructions,
                    vadThreshold = 0.5f,
                    silenceDurationMs = 1200,
                    transcriptionModel = "whisper-1"
                )
                val json = gson.toJson(sessionUpdate)
                Log.d(TAG, "Sending session.update: ${json.take(200)}")
                webSocket.send(json)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val event = RealtimeEvent.parseServerEvent(text)
                    Log.d(TAG, "Event: ${event.type}" +
                        (event.delta?.let { " delta=${it.take(50)}" } ?: "") +
                        (event.text?.let { " text=${it.take(50)}" } ?: "") +
                        (event.errorMessage?.let { " err=$it" } ?: ""))
                    when (event.type) {
                        "session.updated" -> listener.onSessionReady()
                        "response.output_text.delta",
                        "response.text.delta" -> listener.onTextDelta(event.delta ?: "")
                        "response.output_text.done",
                        "response.text.done" -> listener.onTextDone(event.text ?: "")
                        "input_audio_buffer.speech_started" -> listener.onSpeechStarted()
                        "input_audio_buffer.speech_stopped" -> listener.onSpeechStopped()
                        "conversation.item.input_audio_transcription.completed" ->
                            listener.onTranscriptionCompleted(event.transcript ?: "")
                        "response.done" -> listener.onResponseDone()
                        "error" -> listener.onError(event.errorMessage ?: "Unknown error")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process message", e)
                    listener.onError("Failed to process message: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                isConnected = false
                listener.onError(t.message ?: "Unknown error")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: code=$code reason=$reason")
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
