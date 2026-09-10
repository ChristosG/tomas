package gr.dimitris.app.modules.scripts

import gr.dimitris.app.core.data.now
import gr.dimitris.app.core.judge.Ask
import gr.dimitris.app.core.judge.Kind
import gr.dimitris.app.core.judge.LocalJudge
import gr.dimitris.app.core.judge.Source
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
    /**
     * The full form, the first time there is one on this turn. Null afterwards: it is said once.
     *
     * On an **accepted** turn as often as on a refused one, because a telegraphic answer to «πού
     * είσαι;» is a right answer *and* a sentence worth hearing whole — spec §13 calls that expansion
     * the core therapy. What the screen does with it differs: on a refusal it is what his next
     * «Μίλα» repeats, on an accepted turn it is said once as the conversation moves on.
     */
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
 * With the judge off — no key, «Έλεγχος με Claude» off — nothing changes at all: the phase-11 gentle
 * check against the scripted line, exactly as it was. **A judge that was on and could not be reached
 * lands in the same place**, by the [Source.LOCAL] test in [weigh]: no network, a timeout, a key that
 * stopped working, the toggle turned off mid-run. The alternative was a phone that answered «Μπράβο»
 * to every sound he made for a whole session on a bus, and wrote a CORRECT row for each of them —
 * which is worse for him than having the judge switched off, where the comparison at least means
 * something.
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
     * [target] the line the caregiver scripted for him, which is an example answer and not the
     * answer, and [intent] what any good answer has to convey, which is the thing that makes the
     * question open without making it a guessing game.
     */
    suspend fun weigh(heard: String?, prompt: String?, target: String, intent: String? = null): Weighed {
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
        val ask = Ask(
            kind = Kind.DIALOGUE, prompt = prompt, target = target, heard = heard,
            difficulty = difficulty(), intent = intent,
        )
        val began = now()
        val verdict = try {
            askJudge(ask)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Throwable) {
            // The judge's own contract is that it never throws; if one ever does, the turn does not
            // die on him — the line he has is compared with what he said, exactly as below.
            record(WHERE, e)
            LocalJudge.judge(ask)
        }
        // A LOCAL verdict is not a judgement of an open answer: it is the judge saying it was not
        // there. No network, a timeout, a key that stopped working, the toggle turned off mid-run —
        // [TurnJudge] answers all of them with [LocalJudge], and this turn falls back to the phase-11
        // comparison rather than taking «accept» from a fallback that never saw the question. Without
        // this, a phone on a bus with no signal answered «Μπράβο» to every sound he made and wrote a
        // CORRECT row for each of them, which is worse for him than having the judge switched off.
        val local = verdict.source == Source.LOCAL
        val accepted = if (local) SpeechMatch.phraseMatches(heard, target) else verdict.accept
        check.record(heard, accepted)
        return weighed(
            heard = heard,
            accepted = accepted,
            // Nothing a fallback wrote is shown or said: [LocalJudge] has no feedback and never
            // builds a Greek sentence, and anything that arrived with a LOCAL verdict is not his.
            feedback = if (local || accepted) null else verdict.feedback,
            // On both branches, and once. A telegraphic answer that *counts* is still an answer worth
            // hearing whole (spec §13) — the screen says it and moves on — and a refused turn needs
            // something for his next «Μίλα» to repeat.
            expanded = if (local) null else expansion(verdict.expanded),
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
