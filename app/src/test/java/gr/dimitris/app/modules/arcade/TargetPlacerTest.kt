package gr.dimitris.app.modules.arcade

import gr.dimitris.app.modules.trace.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.random.Random

class TargetPlacerTest {
    private val width = 900f
    private val height = 1400f
    private val size = 100f

    /** A target against the edge is one his finger runs off the glass to reach. */
    @Test fun `two hundred targets all land inside the box, a target's width from every edge`() {
        val placer = TargetPlacer(Random(7))
        var previous: Pt? = null
        repeat(200) {
            val p = placer.next(width, height, size, previous)
            assertTrue("x out of bounds: ${p.x}", p.x >= size && p.x <= width - size)
            assertTrue("y out of bounds: ${p.y}", p.y >= size && p.y <= height - size)
            previous = p
        }
    }

    /** Every target is a reach: two in the same corner is the same movement done twice. */
    @Test fun `consecutive targets are two target widths apart almost every time`() {
        val placer = TargetPlacer(Random(11))
        var previous = placer.next(width, height, size, null)
        var far = 0
        val rounds = 200
        repeat(rounds) {
            val p = placer.next(width, height, size, previous)
            if (hypot(p.x - previous.x, p.y - previous.y) >= TargetPlacer.FAR_ENOUGH * size) far++
            previous = p
        }
        assertTrue("only $far of $rounds targets were a reach", far >= rounds * 95 / 100)
    }

    /**
     * A box too small to hold two targets that far apart still has to hand one back. It gives the
     * best place it found instead of looping for a place that does not exist.
     */
    @Test fun `a box too small for the rule still places a target`() {
        val placer = TargetPlacer(Random(3))
        val p = placer.next(120f, 120f, 100f, Pt(60f, 60f))
        assertEquals(60f, p.x, 0.001f)
        assertEquals(60f, p.y, 0.001f)
    }

    /** A box with no size at all — measured before layout — is the one case with no room to aim in. */
    @Test fun `an unmeasured box places the target at its middle`() {
        val p = TargetPlacer(Random(1)).next(0f, 0f, 100f, null)
        assertEquals(0f, p.x, 0.001f)
        assertEquals(0f, p.y, 0.001f)
    }

    /** Seeded, so the tests above mean the same thing on every machine and every run. */
    @Test fun `the same seed places the same targets`() {
        val first = TargetPlacer(Random(42)).next(width, height, size, null)
        val second = TargetPlacer(Random(42)).next(width, height, size, null)
        assertEquals(first, second)
    }
}
