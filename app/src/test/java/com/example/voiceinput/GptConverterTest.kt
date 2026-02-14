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
        return """
        {
            "choices": [{
                "message": {
                    "content": "$content"
                }
            }]
        }
        """.trimIndent()
    }

    @Test
    fun `convert sends text and returns converted result`() {
        server.enqueue(
            MockResponse()
                .setBody(chatResponse("git status"))
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
        )

        val result = converter.convert("ギットステータス")

        assertEquals("git status", result)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.path!!.contains("chat/completions"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("ギットステータス"))
        assertTrue(body.contains("gpt-4.1-mini"))
    }

    @Test
    fun `convert returns original text on API error`() {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = converter.convert("テスト入力")
        assertEquals("テスト入力", result)
    }

    @Test
    fun `convert includes system prompt with conversion rules`() {
        server.enqueue(
            MockResponse()
                .setBody(chatResponse("ls -la"))
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
        )

        converter.convert("ファイル一覧を表示して")

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("音声入力アシスタント"))
        assertTrue(body.contains("コマンド"))
    }
}
