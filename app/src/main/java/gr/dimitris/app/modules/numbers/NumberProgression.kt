package gr.dimitris.app.modules.numbers

/**
 * CHRIS: rewrite me. When does Dimitris move up a level, and when back down?
 * Claude wrote a first version so nothing blocks; NumberProgressionTest describes the contract.
 *
 * Looks at the last WINDOW first-try results at the current level.
 */
object NumberProgression {
    const val MIN_LEVEL = 1
    const val MAX_LEVEL = 7
    const val WINDOW = 10
    const val UP_RATE = 0.8
    const val DOWN_RATE = 0.5

    /** [results]: oldest first, true = correct on the first try. */
    fun next(level: Int, results: List<Boolean>): Int {
        if (results.size < WINDOW) return level
        val recent = results.takeLast(WINDOW)
        val rate = recent.count { it }.toDouble() / WINDOW
        return when {
            rate >= UP_RATE -> (level + 1).coerceAtMost(MAX_LEVEL)
            rate < DOWN_RATE -> (level - 1).coerceAtLeast(MIN_LEVEL)
            else -> level
        }
    }
}
