package gr.dimitris.app.modules.trace

import kotlin.math.hypot

/** A point on the canvas, in pixels. Its own type so the scorer stays free of Compose and Android. */
data class Pt(val x: Float, val y: Float)

/**
 * How close one traced letter came.
 *
 * [meanDistance] is how far off the letter he was, in pixels; [coverage] is how much of it he
 * actually went over, 0..1. Both are needed: a careful stroke down one stem of an «Α» is accurate
 * and a quarter of a letter, and a fast loop around the whole thing covers everything and is nowhere
 * near it. [passed] is the two of them together.
 */
data class TraceScore(val meanDistance: Float, val coverage: Float, val passed: Boolean)

/**
 * CHRIS: rewrite me. How good does a traced letter have to be? The thresholds are handed in from the
 * screen, which is where the guesses live; TraceScorerTest describes the contract either way.
 *
 * Pure: no Android, no Compose, no clock. It is handed the finger's path, the letter's outline and
 * the letter's ink, all in canvas pixels, and it answers one question — is that letter, near enough?
 *
 * The ink is the thing that makes it answerable. A man told «γράψε Κ» draws one line down the middle
 * of each stroke; the outline is the two *edges* of every stroke, so measured against the outline his
 * line is half a stem out everywhere and the better he writes the worse he scores. Measured against
 * the ink — [inside] — a line down the middle costs nothing, which is what the exercise is asking
 * for, and the outline is left doing the job it is good at: saying how much of the letter he covered.
 */
object TraceScorer {
    /**
     * How far apart the user's path is cut into points before it is judged, as a fraction of the
     * letter's height. Without it a slow finger — hundreds of samples in one corner — would weigh
     * that corner a hundred times, and a fast one would be judged on six points.
     */
    const val RESAMPLE_FRACTION = 0.03f

    /** Nothing to judge: the worst distance there is, no coverage, and never a pass. */
    private val NOTHING = TraceScore(Float.MAX_VALUE, 0f, false)

    /**
     * One stroke, judged. [user] is the points of a single unbroken line; see [scoreStrokes] for the
     * usual case of a letter written in several.
     *
     * [tolerancePx] is how far off the letter he may be on average and [coverageRadiusPx] how near a
     * point of the outline he has to have come for it to count as gone over — both in pixels, worked
     * out by the caller from the letter's height and the size of a fingertip, because "8 % of the
     * height" is a hair's breadth on a word and half a stem on a capital.
     */
    fun score(
        user: List<Pt>,
        template: List<Pt>,
        templateHeight: Float,
        inside: (Pt) -> Boolean = { false },
        tolerancePx: Float,
        coverageRadiusPx: Float,
        minCoverage: Float = 0.6f,
    ): TraceScore = scoreStrokes(listOf(user), template, templateHeight, inside, tolerancePx, coverageRadiusPx, minCoverage)

    /**
     * Everything he drew, judged as what it is: separate strokes.
     *
     * Each is resampled on its own, so the gap between lifting his finger and putting it down again
     * is never walked over — a «Κ» written as a stem and two diagonals must not be marked as though
     * he had dragged a line back across the letter between them.
     */
    fun scoreStrokes(
        strokes: List<List<Pt>>,
        template: List<Pt>,
        templateHeight: Float,
        inside: (Pt) -> Boolean = { false },
        tolerancePx: Float,
        coverageRadiusPx: Float,
        minCoverage: Float = 0.6f,
    ): TraceScore {
        if (template.isEmpty() || templateHeight <= 0f) return NOTHING

        // Evenly spaced along each stroke, so speed stops being part of the mark.
        val step = (templateHeight * RESAMPLE_FRACTION).coerceAtLeast(MIN_STEP)
        val walked = ArrayList<Pt>()
        for (stroke in strokes) if (stroke.isNotEmpty()) walked += resample(stroke, step)
        // Nothing drawn is not a bad attempt, it is no attempt.
        if (walked.isEmpty()) return NOTHING

        var total = 0.0
        for (p in walked) total += if (inside(p)) 0f else nearest(p, template)
        val meanDistance = (total / walked.size).toFloat()

        // Counted over the letter and not over his strokes: what is being asked is how much of the
        // letter he went over, so a finger that went round the same corner twenty times covers one
        // corner, however many points it left behind.
        val covered = template.count { t -> walked.any { dist(it, t) <= coverageRadiusPx } }
        val coverage = covered.toFloat() / template.size

        return TraceScore(meanDistance, coverage, meanDistance <= tolerancePx && coverage >= minCoverage)
    }

    /**
     * [points] walked as a polyline, with a point every [step] pixels: the first point, then one at
     * every [step] of path length, and the far end only when it happens to land on one.
     *
     * The finger reports points as fast as the screen can read it, so what comes in is a crowd where
     * it went slowly and a scattering where it went fast. This makes the path even, which is what
     * lets the mean above mean anything at all.
     */
    fun resample(points: List<Pt>, step: Float): List<Pt> {
        if (points.size < 2 || step <= 0f) return points
        val out = ArrayList<Pt>(points.size)
        out += points.first()
        // How far past the last emitted point we already are.
        var carried = 0f
        for (i in 1 until points.size) {
            var from = points[i - 1]
            val to = points[i]
            var remaining = dist(from, to)
            // A segment long enough to hold one or more steps is cut where they fall, and what is
            // left over is carried into the next one — the spacing is along the path, not per segment.
            while (carried + remaining >= step) {
                val t = (step - carried) / remaining
                val cut = Pt(from.x + t * (to.x - from.x), from.y + t * (to.y - from.y))
                out += cut
                from = cut
                remaining = dist(from, to)
                carried = 0f
            }
            carried += remaining
        }
        return out
    }

    /** Distance from [p] to the nearest point of [template]. Brute force: a letter is ~700 points. */
    private fun nearest(p: Pt, template: List<Pt>): Float {
        var best = Float.MAX_VALUE
        for (t in template) {
            val d = dist(p, t)
            if (d < best) best = d
        }
        return best
    }

    private fun dist(a: Pt, b: Pt): Float = hypot(a.x - b.x, a.y - b.y)

    /** A floor under the resample step, so a degenerate box cannot ask for an infinite number of points. */
    private const val MIN_STEP = 0.5f
}
