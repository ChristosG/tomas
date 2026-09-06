package gr.dimitris.app.core.speech

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two decisions that make the recogniser patient instead of blaming: telling "he has not said it
 * yet" apart from "the phone cannot listen", and starting the window again when the service gives up
 * before he does. Neither can be exercised on an emulator — there is no recognition service there to
 * fail — so this is where both are proved.
 */
class RecognitionTest {

    // Silence and a sound that matched nothing: his, and answered gently.

    @Test fun `silence is heard nothing`() =
        assertTrue(Recognition.heardNothing(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))

    @Test fun `a sound that matched no word is heard nothing`() =
        assertTrue(Recognition.heardNothing(SpeechRecognizer.ERROR_NO_MATCH))

    // Everything else is the phone failing, and must never cost him a try.

    @Test fun `no network is the phone failing`() =
        assertFalse(Recognition.heardNothing(SpeechRecognizer.ERROR_NETWORK))

    @Test fun `a network timeout is the phone failing`() =
        assertFalse(Recognition.heardNothing(SpeechRecognizer.ERROR_NETWORK_TIMEOUT))

    @Test fun `a wedged service is the phone failing`() =
        assertFalse(Recognition.heardNothing(SpeechRecognizer.ERROR_SERVER))

    @Test fun `a busy recogniser is the phone failing`() =
        assertFalse(Recognition.heardNothing(SpeechRecognizer.ERROR_RECOGNIZER_BUSY))

    @Test fun `a microphone it could not open is the phone failing`() =
        assertFalse(Recognition.heardNothing(SpeechRecognizer.ERROR_AUDIO))

    @Test fun `a permission the app does not hold is the phone failing`() =
        assertFalse(Recognition.heardNothing(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))

    @Test fun `a client error is the phone failing`() =
        assertFalse(Recognition.heardNothing(SpeechRecognizer.ERROR_CLIENT))

    /** A code from a newer Android than this one is not assumed to be his fault. */
    @Test fun `an unknown code is the phone failing`() = assertFalse(Recognition.heardNothing(9_999))

    // What his own «Στοπ» does to a code. It outranks all of them.

    /**
     * Some implementations answer `stopListening()` with `ERROR_CLIENT` rather than
     * `ERROR_NO_MATCH`. Telling him the recogniser is broken because he closed the window himself
     * would be the app blaming him for its own convention.
     */
    @Test fun `a client error after his stop is just the end of the window`() =
        assertEquals(
            Recognition.ErrorOutcome.STOPPED,
            Recognition.outcomeOfError(SpeechRecognizer.ERROR_CLIENT, stopped = true),
        )

    @Test fun `and so is anything else after his stop`() {
        for (code in listOf(SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_NO_MATCH, 9_999)) {
            assertEquals(
                "«Στοπ» outranks code $code",
                Recognition.ErrorOutcome.STOPPED,
                Recognition.outcomeOfError(code, stopped = true),
            )
        }
    }

    @Test fun `without a stop the code still decides`() {
        assertEquals(
            Recognition.ErrorOutcome.HEARD_NOTHING,
            Recognition.outcomeOfError(SpeechRecognizer.ERROR_NO_MATCH, stopped = false),
        )
        assertEquals(
            Recognition.ErrorOutcome.NOT_WORKING,
            Recognition.outcomeOfError(SpeechRecognizer.ERROR_CLIENT, stopped = false),
        )
    }

    // The silent restart: the whole of "it stops listening too soon".

    @Test fun `a session that waited and heard nothing is started again`() =
        assertTrue(Recognition.restartsAfterSilence(elapsedMs = 1_500, stopped = false, restarts = 0, sessionMs = 1_500))

    @Test fun `and again, most of the way through the wait`() =
        assertTrue(Recognition.restartsAfterSilence(elapsedMs = 19_999, stopped = false, restarts = 2, sessionMs = 4_000))

    @Test fun `but not once the wait is over`() =
        assertFalse(Recognition.restartsAfterSilence(Recognition.RESTART_WITHIN_MS, stopped = false, restarts = 0, sessionMs = 4_000))

    @Test fun `nor long after it`() =
        assertFalse(Recognition.restartsAfterSilence(elapsedMs = 30_000, stopped = false, restarts = 0, sessionMs = 4_000))

    /** «Στοπ» always wins: he said he was finished, and nothing may reopen the microphone. */
    @Test fun `his stop ends it however early`() =
        assertFalse(Recognition.restartsAfterSilence(elapsedMs = 200, stopped = true, restarts = 0, sessionMs = 4_000))

    /**
     * A session that came back in under a second refused rather than listened. Restarting it would
     * spin the recognition service instead of waiting for him, so the wait ends there.
     */
    @Test fun `a session that answered instantly is not started again`() =
        assertFalse(Recognition.restartsAfterSilence(elapsedMs = 900, stopped = false, restarts = 0, sessionMs = 900))

    @Test fun `a second is long enough to count as having listened`() =
        assertTrue(Recognition.restartsAfterSilence(elapsedMs = 1_000, stopped = false, restarts = 0, sessionMs = Recognition.INSTANT_SESSION_MS))

    /**
     * And the ordinary case is capped, so the wait is a few sessions and not a stream — but the cap
     * is a backstop and the twenty seconds is the bound. Six sessions of the second and a half
     * Chris' phone gives still fill the wait; three would have ended it at about eight seconds.
     */
    @Test fun `the restarts are capped well past the twenty seconds`() {
        assertTrue(Recognition.restartsAfterSilence(elapsedMs = 8_000, stopped = false, restarts = 2, sessionMs = 3_000))
        assertTrue(Recognition.restartsAfterSilence(elapsedMs = 15_000, stopped = false, restarts = 5, sessionMs = 1_500))
        assertFalse(Recognition.restartsAfterSilence(elapsedMs = 8_000, stopped = false, restarts = Recognition.MAX_RESTARTS, sessionMs = 3_000))
        // The bound that matters is the clock: the cap is reached only if he is answered fast.
        assertTrue(
            "the cap has to outlast the wait at the session length Chris described",
            Recognition.MAX_RESTARTS * 1_500L + 1_500L >= Recognition.RESTART_WITHIN_MS / 2,
        )
    }

    @Test fun `the wait, its floors, and the words for a phone that cannot listen`() {
        assertEquals(20_000L, Recognition.RESTART_WITHIN_MS)
        assertEquals(1_000L, Recognition.INSTANT_SESSION_MS)
        assertEquals(6, Recognition.MAX_RESTARTS)
        assertEquals("Η αναγνώριση δεν λειτούργησε. Δες τις ρυθμίσεις.", Recognition.NOT_WORKING)
    }

    /**
     * One session's own bound has to outlast the longest window it can open, or the safety net would
     * be the thing cutting him off.
     */
    @Test fun `one session outlasts its own window`() =
        assertTrue(RecognizerIntents.BOUND_MS > RecognizerIntents.MINIMUM_LENGTH_MS + RecognizerIntents.COMPLETE_SILENCE_MS)

    @Test fun `a failure carries its code so the modules can tell the two apart`() {
        val failed = SpeechFailure.NotWorking(SpeechRecognizer.ERROR_NETWORK)
        assertEquals(SpeechRecognizer.ERROR_NETWORK, failed.code)
        assertTrue(failed.message!!.startsWith(Recognition.NOT_WORKING))
        assertTrue(SpeechFailure.HeardNothing() is SpeechFailure)
    }
}
