package gr.dimitris.app.modules.arcade

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import gr.dimitris.app.core.data.now
import gr.dimitris.app.modules.trace.Pt
import gr.dimitris.app.ui.theme.LocalFeedback
import gr.dimitris.app.ui.theme.Sizes
import kotlin.math.hypot
import kotlin.math.roundToInt

/** How many pucks one round is. Five journeys across the board is a shoulder's worth. */
const val DRAG_ROUNDS = 5

/** The ring is this much wider than the puck: the aim is the journey, not the millimetre. */
const val HOME_RATIO = 1.6f

/**
 * Push the ball into its ring. Five of them, each a journey across the board.
 *
 * This is the one game about holding on: the finger has to stay down the whole way, which is exactly
 * what a hand with a weak grip finds hard. So the grab is forgiving — anywhere near the ball takes
 * it — and the ring is half as wide again as the ball. Letting go short of the ring is a buzz, a
 * bigger ball, and the ball left where he dropped it: he pushes it the rest of the way, he does not
 * start again.
 */
@Composable
fun DragGame(
    sizeDp: Float,
    onResult: (hits: Int, misses: Int, newSizeDp: Float, play: ArcadePlay) -> Unit,
    modifier: Modifier = Modifier,
) {
    val feedback = LocalFeedback.current
    val density = LocalDensity.current
    val placer = remember { TargetPlacer() }
    var size by remember { mutableFloatStateOf(sizeDp) }
    var round by remember { mutableIntStateOf(0) }
    var misses by remember { mutableIntStateOf(0) }
    var puck by remember { mutableStateOf<Pt?>(null) }
    var home by remember { mutableStateOf<Pt?>(null) }
    var held by remember { mutableStateOf(false) }
    /** When this ball was laid out, and what the round has cost his hand so far. See [ArcadePlay]. */
    var roundAt by remember { mutableLongStateOf(now()) }
    val taken = remember { mutableListOf<Long>() }
    val missedBy = remember { mutableListOf<Float>() }
    /** True from a ball he got home until he reaches for the next one: the tick by the counter. */
    var docked by remember { mutableStateOf(false) }
    val finish by rememberUpdatedState(onResult)

    Column(modifier) {
        GameProgress(round, DRAG_ROUNDS, celebrate = docked)
        Spacer(Modifier.height(Sizes.gapSmall))
        GameBoard(Modifier.fillMaxWidth().weight(1f)) { width, height ->
            val sizePx = with(density) { size.dp.toPx() }
            val homePx = with(density) { (size * HOME_RATIO).dp.toPx() }
            // Laid out the moment the board is measured, and again if it is measured differently:
            // a ball remembered from a taller box would sit outside a shorter one. The ring is
            // placed first and the ball clear of it — never inside it, or the round is one he wins
            // by touching the glass — so every round is a real journey.
            LaunchedEffect(width, height, round) {
                if (width > 0f && height > 0f) {
                    val ring = placer.next(width, height, homePx, null, margin = homePx / 2f)
                    home = ring
                    puck = placer.clearOf(width, height, sizePx, ring, clearance = homePx / 2f + sizePx / 2f)
                    roundAt = now()
                }
            }

            Box(
                Modifier.matchParentSize().pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { at ->
                            docked = false
                            val p = puck ?: return@detectDragGestures
                            // Anywhere near the ball counts as having it: a grab that has to be
                            // accurate is a second exercise nobody asked for.
                            held = hypot(at.x - p.x, at.y - p.y) <= size.dp.toPx() / 2f * GRAB
                        },
                        onDrag = { change, delta ->
                            change.consume()
                            val p = puck ?: return@detectDragGestures
                            if (!held) return@detectDragGestures
                            val radius = size.dp.toPx() / 2f
                            puck = Pt(
                                (p.x + delta.x).coerceIn(radius, (width - radius).coerceAtLeast(radius)),
                                (p.y + delta.y).coerceIn(radius, (height - radius).coerceAtLeast(radius)),
                            )
                        },
                        onDragEnd = {
                            // He dragged and the ball did not move. Silence is the one answer a man
                            // who cannot ask what happened cannot use: the buzz says "not the ball",
                            // and nothing is counted against him for reaching.
                            if (!held) { feedback.nudge(); return@detectDragGestures }
                            held = false
                            val p = puck ?: return@detectDragGestures
                            val ring = home ?: return@detectDragGestures
                            // Read here, not from composition: this gesture block is installed once
                            // and keeps whatever it captured, so a ring that grew after two misses
                            // would be drawn wide and judged narrow — the app buzzing at a ball he
                            // put plainly inside the circle it drew him.
                            val accept = (size * HOME_RATIO).dp.toPx() / 2f
                            val reach = hypot(p.x - ring.x, p.y - ring.y)
                            if (reach <= accept) {
                                feedback.success()
                                docked = true
                                round += 1
                                // The whole journey, from the ball being laid out: the drops short
                                // of the ring are part of what it cost him, not separate from it.
                                taken += now() - roundAt
                                size = Adaptive.afterHit(size)
                                if (round >= DRAG_ROUNDS) finish(round, misses, size, ArcadePlay(taken.toList(), missedBy.toList()))
                            } else {
                                // The ball stays where he let go of it. He pushes it on from there.
                                feedback.nudge()
                                misses += 1
                                // How far short of the ring he let go, in dp.
                                missedBy += (reach - accept) / density.density
                                size = Adaptive.afterMiss(size)
                            }
                        },
                        onDragCancel = { held = false },
                    )
                }
            )

            home?.let { ring ->
                Box(
                    Modifier
                        .offset {
                            val radius = (size * HOME_RATIO).dp.toPx() / 2f
                            IntOffset((ring.x - radius).roundToInt(), (ring.y - radius).roundToInt())
                        }
                        .size((size * HOME_RATIO).dp)
                        .border(RING, MaterialTheme.colorScheme.primary, CircleShape)
                        .testTag(ARCADE_HOME_TAG)
                )
            }
            puck?.let { p ->
                Box(
                    Modifier
                        .offset {
                            val radius = size.dp.toPx() / 2f
                            IntOffset((p.x - radius).roundToInt(), (p.y - radius).roundToInt())
                        }
                        .size(size.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondary)
                        .testTag(ARCADE_PUCK_TAG)
                )
            }
        }
    }
}

/** How far outside the ball still counts as taking hold of it. */
private const val GRAB = 1.6f

/** The ring: a line, not a disc, so the ball is visible inside it. */
private val RING = 4.dp
