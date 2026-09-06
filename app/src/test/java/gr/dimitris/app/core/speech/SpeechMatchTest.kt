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

    // Whole lines: a dialogue turn or a sung phrase, where word-for-word would be a wall.

    /** The case from the brief: the small word he dropped must not cost him the turn. */
    @Test fun `most of the line is the line`() =
        assertTrue(SpeechMatch.phraseMatches("θέλω καφέ", "θέλω έναν καφέ"))

    @Test fun `the whole line said exactly`() =
        assertTrue(SpeechMatch.phraseMatches("Θέλω έναν καφέ.", "θέλω έναν καφέ"))

    @Test fun `punctuation and accents are not the exercise`() =
        assertTrue(SpeechMatch.phraseMatches("ΝΑΙ, ΘΑ ΕΡΘΩ!", "Ναι, θα έρθω."))

    /** A different sentence altogether is still a different sentence. */
    @Test fun `another line is rejected`() =
        assertFalse(SpeechMatch.phraseMatches("πάμε σπίτι", "καλημέρα"))

    @Test fun `two words of five is not the line`() =
        assertFalse(SpeechMatch.phraseMatches("θέλω ένα", "θέλω ένα ποτήρι κρύο νερό"))

    /** Three of five is 0.6 exactly, which is the line the rule draws. */
    @Test fun `three words of five is the line`() =
        assertTrue(SpeechMatch.phraseMatches("θέλω ποτήρι νερό", "θέλω ένα ποτήρι κρύο νερό"))

    /** A missing final sigma inside a phrase costs nothing, exactly as it does for one word. */
    @Test fun `a word one letter off inside a phrase still counts`() =
        assertTrue(SpeechMatch.phraseMatches("καλησπερα σας", "καλησπέρα σας"))

    /** He said more than he was asked to: the line is still in there. */
    @Test fun `extra words around the line do not spoil it`() =
        assertTrue(SpeechMatch.phraseMatches("ναι βεβαίως θα έρθω αύριο", "θα έρθω"))

    /** A single-word target keeps the strict rule: two thirds of one word is not a word. */
    @Test fun `one word targets are not loosened`() =
        assertFalse(SpeechMatch.phraseMatches("νερό ψωμί", "καφές"))

    @Test fun `nothing heard is not a match`() = assertFalse(SpeechMatch.phraseMatches("", "θέλω έναν καφέ"))

    @Test fun `an empty target matches nothing`() = assertFalse(SpeechMatch.phraseMatches("καλημέρα", ""))
}
