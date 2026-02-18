package com.example.voiceinput

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class NtfyListener(
    private val topic: String,
    private val onNotification: (String) -> Unit
) {
    private var eventSource: EventSource? = null
    private var startTime: Long = 0
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    fun start() {
        if (topic.isBlank()) return
        startTime = System.currentTimeMillis() / 1000
        val url = "https://ntfy.sh/$topic/sse"
        Log.d("NtfyListener", "Connecting to $url (startTime=$startTime)")

        val request = Request.Builder()
            .url(url)
            .build()

        val factory = EventSources.createFactory(client)
        eventSource = factory.newEventSource(request, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                Log.d("NtfyListener", "SSE onEvent: type=$type")
                // ntfy.sh sends no "event:" line for messages, so type is null/empty
                if (type.isNullOrEmpty() || type == "message") {
                    // Ignore cached messages from before connection
                    val msgTime = try {
                        JSONObject(data).optLong("time", 0)
                    } catch (_: Exception) { 0L }
                    if (msgTime >= startTime) {
                        onNotification(data)
                    } else {
                        Log.d("NtfyListener", "Ignoring old message: time=$msgTime < startTime=$startTime")
                    }
                }
            }
            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                Log.e("NtfyListener", "SSE onFailure: ${t?.message}, response=${response?.code}")
                // Reconnect after delay
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    stop()
                    start()
                }, 5000)
            }
        })
    }

    fun stop() {
        eventSource?.cancel()
        eventSource = null
    }
}
