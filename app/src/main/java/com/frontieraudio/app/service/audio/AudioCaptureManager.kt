package com.frontieraudio.app.service.audio

import android.annotation.SuppressLint
import android.media.AudioRecord
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

enum class AudioSource { VOICE_COMMUNICATION, VOICE_RECOGNITION }

@Singleton
class AudioCaptureManager @Inject constructor() {

    private var audioRecord: AudioRecord? = null

    var currentSource: AudioSource = AudioSource.VOICE_COMMUNICATION
        private set

    @SuppressLint("MissingPermission")
    fun start(): Flow<ShortArray> = callbackFlow {
        val record = createAudioRecord()
            ?: throw IllegalStateException("Failed to initialize AudioRecord with any audio source")

        audioRecord = record
        record.startRecording()

        withContext(Dispatchers.Default) {
            val frameSamples = AudioConfig.FRAME_SIZE_SAMPLES
            val buffer = ShortArray(frameSamples)

            while (isActive && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val read = record.read(buffer, 0, frameSamples)
                when {
                    read == frameSamples -> {
                        trySend(buffer.copyOf())
                    }
                    read == AudioRecord.ERROR_INVALID_OPERATION -> {
                        Log.e(TAG, "AudioRecord read: ERROR_INVALID_OPERATION")
                        break
                    }
                    read == AudioRecord.ERROR_BAD_VALUE -> {
                        Log.e(TAG, "AudioRecord read: ERROR_BAD_VALUE")
                        break
                    }
                    read < 0 -> {
                        Log.e(TAG, "AudioRecord read error: $read")
                        break
                    }
                }
            }
        }

        awaitClose { stop() }
    }

    fun stop() {
        audioRecord?.let { record ->
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop()
            }
            record.release()
        }
        audioRecord = null
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord? {
        val primary = tryCreateRecord(AudioConfig.PRIMARY_AUDIO_SOURCE)
        if (primary != null) {
            currentSource = AudioSource.VOICE_COMMUNICATION
            Log.i(TAG, "AudioRecord initialized with VOICE_COMMUNICATION")
            return primary
        }

        Log.w(TAG, "VOICE_COMMUNICATION failed, falling back to VOICE_RECOGNITION")
        val fallback = tryCreateRecord(AudioConfig.FALLBACK_AUDIO_SOURCE)
        if (fallback != null) {
            currentSource = AudioSource.VOICE_RECOGNITION
            Log.i(TAG, "AudioRecord initialized with VOICE_RECOGNITION")
            return fallback
        }

        Log.e(TAG, "Failed to create AudioRecord with any source")
        return null
    }

    @SuppressLint("MissingPermission")
    private fun tryCreateRecord(audioSource: Int): AudioRecord? {
        return try {
            val record = AudioRecord(
                audioSource,
                AudioConfig.SAMPLE_RATE,
                AudioConfig.CHANNEL_CONFIG,
                AudioConfig.AUDIO_FORMAT,
                AudioConfig.BUFFER_SIZE,
            )
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                record
            } else {
                record.release()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord creation failed for source $audioSource", e)
            null
        }
    }

    companion object {
        private const val TAG = "AudioCaptureManager"
    }
}
