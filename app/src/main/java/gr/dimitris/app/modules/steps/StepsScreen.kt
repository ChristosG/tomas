package gr.dimitris.app.modules.steps

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import gr.dimitris.app.LocalAppGraph
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.speech.GentleCheck
import gr.dimitris.app.ui.components.BigButton
import gr.dimitris.app.ui.components.ButtonTone
import gr.dimitris.app.ui.components.DimitrisScreen
import gr.dimitris.app.ui.components.ListenButton
import gr.dimitris.app.ui.components.ListeningIndicator
import gr.dimitris.app.ui.components.ModuleDifficultyRow
import gr.dimitris.app.ui.components.QuietButton
import gr.dimitris.app.ui.components.SuccessMark
import gr.dimitris.app.ui.theme.LocalFeedback
import gr.dimitris.app.ui.theme.Sizes

/** Every tile still on the board, so a test can read what is on offer and tap it. */
const val STEP_TILE_TAG = "step-tile"

/** Every step he has put in the strip, in the order he put it. Tapping one takes it back. */
const val STEP_CHOSEN_TAG = "step-chosen"

/** [count] tasks, one per item the session budgeted for this module. */
@Composable
fun StepsScreen(count: Int, sessionId: String?, onDone: () -> Unit, onLeave: () -> Unit) {
    val graph = LocalAppGraph.current
    // The count is part of the key: a resumed ViewModel would otherwise keep the old session's length.
    val vm: StepsViewModel = viewModel(key = "steps-${sessionId ?: "practice"}-$count") { StepsViewModel(graph, sessionId, count) }
    val s by vm.state.collectAsStateWithLifecycle()

    // Recognition opens the microphone, so «Μίλα» asks for the permission before it starts.
    val askListen = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.listen() else vm.micDenied()
    }

    /**
     * A mixed session ends with its own summary, so a second "well done" screen in the middle of it is
     * one tap of noise: the module hands straight back. Free practice keeps the end screen — it is the
     * only ending there is.
     */
    val endScreen = sessionId == null
    LaunchedEffect(s.done) { if (s.done && !endScreen) onDone() }

    if (s.done && endScreen) {
        DimitrisScreen(bottom = { BigButton("Εντάξει", onClick = onDone) }) {
            // Nothing to do: the seed could not be read, which is the app's fault and not his. He is
            // told plainly and let straight out.
            if (s.task == null) {
                Text("Δεν βρήκα ασκήσεις.", style = MaterialTheme.typography.headlineMedium)
                return@DimitrisScreen
            }
            SuccessMark(visible = true)
            Spacer(Modifier.height(Sizes.gap))
            Text("Τέλος με τα βήματα!", style = MaterialTheme.typography.headlineMedium)
            if (s.error != null) {
                Spacer(Modifier.height(Sizes.gapSmall))
                Text(s.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
            }
        }
        return
    }

    val task = s.task
    DimitrisScreen(
        title = "${StepsModule.titleGreek} ${s.index + 1}/${s.total}",
        // Back is "I want out", not "I finished": the module drops what it was doing and says so.
        onBack = { vm.leave(onLeave) },
        bottom = {
            // Three actions and one primary, whichever stage he is on, and each of them in the same
            // place in both: the green button on top, «Άκου» under it, «Παράλειψη» at the foot. The
            // block must not reshuffle itself between the ordering and the telling — his thumb learns
            // where the buttons are, and the two stages are one screen.
            if (s.listening) {
                // The window is open. Everything else goes away: there is one thing to do, which is to
                // speak, and one button, which stops it when he decides he is finished.
                ListeningIndicator(level = s.listenLevel, onStop = vm::stopListening)
            } else {
                when (s.stage) {
                    StepStage.ORDER -> BigButton(
                        "Έτοιμο", onClick = vm::submit, tone = ButtonTone.Success,
                        enabled = task != null && s.chosen.size == task.steps.size,
                    )
                    // With recognition on, the green button is «Μίλα» until the phone has agreed with
                    // him or has asked him twice. After that «Το είπα!» is back and confirms exactly as
                    // it always did.
                    StepStage.TELL -> when (GentleCheck.primaryFor(s.sttResolved, s.sttOn, s.canConfirm)) {
                        // One DataStore read long, on the first task only: the button cannot be pressed
                        // into the wrong mode before the settings have been read.
                        GentleCheck.Primary.WAITING ->
                            BigButton(SAID_IT, onClick = {}, tone = ButtonTone.Success, enabled = false)
                        // Greyed while the judge is reading what he said: the buttons stay where they
                        // are, and a second window cannot open over a telling that is about to count.
                        GentleCheck.Primary.SPEAK -> BigButton(
                            GentleCheck.SPEAK, onClick = { askListen.launch(Manifest.permission.RECORD_AUDIO) },
                            icon = Icons.Rounded.Mic, tone = ButtonTone.Success, enabled = !s.thinking,
                        )
                        GentleCheck.Primary.CONFIRM ->
                            BigButton(SAID_IT, onClick = vm::confirm, tone = ButtonTone.Success)
                    }
                }
                Spacer(Modifier.height(Sizes.gapSmall))
                // «Άκου» reads the task and the strip as it stands while he is ordering, and the whole
                // telling once he is telling. It is never withheld (spec §12); what it costs is the row.
                ListenButton(onClick = vm::listenModel, enabled = task != null && !s.modelPlaying && !s.thinking)
                Spacer(Modifier.height(Sizes.gapSmall))
                QuietButton("Παράλειψη", onClick = vm::skip, enabled = !s.thinking)
            }
        },
    ) {
        // The five dots, on the first screen of the module and nowhere else (spec §13): how hard this
        // is, is his to set — and the middle of a mixed session is no place to be asked.
        if (sessionId == null && s.index == 0) ModuleDifficultyRow(ModuleId.STEPS, onChanged = vm::reload)
        if (task == null) {
            Text("Ετοιμάζω...", style = MaterialTheme.typography.headlineMedium)
            return@DimitrisScreen
        }
        val scroll = rememberScrollState()
        // A miss puts the whole strip back at the top of the screen. On a six-step task the strip is
        // taller than the phone, so a man who has just been told «Σχεδόν.» could otherwise be looking
        // at steps 3 to 6 with the nudge, and the marked step, both off the top.
        LaunchedEffect(s.misses) { if (s.misses > 0) runCatching { scroll.animateScrollTo(0) } }
        Column(Modifier.fillMaxWidth().verticalScroll(scroll)) {
            Text(task.title, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(Sizes.gapSmall))
            Text(
                if (s.stage == StepStage.ORDER) StepsViewModel.PUT_IN_ORDER else StepsViewModel.TELL_THEM,
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // A miss says so in writing as well as by the buzz — the sound may be off, or missed — and
            // the marked step in the strip says which one. Never «λάθος».
            //
            // Above the strip and not below it, which is where every other module puts its correction:
            // there the thing to read is the sentence *under* the answer, here it is the strip itself,
            // and a nudge under six steps is a nudge off the bottom of the screen.
            if (s.missed) {
                Spacer(Modifier.height(Sizes.gapSmall))
                Text(
                    StepsViewModel.ALMOST, style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            Spacer(Modifier.height(Sizes.gapSmall))

            Strip(s, onRemove = vm::untap)
            Spacer(Modifier.height(Sizes.gapSmall))

            when (s.stage) {
                // The board goes once the strip is the answer: in the telling stage the steps are the
                // thing to read, and seven tiles under them would be seven things to tap that do nothing.
                StepStage.ORDER -> Board(s, onTap = vm::tap)
                StepStage.TELL -> Telling(s)
            }
            if (s.error != null) {
                Spacer(Modifier.height(Sizes.gapSmall))
                Text(s.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
            }
            Spacer(Modifier.height(Sizes.gap))
        }
    }
}

/**
 * The steps he has put down, numbered, one to a line.
 *
 * A vertical list and not the sideways strip «Προτάσεις» and «SQL» use, because a step is a phrase
 * and not a word: six of them side by side would be six columns of wrapped text, and the numbers —
 * which are the whole point — would be the smallest thing on the screen. Down the page it reads as
 * what it is, a list of things to do in order.
 *
 * Tapping a line takes that step back out, which is the undo: the control sits next to the thing it is
 * about, because the bottom block is already «Έτοιμο» / «Άκου» / «Παράλειψη».
 */
@Composable
private fun Strip(s: StepsState, onRemove: (Step) -> Unit) {
    Surface(
        shape = RoundedCornerShape(Sizes.corner), color = MaterialTheme.colorScheme.surfaceVariant,
        // No minimum height of its own: an empty strip holds no control, so the app's 72 dp touch
        // floor does not apply to it, and every dp it does not take is a dp the board below it keeps.
        // The lines inside are 72 dp each, because those *are* controls.
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            if (s.chosen.isEmpty()) {
                // What the strip is, not what to do: the instruction above it already says that, and
                // the same sentence twice on one screen is one of them nobody reads.
                Text(
                    EMPTY_STRIP, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            s.chosen.forEachIndexed { at, step ->
                if (at > 0) Spacer(Modifier.height(Sizes.gapSmall))
                Chosen(
                    step = step,
                    number = at + 1,
                    // One tile marked and only one: the first that is out of place. See [firstWrongStep].
                    marked = s.wrongAt == at,
                    enabled = s.stage == StepStage.ORDER,
                    onClick = { onRemove(step) },
                )
            }
            // The mark for the whole task, on the strip he built rather than on a screen of its own.
            SuccessMark(visible = s.stage == StepStage.TELL, size = Sizes.stripPicture)
        }
    }
}

/** One line of the strip: its number, its drawing and its words. */
@Composable
private fun Chosen(step: Step, number: Int, marked: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val feedback = LocalFeedback.current
    Card(
        onClick = { feedback.tap(); onClick() },
        enabled = enabled,
        shape = RoundedCornerShape(Sizes.corner),
        border = if (marked) BorderStroke(3.dp, MaterialTheme.colorScheme.secondary) else null,
        // The words keep their full contrast when the line stops being tappable, which is the whole of
        // stage 2: the steps are the thing he is reading out, and Material's disabled grey would make
        // the most important text on the screen the palest.
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = MaterialTheme.colorScheme.surface,
            disabledContentColor = MaterialTheme.colorScheme.onSurface,
        ),
        modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchMin).testTag(STEP_CHOSEN_TAG),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$number.", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.size(Sizes.gapSmall))
            Pictogram(step.asset, size = Sizes.stripPicture)
            Spacer(Modifier.size(Sizes.gapSmall))
            Text(step.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * The tiles still to place: three to a row, picture over words.
 *
 * Three and not two, because at difficulty 5 there are seven of them and two columns would put the
 * last row below the fold on a phone — on the one board whose whole exercise is seeing every step at
 * once and deciding which comes next.
 *
 * Written as rows of three rather than as a `LazyVerticalGrid` because the whole screen scrolls: a
 * lazy grid inside a scrolling column has no height to measure against and crashes.
 */
@Composable
private fun Board(s: StepsState, onTap: (Step) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Sizes.gapSmall)) {
        for (row in s.tiles.chunked(TILES_PER_ROW)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Sizes.gapSmall)) {
                for (step in row) {
                    Box(Modifier.weight(1f)) {
                        Tile(
                            step = step,
                            // A tile he has used is dimmed and dead rather than gone: a board that
                            // reshuffles itself under his thumb is a board he has to read again every tap.
                            enabled = s.chosen.none { it.text == step.text },
                            onClick = { onTap(step) },
                        )
                    }
                }
                repeat(TILES_PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** One step on the board: the drawing, and the phrase under it. */
@Composable
private fun Tile(step: Step, enabled: Boolean, onClick: () -> Unit) {
    val feedback = LocalFeedback.current
    Card(
        onClick = { feedback.tap(); onClick() },
        enabled = enabled,
        shape = RoundedCornerShape(Sizes.corner),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchMin).testTag(STEP_TILE_TAG),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(8.dp),
        ) {
            Pictogram(step.asset, size = TILE_PICTURE)
            Text(
                step.text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** What the telling stage puts under the steps: what he was heard to say, and the whole telling. */
@Composable
private fun Telling(s: StepsState) {
    if (s.nudge) {
        Text(
            GentleCheck.TRY_AGAIN, style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
        Spacer(Modifier.height(Sizes.gapSmall))
    }
    // What the recogniser made of him, in his own words, so a mismatch is something he can see rather
    // than a verdict he has to take on trust.
    s.heard?.let {
        Text("«$it»", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(Sizes.gapSmall))
    }
    // The judge's one warm line. Never a verdict on him.
    s.feedback?.let {
        Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.height(Sizes.gapSmall))
    }
    // The whole telling, left on the screen for him to repeat: the connectors are the exercise, and a
    // man who cannot retrieve «πρώτα… μετά… τέλος» is not taught them by being made to guess.
    s.whole?.let {
        Surface(
            shape = RoundedCornerShape(Sizes.corner), color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                it, style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

/** A drawing that is missing or would not load falls back to the placeholder: the words still work. */
@Composable
private fun Pictogram(asset: String?, size: Dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (asset != null) {
            AsyncImage(
                model = asset, contentDescription = null, contentScale = ContentScale.Fit,
                error = rememberVectorPainter(Icons.Rounded.Image), modifier = Modifier.size(size),
            )
        } else {
            Icon(
                Icons.Rounded.Image, contentDescription = null, modifier = Modifier.size(size),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** What the strip says while it is empty. The instruction above it is what asks him to fill it. */
private const val EMPTY_STRIP = "Εδώ μπαίνουν τα βήματα."

/** «Το είπα!»: the same word as in the three speech modules, because it does the same thing. */
private const val SAID_IT = "Το είπα!"

/** Seven tiles at difficulty 5, and three columns is what keeps them all above the fold. */
private const val TILES_PER_ROW = 3

/**
 * Big enough to recognise in a three-column tile, small enough to leave room for the phrase.
 *
 * 56 and not 64: at difficulty 5 there are seven tiles in three rows under a strip of six lines, and
 * the eight dp a drawing gives back is the difference between the last row's words being on the screen
 * and being cut across. The drawing is the second thing he reads here anyway — the phrase is the step.
 */
private val TILE_PICTURE = 56.dp
