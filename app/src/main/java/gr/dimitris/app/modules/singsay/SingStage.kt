package gr.dimitris.app.modules.singsay

import gr.dimitris.app.core.data.Outcome
import gr.dimitris.app.modules.wordcoach.CueLadder

/**
 * The five steps of melodic intonation therapy, in order: he listens, sings along with the phone,
 * sings while the music fades under him, keeps only the tapped beat, and finally says it alone.
 *
 * Each step takes one prop away, so where he stopped is how much help he still needed — that is
 * what [cueLevelFor] hands the Leitner scheduler, on the same 0–4 scale as the word coach's ladder,
 * and what [outcomeFor] turns into the row the caregiver reads.
 */
object SingStage {
    const val LISTEN = 1
    const val TOGETHER = 2
    const val FADING = 3
    const val TAPS_ONLY = 4
    const val SPEAK = 5

    /** The fading stage is three goes, one quieter than the last, and the third has no music at all. */
    const val FADING_REPS = 3

    fun gainFor(stage: Int, repetition: Int): Float = when (stage) {
        LISTEN, TOGETHER -> 1f
        FADING -> listOf(0.6f, 0.3f, 0f).getOrElse(repetition) { 0f }
        else -> 0f
    }

    /**
     * Stage 5 = said it alone = cue 0; stage 1 = only listened = cue 4.
     *
     * [listened] is «Άκου»: the phrase said to him on his own asking, which is the same help the
     * word coach's level 3 is, so a stage that would have scored lower is lifted to it. The button
     * is never withheld (spec §12) — the honesty is in the row, not in the refusal.
     */
    fun cueLevelFor(stage: Int, listened: Boolean = false): Int =
        maxOf((SPEAK - stage).coerceIn(0, 4), if (listened) CueLadder.LISTENED else 0)

    /**
     * What the attempt says. Only the last stage — the phrase spoken with nothing left to lean on
     * and without asking to hear it — is his own; claiming it earlier, or after «Άκου», is real work
     * done with help, and is written as such instead of being lost. A phrase passed over is neither:
     * [Outcome.SKIPPED], whatever stage it reached.
     */
    fun outcomeFor(stage: Int, skipped: Boolean, listened: Boolean = false): Outcome = when {
        skipped -> Outcome.SKIPPED
        stage >= SPEAK && !listened -> Outcome.CORRECT
        else -> Outcome.ASSISTED
    }

    fun label(stage: Int): String = when (stage) {
        LISTEN -> "Άκου"
        TOGETHER -> "Τραγούδα μαζί"
        FADING -> "Τραγούδα, η μουσική σβήνει"
        TAPS_ONLY -> "Πες το με χτύπους"
        else -> "Πες το"
    }

    /**
     * Said out loud on entering the stage. Written text is a hint layer and never the only channel:
     * a man with expressive aphasia understands speech, and the label above is there for whoever is
     * sitting with him. One short adult sentence each — no baby talk, no "let's".
     */
    fun prompt(stage: Int): String = when (stage) {
        LISTEN -> "Άκου."
        TOGETHER -> "Τραγούδα μαζί μου."
        FADING -> "Τραγούδα το μόνος σου."
        TAPS_ONLY -> "Πες το τραγουδιστά."
        else -> "Πες το κανονικά."
    }
}
