package com.frontieraudio.app.data.remote

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertInstanceOf

class AssemblyAiClientTest {

    private val gson = Gson()

    // ── ApiException (pure logic) ──────────────────────────────────────

    @Nested
    inner class ApiExceptionTests {

        @Test
        fun `message formats code and text`() {
            val ex = ApiException(401, "Unauthorized")
            assertEquals("API error 401: Unauthorized", ex.message)
        }

        @Test
        fun `message formats code with null text`() {
            val ex = ApiException(500, null)
            assertEquals("API error 500: null", ex.message)
        }

        @Test
        fun `code property is stored`() {
            val ex = ApiException(422, "Unprocessable")
            assertEquals(422, ex.code)
        }

        @Test
        fun `is an Exception`() {
            assertInstanceOf(Exception::class.java, ApiException(0, ""))
        }
    }

    // ── Data class serialization (Gson, no server) ─────────────────────

    @Nested
    inner class SerializationTests {

        @Test
        fun `TranscriptRequest serializes with SerializedName keys`() {
            val req = TranscriptRequest(
                audioUrl = "https://x.com/a.wav",
                wordBoost = listOf("alpha"),
            )
            val json = JsonParser.parseString(gson.toJson(req)).asJsonObject
            assertEquals("https://x.com/a.wav", json["audio_url"].asString)
            assertEquals("alpha", json["word_boost"].asJsonArray[0].asString)
        }

        @Test
        fun `TranscriptRequest default wordBoost serializes as empty array`() {
            val req = TranscriptRequest(audioUrl = "https://x.com/b.wav")
            val json = JsonParser.parseString(gson.toJson(req)).asJsonObject
            assertTrue(json["word_boost"].asJsonArray.isEmpty)
        }

        @Test
        fun `UploadResponse deserializes upload_url`() {
            val resp = gson.fromJson(
                """{"upload_url":"https://cdn.assemblyai.com/upload/abc"}""",
                UploadResponse::class.java,
            )
            assertEquals("https://cdn.assemblyai.com/upload/abc", resp.uploadUrl)
        }

        @Test
        fun `TranscriptResponse deserializes full JSON with words`() {
            val json = """
                {
                    "id": "tr_123",
                    "status": "completed",
                    "text": "hello world",
                    "words": [
                        {"text": "hello", "start": 100, "end": 500, "confidence": 0.95}
                    ],
                    "error": null
                }
            """.trimIndent()
            val resp = gson.fromJson(json, TranscriptResponse::class.java)
            assertEquals("tr_123", resp.id)
            assertEquals("completed", resp.status)
            assertEquals("hello world", resp.text)
            assertEquals(1, resp.words!!.size)
            val word = resp.words!![0]
            assertEquals("hello", word.text)
            assertEquals(100L, word.start)
            assertEquals(500L, word.end)
            assertEquals(0.95f, word.confidence)
        }

        @Test
        fun `TranscriptResponse with null optional fields deserializes`() {
            val json = """{"id":"tr_x","status":"queued"}"""
            val resp = gson.fromJson(json, TranscriptResponse::class.java)
            assertEquals("tr_x", resp.id)
            assertEquals("queued", resp.status)
            assertEquals(null, resp.text)
            assertEquals(null, resp.words)
            assertEquals(null, resp.error)
        }
    }

    // ── MockWebServer integration tests ────────────────────────────────

    @Nested
    inner class HttpTests {

        private lateinit var server: MockWebServer
        private lateinit var client: AssemblyAiClient

        @BeforeEach
        fun setUp() {
            server = MockWebServer()
            server.start()
            client = AssemblyAiClient(
                apiKey = "test-key-123",
                baseUrl = server.url("/").toString(),
            )
        }

        @AfterEach
        fun tearDown() {
            server.shutdown()
        }

        // uploadAudio

        @Test
        fun `uploadAudio success returns upload URL`() = runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"upload_url":"https://cdn.assemblyai.com/upload/abc"}""")
                    .setHeader("Content-Type", "application/json"),
            )

            val result = client.uploadAudio(byteArrayOf(1, 2, 3))

            assertTrue(result.isSuccess)
            assertEquals("https://cdn.assemblyai.com/upload/abc", result.getOrThrow())

            val req = server.takeRequest()
            assertEquals("/v2/upload", req.path)
            assertEquals("application/octet-stream", req.getHeader("Content-Type"))
        }

        @Test
        fun `uploadAudio 401 returns ApiException`() = runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(401)
                    .setBody("Unauthorized"),
            )

            val result = client.uploadAudio(byteArrayOf(1))

            assertTrue(result.isFailure)
            val ex = result.exceptionOrNull()
            val apiEx = assertInstanceOf(ApiException::class.java, ex)
            assertEquals(401, apiEx.code)
        }

        @Test
        fun `uploadAudio 500 returns ApiException`() = runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(500)
                    .setBody("Internal Server Error"),
            )

            val result = client.uploadAudio(byteArrayOf(1))

            assertTrue(result.isFailure)
            val ex = result.exceptionOrNull()
            val apiEx = assertInstanceOf(ApiException::class.java, ex)
            assertEquals(500, apiEx.code)
        }

        // createTranscript

        @Test
        fun `createTranscript success returns transcript ID`() = runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"id":"tr_abc","status":"queued"}""")
                    .setHeader("Content-Type", "application/json"),
            )

            val result = client.createTranscript("https://cdn.assemblyai.com/upload/abc")

            assertTrue(result.isSuccess)
            assertEquals("tr_abc", result.getOrThrow())

            val req = server.takeRequest()
            val body = req.body.readUtf8()
            assertTrue(body.contains("\"audio_url\""))
        }

        @Test
        fun `createTranscript 403 returns ApiException`() = runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(403)
                    .setBody("Forbidden"),
            )

            val result = client.createTranscript("https://cdn.assemblyai.com/upload/abc")

            assertTrue(result.isFailure)
            val ex = result.exceptionOrNull()
            val apiEx = assertInstanceOf(ApiException::class.java, ex)
            assertEquals(403, apiEx.code)
        }

        // getTranscript

        @Test
        fun `getTranscript success returns full response`() = runTest {
            val responseJson = """
                {
                    "id": "tr_abc",
                    "status": "completed",
                    "text": "hello world",
                    "words": [
                        {"text": "hello", "start": 100, "end": 500, "confidence": 0.95},
                        {"text": "world", "start": 600, "end": 1000, "confidence": 0.88}
                    ],
                    "error": null
                }
            """.trimIndent()
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(responseJson)
                    .setHeader("Content-Type", "application/json"),
            )

            val result = client.getTranscript("tr_abc")

            assertTrue(result.isSuccess)
            val resp = result.getOrThrow()
            assertEquals("tr_abc", resp.id)
            assertEquals("completed", resp.status)
            assertEquals("hello world", resp.text)
            assertEquals(2, resp.words!!.size)
            assertEquals("hello", resp.words!![0].text)
            assertEquals(100L, resp.words!![0].start)
            assertEquals(500L, resp.words!![0].end)
            assertEquals(0.95f, resp.words!![0].confidence)
        }

        @Test
        fun `getTranscript 404 returns ApiException`() = runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(404)
                    .setBody("Not Found"),
            )

            val result = client.getTranscript("tr_missing")

            assertTrue(result.isFailure)
            val ex = result.exceptionOrNull()
            val apiEx = assertInstanceOf(ApiException::class.java, ex)
            assertEquals(404, apiEx.code)
        }

        // Auth header

        @Test
        fun `auth header includes API key on every request`() = runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"upload_url":"https://cdn.assemblyai.com/upload/x"}""")
                    .setHeader("Content-Type", "application/json"),
            )

            client.uploadAudio(byteArrayOf(1))

            val req = server.takeRequest()
            assertEquals("test-key-123", req.getHeader("Authorization"))
        }
    }
}
