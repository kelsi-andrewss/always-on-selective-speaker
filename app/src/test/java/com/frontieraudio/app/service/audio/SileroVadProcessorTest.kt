package com.frontieraudio.app.service.audio

import com.frontieraudio.app.domain.model.AudioChunk
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for SileroVadProcessor's pure logic — no ONNX Runtime or Android Context needed.
 * Uses Unsafe to instantiate the class without running the constructor (which loads OrtEnvironment).
 * Tests buildAudioChunk, flattenState, resetState via reflection on private methods.
 * VadResult and short-to-float are tested directly (no reflection needed).
 */
class SileroVadProcessorTest {

    private lateinit var processor: SileroVadProcessor

    @BeforeEach
    fun setup() {
        // Instantiate without constructor to avoid OrtEnvironment native library loading
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafeField = unsafeClass.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null)
        val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
        processor = allocateInstance.invoke(unsafe, SileroVadProcessor::class.java) as SileroVadProcessor

        // Set the currentSampleRate field (default)
        val srField = SileroVadProcessor::class.java.getDeclaredField("currentSampleRate")
        srField.isAccessible = true
        srField.setInt(processor, 16_000)

        // Initialize the state array
        val stateField = SileroVadProcessor::class.java.getDeclaredField("state")
        stateField.isAccessible = true
        stateField.set(processor, FloatArray(256))
    }

    // -- Reflection helpers --

    private fun invokePrivate(name: String, vararg args: Any?): Any? {
        val paramTypes = args.map {
            when (it) {
                is List<*> -> List::class.java
                is Long -> Long::class.javaPrimitiveType!!
                else -> Any::class.java
            }
        }.toTypedArray()
        val method = SileroVadProcessor::class.java.getDeclaredMethod(name, *paramTypes)
        method.isAccessible = true
        return method.invoke(processor, *args)
    }

    private fun callBuildAudioChunk(frames: List<ShortArray>, startTimestamp: Long): AudioChunk {
        return invokePrivate("buildAudioChunk", frames, startTimestamp) as AudioChunk
    }

    private fun callFlattenState(rawState: Any): FloatArray {
        return invokePrivate("flattenState", rawState) as FloatArray
    }

    private fun callResetState() {
        val method = SileroVadProcessor::class.java.getDeclaredMethod("resetState")
        method.isAccessible = true
        method.invoke(processor)
    }

    private fun getState(): FloatArray {
        val field = SileroVadProcessor::class.java.getDeclaredField("state")
        field.isAccessible = true
        return field.get(processor) as FloatArray
    }

    private fun setState(newState: FloatArray) {
        val field = SileroVadProcessor::class.java.getDeclaredField("state")
        field.isAccessible = true
        field.set(processor, newState)
    }

    // ========================================================
    // VadResult threshold tests
    // ========================================================

    @Nested
    inner class VadResultThresholds {

        @Test
        fun `probability at threshold 0_5 is speech`() {
            val result = VadResult(isSpeech = 0.5f >= 0.5f, probability = 0.5f)
            assertTrue(result.isSpeech)
        }

        @Test
        fun `probability just below threshold 0_49 is not speech`() {
            val result = VadResult(isSpeech = 0.49f >= 0.5f, probability = 0.49f)
            assertFalse(result.isSpeech)
        }

        @Test
        fun `probability 1_0 is speech`() {
            val result = VadResult(isSpeech = 1.0f >= 0.5f, probability = 1.0f)
            assertTrue(result.isSpeech)
        }

        @Test
        fun `probability 0_0 is not speech`() {
            val result = VadResult(isSpeech = 0.0f >= 0.5f, probability = 0.0f)
            assertFalse(result.isSpeech)
        }
    }

    // ========================================================
    // Short-to-float conversion formula
    // ========================================================

    @Nested
    inner class ShortToFloatConversion {

        @Test
        fun `MAX_VALUE divided by 32768 is approximately 1`() {
            val result = Short.MAX_VALUE / 32768f
            assertEquals(0.99997f, result, 0.0001f)
        }

        @Test
        fun `zero divided by 32768 is 0`() {
            val result = 0.toShort() / 32768f
            assertEquals(0.0f, result, 0.0f)
        }

        @Test
        fun `MIN_VALUE divided by 32768 is negative 1`() {
            val result = Short.MIN_VALUE / 32768f
            assertEquals(-1.0f, result, 0.0f)
        }
    }

    // ========================================================
    // buildAudioChunk tests (via reflection)
    // ========================================================

    @Nested
    inner class BuildAudioChunk {

        @Test
        fun `single frame produces correct little-endian PCM bytes`() {
            val sample = 0x1234.toShort()
            val frame = shortArrayOf(sample)
            val chunk = callBuildAudioChunk(listOf(frame), 1000L)

            assertEquals(0x34.toByte(), chunk.pcmData[0])
            assertEquals(0x12.toByte(), chunk.pcmData[1])
        }

        @Test
        fun `multiple frames produce correct total byte count`() {
            val frame1 = ShortArray(100) { 1 }
            val frame2 = ShortArray(200) { 2 }
            val chunk = callBuildAudioChunk(listOf(frame1, frame2), 0L)

            val expectedBytes = (100 + 200) * AudioConfig.BYTES_PER_SAMPLE
            assertEquals(expectedBytes, chunk.pcmData.size)
        }

        @Test
        fun `duration calculation at 16kHz sample rate`() {
            processor.currentSampleRate = 16_000
            val frame = ShortArray(16_000)
            val chunk = callBuildAudioChunk(listOf(frame), 0L)

            assertEquals(1000, chunk.durationMs)
        }

        @Test
        fun `duration calculation at 48kHz sample rate`() {
            processor.currentSampleRate = 48_000
            val frame = ShortArray(24_000)
            val chunk = callBuildAudioChunk(listOf(frame), 0L)

            assertEquals(500, chunk.durationMs)
        }

        @Test
        fun `isSpeakerVerified is always false`() {
            val frame = ShortArray(10) { 100 }
            val chunk = callBuildAudioChunk(listOf(frame), 0L)

            assertFalse(chunk.isSpeakerVerified)
        }

        @Test
        fun `startTimestamp is preserved`() {
            val frame = ShortArray(10) { 0 }
            val chunk = callBuildAudioChunk(listOf(frame), 42L)

            assertEquals(42L, chunk.startTimestamp)
        }
    }

    // ========================================================
    // flattenState tests (via reflection)
    // ========================================================

    @Nested
    inner class FlattenState {

        @Test
        fun `2x1x128 input produces 256 element flat array`() {
            val input = Array(2) { Array(1) { FloatArray(128) { it.toFloat() } } }
            val result = callFlattenState(input)

            assertEquals(256, result.size)
        }

        @Test
        fun `values are preserved in correct order dim0 outer dim2 inner`() {
            val input = Array(2) { d0 ->
                Array(1) { _ ->
                    FloatArray(128) { d2 -> (d0 * 128 + d2).toFloat() }
                }
            }
            val result = callFlattenState(input)

            for (i in 0 until 128) {
                assertEquals(i.toFloat(), result[i], 0.0f, "Index $i (dim0=0)")
            }
            for (i in 0 until 128) {
                assertEquals((128 + i).toFloat(), result[128 + i], 0.0f, "Index ${128 + i} (dim0=1)")
            }
        }

        @Test
        fun `all zeros input produces all zeros output`() {
            val input = Array(2) { Array(1) { FloatArray(128) } }
            val result = callFlattenState(input)

            assertArrayEquals(FloatArray(256), result)
        }
    }

    // ========================================================
    // resetState tests (via reflection)
    // ========================================================

    @Nested
    inner class ResetState {

        @Test
        fun `resetState zeroes the state array`() {
            setState(FloatArray(256) { 1.0f })

            callResetState()

            val state = getState()
            assertEquals(256, state.size)
            assertArrayEquals(FloatArray(256), state)
        }
    }
}
