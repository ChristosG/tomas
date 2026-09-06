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

    /** The lower key, read the way the synth reads it — through [Key.hz], never [Pitch.hz] directly. */
    @Test fun `the low key drops both pitches to their documented frequencies`() {
        assertEquals(146.83, Key.LOW.hz(Pitch.LOW), 0.001)
        assertEquals(185.0, Key.LOW.hz(Pitch.HIGH), 0.001)
        // The normal key is exactly Pitch's own frequencies: nothing sounds different unasked.
        assertEquals(Pitch.LOW.hz, Key.NORMAL.hz(Pitch.LOW), 0.001)
        assertEquals(Pitch.HIGH.hz, Key.NORMAL.hz(Pitch.HIGH), 0.001)
    }
}
