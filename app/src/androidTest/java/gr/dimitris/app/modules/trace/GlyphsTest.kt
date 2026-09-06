package gr.dimitris.app.modules.trace

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * The half of the letter that only a device can answer: the font is the system's, so what a glyph
 * outline and its ink actually contain cannot be asked on the JVM. Everything measurable without a
 * font is a unit test on [TraceScorer] instead.
 */
class GlyphsTest {
    private val boxWidth = 900f
    private val boxHeight = 750f

    /** The phone's own pixels to the dp: what marks him is the size of his fingertip. */
    private val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density

    @Test fun aCapitalAlphaComesBackAsALineToFollow() {
        val glyph = Glyphs.template("Α", boxWidth, boxHeight)
        assertTrue("an «Α» sampled into ${glyph.points.size} points is not a letter", glyph.points.size > 50)
        assertTrue("no height to scale the marking by: ${glyph.height}", glyph.height > 0f)
    }

    /** The same call twice is the same letter: what he traces and what he is marked on are one list. */
    @Test fun theSameLetterInTheSameBoxIsAlwaysTheSameTemplate() {
        assertEquals(Glyphs.template("Α", boxWidth, boxHeight).points, Glyphs.template("Α", boxWidth, boxHeight).points)
    }

    /** It fills the box it was given without touching the edges, wherever his finger overshoots. */
    @Test fun theLetterIsCentredAndFitsInsideTheBox() {
        val glyph = Glyphs.template("Α", boxWidth, boxHeight)
        val left = glyph.points.minOf { it.pt.x }
        val right = glyph.points.maxOf { it.pt.x }
        val top = glyph.points.minOf { it.pt.y }
        val bottom = glyph.points.maxOf { it.pt.y }

        assertTrue("the letter runs off the box: $left..$right, $top..$bottom", left >= 0f && right <= boxWidth && top >= 0f && bottom <= boxHeight)
        assertEquals("the letter is not centred sideways", boxWidth / 2f, (left + right) / 2f, 1f)
        assertEquals("the letter is not centred up and down", boxHeight / 2f, (top + bottom) / 2f, 1f)
        assertEquals("the height reported is not the height drawn", bottom - top, glyph.height, 0.01f)
        // Eight tenths of the box, so there is a margin round it: fills it, but is not squeezed to it.
        assertTrue("a letter that fills ${glyph.height} of a $boxHeight box leaves him no room", glyph.height <= Glyphs.FILL * boxHeight + 1f)
    }

    /**
     * The ink, which is the half that lets him pass. A stem of «Ι» is ink from edge to edge, the
     * paper around the letter is not, and — the case that matters for «Ο», «Θ» and «Β» — the hole in
     * the middle of a letter is not ink either, so a scribble through it is not the letter.
     */
    @Test fun theMaskIsTheInkAndNotTheHoleInIt() {
        val stem = Glyphs.template("Ι", boxWidth, boxHeight)
        assertTrue("the middle of the stem of «Ι» is not ink", stem.inside(Pt(boxWidth / 2f, boxHeight / 2f)))
        assertFalse("the corner of the paper is ink", stem.inside(Pt(2f, 2f)))

        val ring = Glyphs.template("Ο", boxWidth, boxHeight)
        assertFalse("the hole in «Ο» is ink", ring.inside(Pt(boxWidth / 2f, boxHeight / 2f)))
        // The wall of the «Ο» is: half way from the middle to the left edge of the letter.
        val left = ring.points.minOf { it.pt.x }
        assertTrue("the wall of «Ο» is not ink", ring.inside(Pt(left + STEM_PROBE, boxHeight / 2f)))
    }

    /** The face is the ordinary weight: a stem is a line to follow, not a pair of them. */
    @Test fun theStemIsThinEnoughToReadAsOneLine() {
        val glyph = Glyphs.template("Ι", boxWidth, boxHeight)
        val middle = boxHeight / 2f
        val across = (0 until boxWidth.toInt()).count { glyph.inside(Pt(it.toFloat(), middle)) }
        assertTrue("a stem $across px wide on a ${glyph.height} px letter is a bold slab", across < glyph.height * 0.15f)
        assertTrue("no stem found at all", across > 0)
    }

    /** A word is a longer line than a letter, and its letters are set smaller to fit the same box. */
    @Test fun aWholeWordIsAlsoATemplate() {
        val letter = Glyphs.template("Α", boxWidth, boxHeight)
        val word = Glyphs.template("Δημήτρης", boxWidth, boxHeight)
        assertTrue("«Δημήτρης» has fewer points than «Α»", word.points.size > letter.points.size)
        assertTrue("a whole word came out as tall as one capital", word.height < letter.height)
        assertTrue("nothing to trace in a word: ${word.height}", word.height > 0f)
    }

    /** Nothing to write, or nowhere to write it: not a letter he got wrong. */
    @Test fun anEmptyTextOrAnEmptyBoxHasNothingToTrace() {
        assertEquals(emptyList<TemplatePoint>(), Glyphs.template("", boxWidth, boxHeight).points)
        assertEquals(emptyList<TemplatePoint>(), Glyphs.template("Α", 0f, boxHeight).points)
        assertEquals(0f, Glyphs.template("Α", boxWidth, 0f).height, 0f)
        assertFalse("an empty letter has ink", Glyphs.template("", boxWidth, boxHeight).inside(Pt(1f, 1f)))
    }

    /**
     * The letter is cut into pieces of its own to be gone over, contour by contour. The «Ο» is the
     * case that says it works: its hole is pieces of its own, so going round the outside of an «Ο»
     * is not the whole of the letter.
     */
    @Test fun theLetterComesBackInPiecesToBeGoneOver() {
        val ring = Glyphs.template("Ο", boxWidth, boxHeight)
        val pieces = ring.points.map { it.segment }.distinct()
        assertTrue("an «Ο» in ${pieces.size} pieces is not a letter to go over", pieces.size >= 2 * TraceScorer.MIN_SEGMENTS)
        assertEquals("the pieces are not numbered from the start", 0, ring.points.first().segment)

        // A piece is about a twelfth of the letter's height long, so a stroke is a few of them.
        val longest = ring.points.groupBy { it.segment }.values.maxOf { piece ->
            piece.zipWithNext().sumOf { (a, b) -> hypot(a.pt.x - b.pt.x, a.pt.y - b.pt.y).toDouble() }
        }
        assertTrue("a piece of the letter is longer than a stroke of it", longest <= ring.height * TraceScorer.SEGMENT_FRACTION + Glyphs.SAMPLE_STEP)
    }

    /**
     * The field test, with the device's own font: Chris drew a «Κ» over an «Η» and the app said well
     * done. A «Κ» is the wrong letter that comes nearest to being right — it shares the whole left
     * stem of the «Η» — so if anything wrong is going to pass, it is this.
     *
     * Both traces here are hand-like: a line down the middle of every stroke, which is what a person
     * draws when told to write a letter. The right one has to pass and the wrong one must not.
     */
    @Test fun aKWrittenOverAnHIsNotAnH() {
        val h = Glyphs.template("Η", boxWidth, boxHeight)
        val k = Glyphs.template("Κ", boxWidth, boxHeight)
        val handH = HandTrace.centreLine(h)
        val handK = HandTrace.centreLine(k)
        assertTrue("nothing to write", handH.isNotEmpty() && handK.isNotEmpty())

        for (level in listOf(TraceStrictness.NORMAL, TraceStrictness.STRICT)) {
            val s = Strictness.of(level, density, recall = false)
            val right = TraceScorer.score(handH, h.points, h.inside, s)
            val wrong = TraceScorer.score(handK, h.points, h.inside, s)
            val backwards = TraceScorer.score(handH, k.points, k.inside, s)
            Log.i(TAG, "H by hand at $level: $right")
            Log.i(TAG, "K over the H at $level: $wrong")
            Log.i(TAG, "H over the K at $level: $backwards")
            assertTrue("writing «Η» by hand was refused at $level: $right", right.passed)
            assertFalse("a «Κ» passed as an «Η» at $level: $wrong", wrong.passed)
            // And the other way about, so this is about the shape and not about «Η» being easy.
            assertTrue("writing «Κ» by hand was refused at $level", TraceScorer.score(handK, k.points, k.inside, s).passed)
            assertFalse("an «Η» passed as a «Κ» at $level: $backwards", backwards.passed)
        }
        // Χαλαρό is measured and not demanded: it is a caregiver deliberately asking for less, and
        // what the app promises — the wrong letter never passes — it promises at Κανονικό.
        val loose = Strictness.of(TraceStrictness.LOOSE, density, recall = false)
        Log.i(TAG, "K over the H at LOOSE: ${TraceScorer.score(handK, h.points, h.inside, loose)}")
    }

    /**
     * The letter he was asked for passes, however hard a caregiver set the marking: a capital with
     * stems, one with diagonals, a ring, and his own name in eight small letters.
     *
     * This is the promise the whole module rests on. A man who writes the letter and is told «Ξανά»
     * learns that he cannot write, which is the one thing this app must never teach him.
     */
    @Test fun aHandLikeTraceOfTheLetterPassesAtEveryStrictness() {
        for (text in listOf("Η", "Α", "Ο", "Δημήτρης")) {
            val glyph = Glyphs.template(text, boxWidth, boxHeight)
            val hand = HandTrace.centreLine(glyph)
            for (level in TraceStrictness.entries) {
                val s = TraceScorer.score(hand, glyph.points, glyph.inside, Strictness.of(level, density, recall = false))
                Log.i(TAG, "$text by hand at $level: $s")
                assertTrue("writing «$text» by hand was refused at $level: $s", s.passed)
            }
        }
    }

    /** Far enough into the letter to be past the antialiased edge, near enough to be in the wall. */
    private companion object {
        const val STEM_PROBE = 12f
        const val TAG = "TraceNumbers"
    }
}
