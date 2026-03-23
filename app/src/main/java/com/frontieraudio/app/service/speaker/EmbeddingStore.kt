package com.frontieraudio.app.service.speaker

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.frontieraudio.app.domain.model.SpeakerProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EmbeddingStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    suspend fun save(profile: SpeakerProfile) {
        dataStore.edit { prefs ->
            prefs[KEY_ENROLLED] = true
            prefs[KEY_USER_ID] = profile.userId
            prefs[KEY_EMBEDDING] = encodeEmbedding(profile.embeddingVector)
            prefs[KEY_ENROLLED_AT] = profile.enrolledAt
            prefs[KEY_QUALITY_SCORE] = profile.qualityScore
        }
        Log.i(TAG, "Speaker profile saved for user=${profile.userId}, quality=${profile.qualityScore}")
    }

    suspend fun load(): SpeakerProfile? {
        val prefs = dataStore.data.first()
        val enrolled = prefs[KEY_ENROLLED] ?: false
        if (!enrolled) return null

        val userId = prefs[KEY_USER_ID] ?: return null
        val embeddingStr = prefs[KEY_EMBEDDING] ?: return null
        val enrolledAt = prefs[KEY_ENROLLED_AT] ?: return null
        val qualityScore = prefs[KEY_QUALITY_SCORE] ?: return null

        return SpeakerProfile(
            userId = userId,
            embeddingVector = decodeEmbedding(embeddingStr),
            enrolledAt = enrolledAt,
            qualityScore = qualityScore,
        )
    }

    suspend fun isEnrolled(): Boolean {
        return dataStore.data.map { it[KEY_ENROLLED] ?: false }.first()
    }

    suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_ENROLLED)
            prefs.remove(KEY_USER_ID)
            prefs.remove(KEY_EMBEDDING)
            prefs.remove(KEY_ENROLLED_AT)
            prefs.remove(KEY_QUALITY_SCORE)
        }
        Log.i(TAG, "Speaker profile cleared")
    }

    private fun encodeEmbedding(embedding: FloatArray): String {
        val buffer = ByteBuffer.allocate(embedding.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in embedding) buffer.putFloat(v)
        return Base64.getEncoder().encodeToString(buffer.array())
    }

    private fun decodeEmbedding(encoded: String): FloatArray {
        val bytes = Base64.getDecoder().decode(encoded)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val embedding = FloatArray(bytes.size / 4)
        for (i in embedding.indices) {
            embedding[i] = buffer.getFloat()
        }
        return embedding
    }

    companion object {
        private const val TAG = "EmbeddingStore"
        private val KEY_ENROLLED = booleanPreferencesKey("speaker_enrolled")
        private val KEY_USER_ID = stringPreferencesKey("speaker_user_id")
        private val KEY_EMBEDDING = stringPreferencesKey("speaker_embedding")
        private val KEY_ENROLLED_AT = longPreferencesKey("speaker_enrolled_at")
        private val KEY_QUALITY_SCORE = floatPreferencesKey("speaker_quality_score")
    }
}
