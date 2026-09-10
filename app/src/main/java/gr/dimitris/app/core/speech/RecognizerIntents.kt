package gr.dimitris.app.core.speech

import android.media.AudioFormat
import android.speech.RecognizerIntent
import gr.dimitris.app.core.audio.Wav

/**
 * The extras one Greek recognition window is opened with, as plain data.
 *
 * They live here, apart from the recognizer, for one reason: they are the whole of the fix Chris
 * asked for after the first field test — "it stops listening too soon, even I cannot get the word
 * out in time" — and a plain unit test can pin them. Building the [android.content.Intent] itself
 * cannot be tested off a device, so [build] returns the map and [AndroidSpeechToText] pours it in.
 *
 * The three timings are what give a man with Broca's aphasia room to start: he may take several
 * seconds before the first sound arrives, and several more inside the phrase. Google's recognition
 * service is documented as free to ignore them — which is why the wait has to be confirmed on a real
 * phone and not on an emulator that has no recognizer at all.
 */
object RecognizerIntents {
    /** Greek, and only Greek: everything he practises is in it. */
    const val GREEK = "el-GR"

    /** A few alternatives, so a near miss can still be compared against the target. */
    const val MAX_RESULTS = 3

    /**
     * How long the window stays open at the very least, however quiet he is. Eight seconds is the
     * pause Chris measured himself needing; Dimitris' delay before a word arrives is longer still.
     */
    const val MINIMUM_LENGTH_MS = 8_000L

    /** Silence after he has spoken before the phrase is called finished. */
    const val COMPLETE_SILENCE_MS = 3_000L

    /** The same again for a pause the service thinks *might* be the end. A pause is not an ending. */
    const val POSSIBLY_COMPLETE_SILENCE_MS = 3_000L

    /**
     * The bound on one session, and it is on the recognition service rather than on him: if it
     * never calls back at all, that session must not hang for ever. How long the *wait* lasts —
     * across as many sessions as the service throws away — is [Recognition.RESTART_WITHIN_MS].
     */
    const val BOUND_MS = 20_000L

    fun build(language: String = GREEK, maxResults: Int = MAX_RESULTS): Map<String, Any> = mapOf(
        RecognizerIntent.EXTRA_LANGUAGE_MODEL to RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
        RecognizerIntent.EXTRA_LANGUAGE to language,
        RecognizerIntent.EXTRA_MAX_RESULTS to maxResults,
        RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS to MINIMUM_LENGTH_MS,
        RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS to COMPLETE_SILENCE_MS,
        RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS to POSSIBLY_COMPLETE_SILENCE_MS,
    )

    /**
     * What the engine has to be told about the audio the app is going to hand it instead of opening
     * the microphone itself.
     *
     * This is the whole mechanism behind the one speech control. On the on-device path the app owns
     * the microphone ([gr.dimitris.app.core.speech.PcmTake]) and pours the same samples into two
     * places: a pipe the recogniser reads, and a WAV he can play back. The engine cannot guess the
     * format of bytes arriving down a pipe, so these three say it — and they must agree with
     * [Wav] exactly, or the recogniser hears a man speaking at the wrong speed.
     *
     * The pipe itself is not here: a `ParcelFileDescriptor` is not data and cannot be unit-tested, so
     * [gr.dimitris.app.core.speech.AndroidSpeechToText] puts it in the intent alongside these.
     */
    fun audioSource(): Map<String, Any> = mapOf(
        RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT to Wav.CHANNELS,
        RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING to AudioFormat.ENCODING_PCM_16BIT,
        RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE to Wav.SAMPLE_RATE,
    )
}
