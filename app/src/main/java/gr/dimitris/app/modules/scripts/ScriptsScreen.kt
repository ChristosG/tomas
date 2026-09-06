package gr.dimitris.app.modules.scripts

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import gr.dimitris.app.LocalAppGraph
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.Speaker
import gr.dimitris.app.core.speech.GentleCheck
import gr.dimitris.app.core.speech.GentleCheck.Companion.SPEAK
import gr.dimitris.app.ui.components.BigButton
import gr.dimitris.app.ui.components.ButtonTone
import gr.dimitris.app.ui.components.DimitrisScreen
import gr.dimitris.app.ui.components.ListenButton
import gr.dimitris.app.ui.components.ListeningIndicator
import gr.dimitris.app.ui.components.QuietButton
import gr.dimitris.app.ui.components.SuccessMark
import gr.dimitris.app.ui.theme.Sizes

@Composable
fun ScriptsScreen(items: List<Item>, sessionId: String?, onDone: () -> Unit, onLeave: () -> Unit) {
    val graph = LocalAppGraph.current
    val seed = items.first().id
    val vm: ScriptsViewModel = viewModel(key = "scripts-${sessionId ?: "practice"}-$seed") {
        ScriptsViewModel(graph, seed, sessionId)
    }
    val s by vm.state.collectAsStateWithLifecycle()
    // Navigating away — «Μίλα», or the session moving on — is not a back press: the ViewModel is
    // still alive on the back stack, so it is told to hold the dialogue where it stands, and told
    // again when the screen comes back.
    DisposableEffect(vm) { onDispose { vm.screenGone() } }
    LaunchedEffect(vm) { vm.screenHere() }

    /**
     * A mixed session ends with its own summary, so a second "well done" screen in the middle of it
     * is one tap of noise: the module hands straight back. Free practice keeps the end screen — it
     * is the only ending there is.
     */
    val endScreen = sessionId == null
    LaunchedEffect(s.done) { if (s.done && !endScreen) onDone() }

    if (s.done && endScreen) {
        DimitrisScreen(bottom = { BigButton("Εντάξει", onClick = onDone) }) {
            if (s.missing) {
                Text("Ο διάλογος δεν είναι πια εδώ.", style = MaterialTheme.typography.headlineMedium)
            } else {
                SuccessMark(visible = true)
                Spacer(Modifier.height(Sizes.gap))
                Text("Τέλος διαλόγου!", style = MaterialTheme.typography.headlineMedium)
            }
        }
        return
    }

    val listState = rememberLazyListState()
    // The conversation always shows its newest turn: he should never have to scroll to find out
    // whose turn it is.
    LaunchedEffect(s.index, s.phase) {
        if (s.lines.isNotEmpty()) listState.animateScrollToItem(s.index.coerceAtMost(s.lines.lastIndex))
    }
    // A turn may only be answered once it has been drawn. Two of his turns in a row would otherwise
    // let one slip of the thumb confirm the second one before he ever saw it.
    LaunchedEffect(s.index) { vm.turnReady() }

    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.toggleRecording() else vm.micDenied()
    }
    // Recognition opens the microphone too, so it asks for the same permission before it starts.
    val askListen = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.listen() else vm.micDenied()
    }

    DimitrisScreen(
        // The module's own name until the dialogue's is known: a header that is blank for a beat
        // reads as a screen that failed to load.
        title = s.title.ifEmpty { ScriptsModule.titleGreek },
        // Back is "I want out", not "I finished": the module drops what it was doing and says so.
        onBack = { vm.leave(onLeave) },
        bottom = {
            when (s.phase) {
                // «Άκου» is the first button of the top row from the moment the turn appears, and
                // the confirm gets the whole width below it: three big buttons in one row would each
                // be narrower than his thumb, and nothing shrinks to make room for this one.
                ScriptPhase.WAITING_FOR_DIMITRIS -> if (s.listening) {
                    // The window is open. Everything else goes away: there is one thing to do, which
                    // is to speak, and one button, which stops it when he decides he is finished.
                    ListeningIndicator(level = s.listenLevel, onStop = vm::stopListening)
                } else {
                    Row {
                        ListenButton(
                            onClick = vm::listenModel, enabled = !s.modelPlaying && !s.isRecording,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(Sizes.gapSmall))
                        BigButton("Βοήθεια", onClick = vm::hint, tone = ButtonTone.Secondary, enabled = s.canHint, modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(Sizes.gapSmall))
                    // With recognition on, the green button is «Μίλα» until the phone has agreed
                    // with him or has asked him twice. After that «Το είπα!» is back and confirms
                    // exactly as it always did, with «Μίλα» still beside it for another go.
                    when (GentleCheck.primaryFor(s.sttResolved, s.sttOn, s.canConfirm)) {
                        // One DataStore read long, on the first turn only: the button cannot be
                        // pressed into the wrong mode before the settings have been read.
                        GentleCheck.Primary.WAITING ->
                            BigButton("Το είπα!", onClick = {}, tone = ButtonTone.Success, enabled = false)
                        GentleCheck.Primary.SPEAK ->
                            BigButton(SPEAK, onClick = { askListen.launch(Manifest.permission.RECORD_AUDIO) }, icon = Icons.Rounded.Mic, tone = ButtonTone.Success)
                        GentleCheck.Primary.CONFIRM -> {
                            BigButton("Το είπα!", onClick = vm::confirm, tone = ButtonTone.Success)
                            if (s.sttOn) {
                                Spacer(Modifier.height(Sizes.gapSmall))
                                QuietButton(SPEAK, onClick = { askListen.launch(Manifest.permission.RECORD_AUDIO) }, icon = Icons.Rounded.Mic)
                            }
                        }
                    }
                    Spacer(Modifier.height(Sizes.gapSmall))
                    QuietButton("Παράλειψη", onClick = vm::skip)
                }
                // While somebody else is talking there is still something he can press: «Συνέχεια»
                // cuts the line short and moves the conversation on. Not a timer — his own choice —
                // and it is what keeps a wedged speech engine from stranding him on someone else's
                // turn with nothing on screen to do.
                ScriptPhase.OTHER_SPEAKING -> QuietButton("Συνέχεια", onClick = vm::continueNow)
                ScriptPhase.LOADING -> QuietButton("Ετοιμάζω...", onClick = {}, enabled = false)
                ScriptPhase.FINISHED -> QuietButton("Τέλος διαλόγου", onClick = {}, enabled = false)
            }
        },
    ) {
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(Sizes.gapSmall),
            modifier = Modifier.weight(1f),
        ) {
            // Only what has been said so far, plus the turn he is on: a dialogue that showed its
            // own ending would be a script to read, not a conversation to have.
            itemsIndexed(s.lines.take(s.index + 1)) { i, (line, item) ->
                val current = i == s.index && s.phase == ScriptPhase.WAITING_FOR_DIMITRIS
                if (current) {
                    TurnCard(
                        level = s.level,
                        cueText = s.cueText,
                        word = if (s.showsWord) item.text else null,
                        recording = s.isRecording,
                        modelPlaying = s.modelPlaying,
                        listening = s.listening,
                        nudge = s.sttOn && s.nudge,
                        heard = if (s.sttOn) s.heard else null,
                        heardMatched = s.heardMatched,
                        hasTake = s.selfRecordingPath != null,
                        // Stopping is not a permission question: only starting asks.
                        onRecord = { if (s.isRecording) vm.toggleRecording() else askMic.launch(Manifest.permission.RECORD_AUDIO) },
                        onCompare = vm::playComparison,
                    )
                } else {
                    Bubble(
                        text = item.text,
                        mine = line.speaker == Speaker.DIMITRIS,
                        skipped = i in s.skippedLines,
                        // Tapping what was said plays it again: "what did they ask me?" must have an
                        // answer, and it may not interrupt the person still talking — nor speak
                        // into a recogniser he has open, which would be the phone answering itself.
                        onClick = if (line.speaker == Speaker.OTHER && s.phase == ScriptPhase.WAITING_FOR_DIMITRIS && !s.listening) {
                            { vm.replay(i) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
        if (s.error != null) {
            Spacer(Modifier.height(Sizes.gapSmall))
            Text(s.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** A line already said. His own sit right and coloured; the other person's sit left and are tappable. */
@Composable
private fun Bubble(text: String, mine: Boolean, skipped: Boolean, onClick: (() -> Unit)?) {
    val container = when {
        !mine -> MaterialTheme.colorScheme.surfaceVariant
        // A turn he passed on is still part of the conversation, but it is not something he did.
        skipped -> MaterialTheme.colorScheme.surface
        else -> MaterialTheme.colorScheme.primary
    }
    val content = if (mine && !skipped) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Surface(
            color = container,
            shape = RoundedCornerShape(Sizes.corner),
            modifier = Modifier.fillMaxWidth(BUBBLE_WIDTH).heightIn(min = Sizes.touchMin)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(14.dp)) {
                Text(text, style = MaterialTheme.typography.titleLarge, color = content, modifier = Modifier.weight(1f))
                // The icon half of "icon + sound + haptic": a turn he took keeps its tick.
                if (mine && !skipped) {
                    Spacer(Modifier.width(Sizes.gapSmall))
                    Icon(Icons.Rounded.CheckCircle, contentDescription = "Το είπε", tint = content, modifier = Modifier.size(Sizes.icon))
                }
            }
        }
    }
}

/**
 * His turn. Nothing of the line is *written* until he asks: level 0 is the bare invitation, 1 and 2
 * show the sound and the syllable, 3 says it aloud without writing it, and only 4 puts it on screen.
 * Hearing it is another matter — «Άκου» sits in the bottom row and is live from the first second.
 *
 * At any level he may record himself and hear the model and his own take back to back — the half of
 * the cue ladder that gives him feedback on how it came out, and the same pair the word coach has.
 */
@Composable
private fun TurnCard(
    level: Int,
    cueText: String?,
    word: String?,
    recording: Boolean,
    modelPlaying: Boolean,
    listening: Boolean,
    nudge: Boolean,
    heard: String?,
    heardMatched: Boolean,
    hasTake: Boolean,
    onRecord: () -> Unit,
    onCompare: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(Sizes.corner),
            modifier = Modifier.fillMaxWidth(BUBBLE_WIDTH),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "Η σειρά σου", style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                if (level in 1..2 && cueText != null) {
                    Spacer(Modifier.height(Sizes.gapSmall))
                    Text(
                        cueText, style = MaterialTheme.typography.displayLarge, textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (word != null) {
                    Spacer(Modifier.height(Sizes.gapSmall))
                    Text(
                        word, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.fillMaxWidth(),
                    )
                }
                // One nudge and no more. The cue has not moved, «Άκου» is where it was, and the
                // microphone is one tap away again: nothing has been taken away from him.
                if (nudge) {
                    Spacer(Modifier.height(Sizes.gapSmall))
                    Text(
                        GentleCheck.TRY_AGAIN, style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (heard != null) {
                    Spacer(Modifier.height(Sizes.gapSmall))
                    Text(
                        // A miss is the phone's uncertainty, never a verdict on how he said it.
                        if (heardMatched) "Άκουσα «$heard». Μπράβο!" else "Άκουσα «$heard». Το τηλέφωνο δεν είναι σίγουρο.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Spacer(Modifier.height(Sizes.gapSmall))
                // «Άκου ξανά» used to live here, dead until «Βοήθεια» had been pressed — which is
                // what Chris found in the field. It is gone: the one «Άκου» this screen has is the
                // big one in the bottom row, live from the first second of the turn. Two buttons
                // both called «Άκου» is one too many, so the pair below is «Σύγκριση», the word the
                // word coach and «Τραγούδα και πες το» already use for the same thing.
                QuietButton(
                    if (recording) "Στοπ" else "Ηχογράφηση", onClick = onRecord,
                    icon = if (recording) Icons.Rounded.Stop else Icons.Rounded.Mic,
                    // Not while the line is being said to him: he hears «Άκου», reaches straight
                    // for the mic, and the take would be the phone's own voice — which is then what
                    // «Σύγκριση» plays back to him as his, and what his caregiver hears in the
                    // word's recordings. «Στοπ» stays live, or a take could not be closed. Nor
                    // while the recogniser has the microphone: two mouths on one microphone.
                    enabled = recording || (!modelPlaying && !listening),
                )
                // Only once there is something of his to compare the model against, and never
                // while the recogniser is open: the comparison starts by saying his own line.
                if (hasTake && !recording && !listening) {
                    Spacer(Modifier.height(Sizes.gapSmall))
                    QuietButton("Σύγκριση", onClick = onCompare, icon = Icons.Rounded.Compare)
                }
            }
        }
    }
}

/** Wide enough to hold a whole turn at his text size, narrow enough to show which side said it. */
private const val BUBBLE_WIDTH = 0.9f

