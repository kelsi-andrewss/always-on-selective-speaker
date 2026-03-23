package com.frontieraudio.app.data.remote

import android.util.Log
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface OpenAiApi {

    @POST("v1/chat/completions")
    suspend fun chatCompletions(
        @Header("Authorization") auth: String,
        @Body request: ChatCompletionRequest,
    ): Response<ChatCompletionResponse>
}

data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double,
)

data class ChatMessage(
    val role: String,
    val content: String,
)

data class ChatCompletionResponse(
    val choices: List<Choice>,
)

data class Choice(
    val message: ChoiceMessage,
)

data class ChoiceMessage(
    val content: String,
)

class LlmPostProcessor(private val apiKey: String) {

    private val api: OpenAiApi = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(OpenAiApi::class.java)

    suspend fun correctTranscript(rawText: String, context: String? = null): Result<String> =
        runCatching {
            val systemContent = buildString {
                append(SYSTEM_PROMPT)
                if (context != null) {
                    append("\n\nAdditional context: ")
                    append(context)
                }
            }

            val request = ChatCompletionRequest(
                model = MODEL,
                messages = listOf(
                    ChatMessage(role = "system", content = systemContent),
                    ChatMessage(role = "user", content = rawText),
                ),
                temperature = TEMPERATURE,
            )

            val response = api.chatCompletions("Bearer $apiKey", request)
            if (!response.isSuccessful) {
                error("OpenAI API error ${response.code()}: ${response.errorBody()?.string()}")
            }

            val body = response.body()
                ?: error("OpenAI API returned empty body")

            val corrected = body.choices.firstOrNull()?.message?.content
                ?: error("OpenAI API returned no choices")

            Log.d(TAG, "Corrected transcript (${rawText.length} -> ${corrected.length} chars)")
            corrected
        }.onFailure { e ->
            Log.e(TAG, "LLM post-processing failed", e)
        }

    companion object {
        private const val TAG = "LlmPostProcessor"
        private const val BASE_URL = "https://api.openai.com/"
        private const val MODEL = "gpt-4o"
        private const val TEMPERATURE = 0.1

        private const val SYSTEM_PROMPT =
            "You are a speech-to-text error correction assistant for field workers " +
            "(construction, utilities, maintenance). Your task is to correct obvious " +
            "speech-to-text transcription errors while preserving the original meaning exactly. " +
            "Fix only: misspelled technical terminology, garbled proper nouns, incorrect numbers " +
            "that are clearly wrong from context, and common STT misrecognitions. " +
            "Do NOT rephrase, summarize, add punctuation style changes, or alter meaning. " +
            "Return only the corrected text with no explanation or commentary."
    }
}
