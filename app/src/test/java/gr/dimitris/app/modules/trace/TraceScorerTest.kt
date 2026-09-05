package gr.dimitris.app.modules.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What "near enough" has to mean, written as squares because a square can be reasoned about: it is
 * 100 tall, so every threshold below is also a percentage of it.
 *
 * The two halves of the mark are pulled apart on purpose. A trace can be accurate and cover a
 * quarter of the letter (he wrote one stroke of it), or cover all of it and be nowhere near (he
 * wrote it 20 % too low). Both are refused, and each test says which of the two refused it.
 */
class TraceScorerTest {
    /** The letter's height, and so the scale every threshold is a fraction of. */
    private val height = 100f

    /** 8 % of the height by default: how far off the line he may be, on average. */
    private val maxMean = 0.08f * height

    /** A square, walked once round, sampled every unit: 400 points, 100 to a side. */
    private val square = perimeter(side = height, step = 1f)

    @Test fun `going round the letter itself is right on the line and covers all of it`() {
        val s = TraceScorer.score(square, square, height)
        // Not exactly zero: the path is cut into evenly spaced points, and a cut lands between two
        // points of the outline. Half a pixel out on a letter 100 tall is not a wobble.
        assertEquals(0f, s.meanDistance, 1f)
        assertEquals(1f, s.coverage, 0.001f)
        assertTrue("an exact trace was refused: $s", s.passed)
    }

    @Test fun `one side of four is a quarter of the letter and not enough`() {
        val s = TraceScorer.score(listOf(Pt(0f, 0f), Pt(height, 0f)), square, height)
        // A little over a quarter: the corners of the two sides it meets are within reach of it too.
        assertEquals(0.25f, s.coverage, 0.1f)
        // It failed on coverage alone — the stroke he did draw was right on the line.
        assertTrue("one side was off the line: $s", s.meanDistance < maxMean)
        assertFalse("a quarter of the letter passed: $s", s.passed)
    }

    @Test fun `the whole letter written 20 percent of its height away is refused`() {
        val off = square.map { Pt(it.x + 0.2f * height, it.y + 0.2f * height) }
        val s = TraceScorer.score(off, square, height)
        assertEquals(0.2f * height, s.meanDistance, 3f)
        assertTrue("20 % off scored as near the line: $s", s.meanDistance > maxMean)
        assertFalse("a letter written 20 % away passed: $s", s.passed)
    }

    /**
     * The distance half of the mark on its own. 10 % off is close enough for every point of the
     * letter to have been gone over — coverage says yes — and still further off the line than the
     * exercise allows.
     */
    @Test fun `covering the whole letter does not excuse being off the line`() {
        val off = square.map { Pt(it.x + 0.1f * height, it.y + 0.1f * height) }
        val s = TraceScorer.score(off, square, height)
        assertTrue("10 % off missed the letter as well: $s", s.coverage > 0.6f)
        assertTrue("10 % off scored as near the line: $s", s.meanDistance > maxMean)
        assertFalse("10 % off the line passed: $s", s.passed)
    }

    /** The same trace, marked the way level 5 marks it: written from memory, so judged more kindly. */
    @Test fun `a kinder threshold passes what the strict one refuses`() {
        val off = square.map { Pt(it.x + 0.1f * height, it.y + 0.1f * height) }
        assertFalse(TraceScorer.score(off, square, height).passed)
        assertTrue(TraceScorer.score(off, square, height, maxMeanFraction = 0.12f, minCoverage = 0.5f).passed)
    }

    @Test fun `nothing drawn is no attempt, not a bad one`() {
        val s = TraceScorer.score(emptyList(), square, height)
        assertEquals(Float.MAX_VALUE, s.meanDistance, 0f)
        assertEquals(0f, s.coverage, 0f)
        assertFalse(s.passed)
    }

    /** No letter to trace — a box too small to fit one — is not something he can fail at either. */
    @Test fun `an empty template refuses rather than divides by zero`() {
        assertFalse(TraceScorer.score(square, emptyList(), height).passed)
        assertFalse(TraceScorer.score(square, square, 0f).passed)
    }

    @Test fun `a 100 unit line steps into 11 points`() {
        assertEquals(11, TraceScorer.resample(listOf(Pt(0f, 0f), Pt(100f, 0f)), 10f).size)
    }

    @Test fun `resampling spaces the points evenly along the path, corners included`() {
        // Two sides of 100, so a step of 10 walks 10 points down and 10 along, plus the start.
        val path = listOf(Pt(0f, 0f), Pt(0f, 100f), Pt(100f, 100f))
        val walked = TraceScorer.resample(path, 10f)
        assertEquals(21, walked.size)
        assertEquals(0f, walked[5].x, 0.01f)
        assertEquals(50f, walked[5].y, 0.01f)
        // The step carries round the corner rather than restarting at it.
        assertEquals(50f, walked[15].x, 0.01f)
        assertEquals(100f, walked[15].y, 0.01f)
    }

    @Test fun `a path too short to step through is left alone`() {
        val one = listOf(Pt(4f, 7f))
        assertEquals(one, TraceScorer.resample(one, 10f))
        assertEquals(emptyList<Pt>(), TraceScorer.resample(emptyList(), 10f))
        // A finger that never moved reports the same point many times; it must not spin forever.
        assertEquals(2, TraceScorer.resample(listOf(Pt(0f, 0f), Pt(0f, 0f), Pt(0f, 0f), Pt(10f, 0f)), 10f).size)
    }

    /** One turn round a square, clockwise from the top-left corner. */
    private fun perimeter(side: Float, step: Float): List<Pt> {
        val out = mutableListOf<Pt>()
        var d = 0f
        while (d < side) { out += Pt(d, 0f); d += step }
        d = 0f
        while (d < side) { out += Pt(side, d); d += step }
        d = 0f
        while (d < side) { out += Pt(side - d, side); d += step }
        d = 0f
        while (d < side) { out += Pt(0f, side - d); d += step }
        return out
    }
}
