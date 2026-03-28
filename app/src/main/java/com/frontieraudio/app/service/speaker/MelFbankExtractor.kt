package com.frontieraudio.app.service.speaker

import org.jtransforms.fft.FloatFFT_1D
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln

/**
 * Kaldi-compatible mel filterbank feature extractor.
 * Uses JTransforms for FFT. Parameters match WeSpeaker ECAPA-TDNN training config.
 */
object MelFbankExtractor {

    private const val SAMPLE_RATE = 16000
    private const val FRAME_LENGTH_MS = 25
    private const val FRAME_SHIFT_MS = 10
    private const val NUM_MEL_BINS = 80
    private const val LOW_FREQ = 20.0
    private const val HIGH_FREQ = 8000.0
    private const val PRE_EMPHASIS = 0.97f

    private val FRAME_LENGTH = SAMPLE_RATE * FRAME_LENGTH_MS / 1000  // 400
    private val FRAME_SHIFT = SAMPLE_RATE * FRAME_SHIFT_MS / 1000    // 160
    private val FFT_SIZE = nextPow2(FRAME_LENGTH)                     // 512

    private val hammingWindow: FloatArray by lazy { createHammingWindow(FRAME_LENGTH) }
    private val melFilterbank: Array<FloatArray> by lazy { createMelFilterbank() }
    private val fft: FloatFFT_1D by lazy { FloatFFT_1D(FFT_SIZE.toLong()) }

    /**
     * Extract 80-dim mel fbank features from raw float audio samples.
     * @param samples PCM float samples in [-1, 1] range at 16kHz
     * @return [numFrames, 80] flattened as FloatArray (row-major)
     */
    fun extract(samples: FloatArray): FloatArray {
        // Pre-emphasis
        val emphasized = FloatArray(samples.size)
        emphasized[0] = samples[0]
        for (i in 1 until samples.size) {
            emphasized[i] = samples[i] - PRE_EMPHASIS * samples[i - 1]
        }

        val numFrames = numFrames(samples.size)
        if (numFrames == 0) return FloatArray(0)

        val features = FloatArray(numFrames * NUM_MEL_BINS)

        for (f in 0 until numFrames) {
            val start = f * FRAME_SHIFT

            // Window the frame and prepare for FFT
            // JTransforms real FFT expects float array of size N
            val fftInput = FloatArray(FFT_SIZE)
            for (i in 0 until FRAME_LENGTH) {
                val idx = start + i
                if (idx < emphasized.size) {
                    fftInput[i] = emphasized[idx] * hammingWindow[i]
                }
            }

            // Real FFT in-place — output is interleaved [re0, re(N/2), re1, im1, re2, im2, ...]
            fft.realForward(fftInput)

            // Power spectrum: |X[k]|^2 for k = 0..N/2
            val numBins = FFT_SIZE / 2 + 1
            val powerSpec = FloatArray(numBins)

            // DC component (index 0): fftInput[0] is real, imaginary is 0
            powerSpec[0] = fftInput[0] * fftInput[0]
            // Nyquist component (index N/2): fftInput[1] is real, imaginary is 0
            powerSpec[numBins - 1] = fftInput[1] * fftInput[1]
            // Other bins: interleaved as [re_k, im_k] starting at index 2
            for (k in 1 until numBins - 1) {
                val re = fftInput[2 * k]
                val im = fftInput[2 * k + 1]
                powerSpec[k] = re * re + im * im
            }

            // Apply mel filterbank and log
            for (m in 0 until NUM_MEL_BINS) {
                var energy = 0f
                for (k in powerSpec.indices) {
                    energy += melFilterbank[m][k] * powerSpec[k]
                }
                features[f * NUM_MEL_BINS + m] = ln(energy.coerceAtLeast(Float.MIN_VALUE).toDouble()).toFloat()
            }
        }

        // Utterance-level CMVN: subtract per-bin mean
        val mean = FloatArray(NUM_MEL_BINS)
        for (f in 0 until numFrames) {
            for (m in 0 until NUM_MEL_BINS) {
                mean[m] += features[f * NUM_MEL_BINS + m]
            }
        }
        for (m in 0 until NUM_MEL_BINS) {
            mean[m] /= numFrames
        }
        for (f in 0 until numFrames) {
            for (m in 0 until NUM_MEL_BINS) {
                features[f * NUM_MEL_BINS + m] -= mean[m]
            }
        }

        return features
    }

    fun numFrames(numSamples: Int): Int {
        return if (numSamples >= FRAME_LENGTH) {
            1 + (numSamples - FRAME_LENGTH) / FRAME_SHIFT
        } else 0
    }

    private fun createHammingWindow(size: Int): FloatArray {
        return FloatArray(size) { i ->
            (0.54 - 0.46 * cos(2.0 * PI * i / (size - 1))).toFloat()
        }
    }

    private fun createMelFilterbank(): Array<FloatArray> {
        val numFftBins = FFT_SIZE / 2 + 1
        val lowMel = hzToMel(LOW_FREQ)
        val highMel = hzToMel(HIGH_FREQ)

        val melPoints = DoubleArray(NUM_MEL_BINS + 2) { i ->
            lowMel + i * (highMel - lowMel) / (NUM_MEL_BINS + 1)
        }
        val hzPoints = melPoints.map { melToHz(it) }
        val binPoints = hzPoints.map { hz ->
            floor(hz * FFT_SIZE / SAMPLE_RATE + 0.5).toInt()
        }

        return Array(NUM_MEL_BINS) { m ->
            val filter = FloatArray(numFftBins)
            val left = binPoints[m]
            val center = binPoints[m + 1]
            val right = binPoints[m + 2]

            for (k in left until center) {
                if (k in filter.indices && center > left) {
                    filter[k] = (k - left).toFloat() / (center - left)
                }
            }
            for (k in center until right) {
                if (k in filter.indices && right > center) {
                    filter[k] = (right - k).toFloat() / (right - center)
                }
            }
            filter
        }
    }

    private fun hzToMel(hz: Double): Double = 1127.0 * ln(1.0 + hz / 700.0)
    private fun melToHz(mel: Double): Double = 700.0 * (Math.exp(mel / 1127.0) - 1.0)

    private fun nextPow2(n: Int): Int {
        var v = n - 1
        v = v or (v shr 1)
        v = v or (v shr 2)
        v = v or (v shr 4)
        v = v or (v shr 8)
        v = v or (v shr 16)
        return v + 1
    }
}
