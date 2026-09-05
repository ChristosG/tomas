package gr.dimitris.app.modules.trace

import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Typeface

/**
 * The letter he is asked to write, as a line of points to trace.
 *
 * It is the font's own outline, not a hand-drawn skeleton: the system's bold sans-serif is what
 * every sign and menu in Greece is set in, so the shape he is copying is the shape he has to read
 * outside. The points are what the canvas draws as grey dots and what
 * [TraceScorer] measures his finger against — one list, two uses, so what he is shown and what he
 * is marked on can never drift apart.
 *
 * Deterministic for a given text and box: the same call gives the same points, in the same order.
 * It cannot be a JVM unit test — [Paint] and [PathMeasure] are the device's — so `GlyphsTest` is
 * instrumented, and everything that can be judged without a font lives in [TraceScorer] instead.
 */
object Glyphs {
    /** How far apart the outline is sampled, in pixels. Fine enough to draw as a dotted line. */
    const val SAMPLE_STEP = 6f

    /** How much of the box the letter fills, leaving a margin his finger can overshoot into. */
    const val FILL = 0.8f

    /**
     * The outline of [text] laid out to fill [FILL] of a [boxWidth] × [boxHeight] box, centred in
     * it, with the glyph's height alongside — everything the scorer measures is a fraction of that
     * height, never of the screen.
     *
     * An empty box or a blank text gives no points and no height: there is nothing to trace, and
     * the screen and the scorer both read that as "not yet" rather than as a letter he got wrong.
     */
    fun template(text: String, boxWidth: Float, boxHeight: Float): Pair<List<Pt>, Float> {
        if (text.isBlank() || boxWidth <= 0f || boxHeight <= 0f) return emptyList<Pt>() to 0f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = PROBE_SIZE
        }
        // Measured once at a size big enough to be precise, then set to the size that fits. Scaling
        // the *type size* rather than the sampled points keeps the outline true to the font: a
        // stretched small glyph is not the letter he will meet on a sign.
        val bounds = RectF()
        val probe = Path()
        paint.getTextPath(text, 0, text.length, 0f, 0f, probe)
        @Suppress("DEPRECATION") probe.computeBounds(bounds, true)
        if (bounds.width() <= 0f || bounds.height() <= 0f) return emptyList<Pt>() to 0f
        paint.textSize = PROBE_SIZE * minOf(FILL * boxWidth / bounds.width(), FILL * boxHeight / bounds.height())

        val path = Path()
        paint.getTextPath(text, 0, text.length, 0f, 0f, path)
        val points = sample(path)
        if (points.isEmpty()) return emptyList<Pt>() to 0f

        // Centred on what was really drawn, not on the font's line box: a word with no descender
        // sits high in its own metrics, and he would be tracing in the top half of the canvas.
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (p in points) {
            if (p.x < minX) minX = p.x
            if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y
        }
        val dx = boxWidth / 2f - (minX + maxX) / 2f
        val dy = boxHeight / 2f - (minY + maxY) / 2f
        return points.map { Pt(it.x + dx, it.y + dy) } to (maxY - minY)
    }

    /** Every contour of [path], walked in order, a point every [SAMPLE_STEP] pixels. */
    private fun sample(path: Path): List<Pt> {
        val out = mutableListOf<Pt>()
        val measure = PathMeasure(path, false)
        val pos = FloatArray(2)
        do {
            val length = measure.length
            var walked = 0f
            // Strictly less than the length: a glyph contour is closed, so its end is its start and
            // sampling it again would put two dots on the same pixel.
            while (walked < length) {
                if (measure.getPosTan(walked, pos, null)) out += Pt(pos[0], pos[1])
                walked += SAMPLE_STEP
            }
        } while (measure.nextContour())
        return out
    }

    /** Big enough that the measurement below is precise, and never drawn at this size. */
    private const val PROBE_SIZE = 200f
}
