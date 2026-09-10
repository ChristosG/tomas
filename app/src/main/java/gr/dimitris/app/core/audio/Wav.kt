package gr.dimitris.app.core.audio

/**
 * The 44-byte RIFF header his take is wrapped in, and the peak of a block of samples.
 *
 * Both live here, apart from the recorder that uses them, for the same reason the recogniser's
 * decisions do: they can be got wrong in a way no emulator would notice. A header whose length field
 * is one chunk short plays as a truncated word; a peak computed over the wrong byte order reads a
 * quiet room as a shout and throws his voice away as "silence". Neither needs a microphone to prove.
 *
 * 16 kHz mono PCM16 is not a taste: it is what the on-device recogniser is told to expect through
 * `EXTRA_AUDIO_SOURCE_SAMPLING_RATE`, so the same bytes that become the transcript become the file
 * he plays back. One microphone, one stream, two destinations.
 */
object Wav {
    /** What the on-device recogniser is fed, and therefore what the file holds. */
    const val SAMPLE_RATE = 16_000
    const val CHANNELS = 1
    const val BITS_PER_SAMPLE = 16

    /** `RIFF` + `fmt ` + `data`, with no optional chunks: the canonical 44 bytes. */
    const val HEADER_BYTES = 44

    /** The loudest a 16-bit sample can be, and the top of [Recorded.peakAmplitude]'s scale. */
    const val MAX_AMPLITUDE = 32_767

    /**
     * The header for a file holding [dataBytes] of samples.
     *
     * Written twice: once at the start with a zero length, because the length is not known until he
     * stops talking, and once over the top of it at the end with the real one. A player given the
     * first version reads a valid-but-empty file rather than a corrupt one, which is what makes a
     * take that was interrupted — the app killed, the phone out of space — still openable.
     */
    fun header(dataBytes: Int, sampleRate: Int = SAMPLE_RATE, channels: Int = CHANNELS): ByteArray {
        val byteRate = sampleRate * channels * BITS_PER_SAMPLE / 8
        val blockAlign = channels * BITS_PER_SAMPLE / 8
        val out = ByteArray(HEADER_BYTES)
        var at = 0
        fun ascii(text: String) { for (c in text) out[at++] = c.code.toByte() }
        fun int32(value: Int) { repeat(4) { out[at++] = (value ushr (8 * it)).toByte() } }
        fun int16(value: Int) { repeat(2) { out[at++] = (value ushr (8 * it)).toByte() } }

        ascii("RIFF")
        // Everything after this field: the 36 bytes of header that follow it, plus the samples.
        int32(HEADER_BYTES - 8 + dataBytes)
        ascii("WAVE")
        ascii("fmt ")
        int32(16)           // the size of the fmt chunk that follows
        int16(1)            // 1 = uncompressed PCM
        int16(channels)
        int32(sampleRate)
        int32(byteRate)
        int16(blockAlign)
        int16(BITS_PER_SAMPLE)
        ascii("data")
        int32(dataBytes)
        return out
    }

    /**
     * The loudest sample in the first [length] bytes of a little-endian PCM16 block, on
     * [Recorded.peakAmplitude]'s 0..32767 scale.
     *
     * `-32768` is folded down to 32767 rather than negated: its magnitude does not fit in a
     * positive 16-bit value, and a silence check that could read the loudest possible sample as a
     * negative number would delete the one take he got out.
     *
     * An odd trailing byte is ignored — half a sample is not a sample.
     */
    fun peakOf(pcm: ByteArray, length: Int = pcm.size): Int {
        var peak = 0
        var i = 0
        val end = (length.coerceIn(0, pcm.size)) - 1
        while (i < end) {
            val sample = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
            val magnitude = if (sample < 0) -(sample.coerceAtLeast(-MAX_AMPLITUDE)) else sample
            if (magnitude > peak) peak = magnitude
            i += 2
        }
        return peak
    }

    /**
     * How loud to draw the bar for a block whose peak is [peak]. It is a picture of the microphone
     * and never a verdict on him, so the mapping is deliberately generous: a quiet voice at arm's
     * length reads a few thousand on this scale and has to move the bar visibly, or a man who cannot
     * ask "is it hearing me?" gets no answer.
     */
    fun levelOf(peak: Int): Float = (peak / LEVEL_FULL.toFloat()).coerceIn(0f, 1f)

    /** The peak that fills the bar. Well under [MAX_AMPLITUDE]: nobody shouts at their own phone. */
    const val LEVEL_FULL = 8_000
}
