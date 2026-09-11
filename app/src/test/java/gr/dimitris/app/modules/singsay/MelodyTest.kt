package gr.dimitris.app.modules.singsay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MelodyTest {
    private fun pitches(text: String) = Melody.forPhrase(text).map { it.pitch }
    private fun syllables(text: String) = Melody.forPhrase(text).map { it.syllable }

    @Test fun `one note per syllable across words`() = assertEquals(listOf("θέ", "λω", "κα", "φέ"), syllables("θέλω καφέ"))
    @Test fun `stressed syllable is high, others low`() = assertEquals(listOf(Pitch.HIGH, Pitch.LOW, Pitch.LOW, Pitch.HIGH), pitches("θέλω καφέ"))
    @Test fun `monosyllables are low`() = assertEquals(listOf(Pitch.LOW, Pitch.LOW, Pitch.HIGH), pitches("να πά με"))
    @Test fun `word index follows the words`() = assertEquals(listOf(0, 0, 1), Melody.forPhrase("πάμε σπίτι").map { it.wordIndex }.take(3))
    @Test fun `punctuation and extra spaces are ignored`() = assertEquals(listOf("κα", "λη", "μέ", "ρα"), syllables("  Καλημέρα!  "))
    @Test fun `unaccented capitalised word still gets one high note on its last syllable`() = assertEquals(listOf(Pitch.LOW, Pitch.HIGH), pitches("ΝΕΡΟ"))
    @Test fun `empty gives empty`() = assertEquals(emptyList<Note>(), Melody.forPhrase("  "))

    /** Typed on a phone keyboard without accents — the everyday case, recorded here as it behaves. */
    @Test fun `a tonos-less phrase peaks on each word's last syllable`() =
        assertEquals(listOf(Pitch.LOW, Pitch.HIGH, Pitch.LOW, Pitch.HIGH), pitches("θελω καφε"))

    @Test fun `a punctuation-only string has no notes to tap`() = assertEquals(emptyList<Note>(), Melody.forPhrase("!!! ..."))

    @Test fun `the two pitches are the documented ones`() {
        assertEquals(196.0, Pitch.LOW.hz, 0.001)
        assertEquals(246.94, Pitch.HIGH.hz, 0.001)
    }

    @Test fun `the tempo is the documented one`() {
        assertEquals(550, Melody.NOTE_MS)
        assertEquals(80, Melody.GAP_MS)
    }

    /** The caregiver's slow setting, worth exactly its documented note and gap length. */
    @Test fun `slow tempo holds every note longer and widens the gap`() {
        assertEquals(750, Tempo.SLOW.noteMs)
        assertEquals(110, Tempo.SLOW.gapMs)
        // Normal is still Melody's own pace: a caregiver who never touches the setting hears nothing new.
        assertEquals(Melody.NOTE_MS, Tempo.NORMAL.noteMs)
        assertEquals(Melody.GAP_MS, Tempo.NORMAL.gapMs)
    }

    // ------------------------------------------------------------- breath groups

    /** The syllables of each breath group, as the row would show them grouped. */
    private fun groups(text: String): List<List<String>> {
        val out = mutableListOf(mutableListOf<String>())
        Melody.forPhrase(text).forEachIndexed { i, n ->
            if (n.breathBefore && i > 0) out.add(mutableListOf())
            out.last().add(n.syllable)
        }
        return out
    }

    /**
     * A phrase he can sing in one breath is untouched: no mark, one group, exactly the melody the
     * module has played since phase 4. Everything the app shipped before phase 13 was this short.
     */
    @Test fun `a phrase of one breath has no breath in it`() {
        for (text in listOf("ναι", "θέλω καφέ", "καλημέρα σας", "Δεν καταλαβαίνω")) {
            assertEquals("«$text» was broken up", emptySet<Int>(), Melody.breaths(Melody.forPhrase(text)))
        }
        // Six syllables is the longest single breath, and the sixth one still does not break it.
        assertEquals(6, Melody.forPhrase("θέλω καφέ τώρα").size)
        assertEquals(emptySet<Int>(), Melody.breaths(Melody.forPhrase("θέλω καφέ τώρα")))
    }

    /** «να» starts the next clause, so the breath is taken before it and not inside «ραντεβού». */
    @Test fun `a long sentence breathes before να`() {
        assertEquals(
            listOf(listOf("θέ", "λω"), listOf("να", "αλ", "λά", "ξω"), listOf("το", "ρα", "ντε", "βού")),
            groups("Θέλω να αλλάξω το ραντεβού"),
        )
        assertEquals(setOf(2, 6), Melody.breaths(Melody.forPhrase("Θέλω να αλλάξω το ραντεβού")))
    }

    /**
     * Punctuation first: the comma is the author of the phrase saying where the voice stops, so it
     * outranks «και» — which then takes the second cut, inside the group the comma left.
     */
    @Test fun `punctuation outranks the conjunction`() {
        assertEquals(
            listOf(listOf("θέ", "λω", "γά", "λα"), listOf("και", "ψω", "μί"), listOf("πα", "ρα", "κα", "λώ")),
            groups("Θέλω γάλα και ψωμί, παρακαλώ"),
        )
    }

    /** No comma and no conjunction: the cut is the one that leaves the two halves most even. */
    @Test fun `a twelve-syllable sentence with no punctuation splits down the middle`() {
        assertEquals(
            listOf(listOf("πού", "εί", "ναι", "η", "στά", "ση"), listOf("του", "λε", "ω", "φο", "ρεί", "ου")),
            groups("Πού είναι η στάση του λεωφορείου;"),
        )
    }

    /** Five groups, none of them longer than one breath: what the module moved here for. */
    @Test fun `the longest seed sentence is sung in breaths of at most six syllables`() {
        val text = "Θα ήθελα να κλείσω ένα ραντεβού για αύριο το πρωί"
        val groups = groups(text)
        assertEquals(5, groups.size)
        assertTrue("$groups", groups.all { it.size <= Melody.BREATH_SYLLABLES })
        // Nothing is lost or repeated in the grouping: the notes are the phrase, in order.
        assertEquals(Melody.forPhrase(text).map { it.syllable }, groups.flatten())
    }

    /**
     * A breath inside a word is not a breath group, it is a stutter — so a word longer than the
     * limit is sung whole, and only the words around it are grouped.
     */
    @Test fun `a single long word is never broken`() {
        assertEquals(listOf(listOf("φυ", "σι", "ο", "θε", "ρα", "πεί", "α")), groups("φυσιοθεραπεία"))
        assertEquals(
            listOf(listOf("έ", "χω"), listOf("φυ", "σι", "ο", "θε", "ρα", "πεί", "α"), listOf("την", "τρί", "τη")),
            groups("Έχω φυσιοθεραπεία την Τρίτη"),
        )
    }

    /** Where the groups start, as the synth is handed them — and nothing before the first note. */
    @Test fun `the breaths are the indices of the notes that start a group`() {
        val notes = Melody.forPhrase("Θέλω να αλλάξω το ραντεβού")
        assertEquals(notes.indices.filter { notes[it].breathBefore }.toSet(), Melody.breaths(notes))
        assertFalse("the first note can never carry a breath", notes.first().breathBefore)
    }

    /**
     * The rest itself: three gaps where the next note starts a group, one gap everywhere else — and
     * counted in the caregiver's own gap, so a slower tempo breathes wider too.
     */
    @Test fun `the rest between two breath groups is three gaps long`() {
        val breaths = setOf(3)
        assertEquals(Melody.GAP_MS, Melody.gapAfter(0, Melody.GAP_MS, breaths))
        assertEquals(Melody.GAP_MS * Melody.BREATH_GAPS, Melody.gapAfter(2, Melody.GAP_MS, breaths))
        assertEquals(Melody.GAP_MS, Melody.gapAfter(3, Melody.GAP_MS, breaths))
        assertEquals(Tempo.SLOW.gapMs * Melody.BREATH_GAPS, Melody.gapAfter(2, Tempo.SLOW.gapMs, breaths))
        assertEquals(3, Melody.BREATH_GAPS)
        // A phrase with no breath in it is the even gap it always was, note for note.
        (0..5).forEach { assertEquals(Melody.GAP_MS, Melody.gapAfter(it, Melody.GAP_MS, emptySet())) }
    }

    /** The lower key, read the way the synth reads it — through [Key.hz], never [Pitch.hz] directly. */
    @Test fun `the low key drops both pitches to their documented frequencies`() {
        assertEquals(146.83, Key.LOW.hz(Pitch.LOW), 0.001)
        assertEquals(185.0, Key.LOW.hz(Pitch.HIGH), 0.001)
        // The normal key is exactly Pitch's own frequencies: nothing sounds different unasked.
        assertEquals(Pitch.LOW.hz, Key.NORMAL.hz(Pitch.LOW), 0.001)
        assertEquals(Pitch.HIGH.hz, Key.NORMAL.hz(Pitch.HIGH), 0.001)
    }
}
