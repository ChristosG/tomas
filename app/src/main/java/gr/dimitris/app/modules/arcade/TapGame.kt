package gr.dimitris.app.modules.arcade

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import gr.dimitris.app.modules.trace.Pt
import gr.dimitris.app.ui.theme.LocalFeedback
import gr.dimitris.app.ui.theme.Sizes
import kotlin.math.hypot
import kotlin.math.roundToInt

/** How many targets one round of the tap game is. Twelve is about as long as his arm lasts. */
const val TAP_TARGETS = 12

/**
 * Press the circle. Twelve of them, one at a time, each one somewhere else on the board.
 *
 * There is no clock and nothing vanishes: the target waits for him however long it takes. A press
 * that lands outside it is a soft buzz and a *bigger* circle — the game is trying to be caught, not
 * to catch him out — and the same target stays exactly where it is until he gets it. A press that
 * lands on it takes a little off the next one.
 *
 * [onResult] is called once, when the twelfth target is caught, with the hits, the misses and the
 * size his hand ended up working at.
 */
@Composable
fun TapGame(
    sizeDp: Float,
    onResult: (hits: Int, misses: Int, newSizeDp: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val feedback = LocalFeedback.current
    val density = LocalDensity.current
    val placer = remember { TargetPlacer() }
    var size by remember { mutableFloatStateOf(sizeDp) }
    var hits by remember { mutableIntStateOf(0) }
    var misses by remember { mutableIntStateOf(0) }
    var target by remember { mutableStateOf<Pt?>(null) }
    // The gesture is installed once and outlives every recomposition, so it must not close over a
    // callback that has since been replaced.
    val finish by rememberUpdatedState(onResult)

    Column(modifier) {
        GameProgress(hits, TAP_TARGETS)
        Spacer(Modifier.height(Sizes.gapSmall))
        GameBoard(Modifier.fillMaxWidth().weight(1f)) { width, height ->
            val sizePx = with(density) { size.dp.toPx() }
            // Placed the moment the board is measured, and again if it is ever measured differently:
            // a target remembered from a taller box would sit outside a shorter one.
            LaunchedEffect(width, height) {
                if (width > 0f && height > 0f) target = placer.next(width, height, sizePx, target)
            }

            Box(
                Modifier.matchParentSize().pointerInput(Unit) {
                    detectTapGestures { at ->
                        val t = target ?: return@detectTapGestures
                        val radius = size.dp.toPx() / 2f
                        if (hypot(at.x - t.x, at.y - t.y) <= radius) {
                            feedback.success()
                            hits += 1
                            size = Adaptive.afterHit(size)
                            if (hits >= TAP_TARGETS) finish(hits, misses, size)
                            else target = placer.next(width, height, size.dp.toPx(), t)
                        } else {
                            // Never a fail state: the buzz, a bigger circle, and the same target
                            // still there to be found.
                            feedback.nudge()
                            misses += 1
                            size = Adaptive.afterMiss(size)
                        }
                    }
                }
            )

            target?.let { t ->
                Box(
                    Modifier
                        .offset {
                            val radius = size.dp.toPx() / 2f
                            IntOffset((t.x - radius).roundToInt(), (t.y - radius).roundToInt())
                        }
                        .size(size.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondary)
                        .testTag(ARCADE_TARGET_TAG)
                )
            }
        }
    }
}
