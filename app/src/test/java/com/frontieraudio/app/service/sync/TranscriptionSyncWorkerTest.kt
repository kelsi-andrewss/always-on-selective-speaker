package com.frontieraudio.app.service.sync

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TranscriptionSyncWorkerTest {

    private lateinit var pcmData: ByteArray
    private lateinit var wav: ByteArray

    private val sampleRate = 16_000
    private val channels = 1
    private val bitsPerSample = 16

    @BeforeEach
    fun setUp() {
        pcmData = ByteArray(10) { (it + 1).toByte() } // 0x01..0x0A
        wav = WavConverter.pcmToWav(pcmData, sampleRate, channels, bitsPerSample)
    }

    // -- helpers --

    private fun readInt32LE(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or
        ((buf[offset + 1].toInt() and 0xFF) shl 8) or
        ((buf[offset + 2].toInt() and 0xFF) shl 16) or
        ((buf[offset + 3].toInt() and 0xFF) shl 24)

    private fun readInt16LE(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or
        ((buf[offset + 1].toInt() and 0xFF) shl 8)

    private fun asciiAt(buf: ByteArray, offset: Int, length: Int): String =
        String(buf, offset, length, Charsets.US_ASCII)

    // -- tests --

    @Test
    fun riffHeader_hasMagicBytes() {
        assertEquals("RIFF", asciiAt(wav, 0, 4))
        assertEquals("WAVE", asciiAt(wav, 8, 4))
    }

    @Test
    fun riffHeader_fileSizeField() {
        assertEquals(wav.size - 8, readInt32LE(wav, 4))
    }

    @Test
    fun fmtChunk_magicAndSize() {
        assertEquals("fmt ", asciiAt(wav, 12, 4))
        assertEquals(16, readInt32LE(wav, 16))
    }

    @Test
    fun fmtChunk_audioFormat_isPCM() {
        assertEquals(1, readInt16LE(wav, 20))
    }

    @Test
    fun fmtChunk_channelsAndSampleRate() {
        assertEquals(channels, readInt16LE(wav, 22))
        assertEquals(sampleRate, readInt32LE(wav, 24))
    }

    @Test
    fun fmtChunk_byteRateAndBlockAlign() {
        val expectedByteRate = sampleRate * channels * bitsPerSample / 8 // 32000
        val expectedBlockAlign = channels * bitsPerSample / 8            // 2
        assertEquals(expectedByteRate, readInt32LE(wav, 28))
        assertEquals(expectedBlockAlign, readInt16LE(wav, 32))
    }

    @Test
    fun fmtChunk_bitsPerSample() {
        assertEquals(bitsPerSample, readInt16LE(wav, 34))
    }

    @Test
    fun dataChunk_magicAndSize() {
        assertEquals("data", asciiAt(wav, 36, 4))
        assertEquals(pcmData.size, readInt32LE(wav, 40))
    }

    @Test
    fun dataChunk_containsPcmPayload() {
        assertArrayEquals(pcmData, wav.sliceArray(44 until wav.size))
    }

    @Test
    fun totalSize_is44PlusDataSize() {
        assertEquals(44 + pcmData.size, wav.size)
    }

    @Test
    fun emptyPcmData_producesValidHeader() {
        val empty = WavConverter.pcmToWav(ByteArray(0), sampleRate, channels, bitsPerSample)
        assertEquals(44, empty.size)
        assertEquals(0, readInt32LE(empty, 40))
        assertEquals(36, readInt32LE(empty, 4))
    }

    @Test
    fun stereoConfig_calculatesCorrectly() {
        val stereo = WavConverter.pcmToWav(
            pcmData = ByteArray(8),
            sampleRate = 44_100,
            channels = 2,
            bitsPerSample = 16,
        )
        assertEquals(2, readInt16LE(stereo, 22))
        assertEquals(176_400, readInt32LE(stereo, 28)) // 44100 * 2 * 16/8
        assertEquals(4, readInt16LE(stereo, 32))        // 2 * 16/8
    }
}
