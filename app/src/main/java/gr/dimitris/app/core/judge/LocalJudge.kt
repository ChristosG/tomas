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
 * * **No target, no opinion.** Where the exercise handed over no words to compare with, anything he
 *   actually said is accepted — a WORD or a DIALOGUE whose target arrived blank. Refusing him
 *   because a string he was never given did not match would be precisely the wall §13 removed.
 *   Where there *is* a line, a DIALOGUE is compared with it exactly as a SENTENCE is: this verdict
 *   is what a judge that could not be reached falls back to, and a fallback that accepted everything
 *   would have the phone congratulate him for every sound he made.
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

            // The example line, leniently — the same comparison the dialogue module made before any
            // of this existed, and the same one SENTENCE makes.
            //
            // It used to accept anything he said, on the reasoning that the phone cannot tell a
            // sensible reply from a silly one and must not refuse an open answer. True, and it was
            // still the wrong answer: this verdict is what every *failed* judge falls back to — no
            // network, a timeout, a key that stopped working — so a phone with the toggle on and no
            // signal said «Μπράβο» to every sound he made and wrote a CORRECT row for each of them.
            // A phone that agrees with everything is worth less to him than one that compares him
            // with the line it has, which at least means something. Where there is genuinely nothing
            // to compare with — no target at all — the old rule stands, and the uncertainty goes in
            // the score.
            Kind.DIALOGUE -> {
                val onTarget = target.isNotEmpty() && SpeechMatch.phraseMatches(heard, target)
                Verdict(
                    accept = heard.isNotEmpty() && (target.isEmpty() || onTarget),
                    score = when {
                        heard.isEmpty() -> 0f
                        onTarget -> 1f
                        target.isEmpty() -> UNJUDGED
                        else -> 0f
                    },
                    source = Source.LOCAL,
                )
            }
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
