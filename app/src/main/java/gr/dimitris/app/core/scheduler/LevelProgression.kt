package gr.dimitris.app.core.scheduler

/**
 * CHRIS: rewrite me. When does Dimitris move up a level, and when back down?
 * Claude wrote a first version so nothing blocks; LevelProgressionTest describes the contract.
 *
 * The numbers module's rule, written once for any module that has levels, with the range as an
 * argument: numbers have seven, sentences have four. [gr.dimitris.app.modules.numbers.NumberProgression]
 * keeps its own copy — it is the one Chris is being asked to rewrite first, and the two are free to
 * end up different.
 *
 * Looks at one sitting only — the results he just produced, nothing from before. History would carry
 * the very answers that moved him last time, and a level he has just failed would promote him
 * straight back into it.
 */
object LevelProgression {
    /** Under this many answers a sitting is too short to mean anything, so the level stands. */
    const val MIN_RESULTS = 5

    /**
     * [results]: this sitting's answers, oldest first, true = right on the first try. [min]..[max]
     * is the module's own range of levels, and [level] is where he is in it now.
     *
     * [window] caps how much of one sitting is judged, oldest dropped first — it is not a memory of
     * older sittings, which never reach this list at all. Nothing in this app runs long enough to
     * reach it: a module gets eight exercises in free practice and fewer in a mixed session.
     *
     * He moves up at [up] and down under [down]. Half right is exactly the line and holds: a level
     * he is getting half of is a level he is learning, not one he is failing.
     */
    fun next(
        level: Int,
        results: List<Boolean>,
        min: Int,
        max: Int,
        window: Int = 10,
        up: Double = 0.8,
        down: Double = 0.5,
    ): Int {
        val counted = results.takeLast(window)
        if (counted.size < MIN_RESULTS) return level
        val rate = counted.count { it }.toDouble() / counted.size
        return when {
            rate >= up -> (level + 1).coerceIn(min, max)
            rate < down -> (level - 1).coerceIn(min, max)
            else -> level.coerceIn(min, max)
        }
    }
}
