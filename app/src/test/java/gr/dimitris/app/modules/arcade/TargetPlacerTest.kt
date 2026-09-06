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

    /**
     * The drag game's ball, at every size his hand can work at, on the smallest board the app is
     * built for (360 × 480 dp of play area).
     *
     * A ball that starts inside its own ring is a round he wins by touching the glass: a CORRECT row
     * that tells the physio his arm did something it did not do, and five steps down the size ladder
     * he never earned. It has to be impossible at 130 dp — where the ring is 208 dp across and the
     * board barely holds the two of them — not merely unlikely at 96.
     */
    @Test fun `the drag ball never starts inside its ring, at any size on a small board`() {
        val board = 360f
        val tall = 480f
        val placer = TargetPlacer(Random(23))
        var size = Adaptive.MIN
        while (size <= Adaptive.MAX) {
            val homePx = size * HOME_RATIO
            val clearance = homePx / 2f + size / 2f
            repeat(50) {
                val ring = placer.next(board, tall, homePx, null, margin = homePx / 2f)
                val puck = placer.clearOf(board, tall, size, ring, clearance)
                val apart = hypot(puck.x - ring.x, puck.y - ring.y)
                assertTrue(
                    "at ${size}dp the ball started ${apart}px from the ring, inside $clearance",
                    apart >= clearance,
                )
                assertTrue("the ball is off the board at ${size}dp", puck.x >= size / 2f && puck.x <= board - size / 2f)
                assertTrue("the ball is off the board at ${size}dp", puck.y >= size / 2f && puck.y <= tall - size / 2f)
                assertTrue("the ring is off the board at ${size}dp", ring.x >= homePx / 2f && ring.x <= board - homePx / 2f)
                assertTrue("the ring is off the board at ${size}dp", ring.y >= homePx / 2f && ring.y <= tall - homePx / 2f)
            }
            size += 5f
        }
    }

    /**
     * A missed target stays where it is and grows, so one placed at 40 dp near the edge can reach
     * past it at 130 dp and be drawn with a slice cut off. It comes back by the overhang and no
     * further: he is aiming at it.
     */
    @Test fun `a target that grew past the edge is nudged back on to the board`() {
        val moved = TargetPlacer.onBoard(Pt(60f, 1380f), 900f, 1400f, 200f)
        assertEquals(100f, moved.x, 0.001f)
        assertEquals(1300f, moved.y, 0.001f)
        // One that still fits is left exactly where it was.
        val still = TargetPlacer.onBoard(Pt(450f, 700f), 900f, 1400f, 200f)
        assertEquals(450f, still.x, 0.001f)
        assertEquals(700f, still.y, 0.001f)
        // A board too small for the target at all: the middle is the best there is.
        val squeezed = TargetPlacer.onBoard(Pt(10f, 10f), 100f, 100f, 200f)
        assertEquals(50f, squeezed.x, 0.001f)
        assertEquals(50f, squeezed.y, 0.001f)
    }

    /** Seeded, so the tests above mean the same thing on every machine and every run. */
    @Test fun `the same seed places the same targets`() {
        val first = TargetPlacer(Random(42)).next(width, height, size, null)
        val second = TargetPlacer(Random(42)).next(width, height, size, null)
        assertEquals(first, second)
    }
}
