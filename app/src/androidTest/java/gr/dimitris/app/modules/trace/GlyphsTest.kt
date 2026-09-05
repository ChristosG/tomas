package gr.dimitris.app.modules.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The half of the letter that only a device can answer: the font is the system's, so what a glyph
 * outline actually contains cannot be asked on the JVM. Everything measurable without a font is a
 * unit test on [TraceScorer] instead.
 */
class GlyphsTest {
    private val boxWidth = 900f
    private val boxHeight = 750f

    @Test fun aCapitalAlphaComesBackAsALineToFollow() {
        val (points, height) = Glyphs.template("Α", boxWidth, boxHeight)
        assertTrue("an «Α» sampled into ${points.size} points is not a letter", points.size > 50)
        assertTrue("no height to scale the marking by: $height", height > 0f)
    }

    /** The same call twice is the same letter: what he traces and what he is marked on are one list. */
    @Test fun theSameLetterInTheSameBoxIsAlwaysTheSameTemplate() {
        assertEquals(Glyphs.template("Α", boxWidth, boxHeight).first, Glyphs.template("Α", boxWidth, boxHeight).first)
    }

    /** It fills the box it was given without touching the edges, wherever his finger overshoots. */
    @Test fun theLetterIsCentredAndFitsInsideTheBox() {
        val (points, height) = Glyphs.template("Α", boxWidth, boxHeight)
        val left = points.minOf { it.x }
        val right = points.maxOf { it.x }
        val top = points.minOf { it.y }
        val bottom = points.maxOf { it.y }

        assertTrue("the letter runs off the box: $left..$right, $top..$bottom", left >= 0f && right <= boxWidth && top >= 0f && bottom <= boxHeight)
        assertEquals("the letter is not centred sideways", boxWidth / 2f, (left + right) / 2f, 1f)
        assertEquals("the letter is not centred up and down", boxHeight / 2f, (top + bottom) / 2f, 1f)
        assertEquals("the height reported is not the height drawn", bottom - top, height, 0.01f)
        // Eight tenths of the box, so there is a margin round it: fills it, but is not squeezed to it.
        assertTrue("a letter that fills $height of a $boxHeight box leaves him no room", height <= Glyphs.FILL * boxHeight + 1f)
    }

    /** A word is a longer line than a letter, and its height is the height of the letters in it. */
    @Test fun aWholeWordIsAlsoATemplate() {
        val (letter, letterHeight) = Glyphs.template("Α", boxWidth, boxHeight)
        val (word, wordHeight) = Glyphs.template("Δημήτρης", boxWidth, boxHeight)
        assertTrue("«Δημήτρης» has fewer points than «Α»", word.size > letter.size)
        // Eight letters have to be set smaller to fit the same box, not squeezed into the same height.
        assertTrue("a whole word came out as tall as one capital", wordHeight < letterHeight)
        assertTrue("nothing to trace in a word: $wordHeight", wordHeight > 0f)
    }

    /** Nothing to write, or nowhere to write it: not a letter he got wrong. */
    @Test fun anEmptyTextOrAnEmptyBoxHasNothingToTrace() {
        assertEquals(emptyList<Pt>(), Glyphs.template("", boxWidth, boxHeight).first)
        assertEquals(emptyList<Pt>(), Glyphs.template("Α", 0f, boxHeight).first)
        assertEquals(0f, Glyphs.template("Α", boxWidth, 0f).second, 0f)
    }
}
