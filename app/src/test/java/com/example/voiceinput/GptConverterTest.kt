package com.example.voiceinput

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import com.google.gson.Gson
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
    fun `getCandidates returns list of alternatives`() {
        val candidatesJson = """["天気", "天機", "転機"]"""
        val responseBody = """
            {
                "choices": [{
                    "message": {
                        "content": ${Gson().toJson(candidatesJson)}
                    }
                }]
            }
        """.trimIndent()
        server.enqueue(
            MockResponse()
                .setBody(responseBody)
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
        )

        val result = converter.getCandidates("てんき")

        assertEquals(3, result.size)
        assertEquals("天気", result[0])
        assertEquals("天機", result[1])
        assertEquals("転機", result[2])

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("てんき"))
        assertTrue(body.contains("候補"))
    }

    @Test
    fun `getCandidates returns empty list on API error`() {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = converter.getCandidates("テスト")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `getCandidates returns empty list on invalid JSON`() {
        server.enqueue(
            MockResponse()
                .setBody(chatResponse("not a json array"))
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
        )

        val result = converter.getCandidates("テスト")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `convertWithHistory includes correction history in system prompt`() {
        server.enqueue(
            MockResponse()
                .setBody(chatResponse("おはようございます"))
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
        )

        val corrections = listOf(
            CorrectionEntry("おはよう御座います", "おはようございます", 5),
            CorrectionEntry("宜しく", "よろしく", 3)
        )

        val result = converter.convertWithHistory("おはよう御座います", corrections)

        assertEquals("おはようございます", result)

        val body = server.takeRequest().body.readUtf8()
        assertTrue("Should contain correction original", body.contains("おはよう御座います"))
        assertTrue("Should contain correction target", body.contains("おはようございます"))
        assertTrue("Should contain frequency", body.contains("5"))
    }

    @Test
    fun `convertWithHistory with empty corrections behaves like convert`() {
        server.enqueue(
            MockResponse()
                .setBody(chatResponse("テスト結果"))
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
        )

        val result = converter.convertWithHistory("テスト結果", emptyList())
        assertEquals("テスト結果", result)
    }

    @Test
    fun `convertWithHistory returns original on API error`() {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = converter.convertWithHistory("テスト", listOf(
            CorrectionEntry("A", "B", 1)
        ))
        assertEquals("テスト", result)
    }

    @Test
    fun `convert includes system prompt with text correction focus`() {
        server.enqueue(
            MockResponse()
                .setBody(chatResponse("ls -la"))
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
        )

        converter.convert("ファイル一覧を表示して")

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("誤字"))
        assertTrue(body.contains("回答"))
    }
}
