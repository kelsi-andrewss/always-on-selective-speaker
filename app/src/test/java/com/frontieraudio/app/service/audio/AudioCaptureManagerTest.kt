package com.frontieraudio.app.service.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class AudioCaptureManagerTest {

    // ---- Companion: frameSamplesForRate ----

    @Nested
    @DisplayName("frameSamplesForRate")
    inner class FrameSamplesForRate {

        @Test
        fun `16kHz produces 480 samples per frame`() {
            assertEquals(480, AudioCaptureManager.frameSamplesForRate(16_000))
        }

        @Test
        fun `8kHz produces 240 samples per frame`() {
            assertEquals(240, AudioCaptureManager.frameSamplesForRate(8_000))
        }

        @Test
        fun `48kHz produces 1440 samples per frame`() {
            assertEquals(1440, AudioCaptureManager.frameSamplesForRate(48_000))
        }
    }

    // ---- Companion: audioSourceInt ----

    @Nested
    @DisplayName("audioSourceInt")
    inner class AudioSourceIntMapping {

        @Test
        fun `VOICE_COMMUNICATION maps to PRIMARY_AUDIO_SOURCE`() {
            assertEquals(
                AudioConfig.PRIMARY_AUDIO_SOURCE,
                AudioCaptureManager.audioSourceInt(AudioSource.VOICE_COMMUNICATION),
            )
        }

        @Test
        fun `VOICE_RECOGNITION maps to FALLBACK_AUDIO_SOURCE`() {
            assertEquals(
                AudioConfig.FALLBACK_AUDIO_SOURCE,
                AudioCaptureManager.audioSourceInt(AudioSource.VOICE_RECOGNITION),
            )
        }
    }

    // ---- Private: isSilent (via reflection) ----

    @Nested
    @DisplayName("isSilent")
    inner class IsSilent {

        private val manager = AudioCaptureManager()
        private val isSilentMethod = AudioCaptureManager::class.java
            .getDeclaredMethod("isSilent", ShortArray::class.java, Int::class.java)
            .also { it.isAccessible = true }

        private fun isSilent(buffer: ShortArray, length: Int): Boolean =
            isSilentMethod.invoke(manager, buffer, length) as Boolean

        @Test
        fun `all-zero buffer is silent`() {
            val buffer = ShortArray(480)
            assertTrue(isSilent(buffer, 480))
        }

        @Test
        fun `buffer at 49 is silent (below threshold 50)`() {
            val buffer = ShortArray(480) { 49 }
            assertTrue(isSilent(buffer, 480))
        }

        @Test
        fun `buffer at 50 is NOT silent (threshold is strict less-than)`() {
            val buffer = ShortArray(480) { 50 }
            assertFalse(isSilent(buffer, 480))
        }

        @Test
        fun `buffer at 51 is NOT silent`() {
            val buffer = ShortArray(480) { 51 }
            assertFalse(isSilent(buffer, 480))
        }

        @Test
        fun `single large sample diluted by averaging stays silent`() {
            // Short.MAX_VALUE (32767) in a buffer of 1000 zeros: avg = 32767/1000 = 32 < 50
            val buffer = ShortArray(1000)
            buffer[0] = Short.MAX_VALUE
            assertTrue(isSilent(buffer, 1000))
        }

        @Test
        fun `negative amplitudes use absolute value`() {
            val buffer = ShortArray(480) { (-49).toShort() }
            assertTrue(isSilent(buffer, 480))
        }

        @Test
        fun `negative amplitudes above threshold are NOT silent`() {
            val buffer = ShortArray(480) { (-51).toShort() }
            assertFalse(isSilent(buffer, 480))
        }
    }

    // ---- Private companion: silenceFrameCount ----

    @Nested
    @DisplayName("silenceFrameCount")
    inner class SilenceFrameCount {

        private val companionInstance: Any = AudioCaptureManager::class.java
            .getDeclaredField("Companion")
            .let { it.isAccessible = true; it.get(null)!! }

        private val silenceFrameCountMethod = companionInstance::class.java
            .getDeclaredMethod("silenceFrameCount", Int::class.java)
            .also { it.isAccessible = true }

        private fun silenceFrameCount(sampleRate: Int): Int =
            silenceFrameCountMethod.invoke(companionInstance, sampleRate) as Int

        @Test
        fun `returns 66 regardless of sample rate`() {
            // 2000ms / 30ms-per-frame = 66 frames
            assertEquals(66, silenceFrameCount(16_000))
            assertEquals(66, silenceFrameCount(8_000))
            assertEquals(66, silenceFrameCount(48_000))
        }
    }

    // ---- Private constants ----

    @Nested
    @DisplayName("constants")
    inner class Constants {

        @Test
        fun `DISCARD_AFTER_SWITCH_MS is 500`() {
            val field = AudioCaptureManager::class.java.getDeclaredField("DISCARD_AFTER_SWITCH_MS")
            field.isAccessible = true
            assertEquals(500L, field.get(null))
        }

        @Test
        fun `SILENCE_AMPLITUDE_THRESHOLD is 50`() {
            val field = AudioCaptureManager::class.java.getDeclaredField("SILENCE_AMPLITUDE_THRESHOLD")
            field.isAccessible = true
            assertEquals(50, field.get(null))
        }
    }
}
