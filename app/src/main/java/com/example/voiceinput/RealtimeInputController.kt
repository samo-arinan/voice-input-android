package com.example.voiceinput

import okhttp3.OkHttpClient

class RealtimeInputController(
    private val httpClient: OkHttpClient,
    private val postToMain: (() -> Unit) -> Unit,
    internal val createClient: (OkHttpClient, RealtimeClient.Listener) -> RealtimeClient = { http, listener ->
        RealtimeClient(http, listener)
    },
    internal val createStreamer: ((ByteArray) -> Unit) -> RealtimeAudioStreamer = { onChunk ->
        RealtimeAudioStreamer(onChunk)
    }
) {
    interface Callback {
        fun onStateChanged(state: State)
        fun onComposingText(text: String)
        fun onCommitText(text: String)
        fun onError(message: String)
    }

    enum class State { IDLE, CONNECTING, RECORDING, PROCESSING }

    var callback: Callback? = null

    var state: State = State.IDLE
        private set

    val amplitude: Float
        get() = streamer?.currentRms ?: 0f

    private var client: RealtimeClient? = null
    private var streamer: RealtimeAudioStreamer? = null
    private val composing = StringBuilder()
    private var pendingStop = false

    fun start(apiKey: String, model: String, instructions: String) {
        if (state != State.IDLE) return

        composing.clear()
        pendingStop = false
        setState(State.CONNECTING)

        val audioStreamer = createStreamer { pcmChunk ->
            client?.sendAudio(pcmChunk)
        }
        streamer = audioStreamer

        val realtimeClient = createClient(httpClient, object : RealtimeClient.Listener {
            override fun onSessionReady() {
                postToMain {
                    if (audioStreamer.start()) {
                        setState(State.RECORDING)
                    } else {
                        callback?.onError("録音を開始できません")
                        cleanup()
                    }
                }
            }

            override fun onTextDelta(text: String) {
                postToMain {
                    composing.append(text)
                    callback?.onComposingText(composing.toString())
                }
            }

            override fun onTextDone(text: String) {
                postToMain {
                    composing.clear()
                    callback?.onCommitText(text)
                }
            }

            override fun onSpeechStarted() {}

            override fun onSpeechStopped() {
                audioStreamer.pauseStreaming()
                postToMain {
                    audioStreamer.stop()
                    setState(State.PROCESSING)
                }
            }

            override fun onTranscriptionCompleted(transcript: String) {}

            override fun onResponseDone() {
                postToMain {
                    if (pendingStop) {
                        pendingStop = false
                        cleanup()
                    } else {
                        val s = streamer
                        if (s != null && s.start()) {
                            setState(State.RECORDING)
                        } else {
                            cleanup()
                        }
                    }
                }
            }

            override fun onError(message: String) {
                postToMain {
                    callback?.onError(message)
                    cleanup()
                }
            }
        })

        realtimeClient.setInstructions(instructions)
        realtimeClient.connect(apiKey, model)
        client = realtimeClient
    }

    fun stop() {
        when (state) {
            State.RECORDING -> {
                streamer?.pauseStreaming()
                streamer?.stop()
                client?.commitAudio()
                pendingStop = true
                setState(State.PROCESSING)
            }
            State.PROCESSING -> {
                pendingStop = true
            }
            else -> {}
        }
    }

    fun cleanup() {
        pendingStop = false
        streamer?.pauseStreaming()
        streamer?.stop()
        streamer = null
        client?.disconnect()
        client = null
        composing.clear()
        setState(State.IDLE)
    }

    private fun setState(newState: State) {
        state = newState
        callback?.onStateChanged(newState)
    }
}
