package com.example.voiceinput

import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

class WhisperClient(
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com/",
    private val model: String = "gpt-4o-transcribe"
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun transcribe(audioFile: File, prompt: String? = null): String? {
        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                audioFile.name,
                audioFile.asRequestBody("audio/wav".toMediaType())
            )
            .addFormDataPart("model", model)
            .addFormDataPart("language", "ja")
            .addFormDataPart("temperature", "0")

        if (!prompt.isNullOrBlank()) {
            builder.addFormDataPart("prompt", prompt)
        }

        val requestBody = builder.build()

        val request = Request.Builder()
            .url("${baseUrl}v1/audio/transcriptions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            JsonParser.parseString(body).asJsonObject.get("text").asString
        } catch (e: Exception) {
            null
        }
    }
}
