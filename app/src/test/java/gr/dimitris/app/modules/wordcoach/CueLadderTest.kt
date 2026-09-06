package gr.dimitris.app.modules.wordcoach

import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CueLadderTest {
    private val full = Item(text = "καφές", firstSound = "κ", firstSyllable = "κα")
    private val noSyllable = Item(text = "καφές", firstSound = "κ", firstSyllable = null)

    @Test fun `walks 0 1 2 3 4 when a syllable exists`() {
        val l = CueLadder(full)
        assertEquals(listOf(0, 1, 2, 3, 4), l.levels)
        assertNull(l.cueText())
        assertEquals("κ", l.hint().let { l.cueText() })
        assertEquals("κα", l.hint().let { l.cueText() })
        assertEquals("καφές", l.hint().let { l.cueText() })
        assertFalse(l.showsWord)
        l.hint()
        assertTrue(l.showsWord)
        assertFalse(l.canHint)
        assertEquals(4, l.hint())   // stays at the top
    }

    @Test fun `skips level 2 without a syllable`() {
        val l = CueLadder(noSyllable)
        assertEquals(listOf(0, 1, 3, 4), l.levels)
        l.hint(); l.hint()
        assertEquals(3, l.level)
    }

    /**
     * A rung that repeats the one below it is no rung. «όχι» is cued «ο» at level 1 and «ό» at
     * level 2 — the same letter with an accent on it — so he pressed «Βοήθεια» for more help and
     * got back exactly what he was already looking at. Every vowel-initial word whose first
     * syllable is that single vowel did it, and the ladder now steps straight on to the word.
     */
    @Test fun `skips a first syllable that only repeats the first sound`() {
        val l = CueLadder(Item(text = "όχι", firstSound = "ο", firstSyllable = "ό"))
        assertEquals(listOf(0, 1, 3, 4), l.levels)
        assertEquals("ο", l.hint().let { l.cueText() })
        assertEquals("όχι", l.hint().let { l.cueText() })
    }

    /** A syllable that adds a sound is still a step, whatever accents or capitals it carries. */
    @Test fun `a syllable that adds a sound keeps its rung`() {
        assertEquals(listOf(0, 1, 2, 3, 4), CueLadder(Item(text = "εκκλησία", firstSound = "ε", firstSyllable = "εκ")).levels)
        assertEquals(listOf(0, 1, 2, 3, 4), CueLadder(Item(text = "Καφές", firstSound = "κ", firstSyllable = "Κα")).levels)
    }

    @Test fun `outcome depends on the level reached`() {
        val l = CueLadder(full)
        assertEquals(Outcome.CORRECT, l.outcomeFor(confirmed = true))
        l.hint(); l.hint()
        assertEquals(Outcome.CORRECT, l.outcomeFor(true))
        l.hint()
        assertEquals(Outcome.ASSISTED, l.outcomeFor(true))
        assertEquals(Outcome.SKIPPED, l.outcomeFor(false))
    }

    /**
     * A cue is a sound to start from, never a sentence. Dialogue turns carry punctuation the words
     * on the talk board do not: level 2 of «Ναι, θα έρθω.» used to be «Ναι,» — the comma shown at
     * displayLarge and handed to the speech engine — and «Τα λέμε. Γεια.» gave the cue «Τα » with
     * its trailing space. Every module shares this ladder, so every module got it.
     */
    @Test fun `punctuation never reaches the cue, at any level`() {
        val line = Item(text = "«Ναι, θα έρθω.»", firstSound = "ν", firstSyllable = "Ναι,")
        val l = CueLadder(line)
        assertEquals("ν", l.hint().let { l.cueText() })
        assertEquals("Ναι", l.hint().let { l.cueText() })
        assertEquals("Ναι θα έρθω", l.hint().let { l.cueText() })
        assertEquals("Ναι θα έρθω", l.hint().let { l.cueText() })
    }

    @Test fun `a trailing space is not part of the syllable`() {
        val l = CueLadder(Item(text = "Τα λέμε. Γεια.", firstSound = "τ", firstSyllable = "Τα "))
        l.hint(); l.hint()
        assertEquals("Τα", l.cueText())
    }

    /** Nothing but marks is nothing to say, and the screen shows nothing rather than a stray dash. */
    @Test fun `a cue that was only punctuation is no cue at all`() {
        val l = CueLadder(Item(text = "!;", firstSound = "!", firstSyllable = ";"))
        assertNull(l.hint().let { l.cueText() })
        assertNull(l.hint().let { l.cueText() })
    }

    /**
     * «Άκου» is on every screen from the first second now (spec §12): a man who cannot retrieve a
     * word is not taught by being made to fail for it first. What it costs is the row, not the
     * button — the level recorded rises to 3, and the word he is looking at does not change.
     */
    @Test fun `listening is written down as help without moving the ladder`() {
        val l = CueLadder(full)
        assertEquals("the word said to him is level 3's worth of help", 3, l.listened())
        assertEquals("the hint sequence has not moved", 0, l.level)
        assertNull("and nothing of the word is on the screen", l.cueText())
        assertFalse(l.showsWord)
        assertEquals(Outcome.ASSISTED, l.outcomeFor(confirmed = true))
    }

    /** At the top of the ladder there is nothing left to raise: 4 is more help than 3, not less. */
    @Test fun `listening at the top leaves the recorded level where it is`() {
        val l = CueLadder(full).apply { hint(); hint(); hint(); hint() }
        assertEquals(4, l.level)
        assertEquals(4, l.listened())
        assertEquals(4, l.recordedLevel)
    }

    /** «Βοήθεια» after «Άκου» carries on from the ladder's own position, not from the recorded one. */
    @Test fun `a hint after listening continues the ladder from where it stood`() {
        val l = CueLadder(full)
        l.listened()
        assertEquals("κ", l.hint().let { l.cueText() })
        assertEquals(1, l.level)
        assertEquals("the row still says he had it said to him", 3, l.recordedLevel)
        assertEquals("κα", l.hint().let { l.cueText() })
        assertEquals(3, l.recordedLevel)
    }

    @Test fun `reset returns to picture only`() {
        val l = CueLadder(full).apply { hint(); hint(); listened() }
        l.reset()
        assertEquals(0, l.level)
        assertEquals("and to a word he has not had said to him", 0, l.recordedLevel)
    }
}
