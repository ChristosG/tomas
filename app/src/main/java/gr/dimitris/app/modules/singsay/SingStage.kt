package gr.dimitris.app.modules.singsay

/**
 * The five steps of melodic intonation therapy, in order: he listens, sings along with the phone,
 * sings while the music fades under him, keeps only the tapped beat, and finally says it alone.
 *
 * Each step takes one prop away, so where he stopped is how much help he still needed — that is
 * what [cueLevelFor] hands the Leitner scheduler, on the same 0–4 scale as the word coach's ladder.
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

    /** Stage 5 = said it alone = cue 0; stage 1 = only listened = cue 4. */
    fun cueLevelFor(stage: Int): Int = (SPEAK - stage).coerceIn(0, 4)

    fun label(stage: Int): String = when (stage) {
        LISTEN -> "Άκου"
        TOGETHER -> "Τραγούδα μαζί"
        FADING -> "Τραγούδα, η μουσική σβήνει"
        TAPS_ONLY -> "Πες το με χτύπους"
        else -> "Πες το"
    }
}
