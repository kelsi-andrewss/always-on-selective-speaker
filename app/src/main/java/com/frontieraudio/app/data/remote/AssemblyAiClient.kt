package com.frontieraudio.app.data.remote

import com.google.gson.annotations.SerializedName
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import java.util.concurrent.TimeUnit

data class UploadResponse(
    @SerializedName("upload_url") val uploadUrl: String,
)

data class TranscriptRequest(
    @SerializedName("audio_url") val audioUrl: String,
    @SerializedName("word_boost") val wordBoost: List<String> = emptyList(),
)

data class TranscriptResponse(
    @SerializedName("id") val id: String,
    @SerializedName("status") val status: String,
    @SerializedName("text") val text: String? = null,
    @SerializedName("words") val words: List<WordResponse>? = null,
    @SerializedName("error") val error: String? = null,
)

data class WordResponse(
    @SerializedName("text") val text: String,
    @SerializedName("start") val start: Long,
    @SerializedName("end") val end: Long,
    @SerializedName("confidence") val confidence: Float,
)

interface AssemblyAiApi {
    @POST("v2/upload")
    suspend fun uploadAudio(@Body body: okhttp3.RequestBody): Response<UploadResponse>

    @POST("v2/transcript")
    suspend fun createTranscript(@Body request: TranscriptRequest): Response<TranscriptResponse>

    @GET("v2/transcript/{id}")
    suspend fun getTranscript(@Path("id") transcriptId: String): Response<TranscriptResponse>
}

class AssemblyAiClient(apiKey: String) {

    private val api: AssemblyAiApi

    init {
        val authInterceptor = Interceptor { chain ->
            val request = chain.request().newBuilder()
                .addHeader("Authorization", apiKey)
                .build()
            chain.proceed(request)
        }

        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()

        api = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AssemblyAiApi::class.java)
    }

    suspend fun uploadAudio(audioData: ByteArray): Result<String> = runCatching {
        val body = audioData.toRequestBody("application/octet-stream".toMediaType())
        val response = api.uploadAudio(body)
        if (!response.isSuccessful) {
            throw ApiException(response.code(), response.errorBody()?.string())
        }
        response.body()?.uploadUrl ?: throw ApiException(0, "Empty upload response")
    }

    suspend fun createTranscript(audioUrl: String): Result<String> = runCatching {
        val response = api.createTranscript(TranscriptRequest(audioUrl = audioUrl))
        if (!response.isSuccessful) {
            throw ApiException(response.code(), response.errorBody()?.string())
        }
        response.body()?.id ?: throw ApiException(0, "Empty transcript response")
    }

    suspend fun getTranscript(transcriptId: String): Result<TranscriptResponse> = runCatching {
        val response = api.getTranscript(transcriptId)
        if (!response.isSuccessful) {
            throw ApiException(response.code(), response.errorBody()?.string())
        }
        response.body() ?: throw ApiException(0, "Empty transcript response")
    }

    companion object {
        private const val BASE_URL = "https://api.assemblyai.com/"
    }
}

class ApiException(val code: Int, message: String?) : Exception("API error $code: $message")
