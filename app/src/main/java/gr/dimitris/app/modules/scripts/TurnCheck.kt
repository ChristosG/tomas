package gr.dimitris.app.modules.scripts

import gr.dimitris.app.core.data.now
import gr.dimitris.app.core.judge.Ask
import gr.dimitris.app.core.judge.Kind
import gr.dimitris.app.core.judge.LocalJudge
import gr.dimitris.app.core.judge.Verdict
import gr.dimitris.app.core.speech.GentleCheck
import gr.dimitris.app.core.speech.SpeechMatch
import kotlinx.coroutines.CancellationException

/**
 * What the phone made of one answer of his: everything the turn card shows, the row records, and the
 * one thing the screen still owes him.
 *
 * [accepted] is the only verdict that matters to the dialogue — the turn counts, and it is confirmed
 * for him. [expanded] is the full grammatical Greek sentence to show and to say **once** when his
 * answer did not land, so that his next go has something to repeat rather than a wall.
 */
internal data class Weighed(
    /** What the recogniser made of him, or null when the window heard nothing at all. */
    val heard: String?,
    val accepted: Boolean,
    /** The judge's one warm Greek line, or null. Never «λάθος»: `JudgeContract.warm` has seen it first. */
    val feedback: String?,
    /** The full form, the first time there is one on this turn. Null afterwards: it is said once. */
    val expanded: String?,
    val tries: Int,
    val nudging: Boolean,
    val canConfirm: Boolean,
    /** `detail.judge`, or empty when the phone decided this one itself. */
    val judge: Map<String, Any?>,
)

/**
 * One of his turns in a dialogue, weighed — the rules of spec §13's open dialogues, with no Android
 * in them.
 *
 * The wall this removes is the one Chris found: Dimitris answers a question instantly and relevantly,
 * *not* with the words the caregiver happened to script, and the phone — comparing his answer with
 * the scripted line — told him he had got it wrong. A dialogue has no single right answer. So with
 * the judge on, the question asked is [Kind.DIALOGUE]: is this a sensible reply to what was asked?
 * The scripted line goes up as an example and nothing more.
 *
 * With the judge off — no key, «Έλεγχος με Claude» off, no network on the turn before — nothing
 * changes at all: the phase-11 gentle check against the scripted line, exactly as it was. That is
 * deliberate and not a fallback oversight. [LocalJudge] accepts *anything* he says to an open
 * question, which is right for a judge that cannot tell relevance from nonsense but would mean the
 * offline app agreeing with every sound he made; the local comparison at least means something.
 *
 * Everything it needs is a function value, for the reason
 * [gr.dimitris.app.modules.talkboard.ExpansionFlow] is built the same way: the dialogue's ViewModel
 * cannot be built without an Android context, and these rules should be arguable in a test that runs
 * in a second rather than only on a phone.
 *
 * One per turn of his, like the [GentleCheck] it holds.
 */
internal class TurnCheck(
    /** Whether the judge will really be asked: «Έλεγχος με Claude» on and a key saved. */
    private val judged: () -> Boolean,
    /** His own 1–5 dot row for this module. Sent so the model knows how much grammar to insist on. */
    private val difficulty: () -> Int,
    /** [gr.dimitris.app.core.judge.TurnJudge.judge]. Never throws in production; guarded anyway. */
    private val askJudge: suspend (Ask) -> Verdict,
    /** The caregiver's error list. Defaulted to silence so a test that is not about it need not say so. */
    private val record: (String, Throwable) -> Unit = { _, _ -> },
    private val now: () -> Long = ::now,
) {
    private val check = GentleCheck()

    /** The full form has been shown and said on this turn. It is offered once, not on every miss. */
    private var expansionGiven = false

    /**
     * Whether «Το είπα!» is his to press: after an answer that counted, or after two goes that came
     * to nothing. Read before the first window too — with recognition off it is true from the start.
     */
    val canConfirm: Boolean get() = check.canConfirm

    /**
     * One window's worth. [prompt] is the other person's last line — the question he is answering —
     * and [target] the line the caregiver scripted for him, which is an example answer and not the
     * answer.
     */
    suspend fun weigh(heard: String?, prompt: String?, target: String): Weighed {
        // Nothing was heard. The phone did not disagree with him; it did not hear him, and it costs
        // him no try. No judge is asked about an empty string either — the fallback would refuse it
        // anyway, up to eight seconds later, for a window that said nothing about him.
        if (heard == null) {
            check.record(null, false)
            return weighed(null, accepted = false)
        }
        if (!judged()) {
            // Spec §12's gentle check, on a whole turn rather than a word: «θέλω καφέ» for «θέλω έναν
            // καφέ» is the man having the conversation, not failing it.
            val matched = SpeechMatch.phraseMatches(heard, target)
            check.record(heard, matched)
            return weighed(heard, accepted = matched)
        }
        val ask = Ask(kind = Kind.DIALOGUE, prompt = prompt, target = target, heard = heard, difficulty = difficulty())
        val began = now()
        val verdict = try {
            askJudge(ask)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Throwable) {
            // The judge's own contract is that it never throws; if one ever does, his answer still
            // counts rather than the turn dying on him.
            record(WHERE, e)
            LocalJudge.judge(ask)
        }
        check.record(heard, verdict.accept)
        return weighed(
            heard = heard,
            accepted = verdict.accept,
            // Both only on a turn that did not land. An accepted answer moves the conversation on —
            // stopping it to read him a better sentence would be the phone marking work it had just
            // called good. What the model wrote is kept in the row either way: `Verdict.detail`
            // carries `expanded` whenever there was one.
            feedback = if (verdict.accept) null else verdict.feedback,
            expanded = if (verdict.accept) null else expansion(verdict.expanded),
            judge = verdict.detail(ms = now() - began),
        )
    }

    /** The full form the first time this turn produces one, and nothing on any go after it. */
    private fun expansion(expanded: String?): String? {
        if (expansionGiven) return null
        val whole = expanded?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        expansionGiven = true
        return whole
    }

    private fun weighed(
        heard: String?,
        accepted: Boolean,
        feedback: String? = null,
        expanded: String? = null,
        judge: Map<String, Any?> = emptyMap(),
    ) = Weighed(
        heard = heard,
        accepted = accepted,
        feedback = feedback,
        expanded = expanded,
        tries = check.tries,
        nudging = check.nudging,
        canConfirm = check.canConfirm,
        judge = judge,
    )

    companion object {
        /** Where a judge that threw is written down, in the caregiver's «Σφάλματα». */
        const val WHERE = "scripts judge"
    }
}
