package com.frontieraudio.app.service.audio

import android.annotation.SuppressLint
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

enum class AudioSource { VOICE_COMMUNICATION, VOICE_RECOGNITION }

@Singleton
class AudioCaptureManager @Inject constructor() {

    private var audioRecord: AudioRecord? = null

    var currentSource: AudioSource = AudioSource.VOICE_COMMUNICATION
        private set

    var currentSampleRate: Int = AudioConfig.SAMPLE_RATE
        private set

    @Volatile
    private var discardFramesUntil: Long = 0L

    @SuppressLint("MissingPermission")
    fun start(): Flow<ShortArray> = callbackFlow {
        var record = createAudioRecord(currentSampleRate, audioSourceInt(currentSource))
            ?: throw IllegalStateException("Failed to initialize AudioRecord with any audio source")

        audioRecord = record
        record.startRecording()

        withContext(Dispatchers.Default) {
            val frameSamples = frameSamplesForRate(currentSampleRate)
            val buffer = ShortArray(frameSamples)
            var silentFrameCount = 0
            val silenceThresholdFrames = silenceFrameCount(currentSampleRate)

            while (isActive && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val read = record.read(buffer, 0, frameSamples)
                when {
                    read == frameSamples -> {
                        if (System.currentTimeMillis() < discardFramesUntil) continue

                        if (isSilent(buffer, read)) {
                            silentFrameCount++
                            if (silentFrameCount >= silenceThresholdFrames &&
                                currentSource == AudioSource.VOICE_COMMUNICATION
                            ) {
                                Log.w(TAG, "Silence detected for ~2s on VOICE_COMMUNICATION, switching to VOICE_RECOGNITION")
                                val fallback = tryCreateRecord(
                                    AudioConfig.FALLBACK_AUDIO_SOURCE,
                                    currentSampleRate,
                                )
                                if (fallback != null) {
                                    record.stop()
                                    record.release()
                                    audioRecord = fallback
                                    record = fallback
                                    currentSource = AudioSource.VOICE_RECOGNITION
                                    fallback.startRecording()
                                    silentFrameCount = 0
                                    discardFramesUntil = System.currentTimeMillis() + DISCARD_AFTER_SWITCH_MS
                                    continue
                                }
                                silentFrameCount = 0
                            }
                        } else {
                            silentFrameCount = 0
                        }

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

        // Read loop exited (error or external stop) — close channel so collectors complete
        channel.close()

        awaitClose {
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
                record.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing AudioRecord in awaitClose", e)
            }
            if (audioRecord === record) {
                audioRecord = null
            }
        }
    }

    fun recreateForDevice(sampleRate: Int, audioSource: Int) {
        Log.i(TAG, "recreateForDevice: sampleRate=$sampleRate, audioSource=$audioSource")
        stop()

        currentSampleRate = sampleRate
        currentSource = if (audioSource == MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
            AudioSource.VOICE_COMMUNICATION
        } else {
            AudioSource.VOICE_RECOGNITION
        }

        discardFramesUntil = System.currentTimeMillis() + DISCARD_AFTER_SWITCH_MS
    }

    fun stop() {
        audioRecord?.let { record ->
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
                record.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping AudioRecord", e)
            }
        }
        audioRecord = null
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(sampleRate: Int, preferredSource: Int): AudioRecord? {
        val primary = tryCreateRecord(preferredSource, sampleRate)
        if (primary != null) {
            currentSource = if (preferredSource == MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
                AudioSource.VOICE_COMMUNICATION
            } else {
                AudioSource.VOICE_RECOGNITION
            }
            Log.i(TAG, "AudioRecord initialized with source=$preferredSource, rate=$sampleRate")
            return primary
        }

        if (preferredSource == AudioConfig.PRIMARY_AUDIO_SOURCE) {
            Log.w(TAG, "VOICE_COMMUNICATION failed, falling back to VOICE_RECOGNITION")
            val fallback = tryCreateRecord(AudioConfig.FALLBACK_AUDIO_SOURCE, sampleRate)
            if (fallback != null) {
                currentSource = AudioSource.VOICE_RECOGNITION
                Log.i(TAG, "AudioRecord initialized with VOICE_RECOGNITION, rate=$sampleRate")
                return fallback
            }
        }

        Log.e(TAG, "Failed to create AudioRecord with any source at rate $sampleRate")
        return null
    }

    @SuppressLint("MissingPermission")
    private fun tryCreateRecord(audioSource: Int, sampleRate: Int): AudioRecord? {
        return try {
            val minBuffer = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioConfig.CHANNEL_CONFIG,
                AudioConfig.AUDIO_FORMAT,
            )
            val frameSamples = frameSamplesForRate(sampleRate)
            val bufferSize = minBuffer.coerceAtLeast(frameSamples * AudioConfig.BYTES_PER_SAMPLE) * 2

            val record = AudioRecord(
                audioSource,
                sampleRate,
                AudioConfig.CHANNEL_CONFIG,
                AudioConfig.AUDIO_FORMAT,
                bufferSize,
            )
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                record
            } else {
                record.release()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord creation failed for source=$audioSource rate=$sampleRate", e)
            null
        }
    }

    private fun isSilent(buffer: ShortArray, length: Int): Boolean {
        var sum = 0L
        for (i in 0 until length) {
            sum += abs(buffer[i].toInt())
        }
        val avgAmplitude = sum / length
        return avgAmplitude < SILENCE_AMPLITUDE_THRESHOLD
    }

    companion object {
        private const val TAG = "AudioCaptureManager"
        private const val SILENCE_AMPLITUDE_THRESHOLD = 50
        private const val DISCARD_AFTER_SWITCH_MS = 500L

        fun frameSamplesForRate(sampleRate: Int): Int =
            sampleRate * AudioConfig.FRAME_SIZE_MS / 1000

        fun audioSourceInt(source: AudioSource): Int = when (source) {
            AudioSource.VOICE_COMMUNICATION -> AudioConfig.PRIMARY_AUDIO_SOURCE
            AudioSource.VOICE_RECOGNITION -> AudioConfig.FALLBACK_AUDIO_SOURCE
        }

        private fun silenceFrameCount(sampleRate: Int): Int {
            val frameDurationMs = AudioConfig.FRAME_SIZE_MS
            return 2000 / frameDurationMs
        }
    }
}
