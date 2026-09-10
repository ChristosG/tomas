package gr.dimitris.app.core.judge

import gr.dimitris.app.core.speech.SpeechMatch

/**
 * The judge the app has when it has nothing else: no key, a caregiver who has not turned «Έλεγχος με
 * Claude» on, a phone in flight mode, a request that timed out. It is what every module did before
 * phase 12 and it must keep working for ever, because spec §2 rule 9 says the app works fully
 * offline and §13 did not change that.
 *
 * It is the same lenient comparison [gr.dimitris.app.core.speech.GentleCheck] is driven by, wrapped
 * in a [Verdict] so that a caller can write one code path and not two.
 *
 * Two rules about *not* disagreeing with him, both of them deliberate:
 *
 * * **No target, no opinion.** A DIALOGUE has no right answer and the phone cannot judge relevance,
 *   so anything he actually said is accepted. Refusing an open answer because a string did not match
 *   would be precisely the wall §13 removed — and the same holds for a WORD whose target somehow
 *   arrived blank.
 * * **It never expands.** Building a grammatical Greek sentence out of content words is the one thing
 *   here that genuinely needs the model; a local attempt would be wrong often enough to teach him
 *   wrong forms. So EXPAND hands [Ask.heard] straight back, which is what the talk board and the
 *   sentence builder showed before any of this existed.
 *
 * It says nothing: [Verdict.feedback] is always null. The three screens already own their Greek —
 * «Δοκίμασε ξανά» and «Μπράβο» are [gr.dimitris.app.core.speech.GentleCheck]'s words — and a second
 * source of encouragement appearing only when the network is down would read as a different app.
 */
object LocalJudge {

    fun judge(ask: Ask): Verdict {
        val heard = ask.heard.trim()
        val target = ask.target?.trim().orEmpty()
        return when (ask.kind) {
            // Nothing is judged and nothing is invented: his words, unchanged, or nothing at all.
            Kind.EXPAND -> Verdict(
                accept = true,
                expanded = heard.takeIf { it.isNotEmpty() },
                score = if (heard.isEmpty()) 0f else 1f,
                source = Source.LOCAL,
            )

            Kind.WORD -> verdict(
                accept = heard.isNotEmpty() && (target.isEmpty() || SpeechMatch.matches(heard, target)),
            )

            Kind.SENTENCE -> verdict(
                accept = heard.isNotEmpty() && (target.isEmpty() || SpeechMatch.phraseMatches(heard, target)),
            )

            // Any sensible reply counts and the phone cannot tell sensible from not. So: he spoke,
            // it counts. The score is the one place the uncertainty is recorded — a full mark when
            // the answer did happen to land on the example line, half when nobody can say.
            Kind.DIALOGUE -> Verdict(
                accept = heard.isNotEmpty(),
                score = when {
                    heard.isEmpty() -> 0f
                    target.isNotEmpty() && SpeechMatch.phraseMatches(heard, target) -> 1f
                    else -> UNJUDGED
                },
                source = Source.LOCAL,
            )
        }
    }

    private fun verdict(accept: Boolean) =
        Verdict(accept = accept, score = if (accept) 1f else 0f, source = Source.LOCAL)

    /**
     * What an answer is worth when the phone has no way of knowing. Half: it is not a failure and it
     * is not a confirmed success, and a progress screen averaging these should not be able to claim
     * either. Only DIALOGUE can produce it.
     */
    const val UNJUDGED = 0.5f
}
