package gr.dimitris.app.modules.numbers

import org.junit.Assert.assertEquals
import org.junit.Test

class NumberProgressionTest {
    private fun results(correct: Int, total: Int = 10) = List(total) { it < correct }

    @Test fun `a sitting of four answers is too short to move him`() {
        assertEquals(3, NumberProgression.next(3, results(4, 4)))
        assertEquals(3, NumberProgression.next(3, results(0, 4)))
        assertEquals(3, NumberProgression.next(3, emptyList()))
    }

    @Test fun `eight of ten moves up`() = assertEquals(4, NumberProgression.next(3, results(8)))
    @Test fun `four of ten moves down`() = assertEquals(2, NumberProgression.next(3, results(4)))
    @Test fun `in between stays`() = assertEquals(3, NumberProgression.next(3, results(6)))
    @Test fun `clamped to 1 and 15`() {
        assertEquals(1, NumberProgression.next(1, results(0)))
        assertEquals(15, NumberProgression.next(15, results(10)))
        assertEquals(15, NumberProgression.MAX_LEVEL)
    }

    /**
     * The ladder has no gap in it where the old ceiling used to be: a good sitting at 7 — the hardest
     * thing the app could do before phase 12 — now leads to 8 and not back to 7.
     */
    @Test fun `the old ceiling is a step like any other`() {
        assertEquals(8, NumberProgression.next(7, results(10)))
        assertEquals(12, NumberProgression.next(11, results(8)))
        assertEquals(14, NumberProgression.next(15, results(4)))
    }

    /** One step at a time, all the way up: nothing about the longer ladder takes him two at once. */
    @Test fun `a perfect run moves him exactly one level, wherever he is`() {
        (NumberProgression.MIN_LEVEL until NumberProgression.MAX_LEVEL).forEach { level ->
            assertEquals("from $level", level + 1, NumberProgression.next(level, results(10)))
        }
    }

    /** A short mixed session counts: five answers is a session, not a warm-up. */
    @Test fun `five answers are enough to move`() {
        assertEquals(4, NumberProgression.next(3, results(5, 5)))
        assertEquals(2, NumberProgression.next(3, results(2, 5)))
        assertEquals(3, NumberProgression.next(3, results(3, 5)))
    }

    /**
     * The ping-pong this rule exists to stop: he was demoted to 3, does 5 of 7 there, and that is not
     * the eighty per cent that would throw him back into the level he just failed. Nothing from an
     * earlier session is in the list at all — only what he did in this one.
     */
    @Test fun `five of seven after a demotion does not promote him straight back`() =
        assertEquals(3, NumberProgression.next(3, results(5, 7)))

    @Test fun `the whole sitting counts, not a window inside it`() {
        // Ten misses and then nine hits out of ten is 9 of 20 — not the eighty per cent that a
        // last-ten window would have read at the end of it, and not a promotion.
        assertEquals(3, NumberProgression.next(3, List(10) { false } + results(9)))
    }
}
