package gr.dimitris.app.core.speech

import android.speech.SpeechRecognizer
import gr.dimitris.app.core.audio.Recorded

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
     * What kind of trouble the phone is in, and the one Greek line that says so.
     *
     * Chris' report is the whole reason this exists. His phone answered code 12 for a day and then
     * code 2, and the app said «Η αναγνώριση δεν λειτούργησε. Δες τις ρυθμίσεις.» to both — which
     * sent him to a settings screen that could do nothing about either, and told a man who cannot
     * read a stack trace nothing at all. Three of the codes have an answer a person can act on, so
     * three of them get their own sentence:
     *
     * * **no connection** (1, 2): the engine in use is a cloud one and the phone is offline. The fix
     *   is a connection, or Greek downloaded — and both are named rather than implied;
     * * **no Greek** (12, 13): the engine cannot speak his language. The fix is the one button in
     *   the settings, so the line points straight at it;
     * * **busy** (8, 10): something else has the recogniser, or it has been asked too often. Nothing
     *   is wrong and nothing needs fixing: wait a moment.
     *
     * Everything else keeps the old line. A code this app has never seen is not guessed about.
     *
     * None of them is ever a sentence about his voice. That is the rule the classes exist to keep.
     */
    enum class ErrorClass(val line: String) {
        /** Codes 1 and 2. The case that made Chris ask for transcription to stop needing the network. */
        NO_CONNECTION("Χρειάζεται σύνδεση για την αναγνώριση."),

        /** Codes 12 and 13. One tap in the settings away from being fixed for ever. */
        NO_GREEK("Λείπουν τα ελληνικά. Κατέβασέ τα από τις ρυθμίσεις."),

        /** Codes 8 and 10. Not a fault, and not his: a moment's patience is the whole answer. */
        BUSY("Η αναγνώριση είναι απασχολημένη. Δοκίμασε σε λίγο."),

        /** Anything else, including a code from an Android newer than this build knows about. */
        UNKNOWN(NOT_WORKING),
    }

    /**
     * Which class an `onError` code falls in. Pure, and here rather than in [AndroidSpeechToText],
     * because the codes that matter most are the two that cannot be reproduced anywhere but on
     * Chris' own phone.
     */
    fun classOf(errorCode: Int): ErrorClass = when (errorCode) {
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> ErrorClass.NO_CONNECTION
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> ErrorClass.NO_GREEK
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> ErrorClass.BUSY
        else -> ErrorClass.UNKNOWN
    }

    /** The line a module puts on the screen for one code. Shorthand for `classOf(code).line`. */
    fun lineFor(errorCode: Int): String = classOf(errorCode).line

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
     * The rule, in one sentence: **another session is opened while less than [RESTART_WITHIN_MS]
     * have passed since the first one and the last one was not instant.** The clock is the bound —
     * how many sessions that takes is the service's business, not his.
     *
     * [stopped] is his «Στοπ», and it always wins: nothing is ever restarted after he has said he
     * is finished.
     *
     * A session that came back in under [INSTANT_SESSION_MS] never really listened — the service
     * refused rather than waited — and restarting it would bind and unbind the recognition service
     * hundreds of times over the twenty seconds without ever giving him a window. That floor, and
     * not a count, is what catches a misbehaving device; [MAX_RESTARTS] sits well above anything
     * the clock allows and is only there so that a service which answers just over the floor cannot
     * spin for ever. When any of them stops the loop the wait simply ends as having heard nothing,
     * which costs him nothing at all.
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
     * A safety cap on the number of sessions in one wait. It is not the rule, and on any device
     * that behaves it never binds.
     *
     * [RESTART_WITHIN_MS] is what ends the wait. This is here only so that a service answering just
     * over the [INSTANT_SESSION_MS] floor cannot be rebound twenty times inside the twenty seconds.
     *
     * The arithmetic, said plainly rather than promised: at sessions of two seconds or more the cap
     * never binds — ten of them *is* the whole twenty — and at the two-to-three seconds Chris saw
     * the clock ends the wait with restarts to spare. Only a service answering at the one-second
     * floor still meets the cap, at about half the wait, which is the case the cap exists for. The
     * counts that used to be here, three and then six, cut him off after eight and eleven seconds
     * of a twenty-second promise: the safety net doing the rule's job.
     */
    const val MAX_RESTARTS = 10

    /**
     * Said when the phone could not listen at all. It points at the settings because that is where
     * a caregiver can do something about it, and it never suggests he did anything wrong.
     */
    const val NOT_WORKING = "Η αναγνώριση δεν λειτούργησε. Δες τις ρυθμίσεις."
}

/**
 * Why a recognition window came back with no words. The two are treated very differently.
 *
 * Both carry [take], because on the on-device path the window *is* the take: one [PcmTake] feeds the
 * recogniser through a pipe and a WAV at the same time, and the file is finished whatever the engine
 * made of it. A man whose effortful Greek the recogniser answered `ERROR_NO_MATCH` to still said the
 * word, and the recording of him saying it is the thing he presses «Άκου» to hear. Throwing it away
 * because the phone was not sure would be the app disagreeing with him and then hiding the evidence.
 */
sealed class SpeechFailure(message: String) : Exception(message) {
    /** His own voice from this window, when the engine recorded one. Null on every other path. */
    abstract val take: Recorded?

    /**
     * Nothing was heard — silence, or a sound that matched no word. An ordinary outcome, answered
     * with the gentle line and another go.
     */
    class HeardNothing(override val take: Recorded? = null) : SpeechFailure("Δεν άκουσα τίποτα")

    /**
     * The phone could not listen. A caregiver's problem, logged once per class per run, and it costs
     * him no try. The message carries the honest line for the code, so the row in «Σφάλματα» says
     * which of the three things went wrong rather than only that something did.
     */
    class NotWorking(val code: Int, override val take: Recorded? = null) :
        SpeechFailure("${Recognition.lineFor(code)} ($code)")
}
