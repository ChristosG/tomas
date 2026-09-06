package gr.dimitris.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import gr.dimitris.app.ui.theme.Sizes

/** So a test can find the listening state without reading Greek off the screen. */
const val LISTENING_TAG = "listening"

/** What the screen says while the window is open. Not a question, not a countdown: a fact. */
const val LISTENING_NOW = "Σε ακούω…"

/** Closes the window early. His word, on his own timing — the one control that outranks the wait. */
const val STOP_LISTENING = "Στοπ"

/**
 * The whole of "the phone is listening to you", in one place because all three modules that check
 * what he said must show it identically.
 *
 * A pulsing microphone, a bar that moves with his voice, one line of Greek and a «Στοπ» under his
 * thumb. There is deliberately **no** countdown and no timer text: Chris' report was that the
 * recognizer stopped listening before even he could get the word out, and a number ticking down in
 * front of a man who needs several seconds to start a word would be the same pressure written on
 * the screen. The bar is the only feedback, and it answers the one question he cannot ask — "is it
 * hearing me?"
 *
 * [level] is 0..1 from [gr.dimitris.app.core.speech.SpeechToText.level].
 */
@Composable
fun ListeningIndicator(level: Float, onStop: () -> Unit, modifier: Modifier = Modifier) {
    val pulse = rememberInfiniteTransition(label = "listening")
    val scale by pulse.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(PULSE_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "mic",
    )
    Column(
        modifier.fillMaxWidth().testTag(LISTENING_TAG),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Rounded.Mic,
            // The line below says it: a screen reader must not read it twice.
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(MIC).graphicsLayer { scaleX = scale; scaleY = scale },
        )
        Spacer(Modifier.height(Sizes.gapSmall))
        Box(
            Modifier.fillMaxWidth().height(BAR).clip(RoundedCornerShape(BAR / 2))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            // Never quite empty: a bar at zero width reads as a phone that has stopped listening.
            Box(
                Modifier.fillMaxWidth(level.coerceIn(BAR_MIN, 1f)).fillMaxHeight()
                    .background(MaterialTheme.colorScheme.secondary),
            )
        }
        Spacer(Modifier.height(Sizes.gapSmall))
        Text(LISTENING_NOW, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(Sizes.gap))
        // 72dp, the app's touch minimum, and the only button on the screen while the window is open.
        BigButton(
            STOP_LISTENING,
            onClick = onStop,
            tone = ButtonTone.Secondary,
            modifier = Modifier.height(Sizes.touchMin),
        )
    }
}

private val MIC = 64.dp
private val BAR = 16.dp
private const val BAR_MIN = 0.03f
private const val PULSE_MS = 700
