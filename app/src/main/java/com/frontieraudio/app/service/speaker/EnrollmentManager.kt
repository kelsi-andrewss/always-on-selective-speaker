package com.frontieraudio.app.service.speaker

import android.util.Log
import com.frontieraudio.app.domain.model.AudioChunk
import com.frontieraudio.app.domain.model.SpeakerProfile
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EnrollmentManager @Inject constructor(
    private val verifier: SherpaOnnxVerifier,
    private val embeddingStore: EmbeddingStore,
) {
    suspend fun enroll(utterances: List<AudioChunk>): Result<SpeakerProfile> {
        require(utterances.size == REQUIRED_UTTERANCES) {
            "Enrollment requires exactly $REQUIRED_UTTERANCES utterances, got ${utterances.size}"
        }

        val embeddings = try {
            utterances.map { verifier.extractEmbedding(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Embedding extraction failed", e)
            return Result.failure(e)
        }

        for (emb in embeddings) {
            if (emb.size != SherpaOnnxVerifier.EMBEDDING_DIM) {
                return Result.failure(
                    IllegalStateException("Unexpected embedding dimension: ${emb.size}")
                )
            }
        }

        val qualityScore = computeMinPairwiseSimilarity(embeddings)
        if (qualityScore < CONSISTENCY_THRESHOLD) {
            Log.w(TAG, "Enrollment quality too low: $qualityScore < $CONSISTENCY_THRESHOLD")
            return Result.failure(
                EnrollmentQualityException(
                    "Voice samples are not consistent enough. " +
                        "Try again in a quieter environment. " +
                        "(score=$qualityScore, required=$CONSISTENCY_THRESHOLD)"
                )
            )
        }

        val averaged = averageEmbeddings(embeddings)

        val profile = SpeakerProfile(
            userId = UUID.randomUUID().toString(),
            embeddingVector = averaged,
            enrolledAt = System.currentTimeMillis(),
            qualityScore = qualityScore,
        )

        try {
            embeddingStore.save(profile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist speaker profile", e)
            return Result.failure(e)
        }

        Log.i(TAG, "Enrollment succeeded: quality=$qualityScore")
        return Result.success(profile)
    }

    private fun computeMinPairwiseSimilarity(embeddings: List<FloatArray>): Float {
        var minSim = 1f
        for (i in embeddings.indices) {
            for (j in i + 1 until embeddings.size) {
                val sim = SherpaOnnxVerifier.cosineSimilarity(embeddings[i], embeddings[j])
                if (sim < minSim) minSim = sim
            }
        }
        return minSim
    }

    private fun averageEmbeddings(embeddings: List<FloatArray>): FloatArray {
        val dim = embeddings[0].size
        val sum = FloatArray(dim)
        for (emb in embeddings) {
            for (i in sum.indices) {
                sum[i] += emb[i]
            }
        }
        val count = embeddings.size.toFloat()
        for (i in sum.indices) {
            sum[i] /= count
        }
        // Re-normalize
        var norm = 0f
        for (v in sum) norm += v * v
        norm = kotlin.math.sqrt(norm)
        if (norm > 1e-8f) {
            for (i in sum.indices) sum[i] /= norm
        }
        return sum
    }

    companion object {
        private const val TAG = "EnrollmentManager"
        const val REQUIRED_UTTERANCES = 3
        const val CONSISTENCY_THRESHOLD = 0.7f
    }
}

class EnrollmentQualityException(message: String) : Exception(message)
