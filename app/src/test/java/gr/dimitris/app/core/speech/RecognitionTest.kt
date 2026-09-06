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

    // The silent restart: the whole of "it stops listening too soon".

    @Test fun `a session that gave up at once is started again`() =
        assertTrue(Recognition.restartsAfterSilence(elapsedMs = 1_500, stopped = false))

    @Test fun `and again, most of the way through the wait`() =
        assertTrue(Recognition.restartsAfterSilence(elapsedMs = 19_999, stopped = false))

    @Test fun `but not once the wait is over`() =
        assertFalse(Recognition.restartsAfterSilence(elapsedMs = Recognition.RESTART_WITHIN_MS, stopped = false))

    @Test fun `nor long after it`() =
        assertFalse(Recognition.restartsAfterSilence(elapsedMs = 30_000, stopped = false))

    /** «Στοπ» always wins: he said he was finished, and nothing may reopen the microphone. */
    @Test fun `his stop ends it however early`() =
        assertFalse(Recognition.restartsAfterSilence(elapsedMs = 200, stopped = true))

    @Test fun `the wait is twenty seconds, and the words for a phone that cannot listen`() {
        assertEquals(20_000L, Recognition.RESTART_WITHIN_MS)
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
