package gr.dimitris.app.modules.trace

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import gr.dimitris.app.LocalAppGraph
import gr.dimitris.app.core.settings.Settings
import gr.dimitris.app.ui.components.BigButton
import gr.dimitris.app.ui.components.ButtonTone
import gr.dimitris.app.ui.components.DimitrisScreen
import gr.dimitris.app.ui.components.QuietButton
import gr.dimitris.app.ui.components.SuccessMark
import gr.dimitris.app.ui.theme.Sizes

/** The paper. Tagged so a test can measure it and drag along the letter it is showing. */
const val TRACE_CANVAS_TAG = "trace-canvas"

/** The letter or word he is being asked to write. Tagged so a test can read what it was given. */
const val TRACE_TEXT_TAG = "trace-text"

/**
 * The same line while level 5 is hiding it. It keeps its place in the layout — the paper must not
 * jump the moment he looks away — so the tag is the only way to tell "there" from "gone", and a test
 * that could not tell them apart could not prove he ever wrote anything from memory.
 */
const val TRACE_TEXT_HIDDEN_TAG = "trace-text-hidden"

/**
 * [count] letters or words, one per item the session budgeted for this module.
 *
 * Nothing on this screen scrolls. The canvas owns the drag — a page that moved under his finger
 * would take the letter with it — so everything else has to fit around it, and the canvas gives up
 * its own height when it does not: a shorter box is a smaller letter, and a hidden «Έτοιμο» is a
 * dead end.
 */
@Composable
fun TraceScreen(count: Int, sessionId: String?, onDone: () -> Unit, onLeave: () -> Unit) {
    val graph = LocalAppGraph.current
    // The count is part of the key: a resumed ViewModel would otherwise keep the old session's length.
    val vm: TraceViewModel = viewModel(key = "trace-${sessionId ?: "practice"}-$count") { TraceViewModel(graph, sessionId, count) }
    val s by vm.state.collectAsStateWithLifecycle()

    /**
     * A mixed session ends with its own summary, so a second "well done" screen in the middle of it
     * is one tap of noise: the module hands straight back. Free practice keeps the end screen — it is
     * the only ending there is — and so does a level change, whichever way it went, because the level
     * is announced on it and he has to be able to read it before it goes.
     */
    val endScreen = sessionId == null || s.levelChanged != null
    LaunchedEffect(s.done) { if (s.done && !endScreen) onDone() }

    if (s.done && endScreen) {
        DimitrisScreen(bottom = { BigButton("Εντάξει", onClick = onDone) }) {
            SuccessMark(visible = true)
            Spacer(Modifier.height(Sizes.gap))
            Text("Τέλος με το γράψιμο!", style = MaterialTheme.typography.headlineMedium)
            if (s.levelChanged != null) {
                Spacer(Modifier.height(Sizes.gapSmall))
                Text(
                    if (s.levelChanged!! > s.level) "Ανεβαίνεις στο επίπεδο ${s.levelChanged}. Μπράβο!"
                    else "Πάμε λίγο πιο εύκολα: επίπεδο ${s.levelChanged}.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            // The end screen speaks too, so a silent phone has to be said here as well.
            if (s.error != null) {
                Spacer(Modifier.height(Sizes.gapSmall))
                Text(s.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
            }
        }
        return
    }

    val passed = s.score?.passed == true
    val missed = s.score?.passed == false
    // Level 5 shows the letter once, then takes it away: from «Το είδα» on, he is writing it himself.
    val recall = s.level >= TraceViewModel.RECALL_LEVEL

    /** True in the stretch of level 5 when he is on his own: no word, no letter, blank paper. */
    val hidden = recall && !s.templateVisible

    DimitrisScreen(
        title = "Γράψε ${s.index + 1}/${s.total}",
        // Back is "I want out", not "I finished": the module drops what it was doing and says so.
        onBack = { vm.leave(onLeave) },
        bottom = {
            // Written: the only way on is «Επόμενο», and it is green because he got there himself.
            if (passed) BigButton("Επόμενο", onClick = vm::next, tone = ButtonTone.Success)
            else {
                if (recall && s.templateVisible) {
                    BigButton("Το είδα", onClick = vm::hide)
                    Spacer(Modifier.height(Sizes.gapSmall))
                }
                Row {
                    // Dead until there is ink to wipe, so it cannot be the button he learns to press.
                    QuietButton("Καθάρισε", onClick = vm::clear, enabled = s.strokes.isNotEmpty(), modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(Sizes.gapSmall))
                    // Dead until there is something to judge, and at level 5 until he has taken the
                    // letter away: an «Έτοιμο» pressed over an empty canvas would nudge him, spend
                    // his first try, and hand back the word he was about to write from memory.
                    BigButton(
                        "Έτοιμο", onClick = vm::check, tone = ButtonTone.Success,
                        enabled = s.strokes.isNotEmpty() && !(recall && s.templateVisible),
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(Sizes.gapSmall))
                QuietButton("Παράλειψη", onClick = vm::skip)
            }
        },
    ) {
        if (s.text.isEmpty()) { Text("Ετοιμάζω...", style = MaterialTheme.typography.headlineMedium); return@DimitrisScreen }

        // Everything but the title, measured before a word of it is laid out, so the one thing that
        // must never be squeezed to nothing — the paper — can be given its room first.
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            // On a short screen at a large font scale there is not room for both the hint and a
            // paper worth writing on. The paper wins: the hand is the same every day, and a canvas
            // too small to write in is a letter he cannot pass.
            val roomForHint = maxHeight >= HINT_NEEDS
            Column(Modifier.fillMaxSize()) {
                if (roomForHint) {
                    Text(
                        if (s.hand == Settings.HAND_RIGHT) "Με το δεξί χέρι" else "Με το αριστερό χέρι",
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Sizes.gapSmall))
                }

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    // At level 5 the word goes on holding its place after «Το είδα» — invisible, not
                    // gone, so the canvas does not jump up the screen the moment he looks away.
                    Text(
                        s.text,
                        // «ΔΗΜΗΤΡΗΣ» at 44 sp is wider than a phone; a long word steps down a size
                        // rather than being cut off, because the word is what he is being asked for.
                        style = if (s.text.length > LONG_TEXT) MaterialTheme.typography.headlineMedium
                        else MaterialTheme.typography.displayLarge,
                        maxLines = 1, textAlign = TextAlign.Start,
                        modifier = Modifier.weight(1f)
                            .alpha(if (hidden) 0f else 1f)
                            .testTag(if (hidden) TRACE_TEXT_HIDDEN_TAG else TRACE_TEXT_TAG),
                    )
                    // Smaller than the letter beside it, so the tick arriving cannot move the canvas.
                    SuccessMark(visible = passed, size = TICK)
                }
                // A miss says so in writing as well as with the buzz: the sound may be off, or
                // missed. The strokes stay where they are and the letter comes back under them.
                if (missed) {
                    Text(
                        TraceViewModel.TRY_AGAIN, style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                if (s.error != null) {
                    Text(s.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
                }
                Spacer(Modifier.height(Sizes.gapSmall))

                // The paper is the biggest one that fits above the buttons. A single letter takes
                // the whole slot — every pixel of it is tolerance for his hand, and a letter drawn
                // half the size is a pass line half as wide. A word keeps its taller shape, capped
                // by whichever of the two directions runs out first.
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                    val word = s.text.length > 1
                    val height = maxHeight.coerceAtLeast(MIN_PAPER)
                    val width = if (word) minOf(maxWidth, height * WORD_BOX) else maxWidth
                    TraceCanvas(
                        template = s.template,
                        // Always there to follow, except in the seconds of level 5 when the point is
                        // that it is not.
                        showTemplate = s.templateVisible && s.template.isNotEmpty(),
                        strokes = s.strokes,
                        onStroke = vm::addStroke,
                        modifier = Modifier.size(width, if (word) width / WORD_BOX else height)
                            .align(Alignment.TopCenter)
                            .onSizeChanged { vm.setCanvasSize(it.width.toFloat(), it.height.toFloat()) }
                            .testTag(TRACE_CANVAS_TAG),
                        enabled = !passed,
                    )
                }
            }
        }
    }
}

/** A word needs the room, so its box is taller than it is wide. A single letter takes the lot. */
private const val WORD_BOX = 0.9f

/** Below this the hand hint goes, so the paper does not. */
private val HINT_NEEDS = 420.dp

/** Paper smaller than this is not writeable; below it the layout spills rather than the letter. */
private val MIN_PAPER = 200.dp

/** Longer than this and the prompt steps down a size to stay on one line. */
private const val LONG_TEXT = 6

/** No taller than the letter it sits next to: a tick that changes the layout moves his paper. */
private val TICK = 56.dp
