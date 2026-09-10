package gr.dimitris.app.modules.scripts

import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.Outcome
import gr.dimitris.app.core.judge.Ask
import gr.dimitris.app.core.judge.Kind
import gr.dimitris.app.core.judge.LocalJudge
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
 * * a telegraphic one that counts still gets the whole sentence, said once, before the conversation
 *   moves on — that expansion is what §13 calls the core therapy;
 * * one it does not buys the warm line and the whole sentence, shown and said **once**, so his next
 *   «Μίλα» has something to repeat rather than a wall;
 * * a sentence said to him *before* his next go is help, and the row has to say so — which is the
 *   difference between CORRECT and ASSISTED on the turn he then gets right;
 * * two goes and no more: «Το είπα!» comes back and confirms as it always did;
 * * with the judge off, nothing changes at all — the phase-11 gentle check, against the line;
 * * and a judge that was on and could not be reached lands in exactly that same place, instead of
 *   rubber-stamping every sound he made.
 */
class ScriptsViewModelTest {

    /** His scripted turn: an example answer, not the answer. */
    private val target = "Στο σπίτι είμαι."

    /** What the other person asked. */
    private val prompt = "Δημήτρη! Τι κάνεις, πού είσαι;"

    /** What any good answer to it has to convey — the caregiver's «Σκοπός». */
    private val intent = "λέει πώς είναι και πού είναι"

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
        val weighed = check().weigh("στο σπίτι", prompt, target, intent)

        assertTrue("a relevant answer counts", weighed.accepted)
        assertNull("nothing to repeat: the conversation moves on", weighed.expanded)
        assertEquals(Outcome.CORRECT, ladder.outcomeFor(confirmed = true))
        // What went up, and nothing more of him than the words he just said.
        val ask = asked.single()
        assertEquals(Kind.DIALOGUE, ask.kind)
        assertEquals("the question he was answering", prompt, ask.prompt)
        assertEquals("the scripted line, as an example", target, ask.target)
        assertEquals("what any good answer has to convey", intent, ask.intent)
        assertEquals("στο σπίτι", ask.heard)
        assertEquals("his own dot row decides how much grammar is asked of him", 3, ask.difficulty)
        // The row carries what decided the turn.
        assertEquals(Source.JUDGE.name, weighed.judge["source"])
        assertEquals(true, weighed.judge["accept"])
    }

    /**
     * Spec §13's core therapy on the branch that produces it most often: «σπίτι» *is* a right answer
     * to «πού είσαι;» and «Είμαι στο σπίτι μου» is what he wanted to have said. The turn counts, the
     * sentence is handed over once, and the conversation moves on.
     *
     * The ladder is deliberately **not** marked here — the ViewModel says the sentence and advances
     * without calling [CueLadder.listened] — because the help arrived after he had already produced
     * the answer. A row that said ASSISTED would be claiming he needed it.
     */
    @Test fun `an accepted telegraphic answer is given the whole sentence too`() = runTest {
        val whole = "Είμαι στο σπίτι μου."
        verdicts += accepted(expanded = whole)

        val weighed = check().weigh("σπίτι", prompt, target, intent)

        assertTrue(weighed.accepted)
        assertEquals("the sentence he meant, to hear once", whole, weighed.expanded)
        assertNull("nothing is said *about* an answer that counted", weighed.feedback)
        assertEquals("work he did himself", Outcome.CORRECT, ladder.outcomeFor(confirmed = true))
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

        val first = check.weigh("καλημέρα", prompt, target, intent)
        assertFalse(first.accepted)
        assertEquals("Πες μου πού είσαι.", first.feedback)
        assertEquals(whole, first.expanded)
        assertEquals("one miss is one nudge", true, first.nudging)
        assertEquals("«Το είπα!» is not his yet: he has another go", false, first.canConfirm)
        first.expanded?.let { say(it) }
        assertEquals(listOf("Πες το έτσι: $whole"), spoken)

        // The repeat. It counts, and it counts as work done with help.
        val second = check.weigh(whole, prompt, target, intent)
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

        check.weigh("καλημέρα", prompt, target, intent).expanded?.let { say(it) }
        val second = check.weigh("πάμε σπίτι", prompt, target, intent)

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

        val first = check.weigh("καλημέρα", prompt, target, intent)
        assertEquals(1, first.tries)
        assertTrue(first.nudging)
        assertFalse(first.canConfirm)
        assertNull(first.expanded)

        val second = check.weigh("πάμε σπίτι", prompt, target, intent)
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

        val first = check.weigh(null, prompt, target, intent)
        assertFalse(first.accepted)
        assertNull(first.heard)
        assertEquals("silence is not him getting it wrong", 0, first.tries)
        assertFalse(first.nudging)
        assertFalse(first.canConfirm)
        assertTrue("nothing was asked of the judge", asked.isEmpty())

        val second = check.weigh(null, prompt, target, intent)
        assertEquals(0, second.tries)
        assertTrue("two dead windows must not lock him out of his own turn", second.canConfirm)
        assertEquals(GentleCheck.WINDOWS_BEFORE_CONFIRM, 2)
    }

    /**
     * The judge's contract is that it never throws. If one ever does, the turn does not die on him:
     * the phone weighs what it heard against the line it has — the phase-11 comparison — and the
     * caregiver gets one row.
     */
    @Test fun `a judge that threw falls back to the line, and he keeps his goes`() = runTest {
        judgeThrows = true
        val logged = mutableListOf<String>()
        val check = TurnCheck(
            judged = { true }, difficulty = { dots },
            askJudge = { throw IOException("offline") },
            record = { where, _ -> logged += where },
        )

        val weighed = check.weigh("στο σπίτι", prompt, target, intent)

        assertTrue("most of the line is the line", weighed.accepted)
        assertEquals(Source.LOCAL.name, weighed.judge["source"])
        assertEquals(listOf(TurnCheck.WHERE), logged)
    }

    /**
     * The defect this pins, and the whole reason a LOCAL verdict is not taken at its word.
     *
     * His phone has the key and «Έλεγχος με Claude» on — the configuration this task exists for — and
     * he practises on the bus with no signal, or the API times out, or a caregiver turns the toggle
     * off in the middle of the session. `TurnJudge` answers every one of those with a `LocalJudge`
     * verdict, and the dialogue must not read that as a judgement: it is the judge saying it was not
     * there. Otherwise every «Μίλα» comes back «Μπράβο», every turn self-confirms, and a session in
     * which he said nothing right at all is written as a run of CORRECTs — into his Leitner boxes and
     * into the rows the adaptation will be read from.
     */
    @Test fun `a judge that could not be reached never says Μπράβο to an answer nobody weighed`() = runTest {
        // Exactly what TurnJudge hands back when there is no network: LocalJudge's own verdict.
        val offline = TurnCheck(
            judged = { true }, difficulty = { dots },
            askJudge = { ask -> asked += ask; LocalJudge.judge(ask) },
        )

        val other = offline.weigh("καλημέρα τι κάνεις", prompt, target, intent)
        assertFalse("an answer nobody judged is not «Μπράβο»", other.accepted)
        assertEquals("it still costs him a try and no more", 1, other.tries)
        assertTrue("and it is the nudge, not a wall", other.nudging)
        assertNull("a fallback has no warm line of its own", other.feedback)
        assertNull("and never invents Greek grammar", other.expanded)
        assertEquals(Source.LOCAL.name, other.judge["source"])

        // And the line he was given still counts, exactly as it did in phase 11.
        val onTarget = offline.weigh("στο σπίτι είμαι", prompt, target, intent)
        assertTrue(onTarget.accepted)
    }

    /**
     * The same, one layer up: the judge is switched off between his first go and his second — the
     * toggle, or a key a caregiver deleted. Nothing about the turn changes shape.
     */
    @Test fun `a judge switched off mid-turn leaves the phase-11 check behind it`() = runTest {
        var on = true
        verdicts += refused(expanded = "Είμαι στο σπίτι μου.")
        val check = TurnCheck(
            judged = { on }, difficulty = { dots },
            askJudge = { ask -> asked += ask; verdicts.removeFirst() },
        )

        check.weigh("καλημέρα", prompt, target, intent)
        on = false
        val second = check.weigh("στο σπίτι είμαι", prompt, target, intent)

        assertTrue("the line he has still means something", second.accepted)
        assertEquals("only the first turn ever reached the judge", 1, asked.size)
        assertTrue("and nothing is written about a judge that was not asked", second.judge.isEmpty())
    }
}
