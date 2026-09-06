package gr.dimitris.app.core.speech

import android.speech.SpeechRecognizer

/**
 * The two decisions the recogniser has to make about its own failures, as pure functions.
 *
 * They are here, apart from [AndroidSpeechToText], because neither can be exercised on an emulator —
 * there is no recognition service there to fail — and because both are the difference between the
 * app being patient with him and the app blaming him for its own bad morning.
 */
object Recognition {
    /**
     * Whether an `onError` code means "he did not say anything I could catch" rather than "I could
     * not listen at all".
     *
     * Only two codes are about the sound: [SpeechRecognizer.ERROR_SPEECH_TIMEOUT] (silence) and
     * [SpeechRecognizer.ERROR_NO_MATCH] (a sound that matched no word). Both are ordinary outcomes
     * of a man with Broca's aphasia taking his time, and both are answered with the gentle line and
     * another go.
     *
     * Everything else — no network, a wedged service, the microphone taken by something else, a
     * permission the app does not hold — is the *phone* failing. He is told so in one Greek line
     * that points at the settings, it is logged once for the caregiver, and it costs him nothing:
     * a phone that cannot listen must never be able to spend his two tries for him.
     */
    fun heardNothing(errorCode: Int): Boolean =
        errorCode == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || errorCode == SpeechRecognizer.ERROR_NO_MATCH

    /**
     * Whether to open another session after one came back having heard nothing.
     *
     * This is the whole of Chris' first finding. Google's recogniser is documented as free to ignore
     * the timing extras, and in the field it does: it gives up after a second or two of silence and
     * answers `ERROR_SPEECH_TIMEOUT` while he is still gathering the word. So a silent session is
     * simply started again, silently — the indicator never blinks, the bar keeps moving, and from
     * where he is sitting the phone is still listening. That continues until [RESTART_WITHIN_MS]
     * have passed since he tapped «Μίλα».
     *
     * [stopped] is his «Στοπ», and it always wins: nothing is ever restarted after he has said he
     * is finished.
     */
    fun restartsAfterSilence(elapsedMs: Long, stopped: Boolean): Boolean =
        !stopped && elapsedMs < RESTART_WITHIN_MS

    /**
     * How long the app keeps listening for him across restarts, measured from «Μίλα».
     *
     * Twenty seconds is long past the point where any recogniser would have given up on its own,
     * and «Στοπ» is under his thumb the whole time. It is a bound on the *app*, not on him: there
     * is no countdown on the screen and nothing tells him to hurry.
     */
    const val RESTART_WITHIN_MS = 20_000L

    /**
     * Said when the phone could not listen at all. It points at the settings because that is where
     * a caregiver can do something about it, and it never suggests he did anything wrong.
     */
    const val NOT_WORKING = "Η αναγνώριση δεν λειτούργησε. Δες τις ρυθμίσεις."
}

/** Why a recognition window came back with no words. The two are treated very differently. */
sealed class SpeechFailure(message: String) : Exception(message) {
    /**
     * Nothing was heard — silence, or a sound that matched no word. An ordinary outcome, answered
     * with the gentle line and another go.
     */
    class HeardNothing : SpeechFailure("Δεν άκουσα τίποτα")

    /** The phone could not listen. A caregiver's problem, logged once, and it costs him no try. */
    class NotWorking(val code: Int) : SpeechFailure("${Recognition.NOT_WORKING} ($code)")
}
