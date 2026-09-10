package gr.dimitris.app.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The container his own take is written in, and the number the silence check is made on.
 *
 * Both are worth a test of their own because both fail invisibly. A header whose length field is a
 * chunk short plays as a word cut off halfway, and a peak read with the bytes the wrong way round
 * turns a quiet room into a shout — which would keep a silent take, the exact bug Chris found, and
 * pointing the other way would throw away the one word he got out.
 */
class WavTest {
    private fun header(dataBytes: Int) = Wav.header(dataBytes)

    private fun ascii(bytes: ByteArray, at: Int, length: Int) =
        String(bytes, at, length, Charsets.US_ASCII)

    private fun int32(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or ((bytes[at + 3].toInt() and 0xFF) shl 24)

    private fun int16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    // The header, field by field, read back the way a player reads it.

    @Test fun `the header is the canonical forty-four bytes`() {
        assertEquals(44, Wav.HEADER_BYTES)
        assertEquals(44, header(0).size)
    }

    @Test fun `it is a RIFF WAVE file with a fmt and a data chunk`() {
        val h = header(1_000)
        assertEquals("RIFF", ascii(h, 0, 4))
        assertEquals("WAVE", ascii(h, 8, 4))
        assertEquals("fmt ", ascii(h, 12, 4))
        assertEquals("data", ascii(h, 36, 4))
    }

    /**
     * The two lengths, and the one that is written twice. `RIFF` counts everything after its own
     * size field; `data` counts the samples alone. A file whose `data` size is short by one chunk is
     * a take that stops mid-word, and nothing about playing it would say why.
     */
    @Test fun `the two lengths say how much audio there really is`() {
        val h = header(32_000)
        assertEquals("everything after the RIFF size field", 36 + 32_000, int32(h, 4))
        assertEquals("the samples alone", 32_000, int32(h, 40))
    }

    /** The header written at the start, before a word has been said: valid, and empty. */
    @Test fun `an empty take is a valid file rather than a corrupt one`() {
        val h = header(0)
        assertEquals(36, int32(h, 4))
        assertEquals(0, int32(h, 40))
    }

    @Test fun `uncompressed sixteen-bit mono at sixteen kilohertz`() {
        val h = header(0)
        assertEquals("the fmt chunk is the sixteen-byte PCM one", 16, int32(h, 16))
        assertEquals("1 = uncompressed PCM", 1, int16(h, 20))
        assertEquals("one channel", 1, int16(h, 22))
        assertEquals("sixteen kilohertz", 16_000, int32(h, 24))
        assertEquals("sixteen bits a sample", 16, int16(h, 34))
    }

    /**
     * The byte rate and the block alignment have to follow from the other three or a player will
     * play his voice at the wrong speed — which, with an engine fed the same numbers through
     * `EXTRA_AUDIO_SOURCE_SAMPLING_RATE`, would also be the recogniser hearing a different man.
     */
    @Test fun `the byte rate and block alignment follow from the format`() {
        val h = header(0)
        assertEquals(16_000 * 1 * 2, int32(h, 28))
        assertEquals(2, int16(h, 32))
    }

    /** A caregiver's 44.1 kHz model is not what this writes, but the header must not lie about it. */
    @Test fun `another rate is written as that rate`() {
        val h = Wav.header(dataBytes = 100, sampleRate = 44_100, channels = 2)
        assertEquals(44_100, int32(h, 24))
        assertEquals(2, int16(h, 22))
        assertEquals(44_100 * 2 * 2, int32(h, 28))
        assertEquals(4, int16(h, 32))
    }

    // The peak: the number [Recorded.isSilent] is decided on.

    @Test fun `silence has no peak at all`() = assertEquals(0, Wav.peakOf(ByteArray(1_000)))

    @Test fun `and a peak of zero is silence by the recorder's own line`() =
        assertTrue(Recorded(file = java.io.File("take.wav"), durationMs = 1_000, peakAmplitude = 0).isSilent)

    /** Little-endian, which is the one thing that is easy to get backwards. */
    @Test fun `a sample is read low byte first`() {
        // 0x1234 = 4660, written as 34 12.
        assertEquals(4_660, Wav.peakOf(byteArrayOf(0x34, 0x12)))
        // The same bytes the other way round would be 0x3412 = 13330, which is what a big-endian
        // read would answer — and is how a quiet room becomes a shout.
        assertEquals(13_330, Wav.peakOf(byteArrayOf(0x12, 0x34)))
    }

    @Test fun `a negative sample is as loud as its positive twin`() {
        assertEquals(4_660, Wav.peakOf(byteArrayOf(0xCC.toByte(), 0xED.toByte())))   // -4660
    }

    /**
     * `-32768` has no positive twin in sixteen bits. It is folded down rather than negated: a
     * silence check that could read the loudest possible sample as a negative number would throw
     * away the loudest take he ever made.
     */
    @Test fun `the loudest possible sample reads as the top of the scale`() {
        assertEquals(32_767, Wav.peakOf(byteArrayOf(0x00, 0x80.toByte())))
        assertEquals(32_767, Wav.peakOf(byteArrayOf(0xFF.toByte(), 0x7F)))
        assertEquals(32_767, Wav.MAX_AMPLITUDE)
    }

    @Test fun `the loudest sample anywhere in the block is the peak`() {
        val block = ByteArray(200)
        // One loud sample in the middle of a quiet second: a single syllable is exactly this shape.
        block[100] = 0x10
        block[101] = 0x27   // 0x2710 = 10000
        assertEquals(10_000, Wav.peakOf(block))
    }

    @Test fun `only the bytes it was given are read`() {
        val block = byteArrayOf(0x00, 0x00, 0x10, 0x27)
        assertEquals("the loud sample is past the length", 0, Wav.peakOf(block, length = 2))
        assertEquals(10_000, Wav.peakOf(block, length = 4))
    }

    /** Half a sample is not a sample, and must not be read as one. */
    @Test fun `an odd trailing byte is ignored`() {
        assertEquals(0, Wav.peakOf(byteArrayOf(0x7F), length = 1))
        assertEquals(4_660, Wav.peakOf(byteArrayOf(0x34, 0x12, 0x7F), length = 3))
    }

    @Test fun `a length outside the block cannot read past it`() {
        assertEquals(4_660, Wav.peakOf(byteArrayOf(0x34, 0x12), length = 9_999))
        assertEquals(0, Wav.peakOf(byteArrayOf(0x34, 0x12), length = -1))
    }

    // The bar. A picture of the microphone, never a verdict on him.

    @Test fun `the bar is empty in silence and full at a normal voice`() {
        assertEquals(0f, Wav.levelOf(0), 0.001f)
        assertEquals(1f, Wav.levelOf(Wav.LEVEL_FULL), 0.001f)
        assertEquals(1f, Wav.levelOf(Wav.MAX_AMPLITUDE), 0.001f)
        assertTrue("a quiet voice has to move it visibly", Wav.levelOf(2_000) > 0.2f)
    }

    /**
     * The bar moves long before the silence line is reached, so a take that will be refused as
     * silent is one he could *see* was not being heard while he was making it.
     */
    @Test fun `the bar moves well below the silence line`() =
        assertTrue(Wav.levelOf(Recorded.SILENCE_PEAK) > 0.1f)
}
