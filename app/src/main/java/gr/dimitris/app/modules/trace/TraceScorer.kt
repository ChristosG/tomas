package gr.dimitris.app.modules.trace

import kotlin.math.ceil
import kotlin.math.hypot

/** A point on the canvas, in pixels. Its own type so the scorer stays free of Compose and Android. */
data class Pt(val x: Float, val y: Float)

/**
 * One point of the letter's outline, and which piece of the letter it belongs to.
 *
 * The piece is what makes a shape a shape. Distance alone says how near his ink came to *some* part
 * of the letter, and a big enough scribble is always near some part of it; the segments ask the
 * other question — did he go over *this* piece, and this one, and this one. A «Κ» drawn over an «Η»
 * never touches the crossbar, and that is the sentence "it is not an Η" written as a number.
 */
data class TemplatePoint(val pt: Pt, val segment: Int)

/**
 * How close one traced letter came.
 *
 * [coverage] is how much of the letter he went over, 0..1, counted in pieces rather than in points:
 * the fraction of the letter's segments his ink reached. [precision] is the other half — how much of
 * what he drew was on the letter at all, 0..1 — and it is the half that refuses a wrong letter form.
 * Both are needed: a careful stroke down one stem of an «Α» is all precision and a quarter of a
 * letter, and a fast loop around the whole thing covers everything and is nowhere near it.
 *
 * [meanDistance] is how far off the letter he was, in pixels. Nothing is decided by it any more —
 * it is kept because it is the one number a person reading the attempt rows can picture.
 */
data class TraceScore(
    val coverage: Float,
    val precision: Float,
    val meanDistance: Float,
    val passed: Boolean,
)

/**
 * How hard the writing module marks, as a caregiver sets it: «Χαλαρό», «Κανονικό», «Αυστηρό».
 *
 * The tolerances are in dp, not in fractions of the letter, because they are about the size of his
 * fingertip and his fingertip is the same size on a capital and on a word of eight letters. That is
 * the whole of the field-test bug: a «Κ» drawn over an «Η» passed, because on a big letter a
 * tolerance of a tenth of its height is most of the paper.
 */
enum class TraceStrictness(
    val toleranceDp: Float,
    val coverRadiusDp: Float,
    val minCoverage: Float,
    val minPrecision: Float,
) {
    LOOSE(16f, 18f, 0.70f, 0.70f),
    NORMAL(12f, 14f, 0.80f, 0.80f),
    STRICT(8f, 10f, 0.90f, 0.90f);

    companion object {
        /** What he is marked at until somebody says otherwise. */
        val DEFAULT = NORMAL

        /** The stored name read back, with anything else — an old backup, a newer version — as [DEFAULT]. */
        fun named(name: String?): TraceStrictness = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * One strictness worked out for one screen: the two distances in pixels, and the two lines he has to
 * clear. Made by [of] and handed to [TraceScorer.score], so the scorer itself knows nothing about
 * densities, levels or settings.
 */
data class Strictness(
    val tolerancePx: Float,
    val coverRadiusPx: Float,
    val minCoverage: Float,
    val minPrecision: Float,
) {
    companion object {
        /**
         * What writing from memory is worth. Level 5 shows the word once and takes it away; what
         * comes back is the shape he remembers, not the shape he can follow, and it is marked as
         * such. The *distances* are not loosened — a wrong letter is still a wrong letter — only how
         * much of it he has to get.
         */
        const val RECALL_ALLOWANCE = 0.15f

        fun of(level: TraceStrictness, density: Float, recall: Boolean): Strictness {
            // A density of zero is a screen nobody can write on; 1 keeps the numbers meaning dp.
            val scale = if (density > 0f) density else 1f
            val give = if (recall) RECALL_ALLOWANCE else 0f
            return Strictness(
                tolerancePx = level.toleranceDp * scale,
                coverRadiusPx = level.coverRadiusDp * scale,
                minCoverage = (level.minCoverage - give).coerceAtLeast(0f),
                minPrecision = (level.minPrecision - give).coerceAtLeast(0f),
            )
        }
    }
}

/**
 * Is that letter, near enough? Pure: no Android, no Compose, no clock. It is handed the finger's
 * path, the letter's outline cut into segments and the letter's ink, all in canvas pixels.
 *
 * Two numbers answer it, and they answer different halves. **Coverage** — did he go over every piece
 * of the letter? — is what a «Κ» over an «Η» fails: the crossbar is never touched. **Precision** —
 * was what he drew on the letter? — is what a big shape over a small letter fails: the diagonals of
 * that «Κ» are out in the white. Neither alone is enough, which is why the old scorer, which had
 * only a mean distance and a coverage counted in points, passed both of Chris's wrong letters.
 *
 * The ink — [inside] — is what makes precision fair. A man told «γράψε Κ» draws one line down the
 * middle of each stroke; the outline is the two *edges* of every stroke, so measured against the
 * outline alone his line is half a stem out everywhere and the better he writes the worse he scores.
 * Measured against the ink, a line down the middle costs nothing, which is what the exercise asks
 * for, and the outline is left doing the job it is good at: saying which pieces he went over.
 */
object TraceScorer {
    /**
     * How long one segment of the letter is, as a fraction of the letter's height. A twelfth of the
     * height is about the length of the shortest thing anyone would call a stroke — the crossbar of
     * an «Η» is a few of them, the dot of an accent is one — so a letter is cut into pieces the size
     * of the movements it is made of.
     */
    const val SEGMENT_FRACTION = 0.12f

    /**
     * However short a contour is, it is still cut into this many pieces. Without it the ring of an
     * «Ο» drawn a third of the way round would count as a third of one segment out of one, and a
     * third of a letter would be a pass.
     */
    const val MIN_SEGMENTS = 8

    /**
     * How far apart his path is cut into points before it is judged, as a fraction of the tolerance.
     * Without resampling a slow finger — hundreds of samples in one corner — would weigh that corner
     * a hundred times, and a fast one would be judged on six points. A third of the tolerance is fine
     * enough that no piece of the letter is stepped over and coarse enough to stay cheap.
     */
    const val STEP_OF_TOLERANCE = 1f / 3f

    /** The same, for the arcade's line-following game, which is measured against a path's own size. */
    const val RESAMPLE_FRACTION = 0.03f

    /** Nothing to judge: no coverage, no precision, the worst distance there is, and never a pass. */
    private val NOTHING = TraceScore(0f, 0f, Float.MAX_VALUE, false)

    /**
     * The letter's outline, cut into pieces. [contours] is every closed line of the glyph in walk
     * order — the outside of an «Ο» and then the hole in it — and each is split by arc length into
     * `ceil(length / (height * SEGMENT_FRACTION))` pieces, never fewer than [MIN_SEGMENTS].
     *
     * The ids run on across contours, so the hole in an «Ο» is pieces of its own to be gone over and
     * not more of the outside. Kept here, away from the font, so the rule can be read and tested
     * without a device.
     */
    fun segments(contours: List<List<Pt>>, height: Float): List<TemplatePoint> {
        if (height <= 0f) return emptyList()
        val target = height * SEGMENT_FRACTION
        val out = ArrayList<TemplatePoint>()
        var next = 0
        for (contour in contours) {
            if (contour.isEmpty()) continue
            // Where each point falls along its own contour, and how long that contour is.
            val at = FloatArray(contour.size)
            for (i in 1 until contour.size) at[i] = at[i - 1] + dist(contour[i - 1], contour[i])
            val length = at.last()
            // A contour of no length at all — one point, or a hundred on the same pixel — is one
            // piece: it is a mark on the letter, not eight pieces of it that can never be reached.
            val pieces = if (length <= 0f) 1 else maxOf(MIN_SEGMENTS, ceil(length / target).toInt())
            for (i in contour.indices) {
                val piece = if (length <= 0f) 0 else ((at[i] / length) * pieces).toInt().coerceIn(0, pieces - 1)
                out += TemplatePoint(contour[i], next + piece)
            }
            next += pieces
        }
        return out
    }

    /**
     * Everything he drew, judged as what it is: separate strokes, against one letter.
     *
     * Each stroke is resampled on its own, so the gap between lifting his finger and putting it down
     * again is never walked over — a «Κ» written as a stem and two diagonals must not be marked as
     * though he had dragged a line back across the letter between them.
     *
     * Coverage is counted over the pieces of the letter and not over his strokes: what is being asked
     * is how much of the letter he went over, so a finger that went round the same corner twenty
     * times covers one corner, however many points it left behind. Only pieces that have outline
     * points in them are counted — a short contour is cut into [MIN_SEGMENTS] pieces whether or not
     * there are points enough to fill them, and a piece with nothing in it is not a piece he missed.
     * And where his finger is on the ink, the radius is measured from the edge of the letter rather
     * than from his finger: both edges of a stem are the one stroke he is drawing down the middle of.
     */
    fun score(
        strokes: List<List<Pt>>,
        template: List<TemplatePoint>,
        inside: (Pt) -> Boolean,
        s: Strictness,
    ): TraceScore {
        if (template.isEmpty()) return NOTHING

        // Evenly spaced along each stroke, so speed stops being part of the mark.
        val step = (s.tolerancePx * STEP_OF_TOLERANCE).coerceAtLeast(MIN_STEP)
        val walked = ArrayList<Pt>()
        for (stroke in strokes) if (stroke.isNotEmpty()) walked += resample(stroke, step)
        // Nothing drawn is not a bad attempt, it is no attempt.
        if (walked.isEmpty()) return NOTHING

        val present = HashSet<Int>()
        for (t in template) present += t.segment
        val covered = HashSet<Int>()
        // Kept between points rather than rebuilt: one letter is ~700 points and one trace ~200.
        val away = FloatArray(template.size)

        var total = 0.0
        var onLetter = 0
        for (p in walked) {
            var best = Float.MAX_VALUE
            for (i in template.indices) {
                val d = dist(p, template[i].pt)
                away[i] = d
                if (d < best) best = d
            }
            // On the ink costs nothing: a line down the middle of a stroke is the letter, written.
            val onInk = inside(p)
            total += if (onInk) 0f else best
            if (onInk || best <= s.tolerancePx) onLetter++
            // How far this one point of his reaches. The radius is measured from the edge of the
            // letter, not from the middle of it: a man told «γράψε Η» draws one line down the
            // middle of the stem, and the *far* edge of that stem is not a piece of the letter he
            // missed — it is the other side of the very stroke he is standing on. Without this the
            // stem of a big capital, 17 dp of ink across on the device's own font, would be wider
            // than the 14 dp he is allowed, and writing the letter correctly would fail.
            val reach = if (onInk) best + s.coverRadiusPx else s.coverRadiusPx
            for (i in template.indices) if (away[i] <= reach) covered += template[i].segment
        }

        val coverage = covered.size.toFloat() / present.size
        val precision = onLetter.toFloat() / walked.size
        val meanDistance = (total / walked.size).toFloat()
        return TraceScore(
            coverage = coverage,
            precision = precision,
            meanDistance = meanDistance,
            passed = coverage >= s.minCoverage && precision >= s.minPrecision,
        )
    }

    /**
     * One stroke against a bare line, marked the old way: how far off it he was on average, and how
     * much of it he went over. This is the arcade's line-following game — a road, not a letter, with
     * no ink to be inside of and no shape to get wrong — and it is the only caller left.
     *
     * [tolerancePx] is how far off the line he may be on average and [coverageRadiusPx] how near a
     * point of it he has to have come for it to count as gone over.
     */
    fun score(
        user: List<Pt>,
        template: List<Pt>,
        templateHeight: Float,
        tolerancePx: Float,
        coverageRadiusPx: Float,
        minCoverage: Float = 0.6f,
    ): TraceScore {
        if (template.isEmpty() || templateHeight <= 0f) return NOTHING
        val step = (templateHeight * RESAMPLE_FRACTION).coerceAtLeast(MIN_STEP)
        val walked = if (user.isEmpty()) emptyList() else resample(user, step)
        if (walked.isEmpty()) return NOTHING

        var total = 0.0
        var near = 0
        for (p in walked) {
            val d = nearest(p, template)
            total += d
            if (d <= tolerancePx) near++
        }
        val meanDistance = (total / walked.size).toFloat()
        val covered = template.count { t -> walked.any { dist(it, t) <= coverageRadiusPx } }
        val coverage = covered.toFloat() / template.size
        return TraceScore(
            coverage = coverage,
            // Said for the record only: what passes a line here is the mean distance, as it always was.
            precision = near.toFloat() / walked.size,
            meanDistance = meanDistance,
            passed = meanDistance <= tolerancePx && coverage >= minCoverage,
        )
    }

    /**
     * [points] walked as a polyline, with a point every [step] pixels: the first point, then one at
     * every [step] of path length, and the far end only when it happens to land on one.
     *
     * The finger reports points as fast as the screen can read it, so what comes in is a crowd where
     * it went slowly and a scattering where it went fast. This makes the path even, which is what
     * lets every count above mean anything at all.
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
