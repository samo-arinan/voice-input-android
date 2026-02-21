package com.example.voiceinput

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class VisionClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: VisionClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = VisionClient(
            apiKey = "test-key",
            baseUrl = server.url("/").toString()
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `sends image and question and returns response`() {
        server.enqueue(MockResponse().setBody("""
            {
                "choices": [{
                    "message": {
                        "content": "This is a login screen with an error message."
                    }
                }]
            }
        """))

        val result = client.ask("aW1hZ2VkYXRh", "What is shown in this image?")

        assertEquals("This is a login screen with an error message.", result)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.path!!.endsWith("v1/chat/completions"))
        assertEquals("Bearer test-key", request.getHeader("Authorization"))

        val body = request.body.readUtf8()
        assertTrue(body.contains("gpt-4o"))
        assertTrue(body.contains("aW1hZ2VkYXRh"))
        assertTrue(body.contains("What is shown in this image?"))
    }

    @Test
    fun `returns null on HTTP error`() {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = client.ask("aW1hZ2VkYXRh", "question")

        assertNull(result)
    }

    @Test
    fun `uses default question when question is null`() {
        server.enqueue(MockResponse().setBody("""
            {
                "choices": [{
                    "message": {
                        "content": "A screenshot of a web page."
                    }
                }]
            }
        """))

        val result = client.ask("aW1hZ2VkYXRh", null)

        assertEquals("A screenshot of a web page.", result)

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("この画像について説明してください"))
    }

    @Test
    fun `request body contains correct image_url format`() {
        server.enqueue(MockResponse().setBody("""
            {"choices": [{"message": {"content": "ok"}}]}
        """))

        client.ask("dGVzdA==", "test")

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("data:image/png;base64,dGVzdA=="))
        assertTrue(body.contains("image_url"))
    }
}
