package gr.dimitris.app.modules.arcade

/**
 * How big the thing he is aiming at is, in dp, and how that changes.
 *
 * The whole arcade is one rule: what he hits gets a little smaller, what he misses gets bigger. It
 * is the exercise itself — a hand that can find a 40 dp circle is a hand that can find a button —
 * and it is why these targets are allowed under the app's 72 dp floor, which every button he has to
 * *navigate* with still keeps.
 *
 * The step is small on purpose. Eight per cent off a hit is a change he cannot feel in one round and
 * cannot miss over a week; fifteen per cent back on a miss is faster, because a man who cannot reach
 * the target twice must not be left to fail it a third time. [MIN] is about the width of a fingertip
 * and [MAX] is as much of a phone screen as one target may take.
 *
 * Pure: no Android, no Compose, no clock. It is stored in
 * [gr.dimitris.app.core.settings.Settings.arcadeTargetDp] and survives between sittings, so the size
 * he arrives at is where he starts tomorrow.
 */
object Adaptive {
    /** Where a hand that has never played starts: a comfortable button. */
    const val START = 96f

    /** No smaller than a fingertip. Under this it stops being practice and becomes a trick. */
    const val MIN = 40f

    /** No bigger than this: a target that fills the screen is not one he has to aim at. */
    const val MAX = 130f

    /** A hit takes eight per cent off — small enough not to be felt in one round. */
    const val SHRINK = 0.92f

    /** A miss gives fifteen per cent back — faster than it went, so a bad day is not a wall. */
    const val GROW = 1.15f

    fun afterHit(size: Float): Float = (size * SHRINK).coerceAtLeast(MIN)

    fun afterMiss(size: Float): Float = (size * GROW).coerceAtMost(MAX)

    /**
     * A size read back from the settings — or from a backup written by another version — held to
     * what the games can actually draw. Nothing that comes out of the store may leave him with a
     * target too small to touch or too big to fit.
     */
    fun clamp(size: Float): Float = if (size.isNaN()) START else size.coerceIn(MIN, MAX)
}
