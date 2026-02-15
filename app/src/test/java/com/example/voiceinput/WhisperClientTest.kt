package com.example.voiceinput

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class WhisperClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: WhisperClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = WhisperClient(
            apiKey = "sk-test",
            baseUrl = server.url("/").toString()
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `transcribe sends audio file and returns text`() {
        server.enqueue(
            MockResponse()
                .setBody("""{"text": "こんにちは世界"}""")
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
        )

        val audioFile = File.createTempFile("test_audio", ".wav").apply {
            writeBytes(ByteArray(100))
            deleteOnExit()
        }

        val result = client.transcribe(audioFile)

        assertEquals("こんにちは世界", result)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.path!!.contains("audio/transcriptions"))
        assertTrue(request.getHeader("Authorization")!!.contains("sk-test"))
    }

    @Test
    fun `transcribe uses specified model in request`() {
        val customClient = WhisperClient(
            apiKey = "sk-test",
            baseUrl = server.url("/").toString(),
            model = "whisper-1"
        )

        server.enqueue(
            MockResponse()
                .setBody("""{"text": "テスト"}""")
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
        )

        val audioFile = File.createTempFile("test_audio", ".wav").apply {
            writeBytes(ByteArray(100))
            deleteOnExit()
        }

        customClient.transcribe(audioFile)

        val request = server.takeRequest()
        val body = request.body.readUtf8()
        assertTrue(body.contains("whisper-1"))
    }

    @Test
    fun `transcribe returns null on API error`() {
        server.enqueue(MockResponse().setResponseCode(500))

        val audioFile = File.createTempFile("test_audio", ".wav").apply {
            writeBytes(ByteArray(100))
            deleteOnExit()
        }

        val result = client.transcribe(audioFile)
        assertNull(result)
    }

    @Test
    fun `transcribe sends temperature 0 by default`() {
        server.enqueue(
            MockResponse()
                .setBody("""{"text": "テスト"}""")
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
        )

        val audioFile = File.createTempFile("test_audio", ".wav").apply {
            writeBytes(ByteArray(100))
            deleteOnExit()
        }

        client.transcribe(audioFile)

        val request = server.takeRequest()
        val body = request.body.readUtf8()
        assertTrue("Request body should contain temperature field", body.contains("temperature"))
        assertTrue("Request body should contain temperature value 0", body.contains("\r\n0\r\n"))
    }

    @Test
    fun `transcribe sends prompt when provided`() {
        server.enqueue(
            MockResponse()
                .setBody("""{"text": "テスト"}""")
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
        )

        val audioFile = File.createTempFile("test_audio", ".wav").apply {
            writeBytes(ByteArray(100))
            deleteOnExit()
        }

        client.transcribe(audioFile, prompt = "前回の文脈です")

        val request = server.takeRequest()
        val body = request.body.readUtf8()
        assertTrue("Request body should contain prompt field", body.contains("prompt"))
        assertTrue("Request body should contain prompt text", body.contains("前回の文脈です"))
    }
}
