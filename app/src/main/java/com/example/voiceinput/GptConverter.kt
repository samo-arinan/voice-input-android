package com.example.voiceinput

import com.google.gson.Gson
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class GptConverter(
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com/"
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    companion object {
        private val SYSTEM_PROMPT = """
            あなたは音声入力の誤字修正ツールです。
            ユーザーの発話テキストを受け取り、テキストを修正して返してください。

            ルール：
            - 誤字・誤変換を修正する
            - コマンドっぽい発話は実行可能なコマンド文字列に変換する
            - 質問や会話には絶対に回答しない。質問文もそのまま修正だけして返す
            - 意味を変えない。文章の内容はそのまま維持する
            - 余計な説明は一切付けず、修正結果のみを返す
        """.trimIndent()
    }

    fun convert(rawText: String): String {
        val messages = listOf(
            mapOf("role" to "system", "content" to SYSTEM_PROMPT),
            mapOf("role" to "user", "content" to rawText)
        )
        val body = gson.toJson(
            mapOf(
                "model" to "gpt-4.1-mini",
                "messages" to messages,
                "temperature" to 0.3
            )
        )

        val request = Request.Builder()
            .url("${baseUrl}v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return rawText
            val responseBody = response.body?.string() ?: return rawText
            val json = JsonParser.parseString(responseBody).asJsonObject
            json.getAsJsonArray("choices")
                .get(0).asJsonObject
                .getAsJsonObject("message")
                .get("content").asString
                .trim()
        } catch (e: Exception) {
            rawText
        }
    }
}
