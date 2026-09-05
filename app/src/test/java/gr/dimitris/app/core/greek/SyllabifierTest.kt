package gr.dimitris.app.core.greek

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyllabifierTest {

    /** Holds for any implementation: null only for blank input, otherwise a prefix. */
    @Test fun `firstSyllable is null or a prefix of the word`() {
        val s = Syllabifier.firstSyllable("καφές")
        assertTrue(s == null || "καφές".startsWith(s))
    }

    private fun check(word: String, vararg expected: String) = assertEquals(expected.toList(), Syllabifier.syllables(word))

    @Test fun `single consonant goes right`() { check("καφές", "κα", "φές"); check("νερό", "νε", "ρό"); check("θέλω", "θέ", "λω") }
    @Test fun `word-initial clusters stay together`() { check("σπίτι", "σπί", "τι"); check("άνθρωπος", "άν", "θρω", "πος") }
    @Test fun `four syllables`() { check("καλημέρα", "κα", "λη", "μέ", "ρα"); check("τηλέφωνο", "τη", "λέ", "φω", "νο") }
    @Test fun `vowel digraphs are one unit`() { check("ουρανός", "ου", "ρα", "νός"); check("παιδί", "παι", "δί"); check("αυτοκίνητο", "αυ", "το", "κί", "νη", "το") }
    @Test fun `consonant digraphs are one unit`() { check("μπάλα", "μπά", "λα"); check("ντομάτα", "ντο", "μά", "τα") }
    @Test fun `double consonants split`() { check("ελλάδα", "ελ", "λά", "δα"); check("εκκλησία", "εκ", "κλη", "σί", "α") }
    @Test fun `adjacent vowels split`() { check("αέρας", "α", "έ", "ρας") }
    @Test fun `blank is null`() { assertEquals(null, Syllabifier.syllables("  ")) }
    @Test fun `no-vowel input returns the word as one syllable`() { check("ψ", "ψ"); check("στ", "στ") }
    @Test fun `uppercase input keeps case`() { check("ΝΑΙ", "ΝΑΙ"); check("ΚΑΦΕΣ", "ΚΑ", "ΦΕΣ") }
    @Test fun `dialytika keep vowels apart`() { check("μαϊμού", "μα", "ϊ", "μού"); check("προϊόν", "προ", "ϊ", "όν") }
}
