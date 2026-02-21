package com.example.voiceinput

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class VisionClient(
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com/"
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val gson = GsonBuilder().disableHtmlEscaping().create()

    companion object {
        private const val DEFAULT_QUESTION = "この画像について説明してください"
    }

    fun ask(imageBase64: String, question: String?): String? {
        val textContent = mapOf("type" to "text", "text" to (question ?: DEFAULT_QUESTION))
        val imageContent = mapOf(
            "type" to "image_url",
            "image_url" to mapOf("url" to "data:image/png;base64,$imageBase64")
        )
        val messages = listOf(
            mapOf(
                "role" to "user",
                "content" to listOf(textContent, imageContent)
            )
        )
        val body = gson.toJson(
            mapOf(
                "model" to "gpt-4o",
                "messages" to messages,
                "max_tokens" to 1024
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
