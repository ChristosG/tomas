package gr.dimitris.app.modules.arcade

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import gr.dimitris.app.modules.trace.Pt
import gr.dimitris.app.modules.trace.TraceScorer
import gr.dimitris.app.ui.theme.LocalFeedback
import gr.dimitris.app.ui.theme.Palette
import gr.dimitris.app.ui.theme.Sizes

/** Four lines to follow: straight, diagonal, zigzag, dome. */
const val TRACE_PATHS = 4

/** What a line he wandered off says on the screen. Never "λάθος": there is nothing to fail here. */
const val TRACE_PATH_TRY_AGAIN = "Ξανά"

/**
 * Follow the line with one finger. Four of them, each harder to stay on than the last.
 *
 * It is marked by the same scorer as the writing module — how far off the line he was on average,
 * and how much of it he actually went over — because "did his hand follow that" is the same question
 * whether the shape is a letter or a road. What the adaptive size changes here is how near counts as
 * on it: as his hand steadies, the line gets narrower.
 *
 * A drag that misses is a buzz, «Ξανά» and the same line again, as many times as he likes.
 */
@Composable
fun TracePathGame(
    sizeDp: Float,
    onResult: (hits: Int, misses: Int, newSizeDp: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val feedback = LocalFeedback.current
    val density = LocalDensity.current
    var size by remember { mutableFloatStateOf(sizeDp) }
    var index by remember { mutableIntStateOf(0) }
    var misses by remember { mutableIntStateOf(0) }
    var missed by remember { mutableStateOf(false) }
    /** True from a line he followed until he starts the next one: the tick beside the counter. */
    var followed by remember { mutableStateOf(false) }
    val finish by rememberUpdatedState(onResult)

    Column(modifier) {
        Row {
            GameProgress(index, TRACE_PATHS, celebrate = followed)
            if (missed) {
                Spacer(Modifier.width(Sizes.gapSmall))
                Text(
                    TRACE_PATH_TRY_AGAIN,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
        Spacer(Modifier.height(Sizes.gapSmall))
        GameBoard(Modifier.fillMaxWidth().weight(1f)) { width, height ->
            val margin = with(density) { MARGIN.toPx() }
            val paths = remember(width, height, margin) { ArcadePaths.all(width, height, margin) }
            val path = paths.getOrNull(index).orEmpty()

            /**
             * The line under his finger, drawn from here until he lifts it. Without it the ink would
             * appear only at the end, and following a line you cannot see yourself following is not
             * following it.
             */
            val live = remember { mutableStateListOf<Pt>() }

            Canvas(
                Modifier.matchParentSize().pointerInput(path) {
                    if (path.isEmpty()) return@pointerInput
                    detectDragGestures(
                        onDragStart = { live.clear(); missed = false; followed = false; live += Pt(it.x, it.y) },
                        // Consumed: this is his finger on the line, not a scroll for anyone else.
                        onDrag = { change, _ -> change.consume(); live += Pt(change.position.x, change.position.y) },
                        onDragEnd = {
                            val scale = spread(path)
                            val score = TraceScorer.score(
                                user = live.toList(),
                                template = path,
                                templateHeight = scale,
                                tolerancePx = TOLERANCE * scale,
                                // The adaptive size is the width of the line: as his hand steadies,
                                // "on the line" gets stricter.
                                coverageRadiusPx = (size.dp.toPx() / 2f).coerceAtLeast(MIN_RADIUS.toPx()),
                                minCoverage = MIN_COVERAGE,
                            )
                            live.clear()
                            if (score.passed) {
                                feedback.success()
                                followed = true
                                index += 1
                                size = Adaptive.afterHit(size)
                                if (index >= TRACE_PATHS) finish(index, misses, size)
                            } else {
                                feedback.nudge()
                                misses += 1
                                missed = true
                                size = Adaptive.afterMiss(size)
                            }
                        },
                        onDragCancel = { live.clear() },
                    )
                }
            ) {
                // The line to follow: grey dots, the same language as the letters in «Γράψε».
                val dot = DOT.toPx()
                for (p in path) drawCircle(Palette.mist, radius = dot, center = Offset(p.x, p.y))
                if (live.size > 1) {
                    val ink = Path()
                    ink.moveTo(live[0].x, live[0].y)
                    for (i in 1 until live.size) ink.lineTo(live[i].x, live[i].y)
                    drawPath(
                        ink, Palette.navy,
                        style = Stroke(width = INK.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
            }
        }
    }
}

/** How big the path is across its longer side: the scale everything about it is measured in. */
private fun spread(path: List<Pt>): Float {
    if (path.isEmpty()) return 0f
    val width = path.maxOf { it.x } - path.minOf { it.x }
    val height = path.maxOf { it.y } - path.minOf { it.y }
    return maxOf(width, height, 1f)
}

/** How far off the line he may be on average, as a fraction of the path's own size. */
private const val TOLERANCE = 0.12f

/** How much of the line he has to have gone over. Half of it is following it; a corner is not. */
private const val MIN_COVERAGE = 0.5f

/** Clear of the board's edges, so no part of a line is under his palm. */
private val MARGIN = 40.dp

/** However small the targets get, "on the line" is never tighter than this. */
private val MIN_RADIUS = 16.dp

/** The line's dots, and the width of his own mark over them. */
private val DOT = 3.dp
private val INK = 12.dp
