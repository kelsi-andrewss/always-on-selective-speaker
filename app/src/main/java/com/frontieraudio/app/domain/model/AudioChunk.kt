package com.frontieraudio.app.domain.model

data class AudioChunk(
    val pcmData: ByteArray,
    val startTimestamp: Long,
    val durationMs: Int,
    val sampleRate: Int,
    val isSpeakerVerified: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioChunk) return false
        return pcmData.contentEquals(other.pcmData) &&
            startTimestamp == other.startTimestamp &&
            durationMs == other.durationMs &&
            sampleRate == other.sampleRate &&
            isSpeakerVerified == other.isSpeakerVerified
    }

    override fun hashCode(): Int {
        var result = pcmData.contentHashCode()
        result = 31 * result + startTimestamp.hashCode()
        result = 31 * result + durationMs
        result = 31 * result + sampleRate
        result = 31 * result + isSpeakerVerified.hashCode()
        return result
    }
}
