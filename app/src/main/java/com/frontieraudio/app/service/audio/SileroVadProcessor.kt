package com.frontieraudio.app.service.audio

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.frontieraudio.app.domain.model.AudioChunk
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton

data class VadResult(
    val isSpeech: Boolean,
    val probability: Float,
)

@Singleton
class SileroVadProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val ortEnvironment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var ortSession: OrtSession? = null

    private var state: FloatArray = FloatArray(STATE_SIZE)

    @Volatile
    var currentSampleRate: Int = AudioConfig.SAMPLE_RATE

    private val speechThreshold = 0.5f
    private val silenceDurationFrames = 10

    fun init() {
        if (ortSession != null) return
        val modelBytes = context.assets.open(MODEL_FILE).readBytes()
        ortSession = ortEnvironment.createSession(modelBytes)
        resetState()
        Log.i(TAG, "Silero VAD model loaded")
    }

    fun release() {
        ortSession?.close()
        ortSession = null
        resetState()
    }

    fun process(frame: ShortArray): VadResult {
        val session = ortSession
            ?: throw IllegalStateException("SileroVadProcessor not initialized. Call init() first.")

        val resampled = AudioResampler.resample(frame, currentSampleRate, MODEL_SAMPLE_RATE)
        val floatFrame = FloatArray(resampled.size) { resampled[it] / 32768f }

        val inputTensor = OnnxTensor.createTensor(
            ortEnvironment,
            FloatBuffer.wrap(floatFrame),
            longArrayOf(1, resampled.size.toLong()),
        )
        val stateTensor = OnnxTensor.createTensor(
            ortEnvironment,
            FloatBuffer.wrap(state),
            longArrayOf(2, 1, STATE_DIM.toLong()),
        )
        val srTensor = OnnxTensor.createTensor(
            ortEnvironment,
            longArrayOf(MODEL_SAMPLE_RATE.toLong()),
        )

        val inputs = mapOf(
            "input" to inputTensor,
            "state" to stateTensor,
            "sr" to srTensor,
        )

        val results = session.run(inputs)

        val outputProb = (results["output"].get().value as Array<FloatArray>)[0][0]

        val newStateRaw = results["stateN"].get().value
        state = flattenState(newStateRaw)

        inputTensor.close()
        stateTensor.close()
        srTensor.close()
        results.close()

        return VadResult(
            isSpeech = outputProb >= speechThreshold,
            probability = outputProb,
        )
    }

    fun collectSpeechSegment(frames: Flow<ShortArray>): Flow<AudioChunk> = flow {
        val speechFrames = mutableListOf<ShortArray>()
        var silenceCount = 0
        var segmentStartTime = 0L

        frames.collect { frame ->
            val result = process(frame)

            if (result.isSpeech) {
                if (speechFrames.isEmpty()) {
                    segmentStartTime = System.currentTimeMillis()
                }
                speechFrames.add(frame.copyOf())
                silenceCount = 0
            } else if (speechFrames.isNotEmpty()) {
                silenceCount++
                if (silenceCount >= silenceDurationFrames) {
                    emit(buildAudioChunk(speechFrames, segmentStartTime))
                    speechFrames.clear()
                    silenceCount = 0
                }
            }
        }

        if (speechFrames.isNotEmpty()) {
            emit(buildAudioChunk(speechFrames, segmentStartTime))
        }
    }

    private fun buildAudioChunk(speechFrames: List<ShortArray>, startTimestamp: Long): AudioChunk {
        val totalSamples = speechFrames.sumOf { it.size }
        val pcmData = ByteArray(totalSamples * AudioConfig.BYTES_PER_SAMPLE)
        var offset = 0
        for (frame in speechFrames) {
            for (sample in frame) {
                pcmData[offset++] = (sample.toInt() and 0xFF).toByte()
                pcmData[offset++] = (sample.toInt() shr 8 and 0xFF).toByte()
            }
        }
        val rate = currentSampleRate
        val durationMs = (totalSamples * 1000) / rate
        return AudioChunk(
            pcmData = pcmData,
            startTimestamp = startTimestamp,
            durationMs = durationMs,
            sampleRate = rate,
            isSpeakerVerified = false,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun flattenState(rawState: Any): FloatArray {
        val state3d = rawState as Array<Array<FloatArray>>
        val result = FloatArray(STATE_SIZE)
        var idx = 0
        for (dim0 in state3d) {
            for (dim1 in dim0) {
                for (value in dim1) {
                    result[idx++] = value
                }
            }
        }
        return result
    }

    private fun resetState() {
        state = FloatArray(STATE_SIZE)
    }

    companion object {
        private const val TAG = "SileroVadProcessor"
        private const val MODEL_FILE = "silero_vad.onnx"
        private const val MODEL_SAMPLE_RATE = 16_000
        private const val STATE_DIM = 128
        private const val STATE_SIZE = 2 * 1 * STATE_DIM
    }
}
