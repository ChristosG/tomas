package gr.dimitris.app.modules.trace

import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.graphics.Typeface
import kotlin.math.roundToInt

/**
 * The letter he is asked to write: the line to follow, its height, and the ink itself.
 *
 * [points] is the outline — what the canvas draws as grey dots — with each point carrying the piece
 * of the letter it belongs to, so the scorer can ask whether every piece was gone over rather than
 * whether enough points were. [inside] is the filled letter, and it is the half that makes the
 * exercise possible: a man told «γράψε Κ» draws a line down the middle of the stem, not around both
 * of its edges, and a line down the middle is nowhere near the outline while being exactly right.
 * Anything [inside] answers true for is on the letter, distance nothing.
 */
data class GlyphTemplate(
    val points: List<TemplatePoint>,
    val height: Float,
    /** Roughly how long this letter is as a line: [TraceScorer.skeleton] of its contours. */
    val skeleton: Float = 0f,
    /** True where the ink is, in the same canvas pixels as [points]. Counters are not ink. */
    val inside: (Pt) -> Boolean,
)

/**
 * The letter he is asked to write, laid out for one canvas.
 *
 * The face is the system's sans-serif at its ordinary weight — not bold: a bold stem is 20 % of the
 * letter's height wide, and its two edges are two lines he would have to choose between. The regular
 * face's stem is thin enough that the outline reads as one line, which is what he is being asked to
 * follow.
 *
 * Deterministic for a given text and box: the same call gives the same points, in the same order.
 * It cannot be a JVM unit test — [Paint], [PathMeasure] and [Region] are the device's — so
 * `GlyphsTest` is instrumented, and everything measurable without a font lives in [TraceScorer].
 */
object Glyphs {
    /** How far apart the outline is sampled, in pixels. Fine enough to draw as a dotted line. */
    const val SAMPLE_STEP = 6f

    /** How much of the box the letter fills, leaving a margin his finger can overshoot into. */
    const val FILL = 0.8f

    /**
     * The letter [text] laid out to fill [FILL] of a [boxWidth] × [boxHeight] box and centred in it.
     *
     * An empty box or a blank text gives no points, no height and a mask that is false everywhere:
     * there is nothing to trace, and the screen and the scorer both read that as "not yet" rather
     * than as a letter he got wrong.
     */
    fun template(text: String, boxWidth: Float, boxHeight: Float): GlyphTemplate {
        if (text.isBlank() || boxWidth <= 0f || boxHeight <= 0f) return EMPTY

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textSize = PROBE_SIZE
        }
        // Measured once at a size big enough to be precise, then set to the size that fits. Scaling
        // the *type size* rather than the sampled points keeps the outline true to the font: a
        // stretched small glyph is not the letter he will meet on a sign.
        val bounds = RectF()
        val probe = Path()
        paint.getTextPath(text, 0, text.length, 0f, 0f, probe)
        @Suppress("DEPRECATION") probe.computeBounds(bounds, true)
        if (bounds.width() <= 0f || bounds.height() <= 0f) return EMPTY
        paint.textSize = PROBE_SIZE * minOf(FILL * boxWidth / bounds.width(), FILL * boxHeight / bounds.height())

        val path = Path()
        paint.getTextPath(text, 0, text.length, 0f, 0f, path)
        // Contour by contour, because that is what a segment is cut out of: the outside of an «Ο»
        // and the hole in it are two lines to be gone over, not one long one.
        val contours = sample(path)
        val points = contours.flatten()
        if (points.isEmpty()) return EMPTY

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

        // The same move applied to the filled path, so the mask and the dots describe one letter.
        path.offset(dx, dy)
        val height = maxY - minY
        val moved = contours.map { contour -> contour.map { Pt(it.x + dx, it.y + dy) } }
        return GlyphTemplate(TraceScorer.segments(moved, height), height, TraceScorer.skeleton(moved), mask(path))
    }

    /**
     * Where the ink is. [Region] fills the path with the font's own winding rule, so the hole in an
     * «Ο» is outside the letter exactly as the eye says it is — which is what stops a scribble
     * through the middle of a letter from being scored as the letter.
     */
    private fun mask(path: Path): (Pt) -> Boolean {
        val bounds = RectF()
        @Suppress("DEPRECATION") path.computeBounds(bounds, true)
        val clip = Rect(
            bounds.left.toInt() - 1, bounds.top.toInt() - 1,
            bounds.right.toInt() + 1, bounds.bottom.toInt() + 1,
        )
        val region = Region()
        // A path too complex or too thin to fill leaves an empty region; the scorer then falls back
        // to the outline distance, which is the behaviour it had before the mask existed.
        if (!region.setPath(path, Region(clip))) return { false }
        return { p -> region.contains(p.x.roundToInt(), p.y.roundToInt()) }
    }

    /**
     * Every contour of [path] on its own, walked in order, a point every [SAMPLE_STEP] pixels.
     *
     * Kept apart rather than poured into one list: a segment is a piece of one contour, and the
     * jump from the end of the outside of an «Ο» to the start of its hole is not a piece of letter.
     */
    private fun sample(path: Path): List<List<Pt>> {
        val out = mutableListOf<List<Pt>>()
        val measure = PathMeasure(path, false)
        val pos = FloatArray(2)
        do {
            val contour = mutableListOf<Pt>()
            val length = measure.length
            var walked = 0f
            // Strictly less than the length: a glyph contour is closed, so its end is its start and
            // sampling it again would put two dots on the same pixel.
            while (walked < length) {
                if (measure.getPosTan(walked, pos, null)) contour += Pt(pos[0], pos[1])
                walked += SAMPLE_STEP
            }
            if (contour.isNotEmpty()) out += contour
        } while (measure.nextContour())
        return out
    }

    /** Big enough that the measurement below is precise, and never drawn at this size. */
    private const val PROBE_SIZE = 200f

    private val EMPTY = GlyphTemplate(emptyList(), 0f) { false }
}
