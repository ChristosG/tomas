package gr.dimitris.app.modules.arcade

import gr.dimitris.app.modules.trace.Pt
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Where the next target goes in the play area, in canvas pixels.
 *
 * Two rules, and they are both about his arm rather than about the game. It is never against an
 * edge — the margin is a whole target's width, so a circle is always fully on the glass with room
 * around it for a finger that overshoots — and it is never where the last one was: [FAR_ENOUGH]
 * target widths away, so every target is a *reach*. Two in the same corner is the same movement
 * twice, and the point of the exercise is the movement.
 *
 * "Never where the last one was" is a wish, not a law: in a box too small to hold two targets that
 * far apart the placer takes the farthest place it found rather than looping for one that does not
 * exist. Pure and seedable, so the tests can prove both.
 */
class TargetPlacer(private val random: Random = Random.Default) {
    /**
     * A point inside [width] x [height] for a target [sizePx] across, away from [previous] when the
     * box allows it. [previous] null is the first target of a round.
     */
    fun next(width: Float, height: Float, sizePx: Float, previous: Pt?): Pt {
        val size = sizePx.coerceAtLeast(0f)
        val x = span(width, size)
        val y = span(height, size)
        if (previous == null) return Pt(pick(x), pick(y))

        val far = FAR_ENOUGH * size
        var best = Pt(pick(x), pick(y))
        var bestDistance = hypot(best.x - previous.x, best.y - previous.y)
        // The first candidate that is a real reach wins; failing that, the best of a handful. A
        // fixed number of tries, because a box that cannot hold two targets far apart must still
        // hand back a place to put one.
        for (i in 1 until TRIES) {
            if (bestDistance >= far) return best
            val candidate = Pt(pick(x), pick(y))
            val distance = hypot(candidate.x - previous.x, candidate.y - previous.y)
            if (distance > bestDistance) {
                best = candidate
                bestDistance = distance
            }
        }
        return best
    }

    /**
     * Where a centre may fall along one side: a whole target's width in from both ends, or the
     * middle of the side when there is not that much room to give.
     */
    private fun span(length: Float, size: Float): ClosedFloatingPointRange<Float> {
        val margin = minOf(size, length / 2f)
        val from = margin
        val to = length - margin
        return if (to > from) from..to else (length / 2f)..(length / 2f)
    }

    private fun pick(range: ClosedFloatingPointRange<Float>): Float =
        if (range.endInclusive <= range.start) range.start
        else range.start + random.nextFloat() * (range.endInclusive - range.start)

    companion object {
        /** How far the next target must be from the last one, in target widths. */
        const val FAR_ENOUGH = 2f

        /** Tries before it settles for the best place it has seen. */
        const val TRIES = 24
    }
}
