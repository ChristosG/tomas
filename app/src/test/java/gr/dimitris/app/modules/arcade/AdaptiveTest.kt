package gr.dimitris.app.modules.arcade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveTest {
    @Test fun `a hit makes the next target smaller`() {
        assertEquals(88.32f, Adaptive.afterHit(96f), 0.01f)
        assertTrue(Adaptive.afterHit(Adaptive.START) < Adaptive.START)
    }

    @Test fun `a miss gives the size back, and more of it than the hit took`() {
        assertEquals(110.4f, Adaptive.afterMiss(96f), 0.01f)
        // One miss undoes more than one hit: a hand that cannot find the target twice must not be
        // walked down to the floor on the way back up.
        assertTrue(Adaptive.afterMiss(Adaptive.afterHit(96f)) > 96f)
    }

    /** A fingertip is the floor: below it the exercise stops being aim and starts being luck. */
    @Test fun `hits never take it below the floor`() {
        var size = Adaptive.START
        repeat(100) { size = Adaptive.afterHit(size) }
        assertEquals(Adaptive.MIN, size, 0.001f)
    }

    @Test fun `misses never take it past the ceiling`() {
        var size = Adaptive.START
        repeat(100) { size = Adaptive.afterMiss(size) }
        assertEquals(Adaptive.MAX, size, 0.001f)
    }

    /** A size from an older version, or a backup, is pulled back into what the games can draw. */
    @Test fun `a stored size out of range is clamped both ways`() {
        assertEquals(Adaptive.MIN, Adaptive.clamp(1f), 0.001f)
        assertEquals(Adaptive.MAX, Adaptive.clamp(9000f), 0.001f)
        assertEquals(72f, Adaptive.clamp(72f), 0.001f)
        assertEquals(Adaptive.START, Adaptive.clamp(Float.NaN), 0.001f)
    }

    /** The one number the whole module hangs on, said out loud so a rewrite has to mean it. */
    @Test fun `the sizes are the ones the physio was told about`() {
        assertEquals(96f, Adaptive.START, 0.001f)
        assertEquals(40f, Adaptive.MIN, 0.001f)
        assertEquals(130f, Adaptive.MAX, 0.001f)
    }
}
