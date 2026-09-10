package gr.dimitris.app.modules.numbers

import gr.dimitris.app.core.greek.GreekTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the phone says the moment after he taps — and, more to the point, what it does when it
 * cannot say anything at all.
 *
 * This module's whole contract is that there is no way to fail in it: a wrong tap is a nudge, a
 * second wrong tap is the answer shown and said. An exception thrown while *building* that sentence
 * would go straight out of the coroutine that speaks it and end the sitting, which is a fail state
 * arriving through the one door nobody was watching.
 */
class SpokenAnswerTest {
    @Test fun `each kind of answer is said the way it is meant to be heard`() {
        assertEquals("δεκαέξι", spokenAnswer(NumberExercise.Arithmetic(9, 600, Op.SUB, 584, listOf(16, 17, 6, 26))))
        assertEquals("οκτώ", spokenAnswer(NumberExercise.Missing(11, 4, Op.MUL, 32, 8, missingFirst = true, options = listOf(8, 7, 9, 4))))
        assertEquals("οκτώ ευρώ και τριάντα λεπτά", spokenAnswer(NumberExercise.Change(12, 1000, 170, listOf(830, 930, 730, 840))))
        assertEquals("τρεις και μισή", spokenAnswer(NumberExercise.Clock(13, 3 * 60 + 30, listOf(210, 180, 195, 240))))
        assertEquals("Δευτέρα", spokenAnswer(NumberExercise.DayAfter(13, 1, 6, listOf(0, 1, 2, 3))))
        assertEquals("δέκα", spokenAnswer(NumberExercise.WordProblem(15, "…;", "…;", listOf(10, 15, 30, 11), 10)))
        assertEquals(
            "εννέα χιλιάδες εννιακόσια ενενήντα εννέα",
            spokenAnswer(NumberExercise.WordMatch(14, 9999, listOf(9999, 9099, 9989, 8999), showWord = false)),
        )
    }

    /**
     * The answer that has no Greek name. A negative one is what a level-15 story with a bad bound
     * produced before it was fixed, and it is the shape of every such bug: [spokenAnswer] hands back
     * the digits, tells the caller *why*, and does not throw. He still sees the right button lit up
     * green, and the run goes on.
     */
    @Test fun `an answer no Greek word can say is read as digits instead of thrown`() {
        val impossible = NumberExercise.WordProblem(15, "Πόσα μένουν;", "Πόσα μένουν;", listOf(-1, 2, 3, 4), -1)
        var reported: Throwable? = null
        assertEquals("-1", spokenAnswer(impossible) { reported = it })
        assertTrue("the failure has to reach the error log: $reported", reported is IllegalArgumentException)
    }

    /** Past the words' domain at the other end, too — and with nobody listening for the failure. */
    @Test fun `an answer past nine thousand nine hundred and ninety-nine is read as digits`() {
        val tooBig = NumberExercise.Count(3, 12_345, listOf(12_345, 1, 2))
        assertEquals("12345", spokenAnswer(tooBig))
    }

    /** Money and the clock have no domain to fall out of: they say something for any number. */
    @Test fun `the euro and the clock never need the fallback`() {
        var reported: Throwable? = null
        // Above what the words reach, Euro.spoken falls back to the written amount by itself.
        assertEquals("100000,00 €", spokenAnswer(NumberExercise.Pay(7, 1, listOf(10_000_000))) { reported = it })
        // A time from anywhere lands on the face: a whole turn of it is twelve o'clock.
        assertEquals("δώδεκα ακριβώς", spokenAnswer(NumberExercise.Clock(13, GreekTime.MINUTES, listOf(0))) { reported = it })
        assertNull("nothing should have failed: $reported", reported)
    }
}
