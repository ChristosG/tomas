package gr.dimitris.app.modules.scripts

import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.Outcome
import gr.dimitris.app.core.judge.Ask
import gr.dimitris.app.core.judge.Kind
import gr.dimitris.app.core.judge.Source
import gr.dimitris.app.core.judge.Verdict
import gr.dimitris.app.core.speech.GentleCheck
import gr.dimitris.app.modules.wordcoach.CueLadder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The open dialogue of spec §13, argued with in a second rather than on a phone.
 *
 * The wall this removes is the one Chris reported: Dimitris answers a question instantly and
 * relevantly — «στο σπίτι» to «πού είσαι;» — but not with the words the caregiver happened to script,
 * and the phone told him he had got it wrong. The rules live in [TurnCheck], because the dialogue's
 * ViewModel cannot be built without an Android context; `ScriptsFlowTest` proves the wiring on a
 * device, and what is pinned here is what the wiring is *for*:
 *
 * * an answer the judge accepts is the turn done, with the scripted line only ever an example;
 * * one it does not buys the warm line and the whole sentence, shown and said **once**, so his next
 *   «Μίλα» has something to repeat rather than a wall;
 * * the sentence being said to him is help, and the row has to say so — which is the difference
 *   between CORRECT and ASSISTED on the turn he then gets right;
 * * two goes and no more: «Το είπα!» comes back and confirms as it always did;
 * * and with the judge off, nothing changes at all — the phase-11 gentle check, against the line.
 */
class ScriptsViewModelTest {

    /** His scripted turn: an example answer, not the answer. */
    private val target = "Στο σπίτι είμαι."

    /** What the other person asked. */
    private val prompt = "Δημήτρη! Τι κάνεις, πού είσαι;"

    private val asked = mutableListOf<Ask>()

    /** What the judge answers, window by window. */
    private val verdicts = ArrayDeque<Verdict>()

    private var judged = true
    private var dots = 3
    private var judgeThrows = false

    /** What the phone said out loud, and the ladder the row is scored on. See [say]. */
    private val spoken = mutableListOf<String>()
    private val ladder = CueLadder(Item(text = target))

    private fun check() = TurnCheck(
        judged = { judged },
        difficulty = { dots },
        askJudge = { ask ->
            asked += ask
            if (judgeThrows) throw IOException("offline")
            verdicts.removeFirst()
        },
    )

    private fun accepted(expanded: String? = null, feedback: String? = null) =
        Verdict(accept = true, expanded = expanded, feedback = feedback, score = 1f, source = Source.JUDGE)

    private fun refused(expanded: String? = null, feedback: String? = null) =
        Verdict(accept = false, expanded = expanded, feedback = feedback, score = 0f, source = Source.JUDGE)

    /**
     * What the ViewModel does with a full form: says it, and marks the ladder — the phone has just
     * read him a whole line of his own, which is [CueLadder.LISTENED]'s worth of help however he got
     * it. Two lines of `ScriptsViewModel.sayExpanded`, so the outcome below is the real rule.
     */
    private fun say(whole: String) {
        ladder.listened()
        spoken += "${ScriptsViewModel.SAY_IT_LIKE} $whole"
    }

    @Test fun `any answer that makes sense is the turn done, whatever the script said`() = runTest {
        verdicts += accepted()
        val weighed = check().weigh("στο σπίτι", prompt, target)

        assertTrue("a relevant answer counts", weighed.accepted)
        assertNull("nothing to repeat: the conversation moves on", weighed.expanded)
        assertEquals(Outcome.CORRECT, ladder.outcomeFor(confirmed = true))
        // What went up, and nothing more of him than the words he just said.
        val ask = asked.single()
        assertEquals(Kind.DIALOGUE, ask.kind)
        assertEquals("the question he was answering", prompt, ask.prompt)
        assertEquals("the scripted line, as an example", target, ask.target)
        assertEquals("στο σπίτι", ask.heard)
        assertEquals("his own dot row decides how much grammar is asked of him", 3, ask.difficulty)
        // The row carries what decided the turn.
        assertEquals(Source.JUDGE.name, weighed.judge["source"])
        assertEquals(true, weighed.judge["accept"])
    }

    /**
     * The core of the therapy. His answer is off, so he is given the whole sentence — once, said and
     * shown — and the go he takes after it is his: CORRECT is not what that turn was, because the
     * words were read to him first.
     */
    @Test fun `an answer that missed earns the full sentence, said once, and the row says it was said`() = runTest {
        val whole = "Είμαι στο σπίτι μου."
        verdicts += refused(expanded = whole, feedback = "Πες μου πού είσαι.")
        verdicts += accepted()
        val check = check()

        val first = check.weigh("καλημέρα", prompt, target)
        assertFalse(first.accepted)
        assertEquals("Πες μου πού είσαι.", first.feedback)
        assertEquals(whole, first.expanded)
        assertEquals("one miss is one nudge", true, first.nudging)
        assertEquals("«Το είπα!» is not his yet: he has another go", false, first.canConfirm)
        first.expanded?.let { say(it) }
        assertEquals(listOf("Πες το έτσι: $whole"), spoken)

        // The repeat. It counts, and it counts as work done with help.
        val second = check.weigh(whole, prompt, target)
        assertTrue(second.accepted)
        assertEquals(Outcome.ASSISTED, ladder.outcomeFor(confirmed = true))
        assertEquals("the sentence is said once, not on every go", 1, spoken.size)
    }

    /** A second miss does not read it to him again: it was offered, and he has heard it. */
    @Test fun `the full sentence is offered once a turn`() = runTest {
        val whole = "Είμαι στο σπίτι μου."
        verdicts += refused(expanded = whole)
        verdicts += refused(expanded = whole)
        val check = check()

        check.weigh("καλημέρα", prompt, target).expanded?.let { say(it) }
        val second = check.weigh("πάμε σπίτι", prompt, target)

        assertNull("he has already been given it", second.expanded)
        assertEquals(1, spoken.size)
    }

    /**
     * A refusal with nothing to hand him is still not a wall: one «Δοκίμασε ξανά», and after the
     * second go «Το είπα!» comes back and confirms exactly as it did before any of this existed.
     */
    @Test fun `two misses with nothing to repeat leave him the confirm`() = runTest {
        verdicts += refused()
        verdicts += refused()
        val check = check()

        val first = check.weigh("καλημέρα", prompt, target)
        assertEquals(1, first.tries)
        assertTrue(first.nudging)
        assertFalse(first.canConfirm)
        assertNull(first.expanded)

        val second = check.weigh("πάμε σπίτι", prompt, target)
        assertEquals(2, second.tries)
        assertFalse("the phone stops asking", second.nudging)
        assertTrue("«Το είπα!» is his again", second.canConfirm)
        assertEquals("and it is still his own turn to claim", Outcome.CORRECT, ladder.outcomeFor(confirmed = true))
    }

    /**
     * With «Έλεγχος με Claude» off there is no network in this app at all, and the turn is checked
     * the way phase 11 checked it: the scripted line, leniently — «θέλω καφέ» for «Θέλω έναν καφέ»
     * is the man having the conversation, not failing it.
     */
    @Test fun `with the judge off the gentle check against the line is unchanged`() = runTest {
        judged = false
        val check = TurnCheck(judged = { false }, difficulty = { dots }, askJudge = { ask -> asked += ask; error("never") })

        val hit = check.weigh("θέλω καφέ", "Τι θα πάρετε;", "Θέλω έναν καφέ.")
        assertTrue("most of his line is his line", hit.accepted)
        assertTrue("nothing left the phone", asked.isEmpty())
        assertTrue("and nothing was written about a judge", hit.judge.isEmpty())
        assertNull(hit.feedback)
        assertNull(hit.expanded)

        val miss = TurnCheck(judged = { false }, difficulty = { dots }, askJudge = { error("never") })
            .weigh("καλημέρα", "Τι θα πάρετε;", "Θέλω έναν καφέ.")
        assertFalse(miss.accepted)
        assertTrue(miss.nudging)
    }

    /**
     * A window that came back with nothing is the phone's silence, not his. No judge is asked about
     * an empty string — the fallback would refuse it eight seconds later — no try is spent, and it
     * is still one of his two goes, so a recogniser that never hears him cannot hold «Το είπα!» shut.
     */
    @Test fun `a window that heard nothing costs him a go and never a try`() = runTest {
        val check = check()

        val first = check.weigh(null, prompt, target)
        assertFalse(first.accepted)
        assertNull(first.heard)
        assertEquals("silence is not him getting it wrong", 0, first.tries)
        assertFalse(first.nudging)
        assertFalse(first.canConfirm)
        assertTrue("nothing was asked of the judge", asked.isEmpty())

        val second = check.weigh(null, prompt, target)
        assertEquals(0, second.tries)
        assertTrue("two dead windows must not lock him out of his own turn", second.canConfirm)
        assertEquals(GentleCheck.WINDOWS_BEFORE_CONFIRM, 2)
    }

    /**
     * The judge's contract is that it never throws. If one ever does, the turn is still his: the
     * local judge takes any real answer to an open question, and the caregiver gets one row.
     */
    @Test fun `a judge that threw does not cost him the turn`() = runTest {
        judgeThrows = true
        val logged = mutableListOf<String>()
        val check = TurnCheck(
            judged = { true }, difficulty = { dots },
            askJudge = { throw IOException("offline") },
            record = { where, _ -> logged += where },
        )

        val weighed = check.weigh("στο σπίτι", prompt, target)

        assertTrue("he answered, and it counts", weighed.accepted)
        assertEquals(Source.LOCAL.name, weighed.judge["source"])
        assertEquals(listOf(TurnCheck.WHERE), logged)
    }
}
