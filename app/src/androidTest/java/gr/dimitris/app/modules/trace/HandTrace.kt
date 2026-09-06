package gr.dimitris.app.modules.trace

import kotlin.math.abs
import kotlin.math.hypot

/**
 * How a person writes the letter, worked out from the letter itself.
 *
 * Test scaffolding, not app code. A machine can trace the outline the app drew perfectly and learn
 * nothing: what has to be proved is that a line down the *middle* of each stroke — which is what a
 * hand does when told «γράψε Κ» — is read as the letter. So this walks the ink, takes the middle of
 * every run across it, and joins the middles into the few strokes a hand would make, lifting the
 * finger where the letter does.
 */
object HandTrace {
    /**
     * The letter as a person would draw it: a handful of strokes, each a list of points down the
     * centre of the ink, in the same canvas pixels as [GlyphTemplate.points].
     */
    fun centreLine(glyph: GlyphTemplate): List<List<Pt>> {
        if (glyph.points.isEmpty() || glyph.height <= 0f) return emptyList()
        val left = glyph.points.minOf { it.x }
        val right = glyph.points.maxOf { it.x }
        val top = glyph.points.minOf { it.y }
        val bottom = glyph.points.maxOf { it.y }
        val step = (glyph.height * GRID).coerceAtLeast(2f)
        // Longer than this and the run is along a stroke rather than across it: the middle of the
        // whole width of a crossbar is not a point anyone's finger passes through.
        val widest = glyph.height * WIDEST_STROKE

        val middles = mutableListOf<Pt>()
        // Across, then down: a stem is found by the rows that cross it and a crossbar by the columns.
        var y = top
        while (y <= bottom) {
            runs(from = left, to = right, step = step) { x -> glyph.inside(Pt(x, y)) }
                .filter { it.second - it.first <= widest }
                .forEach { middles += Pt((it.first + it.second) / 2f, y) }
            y += step
        }
        var x = left
        while (x <= right) {
            runs(from = top, to = bottom, step = step) { v -> glyph.inside(Pt(x, v)) }
                .filter { it.second - it.first <= widest }
                .forEach { middles += Pt(x, (it.first + it.second) / 2f) }
            x += step
        }
        if (middles.isEmpty()) return emptyList()

        // One point per cell, so the two passes do not report the same place twice.
        val seen = LinkedHashMap<Pair<Int, Int>, Pt>()
        for (p in middles) seen.putIfAbsent((p.x / step).toInt() to (p.y / step).toInt(), p)
        return strokes(seen.values.toList(), breakAt = step * BREAK)
    }

    /** Each maximal stretch of [inside] between [from] and [to], as (start, end). */
    private inline fun runs(from: Float, to: Float, step: Float, inside: (Float) -> Boolean): List<Pair<Float, Float>> {
        val out = mutableListOf<Pair<Float, Float>>()
        var at = from
        var start: Float? = null
        while (at <= to) {
            if (inside(at)) {
                if (start == null) start = at
            } else if (start != null) {
                out += start to at - step
                start = null
            }
            at += step
        }
        if (start != null) out += start to to
        return out
    }

    /**
     * The points joined into the order a hand would visit them: always on to the nearest one it has
     * not been to, and the finger lifted whenever the nearest is further than [breakAt] — which is
     * where the letter itself stops and starts again.
     */
    private fun strokes(points: List<Pt>, breakAt: Float): List<List<Pt>> {
        val left = points.toMutableList()
        val out = mutableListOf<List<Pt>>()
        var current = mutableListOf<Pt>()
        var at = left.minByOrNull { it.x + it.y } ?: return emptyList()
        left.remove(at)
        current += at
        while (left.isNotEmpty()) {
            val next = left.minByOrNull { hypot(it.x - at.x, it.y - at.y) }!!
            left.remove(next)
            if (hypot(next.x - at.x, next.y - at.y) > breakAt) {
                if (current.size > 1) out += current
                current = mutableListOf()
            }
            current += next
            at = next
        }
        if (current.size > 1) out += current
        return out
    }

    /**
     * The same stroke with only the points that carry its shape, so it can be replayed as a few
     * straight swipes. Ramer–Douglas–Peucker, iterative so a long stroke cannot blow the stack.
     */
    fun simplify(stroke: List<Pt>, tolerance: Float): List<Pt> {
        if (stroke.size < 3) return stroke
        val keep = BooleanArray(stroke.size)
        keep[0] = true
        keep[stroke.lastIndex] = true
        val pending = ArrayDeque<Pair<Int, Int>>()
        pending += 0 to stroke.lastIndex
        while (pending.isNotEmpty()) {
            val (from, to) = pending.removeLast()
            if (to <= from + 1) continue
            var worst = from
            var worstAt = 0f
            for (i in from + 1 until to) {
                val d = perpendicular(stroke[i], stroke[from], stroke[to])
                if (d > worstAt) { worstAt = d; worst = i }
            }
            if (worstAt > tolerance) {
                keep[worst] = true
                pending += from to worst
                pending += worst to to
            }
        }
        return stroke.filterIndexed { i, _ -> keep[i] }
    }

    private fun perpendicular(p: Pt, a: Pt, b: Pt): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val length = hypot(dx, dy)
        if (length == 0f) return hypot(p.x - a.x, p.y - a.y)
        return abs(dy * p.x - dx * p.y + b.x * a.y - b.y * a.x) / length
    }

    /** How finely the ink is walked, as a fraction of the letter's height. */
    private const val GRID = 0.02f

    /** A run wider than this is along a stroke, not across it. */
    private const val WIDEST_STROKE = 0.25f

    /** A jump longer than this many grid steps is the finger coming off the glass. */
    private const val BREAK = 3f
}
