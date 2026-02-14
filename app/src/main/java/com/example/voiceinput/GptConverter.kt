package com.example.voiceinput

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
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
            あなたは音声入力アシスタントです。
            ユーザーの発話テキストを受け取り、意味のある単位に分割して変換してください。

            【最重要ルール】
            - 必ず複数のチャンクに分割すること。1チャンクにまとめるのは禁止
            - 文節（助詞を含む意味のまとまり）を目安に分割する
            - コマンドの意図なら、実行可能なコマンド文字列に変換する
            - 日本語の文章なら、自然な日本語としてそのまま返す

            【出力形式】
            必ずJSON配列で返す。各要素は {"raw": "元の部分", "converted": "変換後"} の形式。
            JSON配列以外の出力は禁止。

            【分割の例】
            入力: "ギットステータスを確認して"
            出力: [{"raw":"ギット","converted":"git"},{"raw":"ステータスを","converted":"status "},{"raw":"確認して","converted":"確認して"}]

            入力: "今日は天気がいいですね"
            出力: [{"raw":"今日は","converted":"今日は"},{"raw":"天気が","converted":"天気が"},{"raw":"いいですね","converted":"いいですね"}]

            入力: "ファイルいちらんをひょうじして"
            出力: [{"raw":"ファイル","converted":"ファイル"},{"raw":"いちらんを","converted":"一覧を"},{"raw":"ひょうじして","converted":"表示して"}]

            入力: "エーピーアイキーをせっていする"
            出力: [{"raw":"エーピーアイ","converted":"API"},{"raw":"キーを","converted":"キーを"},{"raw":"せっていする","converted":"設定する"}]
        """.trimIndent()
    }

    fun convert(rawText: String): String {
        val chunks = convertToChunks(rawText)
        return chunks.joinToString("") { it.converted }
    }

    fun convertToChunks(rawText: String): List<ConversionChunk> {
        val messages = listOf(
            mapOf("role" to "system", "content" to SYSTEM_PROMPT),
            mapOf("role" to "user", "content" to rawText)
        )
        val body = gson.toJson(
            mapOf(
                "model" to "gpt-4o",
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
            if (!response.isSuccessful) return listOf(ConversionChunk(rawText, rawText))
            val responseBody = response.body?.string() ?: return listOf(ConversionChunk(rawText, rawText))
            val json = JsonParser.parseString(responseBody).asJsonObject
            val content = json.getAsJsonArray("choices")
                .get(0).asJsonObject
                .getAsJsonObject("message")
                .get("content").asString
                .trim()

            parseChunks(rawText, content)
        } catch (e: Exception) {
            listOf(ConversionChunk(rawText, rawText))
        }
    }

    private fun parseChunks(rawText: String, content: String): List<ConversionChunk> {
        return try {
            val type = object : TypeToken<List<Map<String, String>>>() {}.type
            val items: List<Map<String, String>> = gson.fromJson(content, type)
            items.map { item ->
                ConversionChunk(
                    raw = item["raw"] ?: "",
                    converted = item["converted"] ?: ""
                )
            }
        } catch (e: Exception) {
            listOf(ConversionChunk(rawText, content))
        }
    }
}
