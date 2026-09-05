package gr.dimitris.app.modules.singsay

import org.junit.Assert.assertEquals
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
}
