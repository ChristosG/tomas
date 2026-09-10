package gr.dimitris.app.core.speech

import gr.dimitris.app.core.audio.Recorded
import kotlinx.coroutines.flow.StateFlow

/**
 * What one window came back with.
 *
 * [take] is his own voice from that same window, as a file, and it is the whole of the one speech
 * control: on the on-device path the app holds the microphone and pours the samples into both the
 * recogniser and a WAV, so the thing the phone judged and the thing «Άκου» plays back to him are the
 * same breath rather than two separate asks. Null everywhere else — below Android 13, without the
 * on-device engine, or when the microphone could not be opened — and then the old take button stays
 * on the screen exactly as it was.
 */
data class Transcript(val text: String, val confidence: Float, val take: Recorded? = null)

/** Hears Greek. Android's recognizer first; a Whisper-class cloud provider can replace it later. */
interface SpeechToText {
    val isAvailable: Boolean

    /**
     * How loud he is right now, 0..1, while a window is open. The listening screen draws it, so that
     * a man who cannot ask "is it hearing me?" can see the answer instead.
     *
     * Zero when nothing is listening. It is a picture of the microphone, never a verdict on him.
     */
    val level: StateFlow<Float>

    /**
     * Listens until he has been quiet for a moment. The implementation bounds itself; there is no
     * timer on him — Chris found in the field that even *he* could not get a word out before the
     * old window closed, and Dimitris needs longer than Chris does.
     */
    suspend fun listen(): Result<Transcript>

    /**
     * «Στοπ»: close the window now. Whatever was heard still comes back through the [listen] that
     * opened it — an empty [Transcript] if there was nothing — so the caller has exactly one place
     * where a result arrives.
     */
    fun stop()

    /**
     * Which recogniser would answer «Μίλα» on this phone right now, and therefore what the screen
     * puts in front of him: one control on [OnDeviceSupport.Engine.ON_DEVICE], the old pair of
     * buttons on every other path.
     *
     * [fresh] re-asks the engine about its languages instead of using what it last said. The
     * settings row passes true while it is polling a download; «Μίλα» does not, because a service
     * bind before the microphone opens is a pause a man waiting to speak would feel.
     */
    suspend fun engine(fresh: Boolean = false): OnDeviceSupport.Engine

    /**
     * Whether the engine is already fetching Greek, asked for by an earlier tap. The settings row
     * reads it so that a caregiver who came back to the screen is told «λήψη…» rather than being
     * offered the same button again. Cheap: it reads what the last [engine] call learned.
     */
    suspend fun greekPending(): Boolean

    /**
     * Asks the on-device engine to fetch Greek, and suspends until it says it has (or has scheduled
     * it, or has failed). False when this phone has nothing to ask — no on-device engine, or an
     * Android older than 13.
     *
     * Called from the caregiver's settings and nowhere else: it is the one tap that answers Chris'
     * code 12 for good.
     */
    suspend fun downloadGreek(): Boolean
}

/**
 * His take from one window, wherever that window ended up.
 *
 * A match, a mismatch, a silence and a broken recogniser all leave the same file behind on the
 * on-device path, and he is entitled to hear it in every one of those cases — the recogniser being
 * unsure about his Greek has never been a reason to delete the proof that he spoke. So the take hangs
 * off the [Result] rather than off the happy branch of it.
 */
val Result<Transcript>.take: Recorded?
    get() = when (val failure = exceptionOrNull()) {
        null -> getOrNull()?.take
        is SpeechFailure -> failure.take
        else -> null
    }
