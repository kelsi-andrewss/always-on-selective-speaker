package com.frontieraudio.app.service.audio

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.frontieraudio.app.domain.model.AudioChunk
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import java.nio.FloatBuffer
import java.nio.LongBuffer
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
    private var contextBuffer: FloatArray = FloatArray(CONTEXT_SIZE)

    private var diagnosticCounter = 0

    @Volatile
    var currentSampleRate: Int = AudioConfig.SAMPLE_RATE

    private val speechThreshold = 0.5f
    private val silenceDurationFrames = 30  // ~960ms silence before segment ends
    private val minSegmentDurationMs = 1500  // drop segments shorter than 1.5s

    private val _lastVadResult = MutableStateFlow(VadResult(isSpeech = false, probability = 0f))
    val lastVadResult: StateFlow<VadResult> = _lastVadResult.asStateFlow()

    private val _lastSpeechTimestamp = MutableStateFlow(0L)
    val lastSpeechTimestamp: StateFlow<Long> = _lastSpeechTimestamp.asStateFlow()

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

        // Diagnostic: log actual audio energy every 50 frames
        if (diagnosticCounter++ % 50 == 0) {
            var maxAbs: Short = 0
            for (s in frame) { if (kotlin.math.abs(s.toInt()) > maxAbs) maxAbs = kotlin.math.abs(s.toInt()).toShort() }
            var rms = 0.0
            for (s in frame) { rms += (s.toDouble() / 32768.0).let { it * it } }
            rms = kotlin.math.sqrt(rms / frame.size)
            Log.d(TAG, "DIAG frame: size=${frame.size}, maxAbs=$maxAbs, rms=${"%.6f".format(rms)}, floatFrame[0]=${"%.6f".format(floatFrame.getOrNull(0) ?: 0f)}")
        }

        // V5 model expects [1, 576]: 64 context samples + 512 window samples
        val inputWithContext = FloatArray(CONTEXT_SIZE + WINDOW_SIZE)
        System.arraycopy(contextBuffer, 0, inputWithContext, 0, CONTEXT_SIZE)
        val copyLen = floatFrame.size.coerceAtMost(WINDOW_SIZE)
        System.arraycopy(floatFrame, 0, inputWithContext, CONTEXT_SIZE, copyLen)

        // Update context buffer with tail of current frame
        if (floatFrame.size >= CONTEXT_SIZE) {
            System.arraycopy(floatFrame, floatFrame.size - CONTEXT_SIZE, contextBuffer, 0, CONTEXT_SIZE)
        } else {
            // Shift existing context and append
            val shift = CONTEXT_SIZE - floatFrame.size
            System.arraycopy(contextBuffer, floatFrame.size, contextBuffer, 0, shift)
            System.arraycopy(floatFrame, 0, contextBuffer, shift, floatFrame.size)
        }

        val inputTensor = OnnxTensor.createTensor(
            ortEnvironment,
            FloatBuffer.wrap(inputWithContext),
            longArrayOf(1, INPUT_SIZE.toLong()),
        )
        val stateTensor = OnnxTensor.createTensor(
            ortEnvironment,
            FloatBuffer.wrap(state),
            longArrayOf(2, 1, STATE_DIM.toLong()),
        )
        val srTensor = OnnxTensor.createTensor(
            ortEnvironment,
            LongBuffer.wrap(longArrayOf(MODEL_SAMPLE_RATE.toLong())),
            longArrayOf(1),
        )

        val inputs = mapOf(
            "input" to inputTensor,
            "state" to stateTensor,
            "sr" to srTensor,
        )

        val results = session.run(inputs)

        val outputProb = (results[0].value as Array<FloatArray>)[0][0]

        val newStateRaw = results[1].value
        state = flattenState(newStateRaw)

        inputTensor.close()
        stateTensor.close()
        srTensor.close()
        results.close()

        val result = VadResult(
            isSpeech = outputProb >= speechThreshold,
            probability = outputProb,
        )
        _lastVadResult.value = result
        if (result.isSpeech) {
            _lastSpeechTimestamp.value = System.currentTimeMillis()
        }
        Log.d(TAG, "VAD prob=$outputProb speech=${result.isSpeech}")
        return result
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
                    val chunk = buildAudioChunk(speechFrames, segmentStartTime)
                    if (chunk.durationMs >= minSegmentDurationMs) {
                        emit(chunk)
                    } else {
                        Log.d(TAG, "Dropping short segment: ${chunk.durationMs}ms")
                    }
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
        contextBuffer = FloatArray(CONTEXT_SIZE)
    }

    companion object {
        private const val TAG = "SileroVadProcessor"
        private const val MODEL_FILE = "silero_vad.onnx"
        private const val MODEL_SAMPLE_RATE = 16_000
        private const val STATE_DIM = 128
        private const val STATE_SIZE = 2 * 1 * STATE_DIM
        private const val CONTEXT_SIZE = 64
        private const val WINDOW_SIZE = 512
        private const val INPUT_SIZE = CONTEXT_SIZE + WINDOW_SIZE  // 576
    }
}
