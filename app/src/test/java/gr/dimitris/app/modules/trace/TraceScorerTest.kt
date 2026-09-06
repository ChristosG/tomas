package gr.dimitris.app.modules.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What "near enough" has to mean, written as a square letter: a ring of ink 10 wide inside a box 100
 * tall, so every number below is also a percentage of the letter's height.
 *
 * The square stands in for a stroke of a real letter, and the test that matters is the first one: a
 * line down the *middle* of the ink — what a person draws when told to write a letter — has to pass.
 * Measured against the outline alone it would be half a stem out everywhere.
 *
 * The thresholds are the ones the screen works out for a 100-tall letter: 10 px of mean error,
 * 15 px of reach for counting a piece of the outline as gone over.
 */
class TraceScorerTest {
    private val height = 100f
    private val tolerance = 10f
    private val radius = 15f

    /** The letter: a square ring of ink, outer edge 0..100, inner edge 10..90. */
    private val outline = square(0f, 100f, 1f) + square(10f, 90f, 1f)
    private val inside: (Pt) -> Boolean = { p ->
        p.x in 0f..100f && p.y in 0f..100f && !(p.x > 10f && p.x < 90f && p.y > 10f && p.y < 90f)
    }

    /** Down the middle of the ink, all the way round: what writing the letter looks like. */
    private val middle = square(5f, 95f, 1f)

    @Test fun `a line down the middle of the ink is the letter, not a miss`() {
        val s = score(listOf(middle))
        assertEquals("a centre-line trace is not on the letter", 0f, s.meanDistance, 0.01f)
        assertEquals("a centre-line trace missed part of the letter", 1f, s.coverage, 0.001f)
        assertTrue("writing the letter correctly was refused: $s", s.passed)
    }

    @Test fun `a scribble in the middle of the letter is not the letter`() {
        // Inside the hole: on the paper, over the letter, and no part of the letter written.
        val scribble = listOf(Pt(40f, 50f), Pt(60f, 50f), Pt(40f, 55f), Pt(60f, 55f))
        val s = score(listOf(scribble))
        assertTrue("a scribble covered the letter: $s", s.coverage < 0.6f)
        assertFalse("a scribble passed: $s", s.passed)
    }

    @Test fun `the whole letter written a quarter of its height away is refused`() {
        val off = middle.map { Pt(it.x + 25f, it.y + 25f) }
        val s = score(listOf(off))
        assertTrue("a letter written 25 % away scored as on the line: $s", s.meanDistance > tolerance)
        assertFalse("a letter written 25 % away passed: $s", s.passed)
    }

    /** One side of four: right on the letter, and not the letter. Coverage refuses this one alone. */
    @Test fun `one stroke of four is not enough of the letter`() {
        val s = score(listOf(listOf(Pt(5f, 5f), Pt(95f, 5f))))
        assertEquals("the stroke he did draw was off the ink", 0f, s.meanDistance, 0.01f)
        assertTrue("a quarter of the letter counted as most of it: $s", s.coverage < 0.6f)
        assertFalse("a quarter of the letter passed: $s", s.passed)
    }

    /**
     * A letter written in separate strokes. Lifting the finger is not drawing: the gap between two
     * strokes must never be walked over, or a «Κ» written as three lines would be marked as though
     * he had dragged the pen back across the letter twice.
     */
    @Test fun `the gap between two strokes is not something he drew`() {
        val left = listOf(Pt(5f, 5f), Pt(5f, 95f))
        val right = listOf(Pt(95f, 5f), Pt(95f, 95f))
        val apart = score(listOf(left, right))
        assertEquals("two strokes on the ink scored as off it", 0f, apart.meanDistance, 0.01f)

        // The same points as one unbroken line: the jump across the hole is now something he "drew".
        val joined = score(listOf(left + right))
        assertTrue("joining the strokes cost nothing, so the gap was never walked", joined.meanDistance > 10f * apart.meanDistance + 1f)
    }

    /** Level 5's kinder line: written from memory, so less of the letter is asked for. */
    @Test fun `a kinder coverage threshold passes what the strict one refuses`() {
        val part = middle.take((middle.size * 0.4f).toInt())
        val strict = score(listOf(part))
        val kind = score(listOf(part), minCoverage = 0.4f)
        assertFalse("two fifths of the letter passed at the ordinary line: $strict", strict.passed)
        assertTrue("two fifths of the letter was refused at the recall line: $kind", kind.passed)
    }

    @Test fun `nothing drawn is no attempt, not a bad one`() {
        val s = score(emptyList())
        assertEquals(Float.MAX_VALUE, s.meanDistance, 0f)
        assertEquals(0f, s.coverage, 0f)
        assertFalse(s.passed)
        assertFalse("an empty stroke is still nothing", score(listOf(emptyList())).passed)
    }

    /** No letter to trace — a box too small to fit one — is not something he can fail at either. */
    @Test fun `an empty template refuses rather than divides by zero`() {
        assertFalse(TraceScorer.scoreStrokes(listOf(middle), emptyList(), height, inside, tolerance, radius).passed)
        assertFalse(TraceScorer.scoreStrokes(listOf(middle), outline, 0f, inside, tolerance, radius).passed)
    }

    /** Without a mask the outline is all there is, and the centre line is half a stem away from it. */
    @Test fun `with no ink to measure against, the outline is what is left`() {
        val s = TraceScorer.score(middle, outline, height, tolerancePx = tolerance, coverageRadiusPx = radius)
        assertEquals("half the width of the ink", 5f, s.meanDistance, 0.5f)
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

    private fun score(strokes: List<List<Pt>>, minCoverage: Float = 0.6f) =
        TraceScorer.scoreStrokes(strokes, outline, height, inside, tolerance, radius, minCoverage)

    /** One turn round a square, clockwise from the top-left corner. */
    private fun square(from: Float, to: Float, step: Float): List<Pt> {
        val out = mutableListOf<Pt>()
        var d = from
        while (d < to) { out += Pt(d, from); d += step }
        d = from
        while (d < to) { out += Pt(to, d); d += step }
        d = from
        while (d < to) { out += Pt(to - (d - from), to); d += step }
        d = from
        while (d < to) { out += Pt(from, to - (d - from)); d += step }
        return out
    }
}
