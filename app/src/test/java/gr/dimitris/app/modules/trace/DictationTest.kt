package gr.dimitris.app.modules.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Υπαγόρευση» — level 4 of «Γράψε» — argued with in a second rather than on a phone.
 *
 * Dimitris knows all his letters, and in September he told us the app was too easy. Tracing a shape
 * that is already on the paper is copying; hearing a word and producing it letter by letter, with
 * nothing on the paper at all, is writing. This is the whole of that rule: which letter comes next,
 * what a miss costs, and what the finished word says about itself.
 *
 * [TraceViewModel] cannot be built without an Android context, so the scorer here is a fake — a
 * letter "passes" when the test says it passed — and `TraceFlowTest` writes a word on real glass with
 * a real hand. What is pinned here is what that wiring is *for*:
 *
 *  * a letter accepted moves the word on one slot, and the slot opens empty;
 *  * a letter that missed leaves the word exactly where it was, to be shown and traced;
 *  * a word with one shown letter in it is assisted work, and a word with none is his own;
 *  * and every letter keeps its own marks, because a word he finished with one weak letter is a
 *    letter to practise and not a word.
 */
class DictationTest {

    private val word = "ψωμί"

    /** The scorer, as this test plays it: a letter is the letter, or it is not. */
    private fun mark(passed: Boolean, coverage: Float = 0.9f, precision: Float = 0.88f, ink: Float = 1.3f) =
        TraceScore(
            coverage = coverage, precision = precision, meanDistance = 4f, passed = passed,
            letters = listOf(LetterScore("?", coverage, precision, passed, ink)),
            inkRatio = ink,
        )

    @Test fun `the word is walked one letter at a time, and the slots are its own letters`() {
        var d = Dictation(word)
        assertEquals("the first slot is the first letter", "ψ", d.expected)
        assertEquals("nothing written yet", "", d.accepted)
        assertFalse(d.done)

        for (letter in word.map { it.toString() }) {
            assertEquals("the slot wants the letter the word spells", letter, d.expected)
            val next = d.judged(mark(passed = true), shown = false)
            assertNotNull("a letter that passed was refused", next)
            d = next!!
        }
        assertTrue("the word never finished", d.done)
        assertNull("a finished word still wants a letter", d.expected)
        assertEquals("the letters he wrote are not the word", word, d.accepted)
        assertFalse("a word written with nothing shown is his own", d.helped)
        assertEquals(word.length, d.written.size)
    }

    /**
     * A miss is not a wrong answer and not the end of the word: the same slot stands, the letter is put
     * on the paper, and he traces it. What it costs is the word's mark — which is the only thing it
     * costs, and the same trade every other exercise in this app makes.
     */
    @Test fun `a letter that missed leaves the word where it was, and the shown letter makes it assisted`() {
        val start = Dictation(word)
        assertNull("a miss cannot take the slot", start.judged(mark(passed = false), shown = false))

        // The letter is shown to him, and he traces it. Same slot, and this time it passes.
        val traced = start.judged(mark(passed = true), shown = true)!!
        assertEquals("the shown letter was not taken", 1, traced.at)
        assertEquals("ω", traced.expected)
        assertTrue("a letter he had to be shown is not his own work", traced.helped)
        assertTrue("and the letter itself has to say so", traced.written.single().missed)

        // Every letter after it is his own, and the word stays assisted: one shown letter is enough.
        var d = traced
        repeat(word.length - 1) { d = d.judged(mark(passed = true), shown = false)!! }
        assertTrue(d.done)
        assertTrue("one shown letter makes the word assisted work", d.helped)
        assertEquals("and only that one letter was shown", 1, d.written.count { it.missed })
    }

    /**
     * Every letter keeps its own marks, and they are what the attempt row carries:
     * `{c, coverage, precision, ink, missed}` each. A word that passed with one weak letter is a
     * letter to practise — and `docs/ADAPTATION.md` cannot say which letter from an average.
     */
    @Test fun `every letter carries its own marks, and the word carries their middle`() {
        var d = Dictation("ψω")
        d = d.judged(mark(passed = true, coverage = 1f, precision = 1f, ink = 1f), shown = false)!!
        d = d.judged(mark(passed = true, coverage = 0.8f, precision = 0.9f, ink = 2f), shown = true)!!

        val rows = d.detail
        assertEquals(2, rows.size)
        assertEquals("ψ", rows[0]["c"])
        assertEquals(1f, rows[0]["coverage"])
        assertEquals(false, rows[0]["missed"])
        assertEquals("ω", rows[1]["c"])
        assertEquals(0.8f, rows[1]["coverage"])
        assertEquals(true, rows[1]["missed"])

        assertEquals("the word's coverage is its letters'", 0.9f, d.coverage!!, 0.001f)
        assertEquals(0.95f, d.precision!!, 0.001f)
        assertEquals(1.5f, d.ink!!, 0.001f)
    }

    /**
     * Which of his words this level can dictate at all.
     *
     * Case cannot be heard: «Δευτέρα» sounds exactly like «δευτέρα», so the first slot of a
     * capital-initial card refuses a correctly written letter and reveals a capital he had no way of
     * knowing was wanted — one guaranteed miss and an assisted row about a letter he can write. The
     * seed's seven day names are WORD items, and every name a caregiver adds is one too.
     */
    @Test fun `a word he cannot spell from the sound is not one to dictate`() {
        for (word in listOf("νερό", "ψωμί", "λογαριασμός", "ζωή")) {
            assertTrue(word, TraceViewModel.dictatable(word))
        }
        for (word in listOf("Δευτέρα", "Μαρία", "Δημήτρης", "ΔΗΜΗΤΡΗΣ")) {
            assertFalse("«$word» begins with a letter he cannot hear", TraceViewModel.dictatable(word))
        }
        assertFalse("a phrase is a dozen slots on one sheet of paper", TraceViewModel.dictatable("θέλω καφέ"))
        assertFalse(TraceViewModel.dictatable("   "))
    }

    /**
     * And what it says when there is nothing on the device to say: five everyday words, never his
     * name. The other levels fall back on «Δημήτρης» because it is the one word nobody can delete;
     * dictating it would be eight letters he cannot hear the case of, and «ΔΗΜΗΤΡΗΣ» eight more.
     */
    @Test fun `the fallback words are words he can spell from hearing them`() {
        assertTrue("nothing to dictate", TraceViewModel.DICTATION_WORDS.isNotEmpty())
        for (word in TraceViewModel.DICTATION_WORDS) {
            assertTrue("«$word» cannot be dictated", TraceViewModel.dictatable(word))
        }
        for (word in TraceViewModel.NAME) {
            assertFalse("his name is not something to dictate", word in TraceViewModel.DICTATION_WORDS)
        }
    }

    /** A word nobody has written a letter of yet has no numbers, and says so with absence. */
    @Test fun `a word with no letters written has nothing to say about itself`() {
        val d = Dictation(word)
        assertNull(d.coverage)
        assertNull(d.precision)
        assertNull(d.ink)
        assertTrue("an empty word is not a row of letters", d.detail.isEmpty())
        assertFalse("and nothing has been shown to him", d.helped)
    }

    /**
     * The letter the *word* spells, not whatever the mark happens to be named after. The two are the
     * same letter today — the mark comes from a glyph built out of [Dictation.expected] — and a row
     * that said «ψ» for a slot the word spells «ω» would be unreadable a year from now.
     */
    @Test fun `the letter on the row is the letter the word wanted`() {
        val d = Dictation("ψω").judged(mark(passed = true), shown = false)!!
        assertEquals("ψ", d.written.single().c)
    }

    /**
     * A target with no per-letter mark at all cannot happen through [TraceScorer] — a pass always has
     * one — and if it ever does, the word's own numbers are the honest answer rather than a crash or a
     * letter dropped out of the word.
     */
    @Test fun `a pass with no per-letter mark still takes the slot`() {
        val bare = TraceScore(coverage = 0.85f, precision = 0.8f, meanDistance = 5f, passed = true, inkRatio = 1.1f)
        val d = Dictation(word).judged(bare, shown = false)!!
        assertEquals(1, d.at)
        assertEquals("ψ", d.written.single().c)
        assertEquals(0.85f, d.written.single().coverage, 0.001f)
    }
}
