package gr.dimitris.app.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The decision the two caregiver editors make about a finished take. His own takes have been
 * checked since [Recorded.isSilent]; hers were not, and hers is the worse one to lose — a silent
 * .m4a saved as the model voice is an «Άκου» that plays nothing, on the screen where hearing the
 * word is the whole help on offer.
 */
class CaregiverTakeTest {
    @get:Rule val dir = TemporaryFolder()

    private fun take(peak: Int) = Recorded(dir.newFile(), durationMs = 1_200, peakAmplitude = peak)

    @Test fun `a take she spoke into is kept, and the file with it`() {
        val spoken = take(6_000)
        val kept = CaregiverTake.keptOrDiscarded(spoken)
        assertSame(spoken, kept)
        assertTrue("the file is hers now: it is about to become a recording row", spoken.file.exists())
    }

    @Test fun `a take with nothing in it is refused`() {
        assertNull(CaregiverTake.keptOrDiscarded(take(0)))
    }

    @Test fun `room noise is refused too`() {
        assertNull(CaregiverTake.keptOrDiscarded(take(400)))
    }

    /** Nothing will ever point at a refused take, so it does not stay on his phone. */
    @Test fun `the refused file is deleted rather than left behind`() {
        val empty = take(0)
        CaregiverTake.keptOrDiscarded(empty)
        assertFalse("a few hundred kilobytes of nothing", empty.file.exists())
    }

    /** The line itself is the same one his takes are judged on: one rule, one number. */
    @Test fun `the line is Recorded's own`() {
        assertEquals(1_500, Recorded.SILENCE_PEAK)
        val onTheLine = take(Recorded.SILENCE_PEAK)
        assertSame("the line itself counts as a voice", onTheLine, CaregiverTake.keptOrDiscarded(onTheLine))
        assertNull("one below it does not", CaregiverTake.keptOrDiscarded(take(Recorded.SILENCE_PEAK - 1)))
    }

    /** What she is told. Hers is about the take; his is about him. */
    @Test fun `the words said about it are the caregiver's own`() {
        assertEquals("Δεν ακούστηκε τίποτα. Ξαναπές το πιο δυνατά.", Recorded.SILENT_TAKE_CAREGIVER)
        assertEquals("Δεν σε άκουσα. Πες το πιο δυνατά.", Recorded.SILENT_TAKE)
    }
}
