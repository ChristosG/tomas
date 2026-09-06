package gr.dimitris.app.modules.arcade

import gr.dimitris.app.modules.trace.Pt
import gr.dimitris.app.modules.trace.TraceScorer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The four lines his finger is asked to follow, in the order they get harder: straight across, then
 * a diagonal, then a zigzag that makes the arm change direction three times, then a dome that never
 * stops changing it.
 *
 * They are generated from the board rather than stored, so they are the same shapes on any screen,
 * and they are handed back as evenly spaced points — which is what [TraceScorer] measures against
 * and what the screen draws as a dotted line.
 *
 * Pure: no Android, no Compose.
 */
object ArcadePaths {
    /** The four paths for a board [width] x [height] pixels, keeping [margin] clear of every edge. */
    fun all(width: Float, height: Float, margin: Float): List<List<Pt>> {
        if (width <= 0f || height <= 0f) return emptyList()
        val edge = margin.coerceIn(0f, minOf(width, height) / 3f)
        val left = edge
        val right = width - edge
        val top = edge
        val bottom = height - edge
        if (right <= left || bottom <= top) return emptyList()
        val step = (maxOf(width, height) / STEPS).coerceAtLeast(MIN_STEP)

        val line = listOf(Pt(left, (top + bottom) / 2f), Pt(right, (top + bottom) / 2f))
        val diagonal = listOf(Pt(left, bottom), Pt(right, top))
        // Four legs across the board, top-bottom-top-bottom: three changes of direction.
        val zigzag = (0..ZIGZAG_LEGS).map { i ->
            Pt(left + (right - left) * i / ZIGZAG_LEGS.toFloat(), if (i % 2 == 0) top else bottom)
        }
        val arc = arc(left, right, top, bottom)

        return listOf(line, diagonal, zigzag, arc).map { TraceScorer.resample(it, step) }.filter { it.size > 1 }
    }

    /** A dome across the board: half a circle, flattened to whatever room the board has. */
    private fun arc(left: Float, right: Float, top: Float, bottom: Float): List<Pt> {
        val radiusX = (right - left) / 2f
        val radiusY = (bottom - top) / 2f
        val centreX = (left + right) / 2f
        return (0..ARC_POINTS).map { i ->
            val angle = PI * i / ARC_POINTS
            Pt(centreX - radiusX * cos(angle).toFloat(), bottom - radiusY * sin(angle).toFloat())
        }
    }

    /** How finely a path is sampled: a point about every fortieth of the board. */
    private const val STEPS = 40f

    /** Under this the sampling would ask for more points than a degenerate board can hold. */
    private const val MIN_STEP = 2f

    private const val ZIGZAG_LEGS = 4

    /** Corners of the dome before it is resampled: enough that it reads as a curve, not a fan. */
    private const val ARC_POINTS = 24
}
