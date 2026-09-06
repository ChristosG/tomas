package gr.dimitris.app.modules.trace

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import gr.dimitris.app.ui.theme.Palette
import gr.dimitris.app.ui.theme.Sizes

/**
 * The paper. [template] is the letter as grey dots, [strokes] is everything he has drawn so far, and
 * one finished stroke is handed back through [onStroke] when his finger comes off the glass.
 *
 * The gesture is the canvas's own and it consumes what it gets, so nothing above it can steal the
 * drag: a page that scrolled while he wrote would take the letter with it. That is also why the
 * screen around this never scrolls.
 *
 * [enabled] goes false once the letter is done, so a stroke can never be drawn here and refused by
 * the ViewModel — what he sees on the paper is always exactly what was counted.
 */
@Composable
fun TraceCanvas(
    template: List<TemplatePoint>,
    showTemplate: Boolean,
    strokes: List<List<Pt>>,
    onStroke: (List<Pt>) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /**
     * How many of [strokes] belong to a try that has already been marked. They are drawn faded: the
     * paper still shows him where he went, and the dark ink is the letter he is writing now.
     */
    judged: Int = 0,
    /**
     * The letters he has to look at again. They are drawn in the nudge's own colour on a tinted
     * ground, so «δες το «η»» has something to point at: a man who cannot ask which one is meant
     * must be able to see it.
     */
    highlight: Set<Int> = emptySet(),
) {
    // The gesture is installed once and outlives any number of recompositions, so it must not close
    // over a stale callback.
    val emit by rememberUpdatedState(onStroke)

    /**
     * The stroke under his finger, drawn from here until the ViewModel hands it back in [strokes].
     * Without it the ink would appear only when he lifts his finger, and tracing a letter you cannot
     * see yourself drawing is not tracing.
     */
    val live = remember { mutableStateListOf<Pt>() }
    // The echo has arrived (or the board was wiped): the copy under the finger is no longer needed.
    LaunchedEffect(strokes) { live.clear() }

    val paper = RoundedCornerShape(Sizes.corner)
    Canvas(
        modifier
            .clip(paper)
            // White on the cream page, with an edge: paper he can see the limits of. The whole box
            // is his to write in — the letter only ever fills the middle of it — so the edge is
            // what says where the exercise stops and the buttons begin.
            .background(MaterialTheme.colorScheme.surface)
            .border(EDGE, Palette.mist, paper)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                // The gesture is taken apart rather than left to `detectDragGestures`, for one
                // reason: that helper reports the stroke from where the drag was *recognised* — a
                // touch slop of 8 dp along — and the first 8 dp of every stroke would be missing
                // from his writing. On a capital that is nothing; on one letter of «Δημήτρης», with
                // every letter now marked on its own, it is the top of the stem gone from all three
                // strokes of it. His ink starts where his finger did.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    live.clear()
                    live += Pt(down.position.x, down.position.y)
                    // Consumed: this is his handwriting, not a scroll or a swipe for anyone else.
                    down.consume()
                    drag(down.id) { change ->
                        change.consume()
                        live += Pt(change.position.x, change.position.y)
                    }
                    if (live.isNotEmpty()) emit(live.toList())
                }
            }
    ) {
        val width = INK.toPx()
        strokes.forEachIndexed { i, stroke -> ink(stroke, width, if (i < judged) Palette.navy.copy(alpha = FADED) else Palette.navy) }
        ink(live, width, Palette.navy)
        // The letter goes on top of his writing, not under it. His line is as wide as a marker and
        // the letter's is a thin one, so underneath it would disappear the moment he crossed it —
        // and the whole exercise is following a line he can still see.
        if (showTemplate) {
            val radius = DOT.toPx()
            // The letter that missed first: its ground, so his own ink is not what is tinted.
            if (highlight.isNotEmpty()) {
                val pad = MARK_PAD.toPx()
                for (letter in highlight) {
                    val mine = template.filter { it.letter == letter }
                    if (mine.isEmpty()) continue
                    val left = mine.minOf { it.pt.x } - pad
                    val top = mine.minOf { it.pt.y } - pad
                    drawRoundRect(
                        Palette.amber.copy(alpha = MARK_FILL),
                        topLeft = Offset(left, top),
                        size = Size(mine.maxOf { it.pt.x } + pad - left, mine.maxOf { it.pt.y } + pad - top),
                        cornerRadius = CornerRadius(pad, pad),
                    )
                }
            }
            for (p in template) {
                drawCircle(
                    if (p.letter in highlight) Palette.amber else Palette.mist,
                    radius = if (p.letter in highlight) radius * MARK_DOT else radius,
                    center = Offset(p.pt.x, p.pt.y),
                )
            }
        }
    }
}

/** One stroke, as wide as a marker and rounded at both ends like one. */
private fun DrawScope.ink(points: List<Pt>, width: Float, colour: Color) {
    if (points.isEmpty()) return
    // A tap that never moved is still a mark he made: a dot, not nothing.
    if (points.size == 1) {
        drawCircle(colour, radius = width / 2f, center = Offset(points[0].x, points[0].y))
        return
    }
    val path = Path()
    path.moveTo(points[0].x, points[0].y)
    for (i in 1 until points.size) path.lineTo(points[i].x, points[i].y)
    drawPath(path, colour, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/**
 * How much of a try that has already been marked is left on the paper. Faint enough that the letter
 * he is writing now is obviously the dark one, dark enough that he can still see where he went.
 */
private const val FADED = 0.22f

/** How the letter he has to look at again is marked: a tinted ground and a heavier dotted line. */
private const val MARK_FILL = 0.16f
private const val MARK_DOT = 1.6f
private val MARK_PAD = 8.dp

/** As wide as a felt-tip: a hairline is not what a shaking hand can aim, or see afterwards. */
private val INK = 14.dp

/** The paper's edge: enough to see where it ends, not enough to be a line he might trace. */
private val EDGE = 2.dp

/** The template's dots: there to be followed, never so heavy that his own line disappears into them. */
private val DOT = 2.5.dp
