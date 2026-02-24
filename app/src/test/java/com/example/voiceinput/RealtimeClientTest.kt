package com.example.voiceinput

import com.google.gson.Gson
import io.mockk.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RealtimeClientTest {

    private lateinit var mockOkHttpClient: OkHttpClient
    private lateinit var mockWebSocket: WebSocket
    private lateinit var capturedListener: CapturingSlot<WebSocketListener>
    private lateinit var capturedRequest: CapturingSlot<Request>
    private lateinit var listener: RealtimeClient.Listener
    private lateinit var client: RealtimeClient
    private val gson = Gson()

    @Before
    fun setUp() {
        mockOkHttpClient = mockk()
        mockWebSocket = mockk(relaxed = true)
        capturedListener = slot()
        capturedRequest = slot()
        listener = mockk(relaxed = true)

        every {
            mockOkHttpClient.newWebSocket(capture(capturedRequest), capture(capturedListener))
        } returns mockWebSocket

        client = RealtimeClient(mockOkHttpClient, listener) {
            java.util.Base64.getEncoder().encodeToString(it)
        }
    }

    // --- connect ---

    @Test
    fun `connect creates WebSocket with correct URL and headers`() {
        client.connect("sk-test-key", "gpt-4o-realtime-preview")

        val request = capturedRequest.captured
        // OkHttp converts wss:// to https:// internally in HttpUrl
        assertEquals(
            "https://api.openai.com/v1/realtime?model=gpt-4o-realtime-preview",
            request.url.toString()
        )
        assertEquals("Bearer sk-test-key", request.header("Authorization"))
        assertEquals("realtime=v1", request.header("OpenAI-Beta"))
    }

    // --- onOpen sends session.update ---

    @Test
    fun `onOpen sends session update event`() {
        val sentJson = slot<String>()
        every { mockWebSocket.send(capture(sentJson)) } returns true

        client.connect("sk-test-key", "gpt-4o-realtime-preview")
        capturedListener.captured.onOpen(mockWebSocket, mockk(relaxed = true))

        val parsed = gson.fromJson(sentJson.captured, Map::class.java)
        assertEquals("session.update", parsed["type"])
    }

    // --- onMessage routes to listener ---

    @Test
    fun `onMessage text delta calls listener onTextDelta`() {
        client.connect("sk-test-key", "gpt-4o-realtime-preview")
        val wsListener = capturedListener.captured

        val json = """{"type":"response.output_text.delta","delta":"Hello"}"""
        wsListener.onMessage(mockWebSocket, json)

        verify { listener.onTextDelta("Hello") }
    }

    @Test
    fun `onMessage text done calls listener onTextDone`() {
        client.connect("sk-test-key", "gpt-4o-realtime-preview")
        val wsListener = capturedListener.captured

        val json = """{"type":"response.output_text.done","text":"Hello world"}"""
        wsListener.onMessage(mockWebSocket, json)

        verify { listener.onTextDone("Hello world") }
    }

    @Test
    fun `onMessage speech started calls listener onSpeechStarted`() {
        client.connect("sk-test-key", "gpt-4o-realtime-preview")
        val wsListener = capturedListener.captured

        val json = """{"type":"input_audio_buffer.speech_started","audio_start_ms":1500}"""
        wsListener.onMessage(mockWebSocket, json)

        verify { listener.onSpeechStarted() }
    }

    @Test
    fun `onMessage speech stopped calls listener onSpeechStopped`() {
        client.connect("sk-test-key", "gpt-4o-realtime-preview")
        val wsListener = capturedListener.captured

        val json = """{"type":"input_audio_buffer.speech_stopped","audio_end_ms":3200}"""
        wsListener.onMessage(mockWebSocket, json)

        verify { listener.onSpeechStopped() }
    }

    @Test
    fun `onMessage transcription completed calls listener onTranscriptionCompleted`() {
        client.connect("sk-test-key", "gpt-4o-realtime-preview")
        val wsListener = capturedListener.captured

        val json = """{"type":"conversation.item.input_audio_transcription.completed","transcript":"testing one two three"}"""
        wsListener.onMessage(mockWebSocket, json)

        verify { listener.onTranscriptionCompleted("testing one two three") }
    }

    @Test
    fun `onMessage response done calls listener onResponseDone`() {
        client.connect("sk-test-key", "gpt-4o-realtime-preview")
        val wsListener = capturedListener.captured

        val json = """{"type":"response.done","response":{"id":"resp_123","status":"completed"}}"""
        wsListener.onMessage(mockWebSocket, json)

        verify { listener.onResponseDone() }
    }

    @Test
    fun `onMessage error calls listener onError`() {
        client.connect("sk-test-key", "gpt-4o-realtime-preview")
        val wsListener = capturedListener.captured

        val json = """{"type":"error","error":{"type":"invalid_request_error","code":"invalid_value","message":"Invalid session config"}}"""
        wsListener.onMessage(mockWebSocket, json)

        verify { listener.onError("Invalid session config") }
    }

    @Test
    fun `onMessage session updated calls listener onSessionReady`() {
        client.connect("sk-test-key", "gpt-4o-realtime-preview")
        val wsListener = capturedListener.captured

        val json = """{"type":"session.updated","session":{"id":"sess_123"}}"""
        wsListener.onMessage(mockWebSocket, json)

        verify { listener.onSessionReady() }
    }

    // --- sendAudio ---

    @Test
    fun `sendAudio sends base64 encoded PCM data`() {
        val sentJson = slot<String>()
        every { mockWebSocket.send(capture(sentJson)) } returns true

        client.connect("sk-test-key", "gpt-4o-realtime-preview")
        // Trigger onOpen to set isConnected
        capturedListener.captured.onOpen(mockWebSocket, mockk(relaxed = true))

        // Clear previous captures (session.update was sent on open)
        clearMocks(mockWebSocket, answers = false)
        every { mockWebSocket.send(capture(sentJson)) } returns true

        val pcmData = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        client.sendAudio(pcmData)

        val parsed = gson.fromJson(sentJson.captured, Map::class.java)
        assertEquals("input_audio_buffer.append", parsed["type"])

        val expectedBase64 = java.util.Base64.getEncoder().encodeToString(pcmData)
        assertEquals(expectedBase64, parsed["audio"])
    }

    // --- commitAudio ---

    @Test
    fun `commitAudio sends input_audio_buffer commit`() {
        val sentJson = slot<String>()
        every { mockWebSocket.send(capture(sentJson)) } returns true

        client.connect("sk-test-key", "gpt-4o-realtime-preview")
        capturedListener.captured.onOpen(mockWebSocket, mockk(relaxed = true))

        clearMocks(mockWebSocket, answers = false)
        every { mockWebSocket.send(capture(sentJson)) } returns true

        client.commitAudio()

        val parsed = gson.fromJson(sentJson.captured, Map::class.java)
        assertEquals("input_audio_buffer.commit", parsed["type"])
    }

    // --- disconnect ---

    @Test
    fun `disconnect closes WebSocket with code 1000`() {
        client.connect("sk-test-key", "gpt-4o-realtime-preview")
        capturedListener.captured.onOpen(mockWebSocket, mockk(relaxed = true))

        client.disconnect()

        verify { mockWebSocket.close(1000, any()) }
    }

    // --- isConnected ---

    @Test
    fun `isConnected is false before connect`() {
        assertFalse(client.isConnected)
    }

    @Test
    fun `isConnected is true after onOpen`() {
        client.connect("sk-test-key", "gpt-4o-realtime-preview")
        capturedListener.captured.onOpen(mockWebSocket, mockk(relaxed = true))

        assertTrue(client.isConnected)
    }

    @Test
    fun `isConnected is false after onClosed`() {
        client.connect("sk-test-key", "gpt-4o-realtime-preview")
        capturedListener.captured.onOpen(mockWebSocket, mockk(relaxed = true))
        assertTrue(client.isConnected)

        capturedListener.captured.onClosed(mockWebSocket, 1000, "Normal closure")
        assertFalse(client.isConnected)
    }

    // --- onFailure ---

    @Test
    fun `onFailure sets isConnected to false and calls listener onError`() {
        client.connect("sk-test-key", "gpt-4o-realtime-preview")
        capturedListener.captured.onOpen(mockWebSocket, mockk(relaxed = true))
        assertTrue(client.isConnected)

        val exception = Exception("Connection lost")
        capturedListener.captured.onFailure(mockWebSocket, exception, null)

        assertFalse(client.isConnected)
        verify { listener.onError("Connection lost") }
    }
}
