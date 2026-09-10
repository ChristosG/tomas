package gr.dimitris.app.modules.sentences

import gr.dimitris.app.core.data.now
import gr.dimitris.app.core.judge.Ask
import gr.dimitris.app.core.judge.Kind
import gr.dimitris.app.core.judge.LocalJudge
import gr.dimitris.app.core.judge.Verdict
import gr.dimitris.app.core.speech.SpeechMatch
import kotlinx.coroutines.CancellationException

/**
 * What the phone made of one sentence he typed.
 *
 * [whole] is the full grammatical sentence to put on the screen and say out loud when his did not
 * land — the model's expansion where there is one, and the sentence the board was built from where
 * there is not. It is null on an answer that counted: a sentence he got right is not a sentence to
 * correct him with.
 */
internal data class Written(
    val accepted: Boolean,
    val whole: String?,
    /** The judge's one warm Greek line, or null. Never «λάθος»: `JudgeContract.warm` has seen it first. */
    val feedback: String?,
    /** True while this is still his first go at this board: it is what CORRECT and ASSISTED turn on. */
    val firstTry: Boolean,
    /** `detail.judge`, or empty when the phone decided this one itself. */
    val judge: Map<String, Any?>,
)

/**
 * The typed sentence of spec §13, with no Android in it.
 *
 * Levels 7 and 8 put a picture and a keyboard in front of him and ask for the whole sentence. It is
 * the only place in the app that asks him to *produce* every word rather than choose it, which is
 * the exercise his speech therapist would call the point of the whole module — and it is the only
 * thing here that cannot be judged on the phone. A sentence is not a word: [SpeechMatch] can tell
 * «καφές» from «καφέ» but it has no opinion about whether «θέλω να φάω ψωμί γιατί πεινάω» is Greek,
 * and pretending otherwise would either wall him or agree with anything he typed.
 *
 * So a typed board only exists when «Έλεγχος με Claude» will really answer
 * ([SentenceTemplates.variantFor]), and this class is what asks it. The fallback path is still
 * written, because the toggle can go off between the board being planned and him pressing «Έτοιμο»:
 * it compares what he typed with the sentence the board was built from, the way phase 11 compared a
 * spoken turn, and lets a near miss through.
 *
 * Never a fail state, exactly like the builder: a sentence that did not land shows him the whole
 * form to copy and hands the keyboard straight back. What it costs is the mark — the first go is
 * CORRECT, everything after it is ASSISTED — and nothing else.
 *
 * One per board, like the [gr.dimitris.app.core.speech.GentleCheck] the dialogues keep.
 */
internal class TypedCheck(
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
    /** True once a sentence of his has been answered with the whole form. It is what spends the mark. */
    var nudged = false
        private set

    /**
     * One press of «Έτοιμο». [prompt] is the word under the picture — the thing the sentence is
     * about, and the only hint the judge is given about what he was looking at — and [target] is the
     * sentence the board was built from.
     */
    suspend fun weigh(typed: String, prompt: String, target: String): Written {
        val first = !nudged
        val said = typed.trim()
        // Nothing typed is nothing to judge, and the screen does not offer «Έτοιμο» on an empty
        // field anyway. Guarded here so that no path can spend eight seconds on an empty string —
        // and it goes back **without** touching [nudged] or handing him a sentence to copy: a board
        // he has not answered yet must not spend the mark it has not been given a chance to earn.
        if (said.isEmpty()) return Written(accepted = false, whole = null, feedback = null, firstTry = first, judge = emptyMap())
        if (!judged()) {
            // The toggle went off between the board being planned and him pressing the button. The
            // phase-11 comparison is not a judgement of his grammar, but it is honest about the one
            // thing it can see: whether he wrote the sentence he was shown.
            val matched = SpeechMatch.phraseMatches(said, target)
            return if (matched) Written(true, null, null, first, emptyMap())
            else refused(target, feedback = null, first = first, judge = emptyMap())
        }
        val ask = Ask(kind = Kind.SENTENCE, prompt = prompt, target = target, heard = said, difficulty = difficulty())
        val began = now()
        val verdict = try {
            askJudge(ask)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Throwable) {
            // The judge's own contract is that it never throws; if one ever does, the board still
            // answers him rather than dying under the keyboard.
            record(WHERE, e)
            LocalJudge.judge(ask)
        }
        val judge = verdict.detail(ms = now() - began)
        if (verdict.accept) return Written(true, null, verdict.feedback, first, judge)
        // «SENTENCE: ποτέ accept false χωρίς expanded» is what the prompt asks of the model, and the
        // board still has to work when it forgets: the sentence it was built from is a correct one.
        return refused(verdict.expanded?.trim()?.takeIf { it.isNotEmpty() } ?: target, verdict.feedback, first, judge)
    }

    private fun refused(whole: String, feedback: String?, first: Boolean, judge: Map<String, Any?>): Written {
        nudged = true
        return Written(accepted = false, whole = whole, feedback = feedback, firstTry = first, judge = judge)
    }

    companion object {
        /** Where a judge that threw is written down, in the caregiver's «Σφάλματα». */
        const val WHERE = "sentences judge"
    }
}
