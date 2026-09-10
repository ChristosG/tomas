package gr.dimitris.app.core.difficulty

import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.modules.arcade.Adaptive
import gr.dimitris.app.modules.numbers.NumberProgression
import gr.dimitris.app.modules.sentences.SentenceTemplates
import gr.dimitris.app.modules.trace.TraceViewModel
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

    /**
     * All eight levels exist since Task 7, so every dot means a different sentence: 3 the articles,
     * 4 a clause, 5 a question. Nothing clamps any more — which is the answer to Dimitris telling us
     * in September that the module was too easy.
     */
    @Test fun `every sentence dot names levels that exist`() {
        assertEquals(8, SentenceTemplates.MAX_LEVEL)
        assertEquals(1..2, Difficulty.sentences(1))
        assertEquals(3..4, Difficulty.sentences(2))
        assertEquals(5..6, Difficulty.sentences(3))
        assertEquals(7..7, Difficulty.sentences(4))
        assertEquals(8..8, Difficulty.sentences(5))
    }

    // ------------------------------------------------------------------ «Γράψε»

    /**
     * «Γράψε» had five levels before it had five dots, and it still has five: capitals, small letters,
     * words with his finger, the word said and not shown, a whole sentence on the keyboard. A band of
     * one level is the point — the automatic progression has nowhere to move him, so what he is
     * writing stays what he chose.
     */
    @Test fun `the writing dots are the writing levels, one each`() {
        for (n in 1..5) assertEquals(n..n, Difficulty.trace(n))
        assertEquals(1..1, Difficulty.trace(0))
        assertEquals(5..5, Difficulty.trace(9))
    }

    /**
     * And which exercise each of those dots is, which is the half of the mapping that moved in phase
     * 13: his own name came off level 3 (he knows all his letters — tracing «Δημήτρης» was copying),
     * the words came down to it, and the two hardest things a hand can be asked to do went on top.
     *
     * Pinned by the module's own constants rather than by repeating the numbers, so a level that is
     * ever renumbered is renumbered in one place and this test still says what it means. Writing from
     * memory is deliberately **not** a level any more — it is reachable inside dot 3 as the word
     * level's own progression, which is a thing a band of one level cannot express.
     */
    @Test fun `each writing dot names the exercise it asks for`() {
        assertEquals("words with his finger", TraceViewModel.WORD_LEVEL..TraceViewModel.WORD_LEVEL, Difficulty.trace(3))
        assertEquals("the word said, not shown", TraceViewModel.DICTATION_LEVEL..TraceViewModel.DICTATION_LEVEL, Difficulty.trace(4))
        assertEquals("a sentence on the keyboard", TraceViewModel.TYPED_LEVEL..TraceViewModel.TYPED_LEVEL, Difficulty.trace(5))
        // The three hardest are three different exercises, and the two new ones are the top two.
        assertEquals(Difficulty.MAX, TraceViewModel.TYPED_LEVEL)
        assertTrue(TraceViewModel.WORD_LEVEL < TraceViewModel.DICTATION_LEVEL)
        assertTrue(TraceViewModel.DICTATION_LEVEL < TraceViewModel.TYPED_LEVEL)
        // And a dot still names the level it is: a caregiver's stepper and his own dots cannot disagree.
        assertEquals(5, Difficulty.traceDot(TraceViewModel.TYPED_LEVEL))
        assertEquals(4, Difficulty.traceDot(TraceViewModel.DICTATION_LEVEL))
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

    /**
     * One dot, one tier, all five of them: before phase 13 the vocabulary was graded by kind alone,
     * so dots 3, 4 and 5 all handed him the same two hundred everyday words. That is the half of
     * "the app is too easy" this module was guilty of.
     */
    @Test fun `each word coach dot asks for a tier of its own`() {
        for (n in 1..5) assertEquals("dot $n", n, Difficulty.wordCoachTier(n))
    }

    /**
     * Cumulative, like the syllables and the dialogues: a dot admits every tier **at or below** it.
     * «νερό» is still worth saying on the day he asks for «ελευθερία», and the sandwich wants an
     * easy word at each end of the sitting.
     */
    @Test fun `a dot admits every tier at or below it and none above`() {
        for (d in 1..5) for (t in 1..5) {
            assertEquals("tier $t at dot $d", t <= d, Difficulty.admitsTier(t, d))
        }
    }

    /**
     * A word nobody has graded is the easiest tier and is in reach from every dot — which is every
     * word already on a phone, every word a caregiver types without touching the chips, and every
     * row pushed by a phone that has never heard of tiers (those arrive carrying a zero).
     */
    @Test fun `an ungraded word is in reach from every dot`() {
        for (d in 1..5) {
            assertTrue("a zero from an old phone at dot $d", Difficulty.admitsTier(0, d))
            assertTrue("the default tier at dot $d", Difficulty.admitsTier(Item.DEFAULT_TIER, d))
        }
        // And a tier from beyond the row of dots is held to the hardest one there is, never dropped.
        assertTrue(Difficulty.admitsTier(99, 5))
        assertFalse(Difficulty.admitsTier(99, 4))
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

    /**
     * The band is a **ceiling**, and this is the test that says so. The first cut was a window and it
     * deleted half his vocabulary on upgrade: at the default dot «Ναι» and «Όχι» were no longer
     * offered, their Leitner rows went overdue for ever, and no dot brought them back.
     */
    @Test fun `a phrase shorter than the dot asks for is still his to sing`() {
        val short = Difficulty.syllablesOf("ναι")            // 1
        val middle = Difficulty.syllablesOf("θέλω καφέ")      // 4
        val long = Difficulty.syllablesOf("καλημέρα σας")     // 5
        assertTrue("the easiest phrases vanish at the default dot", short <= Difficulty.syllableCeiling(Difficulty.DEFAULT))
        assertTrue(middle <= Difficulty.syllableCeiling(Difficulty.DEFAULT))
        assertFalse("dot 2 must not reach a five-syllable phrase", long <= Difficulty.syllableCeiling(Difficulty.DEFAULT))
        // And every dot above it keeps everything the dots below it had.
        (1..5).forEach { d -> assertTrue(short <= Difficulty.syllableCeiling(d)) }
        assertTrue(long <= Difficulty.syllableCeiling(3))
    }

    @Test fun `the syllable ceilings rise with the dots and the top one takes anything`() {
        val ceilings = (1..5).map { Difficulty.syllableCeiling(it) }
        ceilings.zipWithNext { a, b -> assertTrue("$a is not below $b", a < b) }
        assertEquals(Int.MAX_VALUE, ceilings.last())
    }

    /** Which dot a phrase belongs to: the easiest that still admits it. Used once, on the upgrade. */
    @Test fun `a phrase names the easiest dot that admits it`() {
        assertEquals(1, Difficulty.syllableDot(1))
        assertEquals(1, Difficulty.syllableDot(2))
        assertEquals(2, Difficulty.syllableDot(3))
        assertEquals(3, Difficulty.syllableDot(6))
        assertEquals(5, Difficulty.syllableDot(99))
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

    /**
     * And a ceiling, like the phrases: a two-turn errand at the bakery is still worth having on the
     * day he asked for hard work, and a caregiver who writes one must not have it silently dropped.
     */
    @Test fun `a shorter dialogue is still practisable at a harder dot`() {
        assertTrue(4 <= Difficulty.turnCeiling(Difficulty.DEFAULT))
        (2..5).forEach { d -> assertTrue("four turns unreachable at dot $d", 4 <= Difficulty.turnCeiling(d)) }
        assertFalse("dot 1 is two turns, not four", 4 <= Difficulty.turnCeiling(1))
        val ceilings = (1..5).map { Difficulty.turnCeiling(it) }
        ceilings.zipWithNext { a, b -> assertTrue(a < b) }
    }

    @Test fun `a dialogue names the easiest dot that admits it`() {
        assertEquals(1, Difficulty.turnDot(2))
        assertEquals(2, Difficulty.turnDot(4))
        assertEquals(4, Difficulty.turnDot(9))
        assertEquals(5, Difficulty.turnDot(40))
    }

    /**
     * What the module actually plans on since phase 12 gave a dialogue line a tier of its own: one
     * dot, one tier, and the dot admits everything at or below it. Cumulative, like the phrases and
     * like the word coach's kinds — an easier conversation is still worth having on a hard day, and
     * the six dialogues the app shipped with (tiers 1 and 2) stay in reach at every dot from the
     * default up.
     */
    @Test fun `each dot admits its own tier of dialogue and every easier one`() {
        (Difficulty.MIN..Difficulty.MAX).forEach { d -> assertEquals(d, Difficulty.scriptTier(d)) }
        val ceilings = (1..5).map { Difficulty.scriptTier(it) }
        ceilings.zipWithNext { a, b -> assertTrue(a < b) }
        assertTrue("the shipped dialogues stay in reach at the default dot", 2 <= Difficulty.scriptTier(Difficulty.DEFAULT))
        assertTrue("and the hardest eight are not, at dot 1", 3 > Difficulty.scriptTier(1))
        // Out of range from a backup or a half-written edit is held, never an empty ceiling.
        assertEquals(Difficulty.MIN, Difficulty.scriptTier(0))
        assertEquals(Difficulty.MAX, Difficulty.scriptTier(9))
    }

    // ----------------------------------------------------------------- «Βήματα»

    /**
     * One dot, one task length: 1 is three steps, 2 four, 3 five, 4 six, 5 six and a step that belongs
     * to another task. Cumulative, like the dialogues and the word coach's tiers — «Φτιάχνω καφέ» is
     * still worth sequencing on the day he asks for the cash machine, and a band of 5..5 would have
     * retired three quarters of the tasks the moment he asked for hard work.
     */
    @Test fun `each steps dot admits its own task length and every shorter one`() {
        (Difficulty.MIN..Difficulty.MAX).forEach { d -> assertEquals("dot $d", d, Difficulty.stepsTier(d)) }
        val ceilings = (1..5).map { Difficulty.stepsTier(it) }
        ceilings.zipWithNext { a, b -> assertTrue(a < b) }
        for (d in 1..5) for (t in 1..5) {
            assertEquals("a task of difficulty $t at dot $d", t <= d, Difficulty.admitsSteps(t, d))
        }
    }

    /** An ungraded task — a zero from a hand-edited seed — is the easiest, and in reach from every dot. */
    @Test fun `an ungraded task is in reach from every dot`() {
        for (d in 1..5) {
            assertTrue("dot $d", Difficulty.admitsSteps(0, d))
            assertTrue("dot $d", Difficulty.admitsSteps(-3, d))
        }
        // And out of range the other way is held rather than made unreachable for ever.
        assertEquals(Difficulty.MIN, Difficulty.stepsTier(0))
        assertEquals(Difficulty.MAX, Difficulty.stepsTier(9))
        assertTrue(Difficulty.admitsSteps(9, 5))
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

    /**
     * The stored size is the arcade's clinical measure, so the band is a **ceiling** on it and never
     * a floor. A hand that has worked down to 50 dp over months of physiotherapy keeps its 50 dp
     * whatever the dots say; being handed a 94 dp circle back because the dots defaulted to 2 is
     * weeks of work undone by an upgrade.
     */
    @Test fun `a stored arcade size is never made easier by the dots`() {
        assertEquals("50 dp earned is 50 dp kept", 50f, Difficulty.arcadeClamp(50f, Difficulty.DEFAULT), 0.01f)
        assertEquals(Adaptive.MIN, Difficulty.arcadeClamp(40f, 1), 0.01f)
        assertEquals(Adaptive.START, Difficulty.arcadeClamp(Adaptive.START, Difficulty.DEFAULT), 0.01f)
        // Bigger than the dot asks for is what does get pulled in — that is the dot doing its job.
        assertEquals(Difficulty.arcadeStart(5), Difficulty.arcadeClamp(130f, 5), 0.01f)
        // NaN out of a corrupted store must not leave him aiming at nothing.
        assertTrue(Difficulty.arcadeClamp(Float.NaN, 3) <= Difficulty.arcadeStart(3))
        assertTrue(Difficulty.arcadeClamp(Float.NaN, 3) >= Adaptive.MIN)
    }

    /** Which dot a stored size names: the band it falls in. Used once, on the upgrade. */
    @Test fun `a stored arcade size names the band it falls in`() {
        assertEquals(1, Difficulty.arcadeDot(Adaptive.MAX))
        assertEquals(Difficulty.DEFAULT, Difficulty.arcadeDot(Adaptive.START))
        assertEquals(5, Difficulty.arcadeDot(Adaptive.MIN))
        // And the derivation the migration uses is a fixed point: the dot a size names keeps it.
        listOf(40f, 50f, 70f, 96f, 120f, 130f).forEach { size ->
            assertEquals("$size moved on the upgrade", size, Difficulty.arcadeClamp(size, Difficulty.arcadeDot(size)), 0.01f)
        }
    }

    // ------------------------------------------- the level, at both ends of a sitting

    /**
     * The jump happens before the work, not after it. Left to the progression, a stored level below
     * the band made a **failed** sitting a promotion: dot 5 in «Αριθμοί» with the store on level 7,
     * four of ten right, `next` returns 6 — and a clamp into 12..15 opened tomorrow on two-step word
     * problems, with no tap of his own and nothing said.
     */
    @Test fun `the level a sitting runs at is inside the band`() {
        assertEquals(12, Difficulty.levelAtLoad(7, Difficulty.numbers(5)))
        assertEquals(4, Difficulty.levelAtLoad(14, Difficulty.numbers(2)))
        assertEquals("a level already in the band does not move", 6, Difficulty.levelAtLoad(6, Difficulty.numbers(3)))
        assertEquals(3, Difficulty.levelAtLoad(1, Difficulty.sentences(2)))
    }

    @Test fun `a sitting he got wrong can never raise the level`() {
        val band = Difficulty.numbers(5)   // 12..15
        // The scenario from the review, now impossible: he is at 12, the sitting goes badly, `next`
        // says 11, and the answer is 12 — held, never pushed up into the band he is standing in.
        assertEquals(12, Difficulty.levelAfterSitting(from = 12, next = 11, band = band))
        // A level under the band only ever rises at load, never as a "progression".
        assertEquals(7, Difficulty.levelAfterSitting(from = 7, next = 6, band = band))
        assertEquals(7, Difficulty.levelAfterSitting(from = 7, next = 7, band = band))
    }

    /**
     * A band of one level is the common case, not the corner: «Γράψε» is `d..d` at every dot, and
     * «Προτάσεις» is `7..7` and `8..8` at its top two. Both directions have to return where he
     * started, so the sitting writes nothing and the dots stay the only thing that moves him.
     */
    @Test fun `a band of one level holds him whichever way the sitting went`() {
        listOf(Difficulty.trace(3), Difficulty.sentences(5)).forEach { band ->
            val only = band.first
            assertEquals("$band runs backwards", only, band.last)
            assertEquals("a good sitting moved him out of $band", only, Difficulty.levelAfterSitting(only, only + 1, band))
            assertEquals("a bad sitting moved him out of $band", only, Difficulty.levelAfterSitting(only, only - 1, band))
            assertEquals(only, Difficulty.levelAfterSitting(only, only, band))
        }
    }

    @Test fun `a sitting he earned moves him one step, never past the band`() {
        val band = Difficulty.numbers(3)   // 5..7
        assertEquals(6, Difficulty.levelAfterSitting(from = 5, next = 6, band = band))
        assertEquals("the band is the ceiling on a step up", 7, Difficulty.levelAfterSitting(from = 7, next = 8, band = band))
        assertEquals("and a step down stops at its floor", 5, Difficulty.levelAfterSitting(from = 5, next = 4, band = band))
    }

    /** Reading a level back to the dot that owns it: the whole ladder, not the clamped one. */
    @Test fun `a level names the dot whose band owns it`() {
        assertEquals(1, Difficulty.numbersDot(1))
        assertEquals(2, Difficulty.numbersDot(4))
        assertEquals(3, Difficulty.numbersDot(7))
        assertEquals(4, Difficulty.numbersDot(11))
        assertEquals(5, Difficulty.numbersDot(15))
        assertEquals(5, Difficulty.numbersDot(99))
        // Sentences read off the full ladder, which is why the answer did not move when Task 7 built 5..8.
        assertEquals(1, Difficulty.sentencesDot(2))
        assertEquals(2, Difficulty.sentencesDot(4))
        assertEquals(3, Difficulty.sentencesDot(6))
        assertEquals(5, Difficulty.sentencesDot(8))
        // «Γράψε» has one level per dot.
        (1..5).forEach { assertEquals(it, Difficulty.traceDot(it)) }
    }

    /**
     * The property the whole upgrade rests on: the dot a level names is a dot whose band contains it,
     * so [Difficulty.levelAtLoad] moves nothing on the first run after the migration.
     */
    @Test fun `the dot a level names keeps that level exactly where it is`() {
        (1..NumberProgression.MAX_LEVEL).forEach { level ->
            assertEquals("numbers level $level moved", level, Difficulty.levelAtLoad(level, Difficulty.numbers(Difficulty.numbersDot(level))))
        }
        (1..SentenceTemplates.MAX_LEVEL).forEach { level ->
            assertEquals("sentence level $level moved", level, Difficulty.levelAtLoad(level, Difficulty.sentences(Difficulty.sentencesDot(level))))
        }
    }
}
