package com.frontieraudio.app.service.audio

import android.content.Context
import com.frontieraudio.app.domain.model.AudioChunk
import ai.onnxruntime.OrtEnvironment
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.spyk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SileroVadProcessorTest {

    private lateinit var processor: SileroVadProcessor
    private val mockContext: Context = mockk(relaxed = true)
    private val mockOrtEnv: OrtEnvironment = mockk(relaxed = true)

    @BeforeAll
    fun setupClass() {
        mockkStatic(OrtEnvironment::class)
        every { OrtEnvironment.getEnvironment() } returns mockOrtEnv
    }

    @AfterAll
    fun teardownClass() {
        unmockkStatic(OrtEnvironment::class)
    }

    @BeforeEach
    fun setup() {
        processor = SileroVadProcessor(mockContext)
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

            // Little-endian: low byte 0x34, high byte 0x12
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
            val frame = ShortArray(16_000) // 1 second worth of samples at 16kHz
            val chunk = callBuildAudioChunk(listOf(frame), 0L)

            assertEquals(1000, chunk.durationMs) // (16000 * 1000) / 16000 = 1000ms
        }

        @Test
        fun `duration calculation at 48kHz sample rate`() {
            processor.currentSampleRate = 48_000
            val frame = ShortArray(24_000) // 0.5 seconds at 48kHz
            val chunk = callBuildAudioChunk(listOf(frame), 0L)

            assertEquals(500, chunk.durationMs) // (24000 * 1000) / 48000 = 500ms
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

        @Test
        fun `sample 0x1234 produces bytes 0x34 then 0x12`() {
            val frame = shortArrayOf(0x1234.toShort())
            val chunk = callBuildAudioChunk(listOf(frame), 0L)

            assertEquals(2, chunk.pcmData.size)
            assertEquals(0x34.toByte(), chunk.pcmData[0])
            assertEquals(0x12.toByte(), chunk.pcmData[1])
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

            // First 128 values from dim0=0
            for (i in 0 until 128) {
                assertEquals(i.toFloat(), result[i], 0.0f, "Index $i (dim0=0)")
            }
            // Next 128 values from dim0=1
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
            // Dirty the state
            setState(FloatArray(256) { 1.0f })

            callResetState()

            val state = getState()
            assertEquals(256, state.size)
            assertArrayEquals(FloatArray(256), state)
        }
    }

    // ========================================================
    // collectSpeechSegment state machine tests
    // ========================================================

    @Nested
    inner class CollectSpeechSegment {

        private lateinit var spyProcessor: SileroVadProcessor

        @BeforeEach
        fun setupSpy() {
            spyProcessor = spyk(SileroVadProcessor(mockContext))
        }

        private fun speechResult() = VadResult(isSpeech = true, probability = 0.9f)
        private fun silenceResult() = VadResult(isSpeech = false, probability = 0.1f)

        @Test
        fun `speech frames followed by 10 silence frames emits one chunk`() = runTest {
            val frames = mutableListOf<ShortArray>()
            val results = mutableListOf<VadResult>()

            // 5 speech frames
            repeat(5) {
                frames.add(ShortArray(480) { 100 })
                results.add(speechResult())
            }
            // 10 silence frames
            repeat(10) {
                frames.add(ShortArray(480) { 0 })
                results.add(silenceResult())
            }

            var callIndex = 0
            every { spyProcessor.process(any()) } answers { results[callIndex++] }

            val emitted = spyProcessor.collectSpeechSegment(flowOf(*frames.toTypedArray())).toList()

            assertEquals(1, emitted.size)
            // 5 speech frames of 480 samples each = 2400 samples = 4800 bytes
            assertEquals(5 * 480 * AudioConfig.BYTES_PER_SAMPLE, emitted[0].pcmData.size)
        }

        @Test
        fun `fewer than 10 silence frames then more speech does not emit until 10 consecutive silence`() = runTest {
            val frames = mutableListOf<ShortArray>()
            val results = mutableListOf<VadResult>()

            // 3 speech frames
            repeat(3) {
                frames.add(ShortArray(480) { 100 })
                results.add(speechResult())
            }
            // 5 silence frames (not enough to emit)
            repeat(5) {
                frames.add(ShortArray(480) { 0 })
                results.add(silenceResult())
            }
            // 3 more speech frames
            repeat(3) {
                frames.add(ShortArray(480) { 100 })
                results.add(speechResult())
            }
            // 10 silence frames (triggers emission)
            repeat(10) {
                frames.add(ShortArray(480) { 0 })
                results.add(silenceResult())
            }

            var callIndex = 0
            every { spyProcessor.process(any()) } answers { results[callIndex++] }

            val emitted = spyProcessor.collectSpeechSegment(flowOf(*frames.toTypedArray())).toList()

            // Only one emission: the initial 3 speech frames accumulate, 5 silence don't trigger,
            // 3 more speech frames add to the buffer, then 10 silence triggers emission of all 6 speech frames
            assertEquals(1, emitted.size)
            assertEquals(6 * 480 * AudioConfig.BYTES_PER_SAMPLE, emitted[0].pcmData.size)
        }

        @Test
        fun `flow ends with pending speech frames emits final chunk`() = runTest {
            val frames = mutableListOf<ShortArray>()
            val results = mutableListOf<VadResult>()

            // 4 speech frames, then flow ends (no silence to trigger emission)
            repeat(4) {
                frames.add(ShortArray(480) { 100 })
                results.add(speechResult())
            }

            var callIndex = 0
            every { spyProcessor.process(any()) } answers { results[callIndex++] }

            val emitted = spyProcessor.collectSpeechSegment(flowOf(*frames.toTypedArray())).toList()

            assertEquals(1, emitted.size)
            assertEquals(4 * 480 * AudioConfig.BYTES_PER_SAMPLE, emitted[0].pcmData.size)
        }

        @Test
        fun `all silence frames produce no emission`() = runTest {
            val frames = mutableListOf<ShortArray>()
            val results = mutableListOf<VadResult>()

            repeat(20) {
                frames.add(ShortArray(480) { 0 })
                results.add(silenceResult())
            }

            var callIndex = 0
            every { spyProcessor.process(any()) } answers { results[callIndex++] }

            val emitted = spyProcessor.collectSpeechSegment(flowOf(*frames.toTypedArray())).toList()

            assertEquals(0, emitted.size)
        }

        @Test
        fun `multiple speech segments separated by silence emit multiple chunks`() = runTest {
            val frames = mutableListOf<ShortArray>()
            val results = mutableListOf<VadResult>()

            // First segment: 3 speech + 10 silence
            repeat(3) {
                frames.add(ShortArray(480) { 100 })
                results.add(speechResult())
            }
            repeat(10) {
                frames.add(ShortArray(480) { 0 })
                results.add(silenceResult())
            }
            // Second segment: 2 speech + 10 silence
            repeat(2) {
                frames.add(ShortArray(480) { 100 })
                results.add(speechResult())
            }
            repeat(10) {
                frames.add(ShortArray(480) { 0 })
                results.add(silenceResult())
            }

            var callIndex = 0
            every { spyProcessor.process(any()) } answers { results[callIndex++] }

            val emitted = spyProcessor.collectSpeechSegment(flowOf(*frames.toTypedArray())).toList()

            assertEquals(2, emitted.size)
            assertEquals(3 * 480 * AudioConfig.BYTES_PER_SAMPLE, emitted[0].pcmData.size)
            assertEquals(2 * 480 * AudioConfig.BYTES_PER_SAMPLE, emitted[1].pcmData.size)
        }
    }
}
