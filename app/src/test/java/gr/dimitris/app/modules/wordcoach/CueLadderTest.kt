package gr.dimitris.app.modules.wordcoach

import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.core.data.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CueLadderTest {
    /** Any stored path: the ladder asks whether there is a picture, never what is in it. */
    private val PICTURE = "photos/x.png"

    /** A word with everything: a picture, a first sound and a first syllable that is a step of its own. */
    private val full = Item(text = "καφές", firstSound = "κ", firstSyllable = "κα", imagePath = PICTURE)
    private val noSyllable = Item(text = "καφές", firstSound = "κ", firstSyllable = null, imagePath = PICTURE)

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
        val l = CueLadder(Item(text = "όχι", firstSound = "ο", firstSyllable = "ό", imagePath = PICTURE))
        assertEquals(listOf(0, 1, 3, 4), l.levels)
        assertEquals("ο", l.hint().let { l.cueText() })
        assertEquals("όχι", l.hint().let { l.cueText() })
    }

    /** A syllable that adds a sound is still a step, whatever accents or capitals it carries. */
    @Test fun `a syllable that adds a sound keeps its rung`() {
        assertEquals(listOf(0, 1, 2, 3, 4), CueLadder(Item(text = "εκκλησία", firstSound = "ε", firstSyllable = "εκ", imagePath = PICTURE)).levels)
        assertEquals(listOf(0, 1, 2, 3, 4), CueLadder(Item(text = "Καφές", firstSound = "κ", firstSyllable = "Κα", imagePath = PICTURE)).levels)
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
        val line = Item(text = "«Ναι, θα έρθω.»", firstSound = "ν", firstSyllable = "Ναι,", imagePath = PICTURE)
        val l = CueLadder(line)
        assertEquals("ν", l.hint().let { l.cueText() })
        assertEquals("Ναι", l.hint().let { l.cueText() })
        assertEquals("Ναι θα έρθω", l.hint().let { l.cueText() })
        assertEquals("Ναι θα έρθω", l.hint().let { l.cueText() })
    }

    @Test fun `a trailing space is not part of the syllable`() {
        val l = CueLadder(Item(text = "Τα λέμε. Γεια.", firstSound = "τ", firstSyllable = "Τα ", imagePath = PICTURE))
        l.hint(); l.hint()
        assertEquals("Τα", l.cueText())
    }

    /** Nothing but marks is nothing to say, and the screen shows nothing rather than a stray dash. */
    @Test fun `a cue that was only punctuation is no cue at all`() {
        val l = CueLadder(Item(text = "!;", firstSound = "!", firstSyllable = ";", imagePath = PICTURE))
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

    /**
     * Level 0 is the picture and nothing else — no sound, no syllable, the word withheld — so for a
     * word that has no picture it is an empty card and the question "what is this?" asked about
     * nothing at all.
     *
     * Two dozen words are like this: ARASAAC draws things, and «ελπίδα», «σκέψη», «κατάσταση» are
     * not things, while «ελπίζω» and «δουλεύω» ship text-led on purpose because the only drawing
     * ARASAAC has for them already belongs to «ελπίδα» and «δουλειά» — and one picture scored
     * against two different words teaches nothing. The rung goes, exactly as a missing syllable
     * does, and the word opens on the first sound.
     */
    @Test fun `skips level 0 without a picture`() {
        val l = CueLadder(Item(text = "ελπίδα", firstSound = "ε", firstSyllable = "ελ"))
        assertEquals(listOf(1, 2, 3, 4), l.levels)
        assertEquals(1, l.level)
        assertEquals("ε", l.cueText())
        assertEquals("ελ", l.hint().let { l.cueText() })
        assertEquals("ελπίδα", l.hint().let { l.cueText() })
        l.hint()
        assertTrue(l.showsWord)
        assertFalse(l.canHint)
    }

    /** Both rungs can be missing at once, and what is left is still a ladder he can walk. */
    @Test fun `a word with neither a picture nor a syllable of its own starts at the first sound`() {
        val l = CueLadder(Item(text = "όχι", firstSound = "ο", firstSyllable = "ό"))
        assertEquals(listOf(1, 3, 4), l.levels)
        assertEquals("ο", l.cueText())
        assertEquals("όχι", l.hint().let { l.cueText() })
    }

    /**
     * A dialogue turn keeps rung 0 with no picture at all, because there rung 0 never meant the
     * picture: «Διάλογοι» draws no card, and what is on the screen is the conversation so far and
     * the line the other person has just said. That is the rung where he answers with nothing given
     * away — the one the attempt row records as unaided work — and dropping it would start every
     * turn of every dialogue one rung of help in.
     */
    @Test fun `a dialogue turn keeps the rung where nothing is given away`() {
        val turn = Item(text = "Έναν καφέ, παρακαλώ.", kind = ItemKind.SCRIPT_LINE, firstSound = "ε", firstSyllable = "Έ")
        val l = CueLadder(turn)
        assertEquals(listOf(0, 1, 3, 4), l.levels)
        assertEquals(0, l.level)
        assertNull(l.cueText())
        assertEquals(0, l.recordedLevel)
    }

    /** A blank path is no path: an empty string is not a picture. */
    @Test fun `a blank image path is not a picture`() {
        assertEquals(listOf(1, 3, 4), CueLadder(Item(text = "όχι", firstSound = "ο", firstSyllable = "ό", imagePath = "  ")).levels)
    }

    /**
     * What he is scored on is what he was actually given. A word with no picture opens on the first
     * sound, so its easiest attempt records level 1 — he had the sound — and that is still his own
     * retrieval, not assistance.
     */
    @Test fun `a word with no picture is still scored as his own when he says it`() {
        val l = CueLadder(Item(text = "ελπίδα", firstSound = "ε", firstSyllable = "ελ"))
        assertEquals(1, l.recordedLevel)
        assertEquals(Outcome.CORRECT, l.outcomeFor(confirmed = true))
    }
}
