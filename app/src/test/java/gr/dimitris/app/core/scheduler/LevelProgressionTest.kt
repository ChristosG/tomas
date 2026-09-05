package gr.dimitris.app.core.scheduler

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The same rule as the numbers module's, written once for any module with levels: what he did in
 * this sitting, and nothing from before it.
 */
class LevelProgressionTest {
    private fun results(correct: Int, total: Int = 10) = List(total) { it < correct }
    private fun next(level: Int, results: List<Boolean>) = LevelProgression.next(level, results, MIN, MAX)

    @Test fun `a sitting of four answers is too short to move him`() {
        assertEquals(2, next(2, results(4, 4)))
        assertEquals(2, next(2, results(0, 4)))
        assertEquals(2, next(2, emptyList()))
    }

    @Test fun `eight of ten moves up`() = assertEquals(3, next(2, results(8)))
    @Test fun `four of ten moves down`() = assertEquals(1, next(2, results(4)))
    @Test fun `in between stays`() = assertEquals(2, next(2, results(6)))

    /** Half right is not the half-failed the rule demotes on: it is exactly the line, and it holds. */
    @Test fun `five of ten stays`() = assertEquals(2, next(2, results(5)))

    @Test fun `clamped to the module's own floor and ceiling`() {
        assertEquals(1, next(1, results(0)))
        assertEquals(4, next(4, results(10)))
        // Another module's range, the same rule.
        assertEquals(7, LevelProgression.next(7, results(10), 1, 7))
    }

    /** A short mixed session counts: five answers is a session, not a warm-up. */
    @Test fun `five answers are enough to move`() {
        assertEquals(3, next(2, results(5, 5)))
        assertEquals(1, next(2, results(2, 5)))
        assertEquals(2, next(2, results(3, 5)))
    }

    /**
     * The ping-pong this rule exists to stop: he was demoted to 2, does 5 of 7 there, and that is
     * not the eighty per cent that would throw him back into the level he just failed. Nothing from
     * an earlier sitting is in the list at all — only what he did in this one.
     */
    @Test fun `five of seven after a demotion does not promote him straight back`() =
        assertEquals(2, next(2, results(5, 7)))

    /**
     * [LevelProgression.window] is a cap on one sitting, not a memory of older ones: past it, the
     * end of the sitting is what counts. No run in this app is that long — a module gets at most
     * eight exercises in free practice and fewer in a mixed session — so it never truncates
     * anything he actually did today.
     */
    @Test fun `only the last window of a very long sitting counts`() {
        assertEquals(3, LevelProgression.next(2, List(10) { false } + results(10), 1, 4))
        assertEquals(2, LevelProgression.next(2, List(10) { false } + results(10), 1, 4, window = 20))
    }

    private companion object {
        const val MIN = 1
        const val MAX = 4
    }
}
