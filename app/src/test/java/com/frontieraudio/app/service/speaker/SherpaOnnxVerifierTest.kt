package com.frontieraudio.app.service.speaker

import kotlin.math.sqrt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SherpaOnnxVerifierTest {

    @Nested
    inner class CosineSimilarity {

        @Test
        fun `identical unit vectors return 1_0`() {
            val v = normalizedVector(192)
            val result = SherpaOnnxVerifier.cosineSimilarity(v, v.copyOf())
            assertEquals(1.0f, result, 1e-6f)
        }

        @Test
        fun `orthogonal vectors return 0_0`() {
            val a = FloatArray(192) { if (it % 2 == 0) 1f else 0f }
            val b = FloatArray(192) { if (it % 2 == 1) 1f else 0f }
            val result = SherpaOnnxVerifier.cosineSimilarity(a, b)
            assertEquals(0.0f, result, 1e-6f)
        }

        @Test
        fun `opposite vectors return negative 1_0`() {
            val a = normalizedVector(192)
            val b = FloatArray(192) { -a[it] }
            val result = SherpaOnnxVerifier.cosineSimilarity(a, b)
            assertEquals(-1.0f, result, 1e-6f)
        }

        @Test
        fun `zero vector A returns 0_0`() {
            val a = FloatArray(192) { 0f }
            val b = normalizedVector(192)
            val result = SherpaOnnxVerifier.cosineSimilarity(a, b)
            assertEquals(0.0f, result)
        }

        @Test
        fun `zero vector B returns 0_0`() {
            val a = normalizedVector(192)
            val b = FloatArray(192) { 0f }
            val result = SherpaOnnxVerifier.cosineSimilarity(a, b)
            assertEquals(0.0f, result)
        }

        @Test
        fun `both zero vectors return 0_0`() {
            val a = FloatArray(192) { 0f }
            val b = FloatArray(192) { 0f }
            val result = SherpaOnnxVerifier.cosineSimilarity(a, b)
            assertEquals(0.0f, result)
        }

        @Test
        fun `mismatched dimensions throws IllegalArgumentException`() {
            val a = FloatArray(192) { 1f }
            val b = FloatArray(128) { 1f }
            assertThrows<IllegalArgumentException> {
                SherpaOnnxVerifier.cosineSimilarity(a, b)
            }
        }

        @Test
        fun `known manual calculation`() {
            val a = floatArrayOf(3f, 4f)
            val b = floatArrayOf(4f, 3f)
            // dot = 12 + 12 = 24
            // |a| = sqrt(9+16) = 5, |b| = sqrt(16+9) = 5
            // expected = 24 / 25 = 0.96
            val expected = 24f / 25f
            val result = SherpaOnnxVerifier.cosineSimilarity(a, b)
            assertEquals(expected, result, 1e-6f)
        }
    }

    @Nested
    inner class VerificationResultThreshold {

        @Test
        fun `similarity exactly at threshold is match`() {
            val result = VerificationResult(
                isMatch = 0.65f >= SherpaOnnxVerifier.DEFAULT_THRESHOLD,
                similarity = 0.65f,
            )
            assertTrue(result.isMatch)
        }

        @Test
        fun `similarity just below threshold is not match`() {
            val result = VerificationResult(
                isMatch = 0.6499f >= SherpaOnnxVerifier.DEFAULT_THRESHOLD,
                similarity = 0.6499f,
            )
            assertFalse(result.isMatch)
        }

        @Test
        fun `similarity above threshold is match`() {
            val result = VerificationResult(
                isMatch = 0.9f >= SherpaOnnxVerifier.DEFAULT_THRESHOLD,
                similarity = 0.9f,
            )
            assertTrue(result.isMatch)
        }

        @Test
        fun `similarity of 0_0 is not match`() {
            val result = VerificationResult(
                isMatch = 0.0f >= SherpaOnnxVerifier.DEFAULT_THRESHOLD,
                similarity = 0.0f,
            )
            assertFalse(result.isMatch)
        }

        @Test
        fun `negative similarity is not match`() {
            val result = VerificationResult(
                isMatch = -0.5f >= SherpaOnnxVerifier.DEFAULT_THRESHOLD,
                similarity = -0.5f,
            )
            assertFalse(result.isMatch)
        }
    }

    @Nested
    inner class Constants {

        @Test
        fun `EMBEDDING_DIM is 192`() {
            assertEquals(192, SherpaOnnxVerifier.EMBEDDING_DIM)
        }

        @Test
        fun `DEFAULT_THRESHOLD is 0_65f`() {
            assertEquals(0.65f, SherpaOnnxVerifier.DEFAULT_THRESHOLD)
        }
    }

    private fun normalizedVector(size: Int): FloatArray {
        val raw = FloatArray(size) { (it + 1).toFloat() }
        val norm = sqrt(raw.fold(0f) { acc, v -> acc + v * v })
        return FloatArray(size) { raw[it] / norm }
    }
}
