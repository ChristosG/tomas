package gr.dimitris.app.modules.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The half of the letter that only a device can answer: the font is the system's, so what a glyph
 * outline and its ink actually contain cannot be asked on the JVM. Everything measurable without a
 * font is a unit test on [TraceScorer] instead.
 */
class GlyphsTest {
    private val boxWidth = 900f
    private val boxHeight = 750f

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
        val left = glyph.points.minOf { it.x }
        val right = glyph.points.maxOf { it.x }
        val top = glyph.points.minOf { it.y }
        val bottom = glyph.points.maxOf { it.y }

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
        val left = ring.points.minOf { it.x }
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
        assertEquals(emptyList<Pt>(), Glyphs.template("", boxWidth, boxHeight).points)
        assertEquals(emptyList<Pt>(), Glyphs.template("Α", 0f, boxHeight).points)
        assertEquals(0f, Glyphs.template("Α", boxWidth, 0f).height, 0f)
        assertFalse("an empty letter has ink", Glyphs.template("", boxWidth, boxHeight).inside(Pt(1f, 1f)))
    }

    /** Far enough into the letter to be past the antialiased edge, near enough to be in the wall. */
    private companion object { const val STEM_PROBE = 12f }
}
