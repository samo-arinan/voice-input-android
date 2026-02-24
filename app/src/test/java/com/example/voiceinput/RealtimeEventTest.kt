package com.example.voiceinput

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class RealtimeEventTest {

    private val gson = Gson()

    // --- sessionUpdate ---

    @Test
    fun `sessionUpdate has correct type`() {
        val event = RealtimeEvent.sessionUpdate(
            instructions = "You are a helpful assistant.",
            vadThreshold = 0.5f,
            silenceDurationMs = 500,
            transcriptionModel = "whisper-1"
        )
        assertEquals("session.update", event["type"])
    }

    @Test
    fun `sessionUpdate sets modalities to text only`() {
        val event = RealtimeEvent.sessionUpdate(
            instructions = "test",
            vadThreshold = 0.5f,
            silenceDurationMs = 500,
            transcriptionModel = "whisper-1"
        )
        @Suppress("UNCHECKED_CAST")
        val session = event["session"] as Map<String, Any>
        assertEquals(listOf("text"), session["modalities"])
    }

    @Test
    fun `sessionUpdate sets instructions in session`() {
        val event = RealtimeEvent.sessionUpdate(
            instructions = "Convert speech to commands.",
            vadThreshold = 0.5f,
            silenceDurationMs = 500,
            transcriptionModel = "whisper-1"
        )
        @Suppress("UNCHECKED_CAST")
        val session = event["session"] as Map<String, Any>
        assertEquals("Convert speech to commands.", session["instructions"])
    }

    @Test
    fun `sessionUpdate configures server_vad turn detection`() {
        val event = RealtimeEvent.sessionUpdate(
            instructions = "test",
            vadThreshold = 0.7f,
            silenceDurationMs = 300,
            transcriptionModel = "whisper-1"
        )
        @Suppress("UNCHECKED_CAST")
        val session = event["session"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val turnDetection = session["turn_detection"] as Map<String, Any>
        assertEquals("server_vad", turnDetection["type"])
        assertEquals(0.7f, (turnDetection["threshold"] as Number).toFloat(), 0.001f)
        assertEquals(300, (turnDetection["silence_duration_ms"] as Number).toInt())
    }

    @Test
    fun `sessionUpdate configures input audio transcription`() {
        val event = RealtimeEvent.sessionUpdate(
            instructions = "test",
            vadThreshold = 0.5f,
            silenceDurationMs = 500,
            transcriptionModel = "whisper-1"
        )
        @Suppress("UNCHECKED_CAST")
        val session = event["session"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val transcription = session["input_audio_transcription"] as Map<String, Any>
        assertEquals("whisper-1", transcription["model"])
    }

    @Test
    fun `sessionUpdate serializes to valid JSON`() {
        val event = RealtimeEvent.sessionUpdate(
            instructions = "test",
            vadThreshold = 0.5f,
            silenceDurationMs = 500,
            transcriptionModel = "whisper-1"
        )
        val json = gson.toJson(event)
        assertTrue(json.contains("\"type\":\"session.update\""))
        assertTrue(json.contains("\"modalities\":[\"text\"]"))
    }

    // --- audioAppend ---

    @Test
    fun `audioAppend has correct type`() {
        val event = RealtimeEvent.audioAppend("SGVsbG8=")
        assertEquals("input_audio_buffer.append", event["type"])
    }

    @Test
    fun `audioAppend contains base64 audio`() {
        val event = RealtimeEvent.audioAppend("SGVsbG8=")
        assertEquals("SGVsbG8=", event["audio"])
    }

    @Test
    fun `audioAppend serializes to valid JSON`() {
        val event = RealtimeEvent.audioAppend("dGVzdA==")
        val json = gson.toJson(event)
        assertTrue(json.contains("\"type\":\"input_audio_buffer.append\""))
        // Parse back to verify audio value (Gson may HTML-escape '=' in raw string)
        val parsed = gson.fromJson(json, Map::class.java)
        assertEquals("dGVzdA==", parsed["audio"])
    }

    // --- audioCommit ---

    @Test
    fun `audioCommit has correct type`() {
        val event = RealtimeEvent.audioCommit()
        assertEquals("input_audio_buffer.commit", event["type"])
    }

    @Test
    fun `audioCommit has only type field`() {
        val event = RealtimeEvent.audioCommit()
        assertEquals(1, event.size)
    }

    // --- parseServerEvent ---

    @Test
    fun `parseServerEvent handles session created`() {
        val json = """{"type":"session.created","session":{"id":"sess_123"}}"""
        val event = RealtimeEvent.parseServerEvent(json)
        assertEquals("session.created", event.type)
    }

    @Test
    fun `parseServerEvent handles session updated`() {
        val json = """{"type":"session.updated","session":{"id":"sess_123"}}"""
        val event = RealtimeEvent.parseServerEvent(json)
        assertEquals("session.updated", event.type)
    }

    @Test
    fun `parseServerEvent extracts delta from response output text delta`() {
        val json = """{"type":"response.output_text.delta","delta":"Hello"}"""
        val event = RealtimeEvent.parseServerEvent(json)
        assertEquals("response.output_text.delta", event.type)
        assertEquals("Hello", event.delta)
    }

    @Test
    fun `parseServerEvent extracts text from response output text done`() {
        val json = """{"type":"response.output_text.done","text":"Hello world"}"""
        val event = RealtimeEvent.parseServerEvent(json)
        assertEquals("response.output_text.done", event.type)
        assertEquals("Hello world", event.text)
    }

    @Test
    fun `parseServerEvent extracts transcript from transcription completed`() {
        val json = """{"type":"conversation.item.input_audio_transcription.completed","transcript":"testing one two three"}"""
        val event = RealtimeEvent.parseServerEvent(json)
        assertEquals("conversation.item.input_audio_transcription.completed", event.type)
        assertEquals("testing one two three", event.transcript)
    }

    @Test
    fun `parseServerEvent extracts delta from transcription delta`() {
        val json = """{"type":"conversation.item.input_audio_transcription.delta","delta":"test"}"""
        val event = RealtimeEvent.parseServerEvent(json)
        assertEquals("conversation.item.input_audio_transcription.delta", event.type)
        assertEquals("test", event.delta)
    }

    @Test
    fun `parseServerEvent extracts audio_start_ms from speech started`() {
        val json = """{"type":"input_audio_buffer.speech_started","audio_start_ms":1500}"""
        val event = RealtimeEvent.parseServerEvent(json)
        assertEquals("input_audio_buffer.speech_started", event.type)
        assertEquals(1500, event.audioStartMs)
    }

    @Test
    fun `parseServerEvent extracts audio_end_ms from speech stopped`() {
        val json = """{"type":"input_audio_buffer.speech_stopped","audio_end_ms":3200}"""
        val event = RealtimeEvent.parseServerEvent(json)
        assertEquals("input_audio_buffer.speech_stopped", event.type)
        assertEquals(3200, event.audioEndMs)
    }

    @Test
    fun `parseServerEvent handles response done`() {
        val json = """{"type":"response.done","response":{"id":"resp_123","status":"completed"}}"""
        val event = RealtimeEvent.parseServerEvent(json)
        assertEquals("response.done", event.type)
    }

    @Test
    fun `parseServerEvent extracts error details`() {
        val json = """{"type":"error","error":{"type":"invalid_request_error","code":"invalid_value","message":"Invalid session config"}}"""
        val event = RealtimeEvent.parseServerEvent(json)
        assertEquals("error", event.type)
        assertEquals("invalid_request_error", event.errorType)
        assertEquals("invalid_value", event.errorCode)
        assertEquals("Invalid session config", event.errorMessage)
    }

    @Test
    fun `parseServerEvent extracts delta from beta response text delta`() {
        val json = """{"type":"response.text.delta","delta":"Hi"}"""
        val event = RealtimeEvent.parseServerEvent(json)
        assertEquals("response.text.delta", event.type)
        assertEquals("Hi", event.delta)
    }

    @Test
    fun `parseServerEvent extracts text from beta response text done`() {
        val json = """{"type":"response.text.done","text":"Hello there"}"""
        val event = RealtimeEvent.parseServerEvent(json)
        assertEquals("response.text.done", event.type)
        assertEquals("Hello there", event.text)
    }

    @Test
    fun `parseServerEvent handles unknown event type gracefully`() {
        val json = """{"type":"some.unknown.event","data":"whatever"}"""
        val event = RealtimeEvent.parseServerEvent(json)
        assertEquals("some.unknown.event", event.type)
        assertNull(event.delta)
        assertNull(event.text)
        assertNull(event.transcript)
    }

    @Test
    fun `parseServerEvent returns null fields for events without optional data`() {
        val json = """{"type":"session.created","session":{}}"""
        val event = RealtimeEvent.parseServerEvent(json)
        assertNull(event.delta)
        assertNull(event.text)
        assertNull(event.transcript)
        assertNull(event.audioStartMs)
        assertNull(event.audioEndMs)
        assertNull(event.errorType)
        assertNull(event.errorCode)
        assertNull(event.errorMessage)
    }

    @Test
    fun `parseServerEvent handles error with missing fields`() {
        val json = """{"type":"error","error":{"type":"server_error"}}"""
        val event = RealtimeEvent.parseServerEvent(json)
        assertEquals("error", event.type)
        assertEquals("server_error", event.errorType)
        assertNull(event.errorCode)
        assertNull(event.errorMessage)
    }

    @Test
    fun `parseServerEvent handles malformed json`() {
        val event = RealtimeEvent.parseServerEvent("not json at all")
        assertEquals("error", event.type)
        assertNotNull(event.errorMessage)
    }
}
