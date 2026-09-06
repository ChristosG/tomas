package gr.dimitris.app.modules.trace

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The sentence he reads when a trace missed.
 *
 * It is the whole of what a man with aphasia gets to act on: «Ξανά» over a word of eight letters
 * says nothing, and the letter that missed — named, in the shape it is written on the paper — is
 * everything. Pure, so it is proved here rather than one branch at a time on a phone.
 */
class TraceNudgeTest {
    @Test fun `one letter that missed is named`() {
        assertEquals(
            "Ξανά — δες το «η».",
            TraceViewModel.tryAgain(missed(listOf("Δ" to true, "η" to false, "μ" to true))),
        )
    }

    @Test fun `two letters that missed are both named`() {
        assertEquals(
            "Ξανά — δες το «η» και το «ς».",
            TraceViewModel.tryAgain(missed(listOf("η" to false, "μ" to true, "ς" to false))),
        )
    }

    /** Three names is a list to read, and the marked letters on the paper say it better. */
    @Test fun `three letters or more are shown on the paper rather than listed`() {
        assertEquals(
            "Ξανά — δες τα γράμματα.",
            TraceViewModel.tryAgain(missed(listOf("η" to false, "μ" to false, "ς" to false))),
        )
    }

    /**
     * A single letter he was asked for: there is nothing to point at but the letter itself, and
     * «δες το «Α»» over one «Α» on the paper is the app talking to itself. This is the case levels 1
     * and 2 hit on every miss.
     */
    @Test fun `a single letter is nudged without naming itself`() {
        assertEquals("Ξανά", TraceViewModel.tryAgain(missed(listOf("Α" to false))))
        assertEquals("Ξανά", TraceViewModel.tryAgain(TraceScore(0f, 0f, 9f, passed = false)))
        assertEquals("Ξανά", TraceViewModel.tryAgain(null))
    }

    /** Nothing missed — a word whose letters all passed — is still just «Ξανά». */
    @Test fun `a word with nothing wrong in it says only the invitation`() {
        assertEquals("Ξανά", TraceViewModel.tryAgain(missed(listOf("η" to true, "μ" to true))))
    }

    /**
     * Too much ink is its own sentence, because "do less" is different advice from "look at the
     * shape" — and now that the budget is per letter it can say which letter was drowned.
     */
    @Test fun `too much ink over one letter of a word names that letter`() {
        assertEquals(
            "Πολύ μελάνι στο «η». Ξανά, πιο απλά.",
            TraceViewModel.tryAgain(flooded(listOf("Δ" to true, "η" to false, "μ" to true))),
        )
        assertEquals(
            "Πολύ μελάνι στο «η» και στο «ς». Ξανά, πιο απλά.",
            TraceViewModel.tryAgain(flooded(listOf("η" to false, "μ" to true, "ς" to false))),
        )
    }

    /** One letter coloured in, and a word coloured in all over, both say the plain sentence. */
    @Test fun `too much ink with nothing to point at says the plain sentence`() {
        assertEquals("Πολύ μελάνι. Ξανά, πιο απλά.", TraceViewModel.tryAgain(flooded(listOf("Α" to false))))
        assertEquals(
            "Πολύ μελάνι. Ξανά, πιο απλά.",
            TraceViewModel.tryAgain(flooded(listOf("η" to false, "μ" to false, "ς" to false))),
        )
        assertEquals(TraceViewModel.TOO_MUCH_INK, TraceViewModel.tryAgain(flooded(listOf("Α" to false))))
    }

    /** A word marked letter by letter: each pair is the letter and whether it passed. */
    private fun missed(letters: List<Pair<String, Boolean>>) = TraceScore(
        coverage = 0.5f, precision = 0.5f, meanDistance = 20f, passed = false,
        letters = letters.map { (text, ok) -> LetterScore(text, 0.5f, 0.5f, ok) },
    )

    /** The same, refused for ink before any shape was judged: no numbers, only who was flooded. */
    private fun flooded(letters: List<Pair<String, Boolean>>) = TraceScore(
        coverage = 0f, precision = 0f, meanDistance = Float.MAX_VALUE, passed = false, tooMuchInk = true,
        letters = letters.map { (text, ok) -> LetterScore(text, 0f, 0f, ok, ink = if (ok) 1f else 4f) },
        inkRatio = 2f,
    )
}
