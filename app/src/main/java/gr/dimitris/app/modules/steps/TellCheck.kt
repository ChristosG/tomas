package gr.dimitris.app.modules.steps

import gr.dimitris.app.core.data.now
import gr.dimitris.app.core.judge.Ask
import gr.dimitris.app.core.judge.Kind
import gr.dimitris.app.core.judge.Source
import gr.dimitris.app.core.judge.Verdict
import gr.dimitris.app.core.speech.GentleCheck
import gr.dimitris.app.core.speech.SpeechMatch
import kotlinx.coroutines.CancellationException

/**
 * What the phone made of one telling of his: everything the screen shows, the row records, and the
 * one thing the screen still owes him.
 *
 * [accepted] is the only verdict that matters to the exercise — he told the steps, and it is
 * confirmed for him. [whole] is the full telling, handed over **once** when his did not land, so that
 * his next «Μίλα» has a sentence to repeat rather than a wall.
 */
internal data class Told(
    /** What the recogniser made of him, or null when the window heard nothing at all. */
    val heard: String?,
    val accepted: Boolean,
    /** The judge's one warm Greek line, or null. Never «λάθος»: the contract has seen it first. */
    val feedback: String?,
    /**
     * «Πρώτα …, μετά …, τέλος ….», the first time this task produces it, and null on every go after.
     *
     * It is the **task's own** telling and not the model's ([StepTask.telling]), which is the one
     * place this module parts company with the dialogues. There the full sentence can only come from
     * the judge — nobody knows what a good answer to an open question is — and a phone with no key
     * therefore gets no expansion at all. Here the right answer is a fact about the seed: the steps
     * are in order on the screen and the three connectors are fixed Greek. So the help is the same
     * with the judge on and off, which is what spec §12 asks for — the model is never withheld.
     */
    val whole: String?,
    val tries: Int,
    val nudging: Boolean,
    val canConfirm: Boolean,
    /** `detail.judge`, or empty when the phone decided this one itself. */
    val judge: Map<String, Any?>,
)

/**
 * Stage 2 of «Βήματα», weighed — with no Android in it.
 *
 * The question is whether he told the steps *in order*, which is a sentence with a known right answer
 * and therefore [Kind.SENTENCE]: the judge is handed the task as the `prompt` («Φτιάχνω καφέ»), the
 * telling as the `target`, and what the exercise is after as the `intent`. It is not a
 * [Kind.DIALOGUE] — there is exactly one order, and accepting «καφέ, νερό, φωτιά» as one sensible
 * answer among many would throw away the whole exercise.
 *
 * With the judge off — no key, «Έλεγχος με Claude» off, which is a fresh install — the check is
 * [StepTasks.toldInOrder]: every step named, in group order, and **not** the telling as a string. The
 * connectors are what the judge is asked about, and a local matcher that insisted on «πρώτα» and
 * «τέλος» would refuse every telling on a phone with no key. A judge that was on and could not be
 * reached lands in the same place, by the [Source.LOCAL] test in [weigh] — the rule
 * [gr.dimitris.app.modules.scripts.TurnCheck] already states: a phone on a bus with no signal must
 * not answer «Μπράβο» to every sound he makes and write a CORRECT row for each of them.
 *
 * One field of the judge's answer is deliberately dropped: [gr.dimitris.app.core.judge.Verdict.expanded].
 * The SENTENCE contract has the model return a whole correct sentence on every refusal, and here we
 * already have one that is *right* — the task's own [StepTask.telling], built from the steps he can
 * see on the screen. Taking the model's instead would hand him a second wording of the same thing, and
 * a different one on a phone with no key. It is read for nothing; that is on purpose.
 *
 * Everything it needs is a function value, so that these rules can be argued with in a test that runs
 * in a second rather than only on a phone: an [gr.dimitris.app.AppGraph] needs a `Context`.
 *
 * One per task, like the [GentleCheck] it holds.
 */
internal class TellCheck(
    /** Whether the judge will really be asked: «Έλεγχος με Claude» on **and** a key saved. */
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

    /** The telling has been handed over on this task. It is offered once, not on every miss. */
    private var wholeGiven = false

    /**
     * Whether «Το είπα!» is his to press: after a telling that counted, or after two goes that came
     * to nothing. Read before the first window too — with recognition off it is true from the start.
     */
    val canConfirm: Boolean get() = check.canConfirm

    /** How many windows disagreed with him. The row carries it; nothing on the screen does. */
    val tries: Int get() = check.tries

    /**
     * One window's worth. [heard] is null when nothing was heard at all.
     *
     * A window that heard nothing costs him no *try* — the phone did not disagree with him, it did
     * not hear him — and it still counts as one of his two goes, so a recogniser that never hears him
     * cannot hold «Το είπα!» shut. See [GentleCheck].
     */
    suspend fun weigh(heard: String?, task: StepTask): Told {
        // Nothing was heard, so there is nothing to judge — and no judge is asked about an empty
        // string either: the fallback would refuse it anyway, up to eight seconds later. He still gets
        // the telling, because a man who was not heard is owed the model as much as one who was.
        if (heard == null) {
            check.record(null, false)
            return told(null, accepted = false, task = task)
        }
        if (!judged()) return weighLocally(heard, task)
        val ask = Ask(
            kind = Kind.SENTENCE,
            prompt = task.title,
            target = task.telling,
            heard = heard,
            difficulty = difficulty(),
            intent = INTENT,
        )
        val began = now()
        val verdict = try {
            askJudge(ask)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Throwable) {
            // The judge's own contract is that it never throws; if one ever does, the task does not
            // die on him — what he said is compared with the steps, exactly as above.
            record(WHERE, e)
            return weighLocally(heard, task)
        }
        // A LOCAL verdict is the judge saying it was not there: no network, a timeout, a key that
        // stopped working, the toggle turned off mid-sitting. It compared his telling with the
        // *connectors* included, which is not the question this module asks when it is on its own.
        if (verdict.source == Source.LOCAL) return weighLocally(heard, task)
        check.record(heard, verdict.accept)
        return told(
            heard = heard,
            accepted = verdict.accept,
            task = task,
            // Nothing is said to him about a telling that counted beyond the mark itself; a refusal
            // buys the model's one warm line, which is the only Greek in this module that is not ours.
            feedback = if (verdict.accept) null else verdict.feedback,
            judge = verdict.detail(ms = now() - began),
        )
    }

    /**
     * The comparison a phone with no judge makes: every step named, and the groups in the order the
     * task has them ([StepTasks.toldInOrder]).
     *
     * This is the common path and not the rare one — «Έλεγχος με Claude» is off until a caregiver
     * saves a key — which is why it may not be a bag of words. It was one, and it passed a telling
     * with a step missing and a telling said backwards, in the module whose whole subject is «με τη
     * σειρά».
     */
    private fun weighLocally(heard: String, task: StepTask): Told {
        val matched = StepTasks.toldInOrder(task, heard)
        check.record(heard, matched)
        return told(heard, accepted = matched, task = task)
    }

    private fun told(
        heard: String?,
        accepted: Boolean,
        task: StepTask,
        feedback: String? = null,
        judge: Map<String, Any?> = emptyMap(),
    ) = Told(
        heard = heard,
        accepted = accepted,
        feedback = feedback,
        whole = if (accepted) null else whole(task),
        tries = check.tries,
        nudging = check.nudging,
        canConfirm = check.canConfirm,
        judge = judge,
    )

    /** The telling the first time this task needs it, and nothing on any go after it. */
    private fun whole(task: StepTask): String? {
        if (wholeGiven) return null
        val whole = task.telling.takeIf { it.isNotEmpty() } ?: return null
        wholeGiven = true
        return whole
    }

    companion object {
        /** Where a judge that threw is written down, in the caregiver's «Σφάλματα». */
        const val WHERE = "steps judge"

        /**
         * What a good telling has to do, in Greek, for the judge. Written about the exercise and never
         * about him — the rule every [Ask] in this app is built on.
         */
        const val INTENT = "λέει τα βήματα με τη σειρά"
    }
}
