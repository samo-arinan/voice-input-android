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
        fun buildUserMessage(rawText: String, terminalContext: String?): String {
            if (terminalContext.isNullOrBlank()) return rawText
            return "[端末コンテキスト]\n$terminalContext\n\n[音声入力テキスト]\n$rawText"
        }

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

        private val KANJI_PROMPT = """
            ひらがなテキストの漢字変換候補を3〜5個生成してください。
            最も自然な変換を先頭にしてください。
            JSON配列のみを返してください。説明は不要です。
            例: 入力「きょう」→ ["今日", "京", "教"]
        """.trimIndent()

        private val CANDIDATES_PROMPT = """
            テキストの変換候補を3〜5個生成してください。
            漢字変換、別の表現、コマンド変換など、ユーザーが意図しそうな候補を出してください。
            JSON配列のみを返してください。説明は不要です。
            例: ["天気", "天機", "転機"]
        """.trimIndent()
    }

    fun convert(rawText: String): String {
        return callGpt(SYSTEM_PROMPT, rawText) ?: rawText
    }

    fun convertWithHistory(rawText: String, corrections: List<CorrectionEntry>, terminalContext: String? = null): String {
        val prompt = if (corrections.isEmpty()) {
            SYSTEM_PROMPT
        } else {
            val historyLines = corrections.joinToString("\n") { entry ->
                "- 「${entry.original}」→「${entry.corrected}」(${entry.frequency}回)"
            }
            """
                $SYSTEM_PROMPT

                以下はユーザーの過去の修正履歴です。同様のパターンがあれば適用してください：
                $historyLines
            """.trimIndent()
        }
        val userMessage = buildUserMessage(rawText, terminalContext)
        return callGpt(prompt, userMessage) ?: rawText
    }

    fun convertHiraganaToKanji(hiragana: String): List<String> {
        val response = callGpt(KANJI_PROMPT, hiragana) ?: return emptyList()
        return try {
            val jsonArray = JsonParser.parseString(response).asJsonArray
            jsonArray.map { it.asString }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getCandidates(text: String): List<String> {
        val response = callGpt(CANDIDATES_PROMPT, text) ?: return emptyList()
        return try {
            val jsonArray = JsonParser.parseString(response).asJsonArray
            jsonArray.map { it.asString }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun callGpt(systemPrompt: String, userText: String): String? {
        val messages = listOf(
            mapOf("role" to "system", "content" to systemPrompt),
            mapOf("role" to "user", "content" to userText)
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
            if (!response.isSuccessful) return null
            val responseBody = response.body?.string() ?: return null
            val json = JsonParser.parseString(responseBody).asJsonObject
            json.getAsJsonArray("choices")
                .get(0).asJsonObject
                .getAsJsonObject("message")
                .get("content").asString
                .trim()
        } catch (e: Exception) {
            null
        }
    }
}
