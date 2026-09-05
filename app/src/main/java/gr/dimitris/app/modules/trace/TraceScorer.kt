package gr.dimitris.app.modules.trace

import kotlin.math.hypot

/** A point on the canvas, in pixels. Its own type so the scorer stays free of Compose and Android. */
data class Pt(val x: Float, val y: Float)

/**
 * How close one traced letter came.
 *
 * [meanDistance] is how far off the line he was, in pixels; [coverage] is how much of the letter he
 * actually went over, 0..1. Both are needed: a careful scribble over the crossbar of an «Α» is
 * accurate and empty, and a fast loop around the whole letter covers everything and is nowhere near
 * it. [passed] is the two of them together.
 */
data class TraceScore(val meanDistance: Float, val coverage: Float, val passed: Boolean)

/**
 * CHRIS: rewrite me. How good does a traced letter have to be? The thresholds below are a guess —
 * a therapist's guess is worth more, and TraceScorerTest describes the contract either way.
 *
 * Pure: no Android, no Compose, no clock. It is handed the finger's path and the letter's outline,
 * both already in canvas pixels, and it answers one question — is that letter, near enough?
 *
 * "Near enough" is scaled to the letter, never to the screen: [templateHeight] is what every
 * threshold is a fraction of, so the same trace scores the same on a tablet and on a phone, and a
 * word set in small type is not judged as harshly as a capital that fills the box.
 */
object TraceScorer {
    /**
     * How far apart the user's path is cut into points before it is judged, as a fraction of the
     * letter's height. Without it a slow finger — hundreds of samples in one corner — would weigh
     * that corner a hundred times, and a fast one would be judged on six points.
     */
    const val RESAMPLE_FRACTION = 0.03f

    /** How much wider than the distance threshold a template point's "he went over me" radius is. */
    const val COVERAGE_SLACK = 1.5f

    /**
     * [user] is every stroke he drew, in order, as one list of points; [template] is the letter's
     * outline, and [templateHeight] its height in the same pixels.
     *
     * [maxMeanFraction] is how far off the line he may be on average, as a fraction of the letter's
     * height, and [minCoverage] how much of the letter he has to have gone over. Both are arguments
     * because level 5 — writing from memory, with the letter no longer on the screen — is a harder
     * exercise that has to be marked more kindly, or he would never leave it.
     *
     * Nothing drawn is not a bad attempt, it is no attempt: it scores the worst distance there is
     * and no coverage, and never passes.
     */
    fun score(
        user: List<Pt>,
        template: List<Pt>,
        templateHeight: Float,
        maxMeanFraction: Float = 0.08f,
        minCoverage: Float = 0.6f,
    ): TraceScore {
        if (user.isEmpty() || template.isEmpty() || templateHeight <= 0f) return TraceScore(Float.MAX_VALUE, 0f, false)

        // The path he drew, evenly spaced, so speed stops being part of the mark.
        val walked = resample(user, (templateHeight * RESAMPLE_FRACTION).coerceAtLeast(MIN_STEP))
        val maxMean = maxMeanFraction * templateHeight
        val near = maxMean * COVERAGE_SLACK

        var total = 0.0
        for (p in walked) total += nearest(p, template)
        val meanDistance = (total / walked.size).toFloat()

        // Counted over the template and not over his strokes: what is being asked is how much of the
        // letter he went over, so a finger that went round the same corner twenty times covers one
        // corner, however many points it left behind.
        val covered = template.count { t -> walked.any { dist(it, t) <= near } }
        val coverage = covered.toFloat() / template.size

        return TraceScore(meanDistance, coverage, meanDistance <= maxMean && coverage >= minCoverage)
    }

    /**
     * [points] walked as a polyline, with a point every [step] pixels: the first point, then one at
     * every [step] of path length, and the far end only when it happens to land on one.
     *
     * The finger reports points as fast as the screen can read it, so what comes in is a crowd where
     * it went slowly and a scattering where it went fast. This makes the path even, which is what
     * lets the mean below mean anything at all.
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

    /** Distance from [p] to the nearest point of [template]. Brute force: a letter is ~500 points. */
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
