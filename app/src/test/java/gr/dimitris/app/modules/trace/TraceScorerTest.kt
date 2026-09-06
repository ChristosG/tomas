package gr.dimitris.app.modules.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * What "that is the letter" has to mean, written as letters made of bars.
 *
 * The font is the device's, so a real glyph cannot be built here; a letter made of rectangles can,
 * and it carries everything that matters — ink with a width, an outline that is the two *edges* of
 * every stroke, and a hole in the «Ο» that is not ink. Everything is in the pixels of a real phone:
 * a letter 800 px tall on a screen of three pixels to the dp is the 267 dp capital he actually gets,
 * so «12 dp of tolerance» here means what it means on the glass.
 *
 * The two tests that matter are the first two, and they are the field test that made this file:
 * writing an «Η» the way a hand writes it must pass, and drawing a «Κ» over that «Η» must not —
 * however much of the paper the «Κ» covers, however near the letter it wanders. A wrong letter form
 * marked right is a wrong movement practised.
 */
class TraceScorerTest {
    // ---- the letters -------------------------------------------------------------------------

    /** An «Η» 800 px tall: two stems and a crossbar, each 50 px of ink across. */
    private val h = glyph(
        Bar(Pt(180f, 100f), Pt(180f, 900f), STROKE),
        Bar(Pt(620f, 100f), Pt(620f, 900f), STROKE),
        Bar(Pt(180f, 500f), Pt(620f, 500f), STROKE),
    )

    /** A «Κ» in the same box: the same stem, and two diagonals where the crossbar was. */
    private val k = glyph(
        Bar(Pt(180f, 100f), Pt(180f, 900f), STROKE),
        Bar(Pt(620f, 100f), Pt(180f, 500f), STROKE),
        Bar(Pt(180f, 500f), Pt(620f, 900f), STROKE),
    )

    /** An «Ο»: a ring of ink, and the hole in the middle of it, which is not ink. */
    private val o = ring(cx = 400f, cy = 500f, outer = 400f, inner = 350f)

    // ---- how a hand writes them --------------------------------------------------------------

    /** The «Η» as a person writes it: down the middle of each stroke, with a hand's wobble. */
    private val handH = listOf(
        wobbled(line(Pt(180f, 100f), Pt(180f, 900f))),
        wobbled(line(Pt(620f, 100f), Pt(620f, 900f))),
        wobbled(line(Pt(180f, 500f), Pt(620f, 500f))),
    )

    /** A «Κ», written just as well, in the same box: one stem shared, two diagonals of its own. */
    private val handK = listOf(
        wobbled(line(Pt(180f, 100f), Pt(180f, 900f))),
        wobbled(line(Pt(620f, 100f), Pt(180f, 500f))),
        wobbled(line(Pt(180f, 500f), Pt(620f, 900f))),
    )

    /** An «Ο», down the middle of the ring. */
    private val handO = listOf(wobbled(circle(400f, 500f, 375f)))

    // ---- the letter he was asked for ---------------------------------------------------------

    @Test fun `an H written the way a hand writes it passes at every strictness`() {
        for (level in TraceStrictness.entries) {
            val s = score(handH, h, level)
            assertTrue("writing «Η» by hand was refused at $level: $s", s.passed)
            assertEquals("a centre-line trace is not on the ink at $level", 0f, s.meanDistance, 1f)
        }
    }

    /**
     * The field test: Chris drew a «Κ» over an «Η» and it passed. It shares the whole left stem, it
     * is drawn beautifully, and it is not an «Η» — the crossbar is never gone over (coverage) and
     * the diagonals are out in the white (precision).
     */
    @Test fun `a K drawn over the H is not an H`() {
        val normal = score(handK, h, TraceStrictness.NORMAL)
        assertFalse("a «Κ» passed as an «Η» at Κανονικό: $normal", normal.passed)
        assertTrue("the crossbar of the «Η» counted as gone over: $normal", normal.coverage < TraceStrictness.NORMAL.minCoverage)
        assertTrue("the diagonals of the «Κ» counted as on the «Η»: $normal", normal.precision < TraceStrictness.NORMAL.minPrecision)
        assertFalse("a «Κ» passed as an «Η» at Αυστηρό: $normal", score(handK, h, TraceStrictness.STRICT).passed)
        // Χαλαρό is a caregiver's choice, not a licence to write another letter.
        assertFalse("a «Κ» passed as an «Η» even at Χαλαρό", score(handK, h, TraceStrictness.LOOSE).passed)
    }

    /** The other one he drew: an «Ο» over a «Κ». Nothing about it is the letter. */
    @Test fun `an O drawn over the K is not a K, however loosely it is marked`() {
        for (level in TraceStrictness.entries) {
            val s = score(handO, k, level)
            assertFalse("an «Ο» passed as a «Κ» at $level: $s", s.passed)
        }
    }

    /** Half a letter is precise everywhere and is still half a letter: coverage alone refuses it. */
    @Test fun `half the letter is refused for what it is missing, not for where it is`() {
        val half = listOf(handH[0], handH[2])   // the left stem and the crossbar; no right stem
        val s = score(half, h, TraceStrictness.NORMAL)
        assertTrue("half an «Η» counted as most of it: $s", s.coverage < TraceStrictness.NORMAL.minCoverage)
        assertTrue("the half he did write was marked as off the letter: $s", s.precision >= TraceStrictness.NORMAL.minPrecision)
        assertFalse("half an «Η» passed: $s", s.passed)
    }

    /**
     * The same letter, drawn a fifth too big. Every stroke of it is beside the letter rather than on
     * it, which is precisely what precision is for — and on a ring there is nowhere for the extra
     * size to hide, because every part of an «Ο» is the far side of it.
     */
    @Test fun `a perfect letter drawn a fifth too large is not on the letter`() {
        val big = handO.map { stroke -> stroke.map { Pt(400f + 1.2f * (it.x - 400f), 500f + 1.2f * (it.y - 500f)) } }
        val s = score(big, o, TraceStrictness.NORMAL)
        assertTrue("an «Ο» a fifth too big scored as on the letter: $s", s.precision < TraceStrictness.NORMAL.minPrecision)
        assertFalse("an «Ο» a fifth too big passed: $s", s.passed)
        // And the same «Ο» at its own size is the letter, or the test above would prove nothing.
        assertTrue("writing «Ο» by hand was refused: ${score(handO, o, TraceStrictness.NORMAL)}", score(handO, o, TraceStrictness.NORMAL).passed)
    }

    @Test fun `a single tap is never a letter`() {
        val tap = listOf(listOf(Pt(400f, 500f)))
        for (level in TraceStrictness.entries) {
            val s = score(tap, h, level)
            assertFalse("one tap in the middle of the ink passed at $level: $s", s.passed)
        }
    }

    /**
     * Level 5, once «Το είδα» has taken the word away: the same distances, fifteen points less of
     * the letter asked for. What comes back from memory is rougher than what comes back from a line
     * he can see, and it is still the letter — but only fifteen points' worth of rougher.
     */
    @Test fun `writing from memory is marked fifteen points more kindly, and no nearer`() {
        // The left stem, the crossbar, and a stroke out in the white where the right stem is not.
        val nearly = listOf(handH[0], handH[2], line(Pt(760f, 120f), Pt(760f, 970f)))
        val s = score(nearly, h, TraceStrictness.NORMAL)
        assertTrue("the near miss is not a near miss any more: $s", s.coverage in 0.65f..0.79f && s.precision in 0.65f..0.79f)
        assertFalse("a near miss passed with the word on the screen: $s", s.passed)
        assertTrue("the same trace was refused from memory: $s", score(nearly, h, TraceStrictness.NORMAL, recall = true).passed)

        // The allowance is on how much of the letter, never on how near it: a «Κ» is not an «Η»
        // from memory either.
        assertFalse("a «Κ» passed as an «Η» from memory", score(handK, h, TraceStrictness.NORMAL, recall = true).passed)
    }

    // ---- the strictness table ----------------------------------------------------------------

    @Test fun `the three strictnesses are the numbers the caregiver was promised`() {
        val d = DENSITY
        val loose = Strictness.of(TraceStrictness.LOOSE, d, recall = false)
        assertEquals(16f * d, loose.tolerancePx, 0.001f)
        assertEquals(18f * d, loose.coverRadiusPx, 0.001f)
        assertEquals(0.70f, loose.minCoverage, 0.0001f)
        assertEquals(0.70f, loose.minPrecision, 0.0001f)

        val normal = Strictness.of(TraceStrictness.NORMAL, d, recall = false)
        assertEquals(12f * d, normal.tolerancePx, 0.001f)
        assertEquals(14f * d, normal.coverRadiusPx, 0.001f)
        assertEquals(0.80f, normal.minCoverage, 0.0001f)
        assertEquals(0.80f, normal.minPrecision, 0.0001f)

        val strict = Strictness.of(TraceStrictness.STRICT, d, recall = false)
        assertEquals(8f * d, strict.tolerancePx, 0.001f)
        assertEquals(10f * d, strict.coverRadiusPx, 0.001f)
        assertEquals(0.90f, strict.minCoverage, 0.0001f)
        assertEquals(0.90f, strict.minPrecision, 0.0001f)

        assertEquals("Κανονικό is what he is marked at unless someone says otherwise", TraceStrictness.NORMAL, TraceStrictness.DEFAULT)
    }

    @Test fun `recall takes fifteen points off both lines and nothing off the distances`() {
        val plain = Strictness.of(TraceStrictness.NORMAL, DENSITY, recall = false)
        val memory = Strictness.of(TraceStrictness.NORMAL, DENSITY, recall = true)
        assertEquals(plain.tolerancePx, memory.tolerancePx, 0.001f)
        assertEquals(plain.coverRadiusPx, memory.coverRadiusPx, 0.001f)
        assertEquals(0.65f, memory.minCoverage, 0.0001f)
        assertEquals(0.65f, memory.minPrecision, 0.0001f)
    }

    /** The dp are the point: the same setting is more pixels on a denser screen, and never zero. */
    @Test fun `the tolerances are fingertips, so they follow the screen`() {
        assertEquals(12f, Strictness.of(TraceStrictness.NORMAL, 1f, recall = false).tolerancePx, 0.001f)
        assertEquals(48f, Strictness.of(TraceStrictness.NORMAL, 4f, recall = false).tolerancePx, 0.001f)
        // A density of zero is a screen nobody can write on; the numbers still mean dp.
        assertEquals(12f, Strictness.of(TraceStrictness.NORMAL, 0f, recall = false).tolerancePx, 0.001f)
    }

    @Test fun `a stored strictness that is no longer one of the three reads as Κανονικό`() {
        assertEquals(TraceStrictness.LOOSE, TraceStrictness.named("LOOSE"))
        assertEquals(TraceStrictness.STRICT, TraceStrictness.named("STRICT"))
        assertEquals(TraceStrictness.NORMAL, TraceStrictness.named(null))
        assertEquals(TraceStrictness.NORMAL, TraceStrictness.named("VERY_STRICT"))
    }

    // ---- the pieces of a letter ---------------------------------------------------------------

    /**
     * A contour is cut into pieces the length of a stroke, and never into fewer than eight: the ring
     * of an «Ο» gone a third of the way round has to read as a third of a letter, not as one piece
     * out of one.
     */
    @Test fun `every contour is cut into pieces of a twelfth of the letter, at least eight of them`() {
        val height = 800f
        // A line 1200 long: 1200 / (800 * 0.12) = 12.5 → 13 pieces.
        val long = line(Pt(0f, 0f), Pt(1200f, 0f), step = 6f)
        assertEquals(13, TraceScorer.segments(listOf(long), height).map { it.segment }.distinct().size)

        // A line 60 long is under one piece, and is still cut into eight.
        val short = line(Pt(0f, 0f), Pt(60f, 0f), step = 6f)
        assertEquals(TraceScorer.MIN_SEGMENTS, TraceScorer.segments(listOf(short), height).map { it.segment }.distinct().size)
    }

    /** The hole in an «Ο» is pieces of its own, or going round the outside would be the whole letter. */
    @Test fun `two contours never share a piece`() {
        val outer = TraceScorer.segments(listOf(o.contours[0]), o.height).map { it.segment }.toSet()
        val both = TraceScorer.segments(o.contours, o.height).map { it.segment }.toSet()
        assertTrue("the hole in the «Ο» is counted as more of the outside", both.size > outer.size)
        assertEquals("a point of the letter lost its piece", o.contours.sumOf { it.size }, TraceScorer.segments(o.contours, o.height).size)
    }

    /** The pieces are along the line, in order: the first point of a contour is not in the last piece. */
    @Test fun `the pieces run along the contour`() {
        val long = TraceScorer.segments(listOf(line(Pt(0f, 0f), Pt(1200f, 0f), step = 6f)), 800f)
        assertEquals(0, long.first().segment)
        assertEquals(12, long.last().segment)
        assertTrue("the pieces are not in order", long.map { it.segment } == long.map { it.segment }.sorted())
    }

    @Test fun `a letter with no height has no pieces to go over`() {
        assertEquals(emptyList<TemplatePoint>(), TraceScorer.segments(o.contours, 0f))
    }

    // ---- nothing to judge ---------------------------------------------------------------------

    @Test fun `nothing drawn is no attempt, not a bad one`() {
        val nothing = score(emptyList(), h, TraceStrictness.NORMAL)
        assertEquals(Float.MAX_VALUE, nothing.meanDistance, 0f)
        assertEquals(0f, nothing.coverage, 0f)
        assertEquals(0f, nothing.precision, 0f)
        assertFalse(nothing.passed)
        assertFalse("an empty stroke is still nothing", score(listOf(emptyList()), h, TraceStrictness.NORMAL).passed)
    }

    /** No letter to trace — a box too small to fit one — is not something he can fail at either. */
    @Test fun `an empty template refuses rather than divides by zero`() {
        val s = TraceScorer.score(handH, emptyList(), h.inside, Strictness.of(TraceStrictness.NORMAL, DENSITY, false))
        assertFalse(s.passed)
        assertEquals(0f, s.coverage, 0f)
    }

    /** Without a mask the outline is all there is, and the centre line is half a stem away from it. */
    @Test fun `with no ink to measure against, the outline is what is left`() {
        val s = TraceScorer.score(
            strokes = listOf(line(Pt(180f, 100f), Pt(180f, 900f))),
            template = h.template,
            inside = { false },
            s = Strictness.of(TraceStrictness.NORMAL, DENSITY, false),
        )
        // A little under half, because at the two ends of the stem the nearest outline is its cap.
        assertEquals("half the width of the ink", STROKE / 2f, s.meanDistance, 4f)
    }

    // ---- the arcade's line, which is not a letter ----------------------------------------------

    /**
     * The arcade's line-following game shares the scorer and nothing else: a road has no ink to be
     * inside of and no shape to get wrong, so it is still marked on the mean distance and how much
     * of the line he went over.
     */
    @Test fun `a line followed is a line followed, and one wandered off is not`() {
        val path = TraceScorer.resample(line(Pt(0f, 0f), Pt(600f, 0f)), 6f)
        val on = TraceScorer.score(
            user = line(Pt(0f, 4f), Pt(600f, 4f)), template = path, templateHeight = 600f,
            tolerancePx = 30f, coverageRadiusPx = 30f, minCoverage = 0.5f,
        )
        assertTrue("a line followed 4 px away was refused: $on", on.passed)
        assertEquals(4f, on.meanDistance, 0.5f)

        val off = TraceScorer.score(
            user = line(Pt(0f, 90f), Pt(600f, 90f)), template = path, templateHeight = 600f,
            tolerancePx = 30f, coverageRadiusPx = 30f, minCoverage = 0.5f,
        )
        assertFalse("a line missed by 90 px passed: $off", off.passed)

        val half = TraceScorer.score(
            user = line(Pt(0f, 0f), Pt(200f, 0f)), template = path, templateHeight = 600f,
            tolerancePx = 30f, coverageRadiusPx = 30f, minCoverage = 0.5f,
        )
        assertFalse("a third of the line passed: $half", half.passed)

        val none = TraceScorer.score(
            user = emptyList(), template = path, templateHeight = 600f,
            tolerancePx = 30f, coverageRadiusPx = 30f,
        )
        assertFalse("nothing drawn followed the line", none.passed)
    }

    // ---- the resampling everything above stands on ---------------------------------------------

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

    /**
     * The gap between two strokes is not something he drew: a letter written in three strokes must
     * not be marked as though he had dragged the pen back across it twice.
     */
    @Test fun `the gap between two strokes is not something he drew`() {
        val apart = score(listOf(handH[0], handH[1]), h, TraceStrictness.NORMAL)
        val joined = score(listOf(handH[0] + handH[1]), h, TraceStrictness.NORMAL)
        assertTrue("two stems on the ink scored as off it: $apart", apart.precision > 0.98f)
        assertTrue("joining the strokes cost nothing, so the gap was never walked: $joined", joined.precision < apart.precision)
    }

    private fun score(
        strokes: List<List<Pt>>,
        glyph: TestGlyph,
        level: TraceStrictness,
        recall: Boolean = false,
    ): TraceScore = TraceScorer.score(strokes, glyph.template, glyph.inside, Strictness.of(level, DENSITY, recall))
}

// ---- the paper this is all drawn on -----------------------------------------------------------

/** Three pixels to the dp, as on his phone: every dp in this file is a dp he can feel. */
private const val DENSITY = 3f

/** The ink of one stroke, across: 50 px of an 800 px letter, as a sans capital's stem is. */
private const val STROKE = 50f

/** How finely the letter's outline is sampled, as [Glyphs] samples a real one. */
private const val OUTLINE_STEP = 6f

/** How often his finger reports a point. */
private const val INK_STEP = 5f

/** How far off the line a hand wanders. */
private const val WOBBLE_DP = 3f

/** A letter, as bars of ink: the outline of each bar is a contour, and the ink is their union. */
private fun glyph(vararg bars: Bar) = TestGlyph(
    height = bars.flatMap { it.contour() }.let { points -> points.maxOf { it.y } - points.minOf { it.y } },
    contours = bars.map { it.contour() },
    inside = { p -> bars.any { it.holds(p) } },
)

/** An «Ο»: two contours — the outside and the hole — and the ink between them. */
private fun ring(cx: Float, cy: Float, outer: Float, inner: Float) = TestGlyph(
    height = 2f * outer,
    contours = listOf(circle(cx, cy, outer), circle(cx, cy, inner)),
    inside = { p -> hypot(p.x - cx, p.y - cy) in inner..outer },
)

private class TestGlyph(val height: Float, val contours: List<List<Pt>>, val inside: (Pt) -> Boolean) {
    val template: List<TemplatePoint> = TraceScorer.segments(contours, height)
}

/** One stroke of ink: a rectangle [width] across, from [from] to [to], with flat ends. */
private class Bar(val from: Pt, val to: Pt, val width: Float) {
    private val dx = to.x - from.x
    private val dy = to.y - from.y
    private val length = hypot(dx, dy)

    /** True where the ink is: within half a width of the line, between its two ends. */
    fun holds(p: Pt): Boolean {
        if (length <= 0f) return hypot(p.x - from.x, p.y - from.y) <= width / 2f
        val t = ((p.x - from.x) * dx + (p.y - from.y) * dy) / (length * length)
        if (t < 0f || t > 1f) return false
        val perpendicular = abs(dy * (p.x - from.x) - dx * (p.y - from.y)) / length
        return perpendicular <= width / 2f
    }

    /** Its outline: the four corners walked round, sampled the way a glyph's outline is. */
    fun contour(): List<Pt> {
        val nx = -dy / length * width / 2f
        val ny = dx / length * width / 2f
        val corners = listOf(
            Pt(from.x + nx, from.y + ny), Pt(to.x + nx, to.y + ny),
            Pt(to.x - nx, to.y - ny), Pt(from.x - nx, from.y - ny),
        )
        return corners.indices.flatMap { i -> line(corners[i], corners[(i + 1) % corners.size], OUTLINE_STEP).dropLast(1) }
    }
}

/** The points of a straight stroke, [step] pixels apart, both ends included. */
private fun line(from: Pt, to: Pt, step: Float = INK_STEP): List<Pt> {
    val length = hypot(to.x - from.x, to.y - from.y)
    if (length <= 0f) return listOf(from)
    val steps = maxOf(1, (length / step).toInt())
    return (0..steps).map { i ->
        val t = i.toFloat() / steps
        Pt(from.x + t * (to.x - from.x), from.y + t * (to.y - from.y))
    }
}

/** A closed circle, walked round, sampled the way a glyph's outline is. */
private fun circle(cx: Float, cy: Float, r: Float, step: Float = OUTLINE_STEP): List<Pt> {
    val steps = maxOf(8, (2.0 * PI * r / step).toInt())
    return (0 until steps).map { i ->
        val a = 2.0 * PI * i / steps
        Pt(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat())
    }
}

/**
 * The same stroke as a hand would leave it: ±3 dp off the line, back and forth. A machine line down
 * the exact middle of the ink would prove that a machine can trace; what has to hold is that a
 * shaking hand's line is read as the letter.
 */
private fun wobbled(points: List<Pt>): List<Pt> = points.mapIndexed { i, p ->
    val off = ((i % 7) - 3) * (WOBBLE_DP * DENSITY / 3f)
    Pt(p.x + off, p.y + off)
}
