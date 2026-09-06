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
    fun next(width: Float, height: Float, sizePx: Float, previous: Pt?, margin: Float = sizePx): Pt {
        val size = sizePx.coerceAtLeast(0f)
        val x = span(width, margin)
        val y = span(height, margin)
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
     * A place for a target [sizePx] across that is never nearer than [clearance] to [avoid], and is
     * fully on the board rather than a whole width in from it — [margin] is a radius here, because
     * this is the drag game's ball and it needs the room the ring is taking up.
     *
     * Unlike [next], the clearance is a rule and not a wish: a ball that starts inside its own ring
     * is a round he wins by touching the glass, a CORRECT row that overstates his hand, and five
     * steps down the size ladder he did not earn. When no random candidate clears, the place
     * farthest from [avoid] is taken — the corner of the span, which on any board the games are
     * played on is well outside the ring.
     */
    fun clearOf(
        width: Float,
        height: Float,
        sizePx: Float,
        avoid: Pt,
        clearance: Float,
        margin: Float = sizePx / 2f,
    ): Pt {
        val x = span(width, margin)
        val y = span(height, margin)
        var best = Pt(pick(x), pick(y))
        var bestDistance = hypot(best.x - avoid.x, best.y - avoid.y)
        for (i in 1 until TRIES) {
            if (bestDistance >= maxOf(clearance, FAR_ENOUGH * sizePx)) return best
            val candidate = Pt(pick(x), pick(y))
            val distance = hypot(candidate.x - avoid.x, candidate.y - avoid.y)
            if (distance > bestDistance) {
                best = candidate
                bestDistance = distance
            }
        }
        if (bestDistance >= clearance) return best
        // Nothing random cleared it: take the farthest place there is, which is always a corner.
        return listOf(
            Pt(x.start, y.start), Pt(x.start, y.endInclusive),
            Pt(x.endInclusive, y.start), Pt(x.endInclusive, y.endInclusive),
        ).maxBy { hypot(it.x - avoid.x, it.y - avoid.y) }
    }

    /**
     * Where a centre may fall along one side: [margin] in from both ends, or the middle of the side
     * when there is not that much room to give.
     */
    private fun span(length: Float, margin: Float): ClosedFloatingPointRange<Float> {
        val edge = minOf(margin.coerceAtLeast(0f), length / 2f)
        val to = length - edge
        return if (to > edge) edge..to else (length / 2f)..(length / 2f)
    }

    private fun pick(range: ClosedFloatingPointRange<Float>): Float =
        if (range.endInclusive <= range.start) range.start
        else range.start + random.nextFloat() * (range.endInclusive - range.start)

    companion object {
        /**
         * [p] moved as little as it can be for a target [sizePx] across to sit fully on the board.
         *
         * A missed target stays exactly where it is and grows, so one placed near the edge at 40 dp
         * can reach past it at 130 dp and be drawn with a slice cut off by the board's clip. This
         * nudges it back by the overhang and no further: he is aiming at it, and a target that
         * jumps away from a finger already on its way is worse than one that shifts a few pixels.
         */
        fun onBoard(p: Pt, width: Float, height: Float, sizePx: Float): Pt {
            val radius = sizePx / 2f
            fun within(v: Float, length: Float): Float =
                if (length < sizePx) length / 2f else v.coerceIn(radius, length - radius)
            return Pt(within(p.x, width), within(p.y, height))
        }

        /** How far the next target must be from the last one, in target widths. */
        const val FAR_ENOUGH = 2f

        /** Tries before it settles for the best place it has seen. */
        const val TRIES = 24
    }
}
