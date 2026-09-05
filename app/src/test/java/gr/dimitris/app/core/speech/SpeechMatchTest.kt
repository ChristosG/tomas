package gr.dimitris.app.core.speech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechMatchTest {
    @Test fun `exact after normalisation`() = assertTrue(SpeechMatch.matches("Καφές", "καφές"))
    @Test fun `accents and case ignored`() = assertTrue(SpeechMatch.matches("ΚΑΦΕΣ", "καφές"))
    @Test fun `target inside a longer utterance`() = assertTrue(SpeechMatch.matches("θέλω καφέ τώρα", "καφέ"))
    @Test fun `one letter off is accepted for words of four or more letters`() = assertTrue(SpeechMatch.matches("καφε", "καφές"))
    @Test fun `different word is rejected`() = assertFalse(SpeechMatch.matches("νερό", "καφές"))
    @Test fun `short words must match exactly`() = assertFalse(SpeechMatch.matches("να", "ναι"))
}
