package com.example.voiceinput

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

class NtfyListener(
    private val topic: String,
    private val onNotification: (String) -> Unit
) {
    private var eventSource: EventSource? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    fun start() {
        if (topic.isBlank()) return
        val request = Request.Builder()
            .url("https://ntfy.sh/$topic/sse")
            .build()

        val factory = EventSources.createFactory(client)
        eventSource = factory.newEventSource(request, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                onNotification(data)
            }
            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
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
