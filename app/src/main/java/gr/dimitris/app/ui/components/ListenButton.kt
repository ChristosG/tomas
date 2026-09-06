package gr.dimitris.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/** Every «Άκου» in the four speech modules carries it, so a test can find the one on the screen. */
const val LISTEN_TAG = "listen-model"

/**
 * «Άκου» — the one button that is never taken away.
 *
 * Chris found «Άκου ξανά» in the dialogues dead until he pressed «Βοήθεια», which is the opposite
 * of what errorless learning asks for: a man who cannot retrieve a word is not helped by being made
 * to fail for it first. Spec §12 — hearing the model is never withheld. So the button is here from
 * the moment the turn is shown, at every cue level and every stage, and the only things that switch
 * it off are the two that physically cannot share the moment with it: the model already sounding,
 * and the microphone being open.
 *
 * What it costs is honesty in the data, not access: the module raises the attempt's cue level to
 * [gr.dimitris.app.modules.wordcoach.CueLadder.LISTENED] and counts the listens in its detail, so
 * the caregiver's numbers still say how much help the word needed. Nothing on the screen calls it
 * a failure.
 */
@Composable
fun ListenButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = BigButton(
    text = LISTEN,
    onClick = onClick,
    modifier = modifier.testTag(LISTEN_TAG),
    icon = Icons.Rounded.VolumeUp,
    tone = ButtonTone.Secondary,
    enabled = enabled,
)

/** One word, the same word, on all four screens: never «Άκου ξανά», which reads as a second chance. */
const val LISTEN = "Άκου"
