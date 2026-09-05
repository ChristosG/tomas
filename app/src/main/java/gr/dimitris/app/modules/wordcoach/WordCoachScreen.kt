package gr.dimitris.app.modules.wordcoach

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import gr.dimitris.app.LocalAppGraph
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.ui.components.BigButton
import gr.dimitris.app.ui.components.ButtonTone
import gr.dimitris.app.ui.components.DimitrisScreen
import gr.dimitris.app.ui.components.PictureCard
import gr.dimitris.app.ui.components.QuietButton
import gr.dimitris.app.ui.components.SuccessMark
import gr.dimitris.app.ui.theme.Sizes

@Composable
fun WordCoachScreen(items: List<Item>, sessionId: String?, onDone: () -> Unit, onLeave: () -> Unit) {
    val graph = LocalAppGraph.current
    val vm: WordCoachViewModel = viewModel(
        key = "wordcoach-${sessionId ?: "practice"}-${items.size}-${items.firstOrNull()?.id}",
    ) { WordCoachViewModel(graph, items, sessionId) }
    val s by vm.state.collectAsStateWithLifecycle()
    // Navigating away — «Μίλα», or the session moving on — is not a back press: the ViewModel is
    // still alive on the back stack, so it is told to drop its take and stop its sound itself.
    DisposableEffect(vm) { onDispose { vm.screenGone() } }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.toggleRecording() else vm.micDenied()
    }
    // Recognition opens the microphone too, so it asks for the same permission before it starts.
    val askListen = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.listen() else vm.micDenied()
    }

    LaunchedEffect(s.done) { if (s.done) onDone() }

    DimitrisScreen(
        title = "Λέξεις ${s.index + 1}/${s.total}",
        // Back is "I want out", not "I finished": the module drops what it was doing and says so.
        onBack = { vm.leave(onLeave) },
        bottom = {
            if (s.confirmed) {
                BigButton("Επόμενο", onClick = vm::next, tone = ButtonTone.Success)
            } else {
                Row {
                    BigButton("Βοήθεια", onClick = vm::hint, tone = ButtonTone.Secondary, enabled = s.canHint, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(Sizes.gapSmall))
                    BigButton("Το είπα!", onClick = vm::confirm, tone = ButtonTone.Success, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(Sizes.gapSmall))
                QuietButton("Παράλειψη", onClick = vm::skip)
            }
        },
    ) {
        // A big picture, a big cue and up to four buttons do not always fit a small screen at
        // his text size: scrolling is better than a button he cannot reach.
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                PictureCard(imageFile = s.item.imagePath?.let { graph.files.resolve(it) }, label = if (s.showsWord) s.item.text else null, onClick = vm::repeatCue,
                    modifier = Modifier.fillMaxWidth(0.7f))
                SuccessMark(visible = s.confirmed)
            }
            Spacer(Modifier.height(Sizes.gapSmall))
            if (s.level in 1..2) {
                Text(s.cueText ?: "", style = MaterialTheme.typography.displayLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(Sizes.gapSmall))
            // Once he has said it, the word is done: only listening again and "Επόμενο" are left.
            Row {
                // Nothing to say at level 0: the picture is the cue.
                QuietButton("Άκου", onClick = vm::repeatCue, icon = Icons.Rounded.VolumeUp, enabled = s.level > 0, modifier = Modifier.weight(1f))
                if (!s.confirmed) {
                    Spacer(Modifier.width(Sizes.gapSmall))
                    QuietButton(
                        if (s.isRecording) "Στοπ" else "Πες το",
                        onClick = { askMic.launch(Manifest.permission.RECORD_AUDIO) },
                        icon = if (s.isRecording) Icons.Rounded.Stop else Icons.Rounded.Mic,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (!s.confirmed && s.selfRecordingPath != null && !s.isRecording) {
                Spacer(Modifier.height(Sizes.gapSmall))
                QuietButton("Σύγκριση", onClick = vm::playComparison, icon = Icons.Rounded.Compare)
            }
            if (!s.confirmed && s.sttOn) {
                Spacer(Modifier.height(Sizes.gapSmall))
                QuietButton(
                    if (s.listening) "Ακούω..." else "Άκουσέ με",
                    onClick = { askListen.launch(Manifest.permission.RECORD_AUDIO) },
                    icon = Icons.Rounded.Hearing,
                )
                if (s.heard != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            // A miss is the phone's uncertainty, never a verdict on how he said it.
                            if (s.heardMatched) "Άκουσα «${s.heard}». Μπράβο!" else "Άκουσα «${s.heard}». Το τηλέφωνο δεν είναι σίγουρο.",
                            style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
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
}
