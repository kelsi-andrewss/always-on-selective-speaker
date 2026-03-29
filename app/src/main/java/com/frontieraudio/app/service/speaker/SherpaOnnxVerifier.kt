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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _lastVerificationResult = MutableStateFlow<VerificationResult?>(null)
    val lastVerificationResult: StateFlow<VerificationResult?> = _lastVerificationResult.asStateFlow()

    private val _lastVerificationTimestamp = MutableStateFlow(0L)
    val lastVerificationTimestamp: StateFlow<Long> = _lastVerificationTimestamp.asStateFlow()

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

        val rawSamples = pcmBytesToFloat(audioChunk.pcmData)
        Log.d(TAG, "DIAG pcm bytes=${audioChunk.pcmData.size}, floats=${rawSamples.size}, rms=${kotlin.math.sqrt(rawSamples.fold(0.0) { a, v -> a + v * v } / rawSamples.size)}, max=${rawSamples.max()}, min=${rawSamples.min()}")

        // Trim leading/trailing silence using simple energy threshold
        val trimmed = trimSilence(rawSamples)
        Log.d(TAG, "Trimmed ${rawSamples.size} -> ${trimmed.size} samples")

        // RMS-normalize so embeddings are consistent regardless of mic distance/volume
        val floatSamples = rmsNormalize(trimmed)

        // Extract mel fbank features: [numFrames, 80]
        val fbankFlat = MelFbankExtractor.extract(floatSamples)
        val numFrames = MelFbankExtractor.numFrames(floatSamples.size)
        val numMelBins = 80

        if (numFrames == 0) {
            throw IllegalArgumentException("Audio too short for feature extraction")
        }

        Log.d(TAG, "Extracted $numFrames fbank frames from ${floatSamples.size} samples")
        Log.d(TAG, "DIAG fbank: size=${fbankFlat.size}, min=${fbankFlat.min()}, max=${fbankFlat.max()}, mean=${fbankFlat.average()}, first5=${fbankFlat.take(5).map { "%.3f".format(it) }}")

        // Model expects [N, T, 80] where N=batch, T=time frames
        val inputTensor = OnnxTensor.createTensor(
            ortEnvironment,
            FloatBuffer.wrap(fbankFlat),
            longArrayOf(1, numFrames.toLong(), numMelBins.toLong()),
        )

        val results = session.run(mapOf("x" to inputTensor))

        val embedding = extractOutputEmbedding(results)

        inputTensor.close()
        results.close()

        Log.d(TAG, "Embedding dim=${embedding.size}")
        return normalize(embedding)
    }

    fun verify(
        audioChunk: AudioChunk,
        profile: SpeakerProfile,
        threshold: Float = DEFAULT_THRESHOLD,
    ): VerificationResult {
        val embedding = extractEmbedding(audioChunk)
        Log.d(TAG, "DIAG profile[0..4]=${profile.embeddingVector.take(5).map { "%.4f".format(it) }}, norm=${kotlin.math.sqrt(profile.embeddingVector.fold(0f) { a, v -> a + v * v })}")
        Log.d(TAG, "DIAG verify [0..4]=${embedding.take(5).map { "%.4f".format(it) }}, norm=${kotlin.math.sqrt(embedding.fold(0f) { a, v -> a + v * v })}")
        val similarity = cosineSimilarity(embedding, profile.embeddingVector)
        val result = VerificationResult(
            isMatch = similarity >= threshold,
            similarity = similarity,
        )
        _lastVerificationResult.value = result
        _lastVerificationTimestamp.value = System.currentTimeMillis()
        return result
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

    /**
     * Trim leading and trailing silence using RMS energy in 10ms windows.
     * Keeps only the region where energy exceeds the threshold.
     */
    private fun trimSilence(samples: FloatArray, threshold: Float = 0.005f): FloatArray {
        val windowSize = AudioConfig.SAMPLE_RATE / 100  // 10ms = 160 samples
        if (samples.size < windowSize) return samples

        // Compute RMS per window
        val numWindows = samples.size / windowSize
        var start = 0
        var end = samples.size

        // Find first window above threshold
        for (w in 0 until numWindows) {
            var sum = 0f
            for (i in 0 until windowSize) {
                val s = samples[w * windowSize + i]
                sum += s * s
            }
            val rms = kotlin.math.sqrt(sum / windowSize)
            if (rms > threshold) {
                start = (w * windowSize - windowSize).coerceAtLeast(0)  // include one window before
                break
            }
        }

        // Find last window above threshold
        for (w in numWindows - 1 downTo 0) {
            var sum = 0f
            for (i in 0 until windowSize) {
                val s = samples[w * windowSize + i]
                sum += s * s
            }
            val rms = kotlin.math.sqrt(sum / windowSize)
            if (rms > threshold) {
                end = ((w + 2) * windowSize).coerceAtMost(samples.size)  // include one window after
                break
            }
        }

        return if (end > start) samples.copyOfRange(start, end) else samples
    }

    private fun rmsNormalize(samples: FloatArray, targetRms: Float = TARGET_RMS): FloatArray {
        var sumSq = 0.0
        for (s in samples) sumSq += s * s
        val rms = sqrt(sumSq / samples.size).toFloat()
        if (rms < 1e-6f) return samples
        val scale = targetRms / rms
        return FloatArray(samples.size) { (samples[it] * scale).coerceIn(-1f, 1f) }
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
        private const val TARGET_RMS = 0.1f
        const val EMBEDDING_DIM = 512
        const val DEFAULT_THRESHOLD = 0.55f

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
