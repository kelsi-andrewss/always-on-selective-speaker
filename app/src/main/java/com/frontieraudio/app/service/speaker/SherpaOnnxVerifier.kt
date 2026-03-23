package com.frontieraudio.app.service.speaker

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.frontieraudio.app.domain.model.AudioChunk
import com.frontieraudio.app.domain.model.SpeakerProfile
import com.frontieraudio.app.service.audio.AudioConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

data class VerificationResult(
    val isMatch: Boolean,
    val similarity: Float,
)

@Singleton
class SherpaOnnxVerifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val ortEnvironment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var ortSession: OrtSession? = null

    fun init() {
        if (ortSession != null) return
        val modelBytes = context.assets.open(MODEL_FILE).readBytes()
        ortSession = ortEnvironment.createSession(modelBytes)
        Log.i(TAG, "ECAPA-TDNN model loaded")
    }

    fun release() {
        ortSession?.close()
        ortSession = null
    }

    fun extractEmbedding(audioChunk: AudioChunk): FloatArray {
        val session = ortSession
            ?: throw IllegalStateException("SherpaOnnxVerifier not initialized. Call init() first.")

        val floatSamples = pcmBytesToFloat(audioChunk.pcmData)

        val inputTensor = OnnxTensor.createTensor(
            ortEnvironment,
            FloatBuffer.wrap(floatSamples),
            longArrayOf(1, floatSamples.size.toLong()),
        )

        val results = session.run(mapOf("input" to inputTensor))

        val embedding = extractOutputEmbedding(results)

        inputTensor.close()
        results.close()

        return normalize(embedding)
    }

    fun verify(
        audioChunk: AudioChunk,
        profile: SpeakerProfile,
        threshold: Float = DEFAULT_THRESHOLD,
    ): VerificationResult {
        val embedding = extractEmbedding(audioChunk)
        val similarity = cosineSimilarity(embedding, profile.embeddingVector)
        return VerificationResult(
            isMatch = similarity >= threshold,
            similarity = similarity,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractOutputEmbedding(results: OrtSession.Result): FloatArray {
        val outputTensor = results[0]
        val value = outputTensor.value
        return when (value) {
            is Array<*> -> {
                val outer = value as Array<FloatArray>
                outer[0]
            }
            is FloatArray -> value
            else -> throw IllegalStateException("Unexpected ONNX output type: ${value?.javaClass}")
        }
    }

    private fun pcmBytesToFloat(pcmData: ByteArray): FloatArray {
        val numSamples = pcmData.size / AudioConfig.BYTES_PER_SAMPLE
        val floatSamples = FloatArray(numSamples)
        for (i in 0 until numSamples) {
            val low = pcmData[i * 2].toInt() and 0xFF
            val high = pcmData[i * 2 + 1].toInt()
            val sample = (high shl 8) or low
            floatSamples[i] = sample / 32768f
        }
        return floatSamples
    }

    private fun normalize(vector: FloatArray): FloatArray {
        val norm = sqrt(vector.fold(0f) { acc, v -> acc + v * v })
        if (norm < 1e-8f) return vector
        return FloatArray(vector.size) { vector[it] / norm }
    }

    companion object {
        private const val TAG = "SherpaOnnxVerifier"
        private const val MODEL_FILE = "ecapa_tdnn.onnx"
        const val EMBEDDING_DIM = 192
        const val DEFAULT_THRESHOLD = 0.65f

        fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
            require(a.size == b.size) { "Vectors must have same dimension" }
            var dot = 0f
            var normA = 0f
            var normB = 0f
            for (i in a.indices) {
                dot += a[i] * b[i]
                normA += a[i] * a[i]
                normB += b[i] * b[i]
            }
            val denom = sqrt(normA) * sqrt(normB)
            if (denom < 1e-8f) return 0f
            return dot / denom
        }
    }
}
