package com.frontieraudio.app.service.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

object AudioConfig {
    const val SAMPLE_RATE = 16_000
    const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    const val FRAME_SIZE_MS = 32
    const val BYTES_PER_SAMPLE = 2

    val FRAME_SIZE_SAMPLES: Int = SAMPLE_RATE * FRAME_SIZE_MS / 1000

    val MIN_BUFFER_SIZE: Int
        get() = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)

    val BUFFER_SIZE: Int
        get() = MIN_BUFFER_SIZE.coerceAtLeast(FRAME_SIZE_SAMPLES * BYTES_PER_SAMPLE) * 2

    val PRIMARY_AUDIO_SOURCE: Int = MediaRecorder.AudioSource.UNPROCESSED
    val FALLBACK_AUDIO_SOURCE: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION
}
