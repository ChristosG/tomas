package gr.dimitris.app.modules.trace

import androidx.compose.foundation.layout.BoxWithConstraints
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
                    BigButton("Έτοιμο", onClick = vm::check, tone = ButtonTone.Success, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(Sizes.gapSmall))
                QuietButton("Παράλειψη", onClick = vm::skip)
            }
        },
    ) {
        if (s.text.isEmpty()) { Text("Ετοιμάζω...", style = MaterialTheme.typography.headlineMedium); return@DimitrisScreen }

        Text(
            if (s.hand == Settings.HAND_RIGHT) "Με το δεξί χέρι" else "Με το αριστερό χέρι",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Sizes.gapSmall))

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            // At level 5 the word goes on holding its place after «Το είδα» — invisible, not gone, so
            // the canvas underneath does not jump up the screen the moment he takes his eyes off it.
            Text(
                s.text, style = MaterialTheme.typography.displayLarge, maxLines = 1, textAlign = TextAlign.Start,
                modifier = Modifier.weight(1f).alpha(if (recall && !s.templateVisible) 0f else 1f).testTag(TRACE_TEXT_TAG),
            )
            // Smaller than the letter beside it, so the tick arriving cannot move the canvas.
            SuccessMark(visible = passed, size = TICK)
        }
        // A miss says so in writing as well as with the buzz: the sound may be off, or missed. The
        // strokes stay where they are and the letter comes back under them, to be gone over again.
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

        // The paper takes the room that is left and no more. A word wants a taller box than a single
        // letter, but the shape is a preference and the fit is not: measured against what is actually
        // there, so an extra line above — «Ξανά», or level 5's «Το είδα» below — makes the letter
        // smaller instead of pushing «Έτοιμο» off the bottom of a screen that cannot scroll.
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val shape = if (s.text.length > 1) WORD_BOX else LETTER_BOX
            val width = minOf(maxWidth, maxHeight * shape)
            TraceCanvas(
                template = s.template,
                // Always there to follow, except in the seconds of level 5 when the point is that it is not.
                showTemplate = s.templateVisible && s.template.isNotEmpty(),
                strokes = s.strokes,
                onStroke = vm::addStroke,
                modifier = Modifier.size(width, width / shape)
                    .align(Alignment.TopCenter)
                    .onSizeChanged { vm.setCanvasSize(it.width.toFloat(), it.height.toFloat()) }
                    .testTag(TRACE_CANVAS_TAG),
                enabled = !passed,
            )
        }
    }
}

/** A single letter is wider than it is tall in its box: a capital «Α» does not need a page. */
private const val LETTER_BOX = 1.2f

/** A word needs the room, so its box is taller than it is wide. */
private const val WORD_BOX = 0.9f

/** No taller than the letter it sits next to: a tick that changes the layout moves his paper. */
private val TICK = 56.dp
