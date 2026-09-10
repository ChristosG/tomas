package gr.dimitris.app.core.speech

import gr.dimitris.app.core.audio.Wav
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one thing Chris asked for in so many words: that the recogniser waits for him. The emulator
 * has no recognition service at all, so the timings can only be proved here — and they matter more
 * than most constants do, because a window that closes in a second and a half is a man with Broca's
 * aphasia being told he had nothing to say.
 *
 * The keys are asserted as their real names rather than through the constants, so that a rename or
 * a wrong constant cannot make this test agree with itself.
 */
class RecognizerIntentsTest {
    private val extras = RecognizerIntents.build()

    @Test fun `the window stays open eight seconds however quiet he is`() =
        assertEquals(8_000L, extras["android.speech.extras.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS"])

    @Test fun `three seconds of silence before a phrase is called finished`() =
        assertEquals(3_000L, extras["android.speech.extras.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS"])

    @Test fun `a pause that might be the end is given the same three seconds`() =
        assertEquals(3_000L, extras["android.speech.extras.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS"])

    @Test fun `it listens in Greek`() =
        assertEquals("el-GR", extras["android.speech.extra.LANGUAGE"])

    @Test fun `free form, because he is saying words and not commands`() =
        assertEquals("free_form", extras["android.speech.extra.LANGUAGE_MODEL"])

    @Test fun `a few alternatives, so a near miss can still be compared`() =
        assertEquals(3, extras["android.speech.extra.MAX_RESULTS"])

    @Test fun `nothing else rides along`() = assertEquals(6, extras.size)

    /** The only bound is on the service, not on him, and it is longer than any window it opens. */
    @Test fun `the safety bound outlasts the longest window`() {
        val longest = RecognizerIntents.MINIMUM_LENGTH_MS + RecognizerIntents.COMPLETE_SILENCE_MS
        assert(RecognizerIntents.BOUND_MS > longest) { "the bound would cut a window short" }
    }

    /** A language a caregiver could one day pick still leaves the timings alone. */
    @Test fun `only the language changes with the language`() {
        val english = RecognizerIntents.build(language = "en-GB")
        assertEquals("en-GB", english["android.speech.extra.LANGUAGE"])
        assertEquals(
            extras.filterKeys { it != "android.speech.extra.LANGUAGE" },
            english.filterKeys { it != "android.speech.extra.LANGUAGE" },
        )
    }

    // The audio-source extras: the mechanism behind the one speech control.

    private val source = RecognizerIntents.audioSource()

    /**
     * These three are what let one microphone serve both purposes. The app opens it, writes a WAV he
     * can play back, and hands the engine the same samples down a pipe — and the engine cannot guess
     * the format of bytes arriving down a pipe, so it is told.
     *
     * Asserted as the real extra names rather than through the constants, so a rename or a wrong
     * constant cannot make this test agree with itself.
     */
    @Test fun `the engine is told one channel`() =
        assertEquals(1, source["android.speech.extra.AUDIO_SOURCE_CHANNEL_COUNT"])

    @Test fun `sixteen-bit PCM`() =
        // AudioFormat.ENCODING_PCM_16BIT, which is 2. The number matters: the engine reads it raw.
        assertEquals(2, source["android.speech.extra.AUDIO_SOURCE_ENCODING"])

    @Test fun `and sixteen kilohertz`() =
        assertEquals(16_000, source["android.speech.extra.AUDIO_SOURCE_SAMPLING_RATE"])

    @Test fun `nothing else rides along with them either`() = assertEquals(3, source.size)

    /**
     * The format the engine is told and the format the file is written in have to be the same numbers,
     * or the recogniser hears a man speaking at the wrong speed while the take plays back correctly —
     * a mismatch that would look like his Greek being unintelligible.
     */
    @Test fun `what the engine is told is what the file holds`() {
        assertEquals(Wav.SAMPLE_RATE, source["android.speech.extra.AUDIO_SOURCE_SAMPLING_RATE"])
        assertEquals(Wav.CHANNELS, source["android.speech.extra.AUDIO_SOURCE_CHANNEL_COUNT"])
        assertEquals(16, Wav.BITS_PER_SAMPLE)
    }

    /** The pipe itself is not data and cannot be asserted here, but its extra's name can be. */
    @Test fun `the pipe goes in under the audio-source name`() =
        assertEquals("android.speech.extra.AUDIO_SOURCE", android.speech.RecognizerIntent.EXTRA_AUDIO_SOURCE)
}
