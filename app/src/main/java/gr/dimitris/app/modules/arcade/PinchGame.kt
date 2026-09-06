package gr.dimitris.app.modules.arcade

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import gr.dimitris.app.core.data.now
import gr.dimitris.app.ui.theme.LocalFeedback
import gr.dimitris.app.ui.theme.Sizes
import java.io.File

/** How many photos one round is. Three is as much as two weak fingers will give. */
const val PINCH_ROUNDS = 3

/** Open to twice the size, then back down to about where it started: that is the exercise. */
const val PINCH_OPEN = 2f
const val PINCH_CLOSED = 1.2f

/** As far as the photo will go. Past this the fingers are travelling, not working. */
const val PINCH_MAX = 3f

/**
 * Open a photo with two fingers, then close it again. Three of them.
 *
 * It is the hardest thing in the app for his hand and the only place the whole app allows a pinch —
 * everywhere else two fingers are banned, because everywhere else the gesture is navigation. Here it
 * is the exercise.
 *
 * The photos are his own where there are any: a face he knows is worth more than a drawing of a
 * ball, and pictures are what the device is full of. With no pictures at all it is a coloured card,
 * which pinches exactly the same.
 *
 * Letting go having opened it a little and not enough is a buzz and a bigger photo. Letting go
 * without touching it at all is nothing — a hand resting on the glass is not an attempt.
 */
@Composable
fun PinchGame(
    sizeDp: Float,
    photos: List<File>,
    onResult: (hits: Int, misses: Int, newSizeDp: Float, play: ArcadePlay) -> Unit,
    modifier: Modifier = Modifier,
) {
    val feedback = LocalFeedback.current
    var size by remember { mutableFloatStateOf(sizeDp) }
    var round by remember { mutableIntStateOf(0) }
    var misses by remember { mutableIntStateOf(0) }
    var scale by remember { mutableFloatStateOf(1f) }
    /** The widest it has been since this photo began: what says whether he really opened it. */
    var peak by remember { mutableFloatStateOf(1f) }
    /** True from an opened photo until he puts his fingers down again: the tick by the counter. */
    var opened by remember { mutableStateOf(false) }
    /**
     * When this photo went up, and how long each one took him. There is no distance to report here
     * — a pinch either opens the picture or it does not — so [ArcadePlay.missDistanceDp] stays empty.
     */
    var roundAt by remember { mutableLongStateOf(now()) }
    val taken = remember { mutableListOf<Long>() }
    val finish by rememberUpdatedState(onResult)

    Column(modifier) {
        GameProgress(round, PINCH_ROUNDS, celebrate = opened)
        Spacer(Modifier.height(Sizes.gapSmall))
        GameBoard(Modifier.fillMaxWidth().weight(1f)) { _, _ ->
            Box(
                Modifier.matchParentSize().pointerInput(Unit) {
                    // The whole board takes the pinch, not just the photo: two fingers that have to
                    // land on a small picture is a second exercise nobody asked for.
                    //
                    // Written out rather than handed to detectTransformGestures, for two reasons the
                    // exercise depends on: that one tells nobody when the fingers came off the glass
                    // — and letting go is half of what is being marked here — and it swallows the
                    // first stretch of every gesture as touch slop, which is the part of the
                    // movement a weak hand has.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        opened = false
                        /** True once this gesture has already opened and closed a photo. */
                        var scored = false
                        do {
                            val event = awaitPointerEvent()
                            val zoom = event.calculateZoom()
                            if (zoom != 1f) {
                                scale = (scale * zoom).coerceIn(1f, PINCH_MAX)
                                if (scale > peak) peak = scale
                                event.changes.forEach { it.consume() }
                                // Opened wide and brought back: that is the whole movement, and it
                                // counts the moment he completes it rather than when he lets go.
                                if (!scored && peak >= PINCH_OPEN && scale <= PINCH_CLOSED) {
                                    feedback.success()
                                    scored = true
                                    opened = true
                                    round += 1
                                    taken += now() - roundAt
                                    roundAt = now()
                                    size = Adaptive.afterHit(size)
                                    scale = 1f
                                    peak = 1f
                                    if (round >= PINCH_ROUNDS) finish(round, misses, size, ArcadePlay(taken.toList()))
                                }
                            }
                        } while (event.changes.any { it.pressed })
                        // Off the glass. Anything more than a touch that did not finish the movement
                        // is a try that did not come off: a buzz and a bigger photo.
                        //
                        // Never after a photo he has just opened, though: two fingers coming off the
                        // glass spread a little as they lift, and the round he had already won would
                        // be followed by a buzz and a bigger photo for lifting his hand.
                        if (!scored && peak >= PINCH_CLOSED) {
                            feedback.nudge()
                            misses += 1
                            size = Adaptive.afterMiss(size)
                        }
                        scale = 1f
                        peak = 1f
                    }
                }
            )

            val photo = photos.getOrNull(round % photos.size.coerceAtLeast(1))
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size((size * PHOTO).dp)
                    // Scaled in the drawing, not in the layout: the board must not reflow around a
                    // photo that is changing size under his fingers.
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .testTag(ARCADE_PHOTO_TAG)
            ) {
                if (photo != null) {
                    AsyncImage(
                        model = photo,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    // No pictures on the device at all. A coloured card pinches the same.
                    Box(
                        Modifier.fillMaxSize()
                            .clip(RoundedCornerShape(Sizes.corner))
                            .background(MaterialTheme.colorScheme.secondary)
                    )
                }
            }
        }
    }
}

/**
 * The photo is bigger than a target: two fingers need something to hold, and at the smallest target
 * he ever works down to this is still a picture rather than a stamp. The pinch itself is taken
 * anywhere on the board, so this is what he can see, not what he has to hit.
 */
private const val PHOTO = 2.2f
