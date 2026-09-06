package gr.dimitris.app.core.speech

import gr.dimitris.app.core.speech.GentleCheck.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule the word coach, the dialogues and the last stage of «Τραγούδα και πες το» all run: a
 * match confirms for him, one miss is a nudge, and a second miss stops the phone asking. It is the
 * one piece of the gentle check that decides whether a man may say he did his own work, so it is
 * proved here in plain Kotlin rather than only through a device.
 */
class GentleCheckTest {
    private val check = GentleCheck()

    @Test fun `before he speaks the phone has not asked anything of him`() {
        assertEquals(0, check.tries)
        assertNull(check.heard)
        assertFalse("nothing has been heard, so nothing matched", check.matched)
        assertFalse("there is nothing to nudge about yet", check.nudging)
        assertFalse("the confirm belongs to the recogniser until it has had a go", check.canConfirm)
    }

    @Test fun `a match confirms for him at once`() {
        assertEquals(Verdict.MATCHED, check.record("καφές", isMatch = true))
        assertTrue(check.matched)
        assertTrue("he said it: the button is his", check.canConfirm)
        assertFalse("a match is never nudged", check.nudging)
        assertEquals("καφές", check.heard)
    }

    @Test fun `one miss is one nudge and nothing else`() {
        assertEquals(Verdict.NUDGE, check.record("νερό", isMatch = false))
        assertTrue("«Δοκίμασε ξανά» is on the screen", check.nudging)
        assertFalse("he is asked once more before the confirm comes back", check.canConfirm)
        assertEquals(1, check.tries)
    }

    @Test fun `the second miss stops the phone asking`() {
        check.record("νερό", isMatch = false)
        assertEquals(Verdict.OPEN, check.record("ψωμί", isMatch = false))
        assertFalse("the nudge has had its one go", check.nudging)
        assertTrue("«Το είπα!» is back and confirms as it always did", check.canConfirm)
        assertEquals(2, check.tries)
    }

    /**
     * The wall this whole task exists to remove, pointing the other way: two windows that heard
     * nothing at all must open the confirm just as two mismatches do. Otherwise a recogniser having
     * a bad morning leaves a man who *did* say the word with no way to say he had.
     */
    @Test fun `two windows that heard nothing still give him the confirm`() {
        assertEquals(Verdict.NUDGE, check.record(null, isMatch = false))
        assertEquals(Verdict.OPEN, check.record(null, isMatch = false))
        assertTrue(check.canConfirm)
        assertNull("nothing was heard, so nothing is quoted back at him", check.heard)
    }

    /** A match after a miss is still a match: the phone agrees with him and the turn is done. */
    @Test fun `a miss then a match confirms`() {
        check.record("νερό", isMatch = false)
        assertEquals(Verdict.MATCHED, check.record("καφές", isMatch = true))
        assertTrue(check.canConfirm)
        assertFalse(check.nudging)
    }

    /** A third go is his to take, and it never takes the confirm away again. */
    @Test fun `going again after the confirm has come back does not close it`() {
        check.record("νερό", isMatch = false)
        check.record("ψωμί", isMatch = false)
        assertEquals(Verdict.OPEN, check.record("γάλα", isMatch = false))
        assertTrue(check.canConfirm)
        assertEquals(3, check.tries)
    }

    @Test fun `one nudge, and the words of it are an invitation`() {
        assertEquals(2, GentleCheck.TRIES_BEFORE_CONFIRM)
        assertEquals("Δοκίμασε ξανά", GentleCheck.TRY_AGAIN)
        assertEquals("Μίλα", GentleCheck.SPEAK)
    }
}
