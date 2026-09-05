package gr.dimitris.app.modules.trace

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.geometry.Offset
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
    template: List<Pt>,
    showTemplate: Boolean,
    strokes: List<List<Pt>>,
    onStroke: (List<Pt>) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
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
                detectDragGestures(
                    onDragStart = { live.clear(); live += Pt(it.x, it.y) },
                    // Consumed: this is his handwriting, not a scroll or a swipe for anyone else.
                    onDrag = { change, _ -> change.consume(); live += Pt(change.position.x, change.position.y) },
                    onDragEnd = { if (live.isNotEmpty()) emit(live.toList()) },
                    onDragCancel = { live.clear() },
                )
            }
    ) {
        if (showTemplate) {
            val radius = DOT.toPx()
            for (p in template) drawCircle(Palette.mist, radius = radius, center = Offset(p.x, p.y))
        }
        val width = INK.toPx()
        for (stroke in strokes) ink(stroke, width)
        ink(live, width)
    }
}

/** One stroke, in navy, as wide as a marker and rounded at both ends like one. */
private fun DrawScope.ink(points: List<Pt>, width: Float) {
    if (points.isEmpty()) return
    // A tap that never moved is still a mark he made: a dot, not nothing.
    if (points.size == 1) {
        drawCircle(Palette.navy, radius = width / 2f, center = Offset(points[0].x, points[0].y))
        return
    }
    val path = Path()
    path.moveTo(points[0].x, points[0].y)
    for (i in 1 until points.size) path.lineTo(points[i].x, points[i].y)
    drawPath(path, Palette.navy, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** As wide as a felt-tip: a hairline is not what a shaking hand can aim, or see afterwards. */
private val INK = 14.dp

/** The paper's edge: enough to see where it ends, not enough to be a line he might trace. */
private val EDGE = 2.dp

/** The template's dots: there to be followed, never so heavy that his own line disappears into them. */
private val DOT = 2.5.dp
