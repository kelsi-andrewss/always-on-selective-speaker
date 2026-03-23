package com.frontieraudio.app.domain.model

data class SpeakerProfile(
    val userId: String,
    val embeddingVector: FloatArray,
    val enrolledAt: Long,
    val qualityScore: Float,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SpeakerProfile) return false
        return userId == other.userId &&
            embeddingVector.contentEquals(other.embeddingVector) &&
            enrolledAt == other.enrolledAt &&
            qualityScore == other.qualityScore
    }

    override fun hashCode(): Int {
        var result = userId.hashCode()
        result = 31 * result + embeddingVector.contentHashCode()
        result = 31 * result + enrolledAt.hashCode()
        result = 31 * result + qualityScore.hashCode()
        return result
    }
}
