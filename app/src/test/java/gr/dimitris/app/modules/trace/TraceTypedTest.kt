package gr.dimitris.app.modules.trace

import gr.dimitris.app.core.judge.Ask
import gr.dimitris.app.core.judge.JudgeContract
import gr.dimitris.app.core.judge.Kind
import gr.dimitris.app.core.judge.Source
import gr.dimitris.app.core.judge.Verdict
import gr.dimitris.app.modules.sentences.TypedCheck
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level 5 of «Γράψε»: a picture, the keyboard, and a whole sentence of his own about the word.
 *
 * It is the same exercise the sentence builder's typed boards ask, asked of the same man, so it is the
 * same [TypedCheck] — not a second copy of the rule that would drift from the first. What this pins is
 * the one thing «Γράψε» asks of it that «Προτάσεις» does not: the **intent**.
 *
 * Without an intent the SENTENCE contract tells the model to accept only a meaning that agrees with
 * the target ([JudgeContract.SYSTEM_PROMPT]), and the target here is whichever sentence
 * `SentenceTemplates` happened to build about the picture — «ο μπαμπάς πίνει τον καφέ» is one correct
 * Greek sentence about a cup of coffee out of dozens. A man with agrammatism who writes «πίνω τον καφέ
 * το πρωί» has done the exercise; answering that with a correction he could not have avoided teaches
 * him nothing. The intent says what every good answer has in common, and the prompt tells the model
 * that where there is one, the target is only an example.
 *
 * [TraceViewModel] cannot be built without an Android context; `TraceFlowTest` proves the wiring on a
 * device with a fake judge behind it.
 */
class TraceTypedTest {

    /** The board: a picture of coffee, and the sentence the articles level built about it. */
    private val word = "καφές"
    private val target = "ο μπαμπάς πίνει τον καφέ"

    private val asked = mutableListOf<Ask>()
    private val verdicts = ArrayDeque<Verdict>()
    private var judged = true

    /** Exactly as [TraceViewModel] builds it: his own dot row, and the judge behind it. */
    private fun check() = TypedCheck(
        judged = { judged },
        difficulty = { 5 },
        askJudge = { ask -> asked += ask; verdicts.removeFirst() },
        now = { 0L },
    )

    /** One press of «Έτοιμο» at the typed level, with the three things the trace module passes. */
    private suspend fun TypedCheck.write(typed: String) =
        weigh(typed, prompt = word, target = target, intent = TraceViewModel.WRITE_A_SENTENCE)

    @Test fun `what goes up is the word, the example sentence, and what a good answer has to do`() = runTest {
        verdicts += Verdict(accept = true, feedback = "Ωραία πρόταση.", score = 1f, source = Source.JUDGE)
        val written = check().write("πίνω τον καφέ το πρωί")

        assertTrue("a correct sentence about the word is his own work", written.accepted)
        assertTrue("and the first go is his own", written.firstTry)
        assertNull("nothing to correct on a sentence he got right", written.whole)

        val ask = asked.single()
        assertEquals(Kind.SENTENCE, ask.kind)
        assertEquals("the word under the picture is the prompt", word, ask.prompt)
        assertEquals("the sentence the board was built from is the target", target, ask.target)
        assertEquals("πίνω τον καφέ το πρωί", ask.heard)
        assertEquals("his own dot row decides how much grammar to insist on", 5, ask.difficulty)
        assertEquals(
            "without the intent the target is the only sentence he is allowed",
            TraceViewModel.WRITE_A_SENTENCE, ask.intent,
        )
        // And the model is actually told what to do with it. A board that sends an intent to a prompt
        // that does not read one is a board that silently asks for the target after all.
        val sentenceRule = JudgeContract.SYSTEM_PROMPT.substringAfter("SENTENCE:").substringBefore("EXPAND:")
        assertTrue("the SENTENCE rule ignores the intent", sentenceRule.contains("intent"))
        assertTrue("and does not say the target is then an example", sentenceRule.contains("παράδειγμα"))
    }

    /**
     * A sentence that did not land is never a wall: the whole form comes back to be copied or to be
     * written again, and the only thing it costs is the mark. The same rule as a traced letter that
     * missed, one level of the language up.
     */
    @Test fun `a sentence the judge refuses comes back whole, and the next go is assisted`() = runTest {
        val check = check()
        verdicts += Verdict(accept = false, expanded = "Ο μπαμπάς πίνει τον καφέ.", feedback = "Κοντά είσαι.", source = Source.JUDGE)
        val first = check.write("καφέ μπαμπάς")
        assertFalse(first.accepted)
        assertEquals("the whole form, to copy", "Ο μπαμπάς πίνει τον καφέ.", first.whole)
        assertTrue("the first go was still his own", first.firstTry)

        verdicts += Verdict(accept = true, score = 1f, source = Source.JUDGE)
        val second = check.write("Ο μπαμπάς πίνει τον καφέ.")
        assertTrue(second.accepted)
        assertFalse("a sentence copied off the screen is not a first try", second.firstTry)
        assertNull(second.whole)
    }

    /**
     * «Άκου» before he has answered says the **word**, and after a refusal says the sentence he has
     * been shown — as often as he wants it.
     *
     * The two halves are one rule apart. Before he answers, reading the target out would be handing
     * him one of the many correct sentences the board accepts, and the exercise is producing one of
     * his own. After a refusal the whole form is already on the screen and has already been said
     * once, and once is not enough for a man who loses the front of a long sentence — which is phase
     * 12's own ruling for the sibling typed board (f666d9f, «he can hear the whole sentence as often
     * as he needs it»). A different rule on the same screen in a different tile would be a difference
     * he has to learn for nothing.
     */
    @Test fun `Akou says the word until a sentence of his has been answered, and the answer after`() {
        val board = TraceState(text = word, variant = TraceVariant.TYPED, sentence = null)
        assertEquals("the word under the picture", word, TraceViewModel.listened(board))

        val refused = board.copy(whole = "Ο μπαμπάς πίνει τον καφέ.")
        assertEquals(
            "the correction, said as the screen shows it",
            "${TraceViewModel.CORRECTION} Ο μπαμπάς πίνει τον καφέ.", TraceViewModel.listened(refused),
        )
    }

    /**
     * The toggle went off between the sitting being planned and «Έτοιμο». The phase-11 comparison is
     * not a judgement of his grammar, but it is honest about the one thing it can see — and it asks
     * nobody, because there is nobody to ask.
     *
     * The planning half of the rule is [TraceViewModel]'s: with the judge unavailable the typed level
     * is not built at all, the sitting is the word level's work, and the screen says
     * [TraceViewModel.NEEDS_JUDGE] once. `TraceFlowTest` walks that on a device.
     */
    @Test fun `with no judge behind it the board asks nobody`() = runTest {
        judged = false
        val check = check()
        assertTrue("he wrote the sentence he was shown", check.write(target).accepted)
        assertFalse("he wrote something else", check.write("καφέ").accepted)
        assertTrue("nothing was asked of anybody", asked.isEmpty())
    }
}
