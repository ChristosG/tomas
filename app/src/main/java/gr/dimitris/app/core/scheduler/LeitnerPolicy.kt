package gr.dimitris.app.core.scheduler

import gr.dimitris.app.core.data.Outcome

/**
 * CHRIS: rewrite me. This is the move-up / move-down rule of the spaced-repetition system.
 * Claude wrote a first version so nothing blocks; the tests in LeitnerPolicyTest describe the contract.
 *
 * Boxes 1..5. An item in box b comes back after intervalMillis(b).
 */
object LeitnerPolicy {
    const val MIN_BOX = 1
    const val MAX_BOX = 5
    const val DAY_MS = 24L * 60 * 60 * 1000
    private val intervalDays = intArrayOf(1, 2, 4, 8, 16)

    fun nextBox(box: Int, outcome: Outcome, cueLevel: Int?): Int = when (outcome) {
        Outcome.CORRECT -> if ((cueLevel ?: 0) <= 1) (box + 1).coerceAtMost(MAX_BOX) else box
        Outcome.ASSISTED -> box
        Outcome.SKIPPED -> (box - 1).coerceAtLeast(MIN_BOX)
    }.coerceIn(MIN_BOX, MAX_BOX)

    fun intervalMillis(box: Int): Long = intervalDays[(box - MIN_BOX).coerceIn(0, intervalDays.lastIndex)] * DAY_MS
}
