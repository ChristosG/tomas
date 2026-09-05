package gr.dimitris.app.modules.scripts

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
import androidx.compose.material.icons.rounded.VolumeUp
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
import gr.dimitris.app.ui.components.BigButton
import gr.dimitris.app.ui.components.ButtonTone
import gr.dimitris.app.ui.components.DimitrisScreen
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

    DimitrisScreen(
        // The module's own name until the dialogue's is known: a header that is blank for a beat
        // reads as a screen that failed to load.
        title = s.title.ifEmpty { ScriptsModule.titleGreek },
        // Back is "I want out", not "I finished": the module drops what it was doing and says so.
        onBack = { vm.leave(onLeave) },
        bottom = {
            when (s.phase) {
                ScriptPhase.WAITING_FOR_DIMITRIS -> {
                    Row {
                        BigButton("Βοήθεια", onClick = vm::hint, tone = ButtonTone.Secondary, enabled = s.canHint, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(Sizes.gapSmall))
                        BigButton("Το είπα!", onClick = vm::confirm, tone = ButtonTone.Success, modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(Sizes.gapSmall))
                    QuietButton("Παράλειψη", onClick = vm::skip)
                }
                // Nothing to press while somebody else is talking, and the button says which it is
                // rather than going blank: a screen with no button on it looks broken.
                ScriptPhase.OTHER_SPEAKING -> QuietButton("Ακούω...", onClick = {}, enabled = false)
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
                        onListen = vm::repeatCue,
                    )
                } else {
                    Bubble(
                        text = item.text,
                        mine = line.speaker == Speaker.DIMITRIS,
                        skipped = i in s.skippedLines,
                        // Tapping what was said plays it again: "what did they ask me?" must have an
                        // answer, and it may not interrupt the person still talking.
                        onClick = if (line.speaker == Speaker.OTHER && s.phase == ScriptPhase.WAITING_FOR_DIMITRIS) {
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
 * His turn. Nothing of the line is given away until he asks: level 0 is the bare invitation, 1 and 2
 * show the sound and the syllable, 3 says it aloud without writing it, and only 4 puts it on screen.
 */
@Composable
private fun TurnCard(level: Int, cueText: String?, word: String?, onListen: () -> Unit) {
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
                Spacer(Modifier.height(Sizes.gapSmall))
                // Nothing to say at level 0: the invitation is the whole of the cue.
                QuietButton("Άκου ξανά", onClick = onListen, icon = Icons.Rounded.VolumeUp, enabled = level > 0)
            }
        }
    }
}

/** Wide enough to hold a whole turn at his text size, narrow enough to show which side said it. */
private const val BUBBLE_WIDTH = 0.9f

