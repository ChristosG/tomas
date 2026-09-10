package gr.dimitris.app.modules.numbers

/**
 * CHRIS: rewrite me. When does Dimitris move up a level, and when back down?
 * Claude wrote a first version so nothing blocks; NumberProgressionTest describes the contract.
 *
 * Looks at one sitting only — the results he just produced, nothing from before. History would carry
 * the very answers that moved him last time, and a level he has just failed would promote him
 * straight back into it.
 */
object NumberProgression {
    const val MIN_LEVEL = 1

    /**
     * Fifteen, since phase 12. Dimitris said the puzzles up to ten were far too easy, so the ladder
     * now runs past arithmetic with a carry, the tables, change from a note, the clock and the week,
     * four-digit number words and two-step problems. One step at a time is still one step at a time:
     * a man at level 7 meets level 8 next, not level 15, unless he moves the dots himself.
     */
    const val MAX_LEVEL = 15

    /** Under this many answers a sitting is too short to mean anything, so the level stands. */
    const val MIN_RESULTS = 5
    const val UP_RATE = 0.8
    const val DOWN_RATE = 0.4

    /** [results]: this session's answers, oldest first, true = correct on the first try. */
    fun next(level: Int, results: List<Boolean>): Int {
        if (results.size < MIN_RESULTS) return level
        val rate = results.count { it }.toDouble() / results.size
        return when {
            rate >= UP_RATE -> (level + 1).coerceAtMost(MAX_LEVEL)
            rate <= DOWN_RATE -> (level - 1).coerceAtLeast(MIN_LEVEL)
            else -> level
        }
    }
}
