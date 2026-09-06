package gr.dimitris.app.modules.arcade

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import gr.dimitris.app.LocalAppGraph
import gr.dimitris.app.ui.components.BigButton
import gr.dimitris.app.ui.components.DimitrisScreen
import gr.dimitris.app.ui.components.QuietButton
import gr.dimitris.app.ui.components.SuccessMark
import gr.dimitris.app.ui.theme.Palette
import gr.dimitris.app.ui.theme.Sizes

/** The play area. Tagged so a test can measure it and aim inside it. */
const val ARCADE_BOARD_TAG = "arcade-board"

/** The circle he is being asked to press. Tagged so a test can hit it wherever it has moved to. */
const val ARCADE_TARGET_TAG = "arcade-target"

/** The puck of the drag game, and the ring it belongs in. */
const val ARCADE_PUCK_TAG = "arcade-puck"
const val ARCADE_HOME_TAG = "arcade-home"

/** What the pinch game zooms: one of his photos, or a plain shape when there are none. */
const val ARCADE_PHOTO_TAG = "arcade-photo"

/**
 * [count] games, one per item the session budgeted for this module; free practice runs all four.
 *
 * Nothing on this screen scrolls. Each game owns its own gestures and consumes them — a page that
 * moved under his finger would take the target with it — so the board is given the room that is
 * left after the one line of instruction and the 72 dp «Παράλειψη» at the bottom.
 *
 * The targets inside the board are allowed to be smaller than 72 dp. They are the exercise; every
 * button he has to navigate with still is not.
 */
@Composable
fun ArcadeScreen(count: Int, sessionId: String?, onDone: () -> Unit, onLeave: () -> Unit) {
    val graph = LocalAppGraph.current
    // The count is part of the key: a resumed ViewModel would otherwise keep the old session's length.
    val vm: ArcadeViewModel = viewModel(key = "arcade-${sessionId ?: "practice"}-$count") { ArcadeViewModel(graph, sessionId, count) }
    val s by vm.state.collectAsStateWithLifecycle()

    /**
     * A mixed session ends with its own summary, so a second "well done" in the middle of it is one
     * tap of noise: the module hands straight back. Free practice keeps the end screen — it is the
     * only ending there is.
     */
    val endScreen = sessionId == null
    LaunchedEffect(s.done) { if (s.done && !endScreen) onDone() }

    if (s.done && endScreen) {
        // Still «Δεξί χέρι» at the top: which hand this was for is the one thing the screen never
        // stops saying.
        DimitrisScreen(title = ArcadeModule.titleGreek, bottom = { BigButton("Εντάξει", onClick = onDone) }) {
            SuccessMark(visible = true)
            Spacer(Modifier.height(Sizes.gap))
            Text(ArcadeViewModel.FINISHED, style = MaterialTheme.typography.headlineMedium)
            if (s.error != null) {
                Spacer(Modifier.height(Sizes.gapSmall))
                Text(s.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
            }
        }
        return
    }

    DimitrisScreen(
        title = "${ArcadeModule.titleGreek} ${s.index + 1}/${s.total}",
        // Back is "I want out", not "I finished": the module drops what it was doing and says so.
        onBack = { vm.leave(onLeave) },
        bottom = { QuietButton("Παράλειψη", onClick = vm::skip) },
    ) {
        Text(s.game.prompt, style = MaterialTheme.typography.headlineMedium)
        if (s.error != null) {
            Text(s.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(Modifier.height(Sizes.gapSmall))

        if (!s.ready) {
            Text("Ετοιμάζω...", style = MaterialTheme.typography.headlineMedium)
            return@DimitrisScreen
        }

        // Taken out here: inside key() the column scope that weight() belongs to is gone.
        val board = Modifier.fillMaxWidth().weight(1f)
        // Keyed on the game, so a finished round can never leave its hits behind in the next one.
        key(s.index) {
            when (s.game) {
                ArcadeGame.TAP -> TapGame(s.sizeDp, vm::onResult, board)
                ArcadeGame.TRACE -> TracePathGame(s.sizeDp, vm::onResult, board)
                ArcadeGame.DRAG -> DragGame(s.sizeDp, vm::onResult, board)
                ArcadeGame.PINCH -> PinchGame(s.sizeDp, s.photos, vm::onResult, board)
            }
        }
    }
}

/**
 * The board every game is played on: white paper on the cream page, with an edge that says where the
 * exercise stops, and its measured size in pixels handed to whatever is drawn on it.
 *
 * It is a fixed box, never a scrolling one. The gestures inside it are consumed, exactly as the
 * writing canvas consumes them, so a drag across the board is a drag and not a scroll.
 */
@Composable
fun GameBoard(
    modifier: Modifier = Modifier,
    content: @Composable BoxWithConstraintsScope.(width: Float, height: Float) -> Unit,
) {
    val paper = RoundedCornerShape(Sizes.corner)
    BoxWithConstraints(
        modifier
            .clip(paper)
            .background(MaterialTheme.colorScheme.surface)
            .border(EDGE, Palette.mist, paper)
            .testTag(ARCADE_BOARD_TAG)
    ) {
        content(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
    }
}

/** How far he is through the round, for anyone watching over his shoulder. */
@Composable
fun GameProgress(done: Int, of: Int) {
    Text(
        "$done/$of",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The board's edge: enough to see where the game ends, not enough to be part of it. */
private val EDGE = 2.dp
