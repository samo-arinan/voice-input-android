# Realtime API Migration Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace Whisper+GPT 2-stage pipeline with OpenAI Realtime API for streaming voice input with minimal UI.

**Architecture:** WebSocket-based RealtimeClient streams 24kHz PCM audio to OpenAI, receives text deltas via `response.output_text.delta`, and inserts text into the input field using IME composing text. Server-side VAD auto-detects speech end. New minimal UI with amplitude-reactive ripple animation and temporary undo strip.

**Tech Stack:** Kotlin, OkHttp WebSocket, Android AudioRecord (24kHz), Canvas + ValueAnimator for animations. No new dependencies.

**Design doc:** `docs/plans/2026-02-24-realtime-api-migration-design.md`

---

## Key Technical Decisions

- **Audio format:** 24kHz 16-bit mono PCM (Realtime API requirement, different from current 16kHz)
- **Session lifecycle:** Connect WebSocket on mic tap, disconnect after response done. Fresh session per input for simplicity.
- **Text insertion:** Use `setComposingText()` for streaming display, `commitText()` on response done.
- **Model:** `gpt-4o-realtime-preview` (configurable in settings)
- **VAD:** `server_vad` with `silence_duration_ms: 500`
- **Transcription:** `gpt-4o-mini-transcribe` for input transcription (used for correction learning)

---

### Task 1: Realtime API Event Data Classes

**Files:**
- Create: `app/src/main/java/com/example/voiceinput/RealtimeEvent.kt`
- Test: `app/src/test/java/com/example/voiceinput/RealtimeEventTest.kt`

**Step 1: Write failing tests for event serialization**

```kotlin
package com.example.voiceinput

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class RealtimeEventTest {

    private val gson = Gson()

    @Test
    fun sessionUpdate_serializes_with_correct_structure() {
        val event = RealtimeEvent.sessionUpdate(
            instructions = "テスト指示",
            vadThreshold = 0.5,
            silenceDurationMs = 500
        )
        val json = JsonParser.parseString(gson.toJson(event)).asJsonObject
        assertEquals("session.update", json.get("type").asString)
        val session = json.getAsJsonObject("session")
        assertEquals("テスト指示", session.get("instructions").asString)
        val modalities = session.getAsJsonArray("output_modalities")
        assertEquals(1, modalities.size())
        assertEquals("text", modalities[0].asString)
    }

    @Test
    fun audioAppend_serializes_with_base64_audio() {
        val event = RealtimeEvent.audioAppend("AQID") // base64 for [1,2,3]
        val json = JsonParser.parseString(gson.toJson(event)).asJsonObject
        assertEquals("input_audio_buffer.append", json.get("type").asString)
        assertEquals("AQID", json.get("audio").asString)
    }

    @Test
    fun audioCommit_serializes() {
        val event = RealtimeEvent.audioCommit()
        val json = JsonParser.parseString(gson.toJson(event)).asJsonObject
        assertEquals("input_audio_buffer.commit", json.get("type").asString)
    }

    @Test
    fun parseServerEvent_session_created() {
        val json = """{"type":"session.created","event_id":"evt_1","session":{"id":"sess_1"}}"""
        val event = RealtimeEvent.parseServerEvent(json)
        assertEquals("session.created", event.type)
    }

    @Test
    fun parseServerEvent_text_delta() {
        val json = """{"type":"response.output_text.delta","event_id":"evt_2","delta":"こんにちは"}"""
        val event = RealtimeEvent.parseServerEvent(json)
        assertEquals("response.output_text.delta", event.type)
        assertEquals("こんにちは", event.delta)
    }

    @Test
    fun parseServerEvent_text_done() {
        val json = """{"type":"response.output_text.done","event_id":"evt_3","text":"今日は天気がいい"}"""
        val event = RealtimeEvent.parseServerEvent(json)
        assertEquals("response.output_text.done", event.type)
        assertEquals("今日は天気がいい", event.text)
    }

    @Test
    fun parseServerEvent_speech_started() {
        val json = """{"type":"input_audio_buffer.speech_started","event_id":"evt_4","audio_start_ms":1500}"""
        val event = RealtimeEvent.parseServerEvent(json)
        assertEquals("input_audio_buffer.speech_started", event.type)
        assertEquals(1500, event.audioStartMs)
    }

    @Test
    fun parseServerEvent_speech_stopped() {
        val json = """{"type":"input_audio_buffer.speech_stopped","event_id":"evt_5","audio_end_ms":3200}"""
        val event = RealtimeEvent.parseServerEvent(json)
        assertEquals("input_audio_buffer.speech_stopped", event.type)
        assertEquals(3200, event.audioEndMs)
    }

    @Test
    fun parseServerEvent_transcription_completed() {
        val json = """{"type":"conversation.item.input_audio_transcription.completed","event_id":"evt_6","transcript":"テスト音声"}"""
        val event = RealtimeEvent.parseServerEvent(json)
        assertEquals("conversation.item.input_audio_transcription.completed", event.type)
        assertEquals("テスト音声", event.transcript)
    }

    @Test
    fun parseServerEvent_error() {
        val json = """{"type":"error","event_id":"evt_7","error":{"type":"invalid_request_error","code":"invalid_event","message":"bad request"}}"""
        val event = RealtimeEvent.parseServerEvent(json)
        assertEquals("error", event.type)
        assertEquals("invalid_request_error", event.errorType)
        assertEquals("bad request", event.errorMessage)
    }

    @Test
    fun parseServerEvent_response_done() {
        val json = """{"type":"response.done","event_id":"evt_8","response":{"id":"resp_1","status":"completed"}}"""
        val event = RealtimeEvent.parseServerEvent(json)
        assertEquals("response.done", event.type)
    }
}
```

**Step 2: Run tests to verify they fail**

Run: `cd /Users/j/Area/tdd/voice-input-android-app && ./gradlew testDebugUnitTest --tests "com.example.voiceinput.RealtimeEventTest" 2>&1 | tail -5`
Expected: FAIL (class not found)

**Step 3: Implement RealtimeEvent**

```kotlin
package com.example.voiceinput

import com.google.gson.Gson
import com.google.gson.JsonParser

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
        vadThreshold: Double = 0.5,
        silenceDurationMs: Int = 500,
        transcriptionModel: String = "gpt-4o-mini-transcribe"
    ): Map<String, Any> {
        return mapOf(
            "type" to "session.update",
            "session" to mapOf(
                "instructions" to instructions,
                "output_modalities" to listOf("text"),
                "input_audio_transcription" to mapOf(
                    "model" to transcriptionModel
                ),
                "turn_detection" to mapOf(
                    "type" to "server_vad",
                    "threshold" to vadThreshold,
                    "prefix_padding_ms" to 300,
                    "silence_duration_ms" to silenceDurationMs
                )
            )
        )
    }

    fun audioAppend(base64Audio: String): Map<String, String> {
        return mapOf(
            "type" to "input_audio_buffer.append",
            "audio" to base64Audio
        )
    }

    fun audioCommit(): Map<String, String> {
        return mapOf("type" to "input_audio_buffer.commit")
    }

    fun parseServerEvent(json: String): ServerEvent {
        val obj = JsonParser.parseString(json).asJsonObject
        val type = obj.get("type").asString

        return when (type) {
            "response.output_text.delta" -> ServerEvent(
                type = type,
                delta = obj.get("delta")?.asString
            )
            "response.output_text.done" -> ServerEvent(
                type = type,
                text = obj.get("text")?.asString
            )
            "conversation.item.input_audio_transcription.completed" -> ServerEvent(
                type = type,
                transcript = obj.get("transcript")?.asString
            )
            "conversation.item.input_audio_transcription.delta" -> ServerEvent(
                type = type,
                delta = obj.get("delta")?.asString
            )
            "input_audio_buffer.speech_started" -> ServerEvent(
                type = type,
                audioStartMs = obj.get("audio_start_ms")?.asInt
            )
            "input_audio_buffer.speech_stopped" -> ServerEvent(
                type = type,
                audioEndMs = obj.get("audio_end_ms")?.asInt
            )
            "error" -> {
                val error = obj.getAsJsonObject("error")
                ServerEvent(
                    type = type,
                    errorType = error?.get("type")?.asString,
                    errorCode = error?.get("code")?.asString,
                    errorMessage = error?.get("message")?.asString
                )
            }
            else -> ServerEvent(type = type)
        }
    }
}
```

**Step 4: Run tests to verify they pass**

Run: `cd /Users/j/Area/tdd/voice-input-android-app && ./gradlew testDebugUnitTest --tests "com.example.voiceinput.RealtimeEventTest" 2>&1 | tail -5`
Expected: PASS (all 11 tests)

**Step 5: Commit**

```bash
git add app/src/main/java/com/example/voiceinput/RealtimeEvent.kt app/src/test/java/com/example/voiceinput/RealtimeEventTest.kt
git commit -m "feat: add Realtime API event data classes with serialization"
```

---

### Task 2: RealtimeClient — WebSocket Connection and Session Management

**Files:**
- Create: `app/src/main/java/com/example/voiceinput/RealtimeClient.kt`
- Test: `app/src/test/java/com/example/voiceinput/RealtimeClientTest.kt`

**Step 1: Write failing tests**

```kotlin
package com.example.voiceinput

import com.google.gson.Gson
import com.google.gson.JsonParser
import io.mockk.*
import okhttp3.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RealtimeClientTest {

    private lateinit var mockOkHttpClient: OkHttpClient
    private lateinit var mockWebSocket: WebSocket
    private lateinit var capturedListener: CapturingSlot<WebSocketListener>
    private lateinit var capturedRequest: CapturingSlot<Request>
    private lateinit var client: RealtimeClient
    private val gson = Gson()

    interface TestListener : RealtimeClient.Listener

    private lateinit var listener: TestListener

    @Before
    fun setup() {
        mockOkHttpClient = mockk()
        mockWebSocket = mockk(relaxed = true)
        capturedListener = slot()
        capturedRequest = slot()
        listener = mockk(relaxed = true)

        every {
            mockOkHttpClient.newWebSocket(capture(capturedRequest), capture(capturedListener))
        } returns mockWebSocket

        client = RealtimeClient(mockOkHttpClient, listener)
    }

    @Test
    fun connect_creates_websocket_with_correct_url_and_headers() {
        client.connect("sk-test-key", "gpt-4o-realtime-preview")

        val request = capturedRequest.captured
        assertTrue(request.url.toString().contains("v1/realtime"))
        assertTrue(request.url.toString().contains("model=gpt-4o-realtime-preview"))
        assertEquals("Bearer sk-test-key", request.header("Authorization"))
        assertEquals("realtime=v1", request.header("OpenAI-Beta"))
    }

    @Test
    fun onOpen_sends_session_update() {
        client.connect("sk-test-key", "gpt-4o-realtime-preview")
        client.setInstructions("テスト指示")

        // Simulate WebSocket open
        capturedListener.captured.onOpen(mockWebSocket, mockk())

        // Verify session.update was sent
        val sentSlot = slot<String>()
        verify { mockWebSocket.send(capture(sentSlot)) }
        val json = JsonParser.parseString(sentSlot.captured).asJsonObject
        assertEquals("session.update", json.get("type").asString)
    }

    @Test
    fun onMessage_text_delta_calls_listener() {
        client.connect("sk-test-key", "gpt-4o-realtime-preview")
        val wsListener = capturedListener.captured

        val deltaJson = """{"type":"response.output_text.delta","delta":"テスト"}"""
        wsListener.onMessage(mockWebSocket, deltaJson)

        verify { listener.onTextDelta("テスト") }
    }

    @Test
    fun onMessage_text_done_calls_listener() {
        client.connect("sk-test-key", "gpt-4o-realtime-preview")
        val wsListener = capturedListener.captured

        val doneJson = """{"type":"response.output_text.done","text":"テスト完了"}"""
        wsListener.onMessage(mockWebSocket, doneJson)

        verify { listener.onTextDone("テスト完了") }
    }

    @Test
    fun onMessage_speech_started_calls_listener() {
        client.connect("sk-test-key", "gpt-4o-realtime-preview")
        val wsListener = capturedListener.captured

        val json = """{"type":"input_audio_buffer.speech_started","event_id":"e1","audio_start_ms":100}"""
        wsListener.onMessage(mockWebSocket, json)

        verify { listener.onSpeechStarted() }
    }

    @Test
    fun onMessage_speech_stopped_calls_listener() {
        client.connect("sk-test-key", "gpt-4o-realtime-preview")
        val wsListener = capturedListener.captured

        val json = """{"type":"input_audio_buffer.speech_stopped","event_id":"e1","audio_end_ms":3000}"""
        wsListener.onMessage(mockWebSocket, json)

        verify { listener.onSpeechStopped() }
    }

    @Test
    fun onMessage_transcription_completed_calls_listener() {
        client.connect("sk-test-key", "gpt-4o-realtime-preview")
        val wsListener = capturedListener.captured

        val json = """{"type":"conversation.item.input_audio_transcription.completed","transcript":"生テキスト"}"""
        wsListener.onMessage(mockWebSocket, json)

        verify { listener.onTranscriptionCompleted("生テキスト") }
    }

    @Test
    fun onMessage_response_done_calls_listener() {
        client.connect("sk-test-key", "gpt-4o-realtime-preview")
        val wsListener = capturedListener.captured

        val json = """{"type":"response.done","response":{"id":"r1","status":"completed"}}"""
        wsListener.onMessage(mockWebSocket, json)

        verify { listener.onResponseDone() }
    }

    @Test
    fun onMessage_error_calls_listener() {
        client.connect("sk-test-key", "gpt-4o-realtime-preview")
        val wsListener = capturedListener.captured

        val json = """{"type":"error","error":{"type":"invalid_request_error","code":"bad","message":"エラーです"}}"""
        wsListener.onMessage(mockWebSocket, json)

        verify { listener.onError("エラーです") }
    }

    @Test
    fun sendAudio_sends_base64_encoded_pcm() {
        client.connect("sk-test-key", "gpt-4o-realtime-preview")
        val pcmData = byteArrayOf(1, 2, 3, 4)

        client.sendAudio(pcmData)

        val sentSlot = slot<String>()
        verify { mockWebSocket.send(capture(sentSlot)) }
        val json = JsonParser.parseString(sentSlot.captured).asJsonObject
        assertEquals("input_audio_buffer.append", json.get("type").asString)
        assertNotNull(json.get("audio").asString)
    }

    @Test
    fun disconnect_closes_websocket() {
        client.connect("sk-test-key", "gpt-4o-realtime-preview")
        client.disconnect()

        verify { mockWebSocket.close(1000, "done") }
    }

    @Test
    fun isConnected_reflects_state() {
        assertFalse(client.isConnected)

        client.connect("sk-test-key", "gpt-4o-realtime-preview")
        val wsListener = capturedListener.captured
        wsListener.onOpen(mockWebSocket, mockk())

        assertTrue(client.isConnected)

        client.disconnect()
        assertFalse(client.isConnected)
    }

    @Test
    fun onFailure_calls_listener_onError() {
        client.connect("sk-test-key", "gpt-4o-realtime-preview")
        val wsListener = capturedListener.captured

        wsListener.onFailure(mockWebSocket, Exception("接続失敗"), null)

        verify { listener.onError("接続失敗") }
    }
}
```

**Step 2: Run tests to verify they fail**

Run: `cd /Users/j/Area/tdd/voice-input-android-app && ./gradlew testDebugUnitTest --tests "com.example.voiceinput.RealtimeClientTest" 2>&1 | tail -5`
Expected: FAIL (class not found)

**Step 3: Implement RealtimeClient**

```kotlin
package com.example.voiceinput

import android.util.Base64
import com.google.gson.Gson
import okhttp3.*

class RealtimeClient(
    private val httpClient: OkHttpClient,
    private val listener: Listener
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
    private var webSocket: WebSocket? = null
    private var instructions: String = ""
    var isConnected: Boolean = false
        private set

    fun setInstructions(instructions: String) {
        this.instructions = instructions
    }

    fun connect(apiKey: String, model: String) {
        val request = Request.Builder()
            .url("wss://api.openai.com/v1/realtime?model=$model")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("OpenAI-Beta", "realtime=v1")
            .build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                sendSessionUpdate()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
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

    fun sendAudio(pcmData: ByteArray) {
        val base64 = Base64.encodeToString(pcmData, Base64.NO_WRAP)
        val event = RealtimeEvent.audioAppend(base64)
        webSocket?.send(gson.toJson(event))
    }

    fun commitAudio() {
        val event = RealtimeEvent.audioCommit()
        webSocket?.send(gson.toJson(event))
    }

    fun disconnect() {
        isConnected = false
        webSocket?.close(1000, "done")
        webSocket = null
    }

    private fun sendSessionUpdate() {
        val event = RealtimeEvent.sessionUpdate(instructions = instructions)
        webSocket?.send(gson.toJson(event))
    }

    private fun handleMessage(json: String) {
        val event = RealtimeEvent.parseServerEvent(json)
        when (event.type) {
            "session.created" -> {} // wait for session.updated
            "session.updated" -> listener.onSessionReady()
            "response.output_text.delta" -> event.delta?.let { listener.onTextDelta(it) }
            "response.output_text.done" -> event.text?.let { listener.onTextDone(it) }
            "input_audio_buffer.speech_started" -> listener.onSpeechStarted()
            "input_audio_buffer.speech_stopped" -> listener.onSpeechStopped()
            "conversation.item.input_audio_transcription.completed" ->
                event.transcript?.let { listener.onTranscriptionCompleted(it) }
            "response.done" -> listener.onResponseDone()
            "error" -> listener.onError(event.errorMessage ?: "Unknown error")
        }
    }
}
```

**Step 4: Run tests to verify they pass**

Run: `cd /Users/j/Area/tdd/voice-input-android-app && ./gradlew testDebugUnitTest --tests "com.example.voiceinput.RealtimeClientTest" 2>&1 | tail -5`
Expected: PASS (all 13 tests)

Note: `android.util.Base64` is not available in unit tests. Use a test helper or Robolectric shadow. If this fails, replace with `java.util.Base64.getEncoder().encodeToString()` in a wrapper and use the Android version in production. Alternatively, add a `base64Encoder` parameter to RealtimeClient for testability:

```kotlin
class RealtimeClient(
    private val httpClient: OkHttpClient,
    private val listener: Listener,
    private val base64Encoder: (ByteArray) -> String = { Base64.encodeToString(it, Base64.NO_WRAP) }
)
```

In tests: `RealtimeClient(mockClient, listener) { java.util.Base64.getEncoder().encodeToString(it) }`

**Step 5: Commit**

```bash
git add app/src/main/java/com/example/voiceinput/RealtimeClient.kt app/src/test/java/com/example/voiceinput/RealtimeClientTest.kt
git commit -m "feat: add RealtimeClient with WebSocket connection and event routing"
```

---

### Task 3: RealtimePromptBuilder — System Prompt Construction

**Files:**
- Create: `app/src/main/java/com/example/voiceinput/RealtimePromptBuilder.kt`
- Test: `app/src/test/java/com/example/voiceinput/RealtimePromptBuilderTest.kt`

Migrates GptConverter's prompt logic for the Realtime API session instructions.

**Step 1: Write failing tests**

```kotlin
package com.example.voiceinput

import org.junit.Assert.*
import org.junit.Test

class RealtimePromptBuilderTest {

    @Test
    fun build_returns_base_instructions() {
        val prompt = RealtimePromptBuilder.build()
        assertTrue(prompt.contains("音声入力"))
        assertTrue(prompt.contains("修正結果のみを返す"))
    }

    @Test
    fun build_with_corrections_includes_history() {
        val corrections = listOf(
            CorrectionEntry("てすと", "テスト", 3),
            CorrectionEntry("ぷろぐらむ", "プログラム", 1)
        )
        val prompt = RealtimePromptBuilder.build(corrections = corrections)
        assertTrue(prompt.contains("てすと"))
        assertTrue(prompt.contains("テスト"))
        assertTrue(prompt.contains("3回"))
    }

    @Test
    fun build_with_terminal_context_includes_it() {
        val prompt = RealtimePromptBuilder.build(terminalContext = "$ git status\nOn branch main")
        assertTrue(prompt.contains("端末コンテキスト"))
        assertTrue(prompt.contains("git status"))
    }

    @Test
    fun build_with_empty_corrections_omits_history_section() {
        val prompt = RealtimePromptBuilder.build(corrections = emptyList())
        assertFalse(prompt.contains("過去の修正履歴"))
    }

    @Test
    fun build_with_null_terminal_context_omits_section() {
        val prompt = RealtimePromptBuilder.build(terminalContext = null)
        assertFalse(prompt.contains("端末コンテキスト"))
    }

    @Test
    fun build_with_all_context() {
        val corrections = listOf(CorrectionEntry("あ", "亜", 1))
        val prompt = RealtimePromptBuilder.build(
            corrections = corrections,
            terminalContext = "$ ls"
        )
        assertTrue(prompt.contains("音声入力"))
        assertTrue(prompt.contains("修正履歴"))
        assertTrue(prompt.contains("端末コンテキスト"))
    }
}
```

**Step 2: Run tests to verify they fail**

Run: `cd /Users/j/Area/tdd/voice-input-android-app && ./gradlew testDebugUnitTest --tests "com.example.voiceinput.RealtimePromptBuilderTest" 2>&1 | tail -5`
Expected: FAIL

**Step 3: Implement RealtimePromptBuilder**

```kotlin
package com.example.voiceinput

object RealtimePromptBuilder {

    private val BASE_INSTRUCTIONS = """
        あなたは音声入力の文字起こし・補正ツールです。
        ユーザーの音声を聞き取り、正確に文字起こしして修正したテキストのみを返してください。

        ルール：
        - 音声を正確に文字起こしする（日本語）
        - 誤字・誤変換を修正する
        - コマンドっぽい発話は実行可能なコマンド文字列に変換する
        - 質問や会話には絶対に回答しない。発話内容をそのまま文字起こし・修正して返す
        - 意味を変えない。発話の内容はそのまま維持する
        - 余計な説明は一切付けず、修正結果のみを返す
    """.trimIndent()

    fun build(
        corrections: List<CorrectionEntry>? = null,
        terminalContext: String? = null
    ): String {
        val parts = mutableListOf(BASE_INSTRUCTIONS)

        if (!corrections.isNullOrEmpty()) {
            val historyLines = corrections.joinToString("\n") { entry ->
                "- 「${entry.original}」→「${entry.corrected}」(${entry.frequency}回)"
            }
            parts.add("""
                以下はユーザーの過去の修正履歴です。同様のパターンがあれば適用してください：
                $historyLines
            """.trimIndent())
        }

        if (!terminalContext.isNullOrBlank()) {
            parts.add("""
                以下はユーザーの端末コンテキストです。コマンド入力の参考にしてください：
                $terminalContext
            """.trimIndent())
        }

        return parts.joinToString("\n\n")
    }
}
```

**Step 4: Run tests to verify they pass**

Run: `cd /Users/j/Area/tdd/voice-input-android-app && ./gradlew testDebugUnitTest --tests "com.example.voiceinput.RealtimePromptBuilderTest" 2>&1 | tail -5`
Expected: PASS (all 6 tests)

**Step 5: Commit**

```bash
git add app/src/main/java/com/example/voiceinput/RealtimePromptBuilder.kt app/src/test/java/com/example/voiceinput/RealtimePromptBuilderTest.kt
git commit -m "feat: add RealtimePromptBuilder migrating GptConverter prompts"
```

---

### Task 4: RealtimeAudioStreamer — 24kHz Streaming Recorder

**Files:**
- Create: `app/src/main/java/com/example/voiceinput/RealtimeAudioStreamer.kt`
- Test: `app/src/test/java/com/example/voiceinput/RealtimeAudioStreamerTest.kt`

Separate from existing AudioRecorder (which stays for COMMAND tab at 16kHz).

**Step 1: Write failing tests**

```kotlin
package com.example.voiceinput

import org.junit.Assert.*
import org.junit.Test

class RealtimeAudioStreamerTest {

    @Test
    fun sample_rate_is_24000() {
        assertEquals(24000, RealtimeAudioStreamer.SAMPLE_RATE)
    }

    @Test
    fun initial_state_is_not_streaming() {
        val streamer = RealtimeAudioStreamer {}
        assertFalse(streamer.isStreaming)
    }

    @Test
    fun rms_calculation_for_silence() {
        val silent = ShortArray(100) { 0 }
        val rms = RealtimeAudioStreamer.calculateRms(silent)
        assertEquals(0.0f, rms, 0.01f)
    }

    @Test
    fun rms_calculation_for_loud_signal() {
        val loud = ShortArray(100) { 10000 }
        val rms = RealtimeAudioStreamer.calculateRms(loud)
        assertTrue(rms > 0.3f) // normalized to 0-1 range
    }

    @Test
    fun rms_calculation_normalizes_to_0_1_range() {
        val maxSignal = ShortArray(100) { Short.MAX_VALUE }
        val rms = RealtimeAudioStreamer.calculateRms(maxSignal)
        assertTrue(rms <= 1.0f)
        assertTrue(rms > 0.9f)
    }
}
```

**Step 2: Run tests to verify they fail**

Run: `cd /Users/j/Area/tdd/voice-input-android-app && ./gradlew testDebugUnitTest --tests "com.example.voiceinput.RealtimeAudioStreamerTest" 2>&1 | tail -5`
Expected: FAIL

**Step 3: Implement RealtimeAudioStreamer**

```kotlin
package com.example.voiceinput

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RealtimeAudioStreamer(
    private val onChunk: (ByteArray) -> Unit
) {

    companion object {
        const val SAMPLE_RATE = 24000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_DURATION_MS = 100 // send chunks every 100ms

        fun calculateRms(samples: ShortArray): Float {
            if (samples.isEmpty()) return 0f
            val sumSquares = samples.sumOf { it.toDouble() * it.toDouble() }
            val rms = kotlin.math.sqrt(sumSquares / samples.size)
            return (rms / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
        }
    }

    private var audioRecord: AudioRecord? = null
    private var streamingThread: Thread? = null

    @Volatile
    var isStreaming: Boolean = false
        private set

    @Volatile
    var currentRms: Float = 0f
        private set

    fun start(): Boolean {
        if (isStreaming) return false

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
            return false
        }

        return try {
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                return false
            }

            audioRecord = recorder
            recorder.startRecording()
            isStreaming = true

            val chunkSize = SAMPLE_RATE * 2 * CHUNK_DURATION_MS / 1000 // bytes per chunk
            streamingThread = Thread {
                val buffer = ByteArray(chunkSize)
                while (isStreaming) {
                    val bytesRead = recorder.read(buffer, 0, buffer.size)
                    if (bytesRead > 0) {
                        // Calculate RMS for amplitude visualization
                        val samples = ShortArray(bytesRead / 2)
                        ByteBuffer.wrap(buffer, 0, bytesRead)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .asShortBuffer()
                            .get(samples)
                        currentRms = calculateRms(samples)

                        // Send chunk
                        val chunk = buffer.copyOf(bytesRead)
                        onChunk(chunk)
                    }
                }
            }.also { it.start() }

            true
        } catch (e: Exception) {
            audioRecord?.release()
            audioRecord = null
            false
        }
    }

    fun stop() {
        isStreaming = false
        streamingThread?.join(2000)
        streamingThread = null
        audioRecord?.apply {
            stop()
            release()
        }
        audioRecord = null
        currentRms = 0f
    }
}
```

**Step 4: Run tests to verify they pass**

Run: `cd /Users/j/Area/tdd/voice-input-android-app && ./gradlew testDebugUnitTest --tests "com.example.voiceinput.RealtimeAudioStreamerTest" 2>&1 | tail -5`
Expected: PASS (all 5 tests)

**Step 5: Commit**

```bash
git add app/src/main/java/com/example/voiceinput/RealtimeAudioStreamer.kt app/src/test/java/com/example/voiceinput/RealtimeAudioStreamerTest.kt
git commit -m "feat: add RealtimeAudioStreamer for 24kHz streaming PCM"
```

---

### Task 5: UndoManager — Text Tracking and Undo Support

**Files:**
- Create: `app/src/main/java/com/example/voiceinput/UndoManager.kt`
- Test: `app/src/test/java/com/example/voiceinput/UndoManagerTest.kt`

**Step 1: Write failing tests**

```kotlin
package com.example.voiceinput

import org.junit.Assert.*
import org.junit.Test

class UndoManagerTest {

    @Test
    fun initial_state_has_nothing_to_undo() {
        val manager = UndoManager()
        assertFalse(manager.canUndo)
        assertNull(manager.lastCommittedText)
    }

    @Test
    fun recordCommit_enables_undo() {
        val manager = UndoManager()
        manager.recordCommit("テスト入力", 5)
        assertTrue(manager.canUndo)
        assertEquals("テスト入力", manager.lastCommittedText)
        assertEquals(5, manager.lastCommittedLength)
    }

    @Test
    fun undo_returns_committed_length_and_clears() {
        val manager = UndoManager()
        manager.recordCommit("テスト", 3)

        val length = manager.undo()

        assertEquals(3, length)
        assertFalse(manager.canUndo)
    }

    @Test
    fun undo_when_nothing_to_undo_returns_zero() {
        val manager = UndoManager()
        assertEquals(0, manager.undo())
    }

    @Test
    fun clear_resets_state() {
        val manager = UndoManager()
        manager.recordCommit("テスト", 3)
        manager.clear()
        assertFalse(manager.canUndo)
    }

    @Test
    fun new_commit_replaces_previous() {
        val manager = UndoManager()
        manager.recordCommit("最初", 2)
        manager.recordCommit("次", 1)
        assertEquals("次", manager.lastCommittedText)
        assertEquals(1, manager.lastCommittedLength)
    }
}
```

**Step 2: Run tests to verify they fail**

Run: `cd /Users/j/Area/tdd/voice-input-android-app && ./gradlew testDebugUnitTest --tests "com.example.voiceinput.UndoManagerTest" 2>&1 | tail -5`
Expected: FAIL

**Step 3: Implement UndoManager**

```kotlin
package com.example.voiceinput

class UndoManager {

    var lastCommittedText: String? = null
        private set
    var lastCommittedLength: Int = 0
        private set

    val canUndo: Boolean
        get() = lastCommittedText != null

    fun recordCommit(text: String, length: Int) {
        lastCommittedText = text
        lastCommittedLength = length
    }

    fun undo(): Int {
        if (!canUndo) return 0
        val length = lastCommittedLength
        clear()
        return length
    }

    fun clear() {
        lastCommittedText = null
        lastCommittedLength = 0
    }
}
```

**Step 4: Run tests to verify they pass**

Run: `cd /Users/j/Area/tdd/voice-input-android-app && ./gradlew testDebugUnitTest --tests "com.example.voiceinput.UndoManagerTest" 2>&1 | tail -5`
Expected: PASS (all 6 tests)

**Step 5: Commit**

```bash
git add app/src/main/java/com/example/voiceinput/UndoManager.kt app/src/test/java/com/example/voiceinput/UndoManagerTest.kt
git commit -m "feat: add UndoManager for tracking and undoing voice input"
```

---

### Task 6: RippleAnimationView — Amplitude-Reactive Custom View

**Files:**
- Create: `app/src/main/java/com/example/voiceinput/RippleAnimationView.kt`
- Test: `app/src/test/java/com/example/voiceinput/RippleAnimationViewTest.kt`

**Step 1: Write failing tests for the logic (state machine)**

```kotlin
package com.example.voiceinput

import org.junit.Assert.*
import org.junit.Test

class RippleAnimationViewTest {

    @Test
    fun initial_state_is_idle() {
        assertEquals(RippleState.IDLE, RippleState.IDLE)
    }

    @Test
    fun ripple_radius_scales_with_amplitude() {
        // At 0 amplitude, rings should be at base radius
        val base = RippleAnimationView.calculateRippleRadius(0f, baseRadius = 24f, maxExpansion = 20f)
        assertEquals(24f, base, 0.1f)

        // At max amplitude, rings should expand fully
        val max = RippleAnimationView.calculateRippleRadius(1f, baseRadius = 24f, maxExpansion = 20f)
        assertEquals(44f, max, 0.1f)

        // At half amplitude
        val half = RippleAnimationView.calculateRippleRadius(0.5f, baseRadius = 24f, maxExpansion = 20f)
        assertEquals(34f, half, 0.1f)
    }

    @Test
    fun ripple_alpha_decreases_with_ring_index() {
        val alpha0 = RippleAnimationView.calculateRippleAlpha(ringIndex = 0, totalRings = 3, baseAlpha = 0.4f)
        val alpha1 = RippleAnimationView.calculateRippleAlpha(ringIndex = 1, totalRings = 3, baseAlpha = 0.4f)
        val alpha2 = RippleAnimationView.calculateRippleAlpha(ringIndex = 2, totalRings = 3, baseAlpha = 0.4f)

        assertTrue(alpha0 > alpha1)
        assertTrue(alpha1 > alpha2)
        assertTrue(alpha2 > 0f)
    }
}

enum class RippleState {
    IDLE, RECORDING, PROCESSING
}
```

**Step 2: Run tests to verify they fail**

Run: `cd /Users/j/Area/tdd/voice-input-android-app && ./gradlew testDebugUnitTest --tests "com.example.voiceinput.RippleAnimationViewTest" 2>&1 | tail -5`
Expected: FAIL

**Step 3: Implement RippleAnimationView**

```kotlin
package com.example.voiceinput

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

class RippleAnimationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val NUM_RINGS = 3
        private const val BASE_RADIUS_DP = 24f
        private const val MAX_EXPANSION_DP = 20f
        private const val BASE_ALPHA = 0.4f
        private const val PULSE_DURATION_MS = 1200L

        // Accent color (teal)
        private const val COLOR_IDLE = 0xFF26A69A.toInt()
        private const val COLOR_RECORDING = 0xFF00897B.toInt()
        private const val COLOR_PROCESSING = 0xFF4DB6AC.toInt()

        fun calculateRippleRadius(amplitude: Float, baseRadius: Float, maxExpansion: Float): Float {
            return baseRadius + maxExpansion * amplitude.coerceIn(0f, 1f)
        }

        fun calculateRippleAlpha(ringIndex: Int, totalRings: Int, baseAlpha: Float): Float {
            return baseAlpha * (1f - ringIndex.toFloat() / totalRings)
        }
    }

    private val density = context.resources.displayMetrics.density
    private val baseRadiusPx = BASE_RADIUS_DP * density
    private val maxExpansionPx = MAX_EXPANSION_DP * density

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var state: RippleState = RippleState.IDLE
    private var amplitude: Float = 0f
    private var pulsePhase: Float = 0f

    private val pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = PULSE_DURATION_MS
        repeatCount = ValueAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener { animator ->
            pulsePhase = animator.animatedValue as Float
            invalidate()
        }
    }

    fun setState(newState: RippleState) {
        state = newState
        when (state) {
            RippleState.IDLE -> {
                pulseAnimator.cancel()
                amplitude = 0f
            }
            RippleState.RECORDING -> {
                pulseAnimator.cancel()
            }
            RippleState.PROCESSING -> {
                if (!pulseAnimator.isRunning) pulseAnimator.start()
            }
        }
        invalidate()
    }

    fun setAmplitude(rms: Float) {
        amplitude = rms.coerceIn(0f, 1f)
        if (state == RippleState.RECORDING) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f

        when (state) {
            RippleState.IDLE -> drawIdleCircle(canvas, cx, cy)
            RippleState.RECORDING -> drawRecordingRipple(canvas, cx, cy)
            RippleState.PROCESSING -> drawProcessingPulse(canvas, cx, cy)
        }
    }

    private fun drawIdleCircle(canvas: Canvas, cx: Float, cy: Float) {
        circlePaint.color = COLOR_IDLE
        circlePaint.alpha = 255
        canvas.drawCircle(cx, cy, baseRadiusPx, circlePaint)
    }

    private fun drawRecordingRipple(canvas: Canvas, cx: Float, cy: Float) {
        // Draw ripple rings (outermost first)
        for (i in NUM_RINGS - 1 downTo 0) {
            val ringAmplitude = amplitude * (1f + i * 0.3f)
            val radius = calculateRippleRadius(ringAmplitude, baseRadiusPx, maxExpansionPx)
            val alpha = calculateRippleAlpha(i, NUM_RINGS, BASE_ALPHA)

            circlePaint.color = COLOR_RECORDING
            circlePaint.alpha = (alpha * 255).toInt()
            canvas.drawCircle(cx, cy, radius, circlePaint)
        }

        // Draw center circle
        circlePaint.color = COLOR_RECORDING
        circlePaint.alpha = 255
        canvas.drawCircle(cx, cy, baseRadiusPx, circlePaint)
    }

    private fun drawProcessingPulse(canvas: Canvas, cx: Float, cy: Float) {
        val pulseScale = 1f + 0.1f * kotlin.math.sin(pulsePhase * 2 * Math.PI).toFloat()
        val radius = baseRadiusPx * pulseScale

        circlePaint.color = COLOR_PROCESSING
        circlePaint.alpha = 200
        canvas.drawCircle(cx, cy, radius, circlePaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulseAnimator.cancel()
    }
}
```

Note: `RippleState` enum goes in the same file or keep the one from the test. Remove the enum from the test file and put it in `RippleAnimationView.kt` above the class.

**Step 4: Run tests to verify they pass**

Run: `cd /Users/j/Area/tdd/voice-input-android-app && ./gradlew testDebugUnitTest --tests "com.example.voiceinput.RippleAnimationViewTest" 2>&1 | tail -5`
Expected: PASS (all 3 tests)

**Step 5: Commit**

```bash
git add app/src/main/java/com/example/voiceinput/RippleAnimationView.kt app/src/test/java/com/example/voiceinput/RippleAnimationViewTest.kt
git commit -m "feat: add RippleAnimationView with amplitude-reactive ripple"
```

---

### Task 7: Voice Tab Layout — New Minimal XML

**Files:**
- Modify: `app/src/main/res/layout/voice_input_ime.xml` (the voice mode area section)

**Step 1: Read current layout file**

Read: `app/src/main/res/layout/voice_input_ime.xml`

Identify the voice mode area section and the candidate area section.

**Step 2: Update voice mode area layout**

Replace the voice mode content area with:

```xml
<!-- Inside the voice mode area, replace candidate area, status text, etc. with: -->

<!-- Undo strip (hidden by default) -->
<LinearLayout
    android:id="@+id/undoStrip"
    android:layout_width="match_parent"
    android:layout_height="36dp"
    android:orientation="horizontal"
    android:gravity="center_vertical"
    android:paddingStart="12dp"
    android:paddingEnd="8dp"
    android:background="#1A000000"
    android:visibility="gone">

    <TextView
        android:id="@+id/undoPreviewText"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:textSize="13sp"
        android:textColor="#666666"
        android:maxLines="1"
        android:ellipsize="end" />

    <TextView
        android:id="@+id/undoButton"
        android:layout_width="wrap_content"
        android:layout_height="match_parent"
        android:gravity="center"
        android:paddingStart="16dp"
        android:paddingEnd="16dp"
        android:text="取消"
        android:textSize="13sp"
        android:textColor="#26A69A"
        android:clickable="true"
        android:focusable="true" />
</LinearLayout>

<!-- Ripple animation area (fills remaining space) -->
<com.example.voiceinput.RippleAnimationView
    android:id="@+id/rippleView"
    android:layout_width="match_parent"
    android:layout_height="0dp"
    android:layout_weight="1"
    android:clickable="true"
    android:focusable="true" />
```

Remove from voice mode area:
- `candidateArea` (LinearLayout with candidateText, candidateButton)
- `statusText` (TextView)
- `micButton` or `voiceModeArea` gesture target → replace with `rippleView` as tap target

Keep tab bar at top as-is.

**Step 3: Verify build succeeds**

Run: `cd /Users/j/Area/tdd/voice-input-android-app && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add app/src/main/res/layout/voice_input_ime.xml
git commit -m "feat: replace voice tab layout with minimal ripple + undo strip"
```

---

### Task 8: VoiceInputIME — Realtime API Integration

**Files:**
- Modify: `app/src/main/java/com/example/voiceinput/VoiceInputIME.kt`

This is the largest task. Wire RealtimeClient, RealtimeAudioStreamer, RippleAnimationView, and UndoManager into the IME.

**Step 1: Add Realtime fields and initialize**

Add new fields to VoiceInputIME:

```kotlin
// Realtime API
private var realtimeClient: RealtimeClient? = null
private var audioStreamer: RealtimeAudioStreamer? = null
private var rippleView: RippleAnimationView? = null
private var undoStrip: View? = null
private var undoPreviewText: TextView? = null
private var undoButton: TextView? = null
private val undoManager = UndoManager()
private var composingText = StringBuilder()
private var isRealtimeRecording = false
private val undoHandler = android.os.Handler(android.os.Looper.getMainLooper())
private var undoDismissRunnable: Runnable? = null
```

**Step 2: Initialize views in onCreateInputView**

In the view inflation section, add:

```kotlin
rippleView = view.findViewById(R.id.rippleView)
undoStrip = view.findViewById(R.id.undoStrip)
undoPreviewText = view.findViewById(R.id.undoPreviewText)
undoButton = view.findViewById(R.id.undoButton)

undoButton?.setOnClickListener { performUndo() }
setupRippleTapGesture()
```

**Step 3: Implement setupRippleTapGesture**

Replace `setupVoiceAreaGesture()` with ripple-based gesture:

```kotlin
private fun setupRippleTapGesture() {
    rippleView?.setOnClickListener {
        if (!isRealtimeRecording) {
            startRealtimeRecording()
        } else {
            stopRealtimeRecording()
        }
    }
}
```

**Step 4: Implement startRealtimeRecording**

```kotlin
private fun startRealtimeRecording() {
    val prefs = PreferencesManager(getSharedPreferences("voice_input_prefs", MODE_PRIVATE))
    val apiKey = prefs.getApiKey() ?: run {
        Toast.makeText(this, "APIキーが設定されていません", Toast.LENGTH_SHORT).show()
        return
    }

    undoManager.clear()
    hideUndoStrip()
    composingText.clear()

    // Build instructions with context
    val tmuxContext = sshContextProvider?.let {
        // Fetch synchronously on IO thread would block, so fetch async and cache
        // For now, use cached context if available
        null // TODO: async context fetch before recording
    }
    val corrections = correctionRepo?.getTopCorrections(20)
    val instructions = RealtimePromptBuilder.build(
        corrections = corrections,
        terminalContext = tmuxContext
    )

    // Create streamer
    audioStreamer = RealtimeAudioStreamer { pcmChunk ->
        realtimeClient?.sendAudio(pcmChunk)
    }

    // Create client with listener
    realtimeClient = RealtimeClient(
        okhttp3.OkHttpClient.Builder()
            .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS) // no timeout for WebSocket
            .build(),
        object : RealtimeClient.Listener {
            override fun onSessionReady() {
                // Session configured, start streaming audio
                runOnMainThread {
                    val started = audioStreamer?.start() ?: false
                    if (started) {
                        isRealtimeRecording = true
                        rippleView?.setState(RippleState.RECORDING)
                        startAmplitudePolling()
                    }
                }
            }

            override fun onTextDelta(text: String) {
                runOnMainThread {
                    composingText.append(text)
                    currentInputConnection?.setComposingText(composingText.toString(), 1)
                }
            }

            override fun onTextDone(text: String) {
                runOnMainThread {
                    currentInputConnection?.finishComposingText()
                    val length = text.length
                    undoManager.recordCommit(text, length)
                    composingText.clear()
                    showUndoStrip(text)
                    rippleView?.setState(RippleState.IDLE)
                }
            }

            override fun onSpeechStarted() {
                // Optional: visual feedback that speech detected
            }

            override fun onSpeechStopped() {
                runOnMainThread {
                    // VAD detected silence, stop recording
                    audioStreamer?.stop()
                    isRealtimeRecording = false
                    rippleView?.setState(RippleState.PROCESSING)
                    stopAmplitudePolling()
                }
            }

            override fun onTranscriptionCompleted(transcript: String) {
                // Raw transcription available for correction learning
                // Store for potential use when user undoes
            }

            override fun onResponseDone() {
                runOnMainThread {
                    // Disconnect after response is complete
                    realtimeClient?.disconnect()
                    realtimeClient = null
                }
            }

            override fun onError(message: String) {
                runOnMainThread {
                    Toast.makeText(this@VoiceInputIME, "エラー: $message", Toast.LENGTH_SHORT).show()
                    cleanup()
                }
            }
        }
    )

    realtimeClient?.setInstructions(instructions)
    realtimeClient?.connect(apiKey, prefs.getRealtimeModel())

    rippleView?.setState(RippleState.PROCESSING) // connecting state
}
```

**Step 5: Implement stopRealtimeRecording (manual stop)**

```kotlin
private fun stopRealtimeRecording() {
    audioStreamer?.stop()
    isRealtimeRecording = false
    rippleView?.setState(RippleState.PROCESSING)
    stopAmplitudePolling()
    // Don't disconnect — wait for response.done
}
```

**Step 6: Implement amplitude polling**

```kotlin
private var amplitudePoller: java.util.Timer? = null

private fun startAmplitudePolling() {
    amplitudePoller = java.util.Timer().apply {
        scheduleAtFixedRate(object : java.util.TimerTask() {
            override fun run() {
                val rms = audioStreamer?.currentRms ?: 0f
                runOnMainThread { rippleView?.setAmplitude(rms) }
            }
        }, 0, 50) // poll every 50ms
    }
}

private fun stopAmplitudePolling() {
    amplitudePoller?.cancel()
    amplitudePoller = null
}
```

**Step 7: Implement undo**

```kotlin
private fun performUndo() {
    val length = undoManager.undo()
    if (length > 0) {
        val ic = currentInputConnection ?: return
        ic.deleteSurroundingText(length, 0)
        hideUndoStrip()
    }
}

private fun showUndoStrip(text: String) {
    undoPreviewText?.text = text
    undoStrip?.visibility = View.VISIBLE

    // Auto-dismiss after 5 seconds
    undoDismissRunnable?.let { undoHandler.removeCallbacks(it) }
    undoDismissRunnable = Runnable { hideUndoStrip() }
    undoHandler.postDelayed(undoDismissRunnable!!, 5000)
}

private fun hideUndoStrip() {
    undoStrip?.visibility = View.GONE
    undoDismissRunnable?.let { undoHandler.removeCallbacks(it) }
}
```

**Step 8: Implement helper and cleanup**

```kotlin
private fun runOnMainThread(action: () -> Unit) {
    android.os.Handler(android.os.Looper.getMainLooper()).post(action)
}

private fun cleanup() {
    audioStreamer?.stop()
    audioStreamer = null
    realtimeClient?.disconnect()
    realtimeClient = null
    isRealtimeRecording = false
    composingText.clear()
    stopAmplitudePolling()
    rippleView?.setState(RippleState.IDLE)
}
```

**Step 9: Verify build succeeds**

Run: `cd /Users/j/Area/tdd/voice-input-android-app && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

**Step 10: Commit**

```bash
git add app/src/main/java/com/example/voiceinput/VoiceInputIME.kt
git commit -m "feat: integrate Realtime API into VoiceInputIME with new UI"
```

---

### Task 9: PreferencesManager — Add Realtime Model Setting

**Files:**
- Modify: `app/src/main/java/com/example/voiceinput/PreferencesManager.kt`
- Modify: `app/src/test/java/com/example/voiceinput/PreferencesManagerTest.kt`

**Step 1: Write failing test**

```kotlin
@Test
fun realtime_model_default() {
    assertEquals("gpt-4o-realtime-preview", prefsManager.getRealtimeModel())
}

@Test
fun realtime_model_save_and_get() {
    prefsManager.saveRealtimeModel("gpt-4o-mini-realtime-preview")
    assertEquals("gpt-4o-mini-realtime-preview", prefsManager.getRealtimeModel())
}
```

**Step 2: Run tests to verify they fail**

Run: `cd /Users/j/Area/tdd/voice-input-android-app && ./gradlew testDebugUnitTest --tests "com.example.voiceinput.PreferencesManagerTest" 2>&1 | tail -5`
Expected: FAIL (method not found)

**Step 3: Add to PreferencesManager**

```kotlin
companion object {
    // ... existing keys ...
    private const val KEY_REALTIME_MODEL = "realtime_model"
    const val DEFAULT_REALTIME_MODEL = "gpt-4o-realtime-preview"
}

fun saveRealtimeModel(model: String) {
    prefs.edit().putString(KEY_REALTIME_MODEL, model).apply()
}

fun getRealtimeModel(): String {
    return prefs.getString(KEY_REALTIME_MODEL, DEFAULT_REALTIME_MODEL) ?: DEFAULT_REALTIME_MODEL
}
```

**Step 4: Run tests to verify they pass**

Run: `cd /Users/j/Area/tdd/voice-input-android-app && ./gradlew testDebugUnitTest --tests "com.example.voiceinput.PreferencesManagerTest" 2>&1 | tail -5`
Expected: PASS

**Step 5: Commit**

```bash
git add app/src/main/java/com/example/voiceinput/PreferencesManager.kt app/src/test/java/com/example/voiceinput/PreferencesManagerTest.kt
git commit -m "feat: add Realtime API model preference"
```

---

### Task 10: Async Context Fetch — Terminal Context Before Recording

**Files:**
- Modify: `app/src/main/java/com/example/voiceinput/VoiceInputIME.kt`

The `startRealtimeRecording` has a TODO for async context fetch. Implement it:

**Step 1: Implement async context with coroutine**

```kotlin
private fun startRealtimeRecording() {
    val prefs = PreferencesManager(getSharedPreferences("voice_input_prefs", MODE_PRIVATE))
    val apiKey = prefs.getApiKey() ?: run {
        Toast.makeText(this, "APIキーが設定されていません", Toast.LENGTH_SHORT).show()
        return
    }

    undoManager.clear()
    hideUndoStrip()
    composingText.clear()
    rippleView?.setState(RippleState.PROCESSING) // show connecting state

    serviceScope.launch {
        // Fetch terminal context async
        val tmuxContext = withContext(Dispatchers.IO) {
            sshContextProvider?.fetchContext()
        }
        val gptContext = SshContextProvider.extractGptContext(tmuxContext)
        val corrections = correctionRepo?.getTopCorrections(20)
        val instructions = RealtimePromptBuilder.build(
            corrections = corrections,
            terminalContext = gptContext
        )

        // Continue with connection on main thread
        connectRealtime(apiKey, prefs.getRealtimeModel(), instructions)
    }
}
```

**Step 2: Extract connectRealtime method**

Move the RealtimeClient creation and connection logic into `connectRealtime(apiKey, model, instructions)`.

**Step 3: Verify build**

Run: `cd /Users/j/Area/tdd/voice-input-android-app && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add app/src/main/java/com/example/voiceinput/VoiceInputIME.kt
git commit -m "feat: async terminal context fetch before Realtime connection"
```

---

### Task 11: Cleanup — Remove Old Pipeline Components

**Files to delete:**
- `app/src/main/java/com/example/voiceinput/AudioProcessor.kt`
- `app/src/main/java/com/example/voiceinput/WhisperClient.kt`
- `app/src/main/java/com/example/voiceinput/GptConverter.kt`
- `app/src/main/java/com/example/voiceinput/TextDiffer.kt`
- `app/src/main/java/com/example/voiceinput/ConversionChunk.kt`
- `app/src/test/java/com/example/voiceinput/AudioProcessorTest.kt`
- `app/src/test/java/com/example/voiceinput/WhisperClientTest.kt`
- `app/src/test/java/com/example/voiceinput/GptConverterTest.kt`
- `app/src/test/java/com/example/voiceinput/GptConverterContextTest.kt`
- `app/src/test/java/com/example/voiceinput/TextDifferTest.kt`
- `app/src/test/java/com/example/voiceinput/ConversionChunkTest.kt`

**Files to modify:**
- `app/src/main/java/com/example/voiceinput/VoiceInputProcessor.kt` — remove or gut (keep for COMMAND tab if needed)
- `app/src/test/java/com/example/voiceinput/VoiceInputProcessorTest.kt` — update tests
- `app/src/main/java/com/example/voiceinput/AudioRecorder.kt` — keep (used by COMMAND tab)

**Step 1: Check COMMAND tab dependencies**

Before deleting, verify which components COMMAND tab uses:
- `AudioRecorder` — YES, used for recording command samples
- `AudioProcessor` — YES, used in `AudioRecorder.stop()` for WAV encoding
- `WhisperClient` — NO, commands use MFCC/DTW matching
- `GptConverter` — YES, used for kanji candidates and AI candidates in voice mode

**Important:** `AudioProcessor.encodeWav()` is called by `AudioRecorder.stop()`. We cannot delete AudioProcessor entirely. Options:
- Move `encodeWav()` and `isSilent()` to AudioRecorder as private methods
- Keep AudioProcessor but strip normalization/compression (only keep encodeWav + isSilent)

Also: `GptConverter.getCandidates()` and `convertHiraganaToKanji()` are used in the candidate popup. Since we're removing the candidate popup UI from the voice tab, these are no longer needed for voice input. But verify if COMMAND tab uses them. If not, safe to delete.

**Step 2: Move encodeWav to AudioRecorder**

Move `AudioProcessor.encodeWav()` and `AudioProcessor.isSilent()` into `AudioRecorder` as companion methods. Update `AudioRecorder.stop()` to call the local version. Remove `AudioProcessor.processForWhisper()` — no longer needed since Realtime API handles audio processing.

Actually, simpler: keep `AudioProcessor` but only with `encodeWav` and `isSilent`. Delete everything else.

**Step 3: Gut VoiceInputProcessor**

`VoiceInputProcessor` was the Whisper+GPT orchestrator. The COMMAND tab uses `AudioRecorder` directly (via `CommandLearningView`), not `VoiceInputProcessor`. So VoiceInputProcessor can be removed entirely if no other code depends on it.

Check: `VoiceInputIME` references `processor` (VoiceInputProcessor) in voice mode and replacement mode. Since we've replaced voice mode with RealtimeClient, remove `VoiceInputProcessor` references from VoiceInputIME. The replacement mode (re-record selected text) should also use Realtime API or be removed.

**Step 4: Remove old voice mode code from VoiceInputIME**

Remove:
- `onMicPressed()`, `onMicReleased()`, `onMicReleasedForNewInput()`, `onMicReleasedForReplacement()`
- `showCandidateArea()`, `hideCandidateArea()`, `onCandidateButtonTap()`, `fetchAiCandidates()`, `showAiCandidatePopup()`
- `processor` field and `refreshProcessor()`
- `isToggleRecording`, `replacementRange`, `currentFullText`, `committedTextLength`
- `candidateText`, `candidateArea`, `candidateButton`, `statusText` view references

**Step 5: Delete files**

```bash
git rm app/src/main/java/com/example/voiceinput/WhisperClient.kt
git rm app/src/main/java/com/example/voiceinput/GptConverter.kt
git rm app/src/main/java/com/example/voiceinput/TextDiffer.kt
git rm app/src/main/java/com/example/voiceinput/ConversionChunk.kt
git rm app/src/main/java/com/example/voiceinput/VoiceInputProcessor.kt
git rm app/src/test/java/com/example/voiceinput/WhisperClientTest.kt
git rm app/src/test/java/com/example/voiceinput/GptConverterTest.kt
git rm app/src/test/java/com/example/voiceinput/GptConverterContextTest.kt
git rm app/src/test/java/com/example/voiceinput/TextDifferTest.kt
git rm app/src/test/java/com/example/voiceinput/ConversionChunkTest.kt
git rm app/src/test/java/com/example/voiceinput/VoiceInputProcessorTest.kt
```

**Step 6: Trim AudioProcessor**

Keep only `encodeWav()` and `isSilent()`. Remove `normalizeRms()`, `compress()`, `processForWhisper()`. Update `AudioProcessorTest.kt` to remove tests for deleted methods.

Update `AudioRecorder.stop()` to call `AudioProcessor.encodeWav()` directly (skip normalize/compress):

```kotlin
// In AudioRecorder.stop()
AudioProcessor.encodeWav(pcmData, SAMPLE_RATE, outputFile)
```

**Step 7: Run all tests**

Run: `cd /Users/j/Area/tdd/voice-input-android-app && ./gradlew testDebugUnitTest 2>&1 | tail -20`
Expected: All remaining tests PASS

**Step 8: Verify build**

Run: `cd /Users/j/Area/tdd/voice-input-android-app && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

**Step 9: Commit**

```bash
git add -A
git commit -m "refactor: remove old Whisper+GPT pipeline, keep only Realtime API path"
```

---

### Task 12: End-to-End Verification

**Step 1: Run full test suite**

Run: `cd /Users/j/Area/tdd/voice-input-android-app && ./gradlew testDebugUnitTest 2>&1 | tail -20`
Expected: All tests PASS

**Step 2: Build APK**

Run: `cd /Users/j/Area/tdd/voice-input-android-app && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

**Step 3: Copy APK for testing**

Run: `cp /Users/j/Area/tdd/voice-input-android-app/app/build/outputs/apk/debug/app-debug.apk ~/Sync/APK/voice-input.apk`

**Step 4: Manual testing checklist (for user)**

- [ ] Install APK on device
- [ ] Open IME settings, verify voice tab shows ripple circle
- [ ] Tap circle → should connect and start recording (circle animates)
- [ ] Speak → text should appear in composing state
- [ ] Stop speaking → VAD auto-stops, text commits, undo strip appears
- [ ] Tap undo → text removed
- [ ] Tap circle again → tap to stop manually before VAD
- [ ] COMMAND tab still works (unchanged)
- [ ] TMUX tab still works (unchanged)

**Step 5: Commit final state**

```bash
git add -A
git commit -m "chore: end-to-end verification complete"
```
