package com.example.voiceinput

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GptConverterTest {

    private lateinit var server: MockWebServer
    private lateinit var converter: GptConverter

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        converter = GptConverter(
            apiKey = "sk-test",
            baseUrl = server.url("/").toString()
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun chatResponse(content: String): String {
        val escaped = content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return """
        {
            "choices": [{
                "message": {
                    "content": "$escaped"
                }
            }]
        }
        """.trimIndent()
    }

    @Test
    fun `convertToChunks returns chunks with raw and converted`() {
        val jsonArray = """[{"raw":"ギット","converted":"git"},{"raw":"ステータス","converted":"status"}]"""
        server.enqueue(
            MockResponse()
                .setBody(chatResponse(jsonArray))
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
        )

        val chunks = converter.convertToChunks("ギットステータス")

        assertEquals(2, chunks.size)
        assertEquals("ギット", chunks[0].raw)
        assertEquals("git", chunks[0].converted)
        assertEquals("ステータス", chunks[1].raw)
        assertEquals("status", chunks[1].converted)
    }

    @Test
    fun `convertToChunks returns single chunk on API error`() {
        server.enqueue(MockResponse().setResponseCode(500))

        val chunks = converter.convertToChunks("テスト入力")

        assertEquals(1, chunks.size)
        assertEquals("テスト入力", chunks[0].raw)
        assertEquals("テスト入力", chunks[0].converted)
    }

    @Test
    fun `convertToChunks returns single chunk when response is not JSON array`() {
        server.enqueue(
            MockResponse()
                .setBody(chatResponse("git status"))
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
        )

        val chunks = converter.convertToChunks("ギットステータス")

        assertEquals(1, chunks.size)
        assertEquals("ギットステータス", chunks[0].raw)
        assertEquals("git status", chunks[0].converted)
    }

    @Test
    fun `convertToChunks sends correct prompt requesting JSON`() {
        val jsonArray = """[{"raw":"テスト","converted":"テスト"}]"""
        server.enqueue(
            MockResponse()
                .setBody(chatResponse(jsonArray))
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
        )

        converter.convertToChunks("テスト")

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("JSON"))
        assertTrue(body.contains("raw"))
        assertTrue(body.contains("converted"))
    }
}
