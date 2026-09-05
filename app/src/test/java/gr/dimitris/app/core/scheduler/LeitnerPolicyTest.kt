package gr.dimitris.app.core.scheduler

import gr.dimitris.app.core.data.Outcome
import org.junit.Assert.assertEquals
import org.junit.Test

class LeitnerPolicyTest {
    @Test fun `correct with little help moves up`() {
        assertEquals(2, LeitnerPolicy.nextBox(1, Outcome.CORRECT, 0))
        assertEquals(3, LeitnerPolicy.nextBox(2, Outcome.CORRECT, 1))
    }
    @Test fun `correct at syllable cue stays`() = assertEquals(2, LeitnerPolicy.nextBox(2, Outcome.CORRECT, 2))
    @Test fun `assisted stays`() = assertEquals(3, LeitnerPolicy.nextBox(3, Outcome.ASSISTED, 4))
    @Test fun `skipped moves down`() = assertEquals(2, LeitnerPolicy.nextBox(3, Outcome.SKIPPED, null))
    @Test fun `clamped to 1 and 5`() {
        assertEquals(1, LeitnerPolicy.nextBox(1, Outcome.SKIPPED, null))
        assertEquals(5, LeitnerPolicy.nextBox(5, Outcome.CORRECT, 0))
    }
    @Test fun `null cue counts as no help`() = assertEquals(2, LeitnerPolicy.nextBox(1, Outcome.CORRECT, null))
    @Test fun `intervals double per box`() {
        assertEquals(1 * LeitnerPolicy.DAY_MS, LeitnerPolicy.intervalMillis(1))
        assertEquals(16 * LeitnerPolicy.DAY_MS, LeitnerPolicy.intervalMillis(5))
        assertEquals(16 * LeitnerPolicy.DAY_MS, LeitnerPolicy.intervalMillis(9))
    }
}
