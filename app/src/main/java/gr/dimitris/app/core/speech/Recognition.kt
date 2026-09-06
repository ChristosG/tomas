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
    /** What one `onError` means to him, once his own «Στοπ» is taken into account. */
    enum class ErrorOutcome {
        /** He closed the window himself. Whatever the code says, this is simply what was heard. */
        STOPPED,

        /** Silence, or a sound that matched no word. His, and answered gently. */
        HEARD_NOTHING,

        /** The phone could not listen at all. Never his, and it costs him no try. */
        NOT_WORKING,
    }

    /**
     * The classifier the recogniser actually uses.
     *
     * [stopped] comes first because it outranks the code: some implementations answer
     * `stopListening()` with `ERROR_CLIENT` rather than `ERROR_NO_MATCH`, and telling him the
     * recogniser is broken because he closed the window himself would be the app blaming him for
     * its own convention.
     */
    fun outcomeOfError(errorCode: Int, stopped: Boolean): ErrorOutcome = when {
        stopped -> ErrorOutcome.STOPPED
        heardNothing(errorCode) -> ErrorOutcome.HEARD_NOTHING
        else -> ErrorOutcome.NOT_WORKING
    }

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
     *
     * Two floors keep this from becoming a treadmill on a device that behaves differently from
     * Chris'. A session that came back in under [INSTANT_SESSION_MS] never really listened — the
     * service refused rather than waited — and restarting it would bind and unbind the recognition
     * service hundreds of times over the twenty seconds without ever giving him a window. And
     * [MAX_RESTARTS] caps the ordinary case, so the wait is a handful of long sessions rather than
     * an unbounded stream of short ones. When either floor stops the loop the wait simply ends as
     * having heard nothing, which costs him nothing at all.
     */
    fun restartsAfterSilence(elapsedMs: Long, stopped: Boolean, restarts: Int, sessionMs: Long): Boolean =
        !stopped &&
            elapsedMs < RESTART_WITHIN_MS &&
            restarts < MAX_RESTARTS &&
            sessionMs >= INSTANT_SESSION_MS

    /**
     * How long the app keeps listening for him across restarts, measured from «Μίλα».
     *
     * Twenty seconds is long past the point where any recogniser would have given up on its own,
     * and «Στοπ» is under his thumb the whole time. It is a bound on the *app*, not on him: there
     * is no countdown on the screen and nothing tells him to hurry.
     */
    const val RESTART_WITHIN_MS = 20_000L

    /**
     * A session that answered faster than this did not listen — it refused. Restarting it would
     * spin the recognition service rather than wait for him, so the wait ends instead.
     */
    const val INSTANT_SESSION_MS = 1_000L

    /**
     * At most this many further sessions in one wait — a backstop, not the bound.
     *
     * [RESTART_WITHIN_MS] is what ends the wait; this only stops a service that answers in about a
     * second from being rebound without end. Chris described a recogniser that gives up after one
     * to two seconds, and at three restarts that made the whole wait some eight seconds rather than
     * the twenty he was promised — the safety net cutting him off instead of the rule. Six sessions
     * of a second and a half still reach the twenty, and the [INSTANT_SESSION_MS] floor below is
     * what actually catches a service that refuses rather than listens.
     */
    const val MAX_RESTARTS = 6

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
