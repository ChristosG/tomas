package gr.dimitris.app.core.difficulty

import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.modules.arcade.Adaptive
import gr.dimitris.app.modules.numbers.NumberProgression
import gr.dimitris.app.modules.sentences.SentenceTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What each of the five dots means, module by module, as numbers rather than as screenshots.
 *
 * Every mapping here is one Dimitris can feel and nobody can see: a dot that quietly means the same
 * as the dot beside it is an app that ignores him, and the only place that can be caught is a test
 * over the pure functions.
 */
class DifficultyTest {
    @Test fun `the range is one to five and everything else is held inside it`() {
        assertEquals(1, Difficulty.MIN)
        assertEquals(5, Difficulty.MAX)
        assertEquals(1, Difficulty.clamp(0))
        assertEquals(1, Difficulty.clamp(-7))
        assertEquals(5, Difficulty.clamp(6))
        assertEquals(5, Difficulty.clamp(Int.MAX_VALUE))
        for (n in 1..5) assertEquals(n, Difficulty.clamp(n))
    }

    /** Two, because every band below is built so that two is the app exactly as it was before §13. */
    @Test fun `everyone starts at two`() = assertEquals(2, Difficulty.DEFAULT)

    @Test fun `the caregiver's bounds hold the value`() {
        assertEquals(3, Difficulty.clamp(1, floor = 3, ceiling = 5))
        assertEquals(3, Difficulty.clamp(5, floor = 1, ceiling = 3))
        assertEquals(4, Difficulty.clamp(4, floor = 2, ceiling = 4))
    }

    /**
     * A pair that arrived the wrong way round — an old backup, a half-written edit — must never
     * produce an empty range he is locked out of altogether. The floor wins, and he keeps a value.
     */
    @Test fun `bounds the wrong way round still leave him a value`() {
        assertEquals(4, Difficulty.clamp(1, floor = 4, ceiling = 2))
        assertEquals(4, Difficulty.clamp(5, floor = 4, ceiling = 2))
    }

    @Test fun `bounds from outside one to five are themselves held`() {
        assertEquals(1, Difficulty.clamp(3, floor = -5, ceiling = 1))
        assertEquals(5, Difficulty.clamp(3, floor = 5, ceiling = 99))
    }

    // ---------------------------------------------------------------- «Αριθμοί»

    /** The ladder Task 5 is building, unclamped: five bands, ascending, touching, no gaps. */
    @Test fun `the number bands are ascending and contiguous`() {
        assertEquals(5, Difficulty.NUMBERS_BANDS.size)
        assertEquals(1, Difficulty.NUMBERS_BANDS.first().first)
        Difficulty.NUMBERS_BANDS.zipWithNext { a, b -> assertEquals(a.last + 1, b.first) }
        Difficulty.NUMBERS_BANDS.forEach { assertTrue("$it runs backwards", it.first <= it.last) }
    }

    /**
     * Since Task 5 all fifteen levels exist, so no band clamps any more and every dot means something
     * the one beside it does not: the two hardest used to be the third-hardest twice over.
     */
    @Test fun `every number dot now means a band of its own`() {
        assertEquals(15, NumberProgression.MAX_LEVEL)
        assertEquals(1..2, Difficulty.numbers(1))
        assertEquals(3..4, Difficulty.numbers(2))
        assertEquals(5..7, Difficulty.numbers(3))
        assertEquals(8..11, Difficulty.numbers(4))
        assertEquals(12..15, Difficulty.numbers(5))
        assertEquals("a dot that means the same as its neighbour", 5, (1..5).map { Difficulty.numbers(it) }.distinct().size)
    }

    /** The level he is moved to when the dots move: the easiest of the harder work, never the hardest. */
    @Test fun `moving the number dots lands on the bottom of the band`() {
        assertEquals(1, Difficulty.numbers(1).first)
        assertEquals(3, Difficulty.numbers(2).first)
        assertEquals(5, Difficulty.numbers(3).first)
        assertEquals(8, Difficulty.numbers(4).first)
        assertEquals(12, Difficulty.numbers(5).first)
    }

    // -------------------------------------------------------------- «Προτάσεις»

    @Test fun `the sentence bands are ascending and contiguous`() {
        assertEquals(5, Difficulty.SENTENCES_BANDS.size)
        assertEquals(1, Difficulty.SENTENCES_BANDS.first().first)
        Difficulty.SENTENCES_BANDS.zipWithNext { a, b -> assertEquals(a.last + 1, b.first) }
    }

    /** Levels 5 to 8 arrive in Task 7; until then the top three dots all mean level 4. */
    @Test fun `the top sentence bands clamp to the hardest level that exists`() {
        assertEquals(4, SentenceTemplates.MAX_LEVEL)
        assertEquals(1..2, Difficulty.sentences(1))
        assertEquals(3..4, Difficulty.sentences(2))
        assertEquals(4..4, Difficulty.sentences(3))
        assertEquals(4..4, Difficulty.sentences(4))
        assertEquals(4..4, Difficulty.sentences(5))
    }

    // ------------------------------------------------------------------ «Γράψε»

    /**
     * «Γράψε» had five levels before it had five dots, and they are the same five: capitals, small
     * letters, his name, words, words from memory. A band of one level is the point — the automatic
     * progression has nowhere to move him, so what he is writing stays what he chose.
     */
    @Test fun `the writing dots are the writing levels, one each`() {
        for (n in 1..5) assertEquals(n..n, Difficulty.trace(n))
        assertEquals(1..1, Difficulty.trace(0))
        assertEquals(5..5, Difficulty.trace(9))
    }

    // ------------------------------------------------------------------ «Λέξεις»

    @Test fun `the word coach asks for single words at one and adds phrases from two up`() {
        assertEquals(listOf(ItemKind.WORD), Difficulty.wordCoachKinds(1))
        for (n in 2..5) {
            assertEquals("tier at $n", listOf(ItemKind.WORD, ItemKind.PHRASE), Difficulty.wordCoachKinds(n))
        }
    }

    /** An upgraded phone must see the same vocabulary it saw yesterday until he asks otherwise. */
    @Test fun `the default word coach tier is the whole vocabulary it always had`() {
        assertEquals(listOf(ItemKind.WORD, ItemKind.PHRASE), Difficulty.wordCoachKinds(Difficulty.DEFAULT))
        assertEquals(2, Difficulty.wordCoachTier(Difficulty.DEFAULT))
        assertEquals(1, Difficulty.wordCoachTier(1))
    }

    // ------------------------------------------------- «Τραγούδα και πες το»

    @Test fun `the syllable bands are ascending, contiguous and open at the top`() {
        val bands = (1..5).map { Difficulty.syllables(it) }
        assertEquals(1, bands.first().first)
        bands.zipWithNext { a, b -> assertEquals(a.last + 1, b.first) }
        assertEquals("the longest phrase anyone writes has to land somewhere", Int.MAX_VALUE, bands.last().last)
    }

    @Test fun `a phrase is worth the syllables of every word in it`() {
        // θέ-λω κα-φές
        assertEquals(4, Difficulty.syllablesOf("θέλω καφέ"))
        assertEquals(2, Difficulty.syllablesOf("νερό"))
        assertEquals(0, Difficulty.syllablesOf("   "))
        // Extra spaces are somebody typing in a hurry, not a word.
        assertEquals(4, Difficulty.syllablesOf("  θέλω   καφέ "))
    }

    /** Anything the splitter cannot read counts as one syllable, never as none. */
    @Test fun `a word the syllabifier cannot split still counts`() {
        assertTrue(Difficulty.syllablesOf("7") >= 1)
        assertTrue(Difficulty.syllablesOf("ok") >= 1)
    }

    /** The seed's phrases run one to seven syllables, so the middle dots are the ones with content. */
    @Test fun `the seed's own phrase lengths land in the lower bands`() {
        assertTrue(Difficulty.syllablesOf("θέλω καφέ") in Difficulty.syllables(2))
        assertTrue(Difficulty.syllablesOf("καλημέρα σας") in Difficulty.syllables(3))
        assertFalse(Difficulty.syllablesOf("θέλω καφέ") in Difficulty.syllables(5))
    }

    // --------------------------------------------------------------- «Διάλογοι»

    @Test fun `the turn bands are ascending, contiguous and open at the top`() {
        val bands = (1..5).map { Difficulty.turns(it) }
        assertEquals(1, bands.first().first)
        bands.zipWithNext { a, b -> assertEquals(a.last + 1, b.first) }
        assertEquals(Int.MAX_VALUE, bands.last().last)
    }

    /** Every dialogue the app ships gives him four turns, so they all sit in the default band. */
    @Test fun `the shipped dialogues sit in the default band`() {
        assertTrue(4 in Difficulty.turns(Difficulty.DEFAULT))
        assertFalse(4 in Difficulty.turns(1))
        assertFalse(4 in Difficulty.turns(3))
    }

    // -------------------------------------------------------------- «Δεξί χέρι»

    @Test fun `the arcade bands cover what the games can draw, hardest last`() {
        assertEquals(Adaptive.MAX, Difficulty.arcade(1).endInclusive, 0.01f)
        assertEquals(Adaptive.MIN, Difficulty.arcade(5).start, 0.01f)
        // Each band starts where the one below it ends: a smaller target is a harder one.
        (1..4).forEach { d ->
            assertEquals("band $d meets band ${d + 1}", Difficulty.arcade(d).start, Difficulty.arcade(d + 1).endInclusive, 0.01f)
        }
    }

    /** A hand that has been playing must not find its target resized by the upgrade alone. */
    @Test fun `the arcade's starting size sits in the default band`() {
        assertTrue("${Adaptive.START} is not in ${Difficulty.arcade(Difficulty.DEFAULT)}", Adaptive.START in Difficulty.arcade(Difficulty.DEFAULT))
    }

    /** Moving the dots starts him on the biggest target of the new band, which is its easiest. */
    @Test fun `the arcade jumps to the easiest size of the band`() {
        assertEquals(Difficulty.arcade(4).endInclusive, Difficulty.arcadeStart(4), 0.01f)
        assertTrue(Difficulty.arcadeStart(5) < Difficulty.arcadeStart(1))
    }

    @Test fun `a stored arcade size is held inside the band and inside what the games can draw`() {
        assertEquals(Difficulty.arcade(1).start, Difficulty.arcadeClamp(40f, 1), 0.01f)
        assertEquals(Difficulty.arcade(5).endInclusive, Difficulty.arcadeClamp(130f, 5), 0.01f)
        assertEquals(Adaptive.START, Difficulty.arcadeClamp(Adaptive.START, Difficulty.DEFAULT), 0.01f)
        // NaN out of a corrupted store must not leave him aiming at nothing.
        assertTrue(Difficulty.arcadeClamp(Float.NaN, 3) in Difficulty.arcade(3))
    }
}
