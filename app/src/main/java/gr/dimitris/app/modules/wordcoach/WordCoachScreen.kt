package gr.dimitris.app.modules.wordcoach

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
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
import gr.dimitris.app.core.speech.GentleCheck
import gr.dimitris.app.core.speech.GentleCheck.Companion.SPEAK
import gr.dimitris.app.ui.components.BigButton
import gr.dimitris.app.ui.components.ButtonTone
import gr.dimitris.app.ui.components.DimitrisScreen
import gr.dimitris.app.ui.components.ListenButton
import gr.dimitris.app.ui.components.ListeningIndicator
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
            // Three actions and one primary, which is the UX rule: the green button, «Άκου» under it,
            // «Παράλειψη» at the foot. «Βοήθεια» used to share the top row and has moved up beside
            // the word it is a hint for — four buttons down here was one more than a man with one
            // working thumb should have to choose between, and the two microphones that used to be
            // among them are now one.
            //
            // «Άκου» is off only for the two things that cannot share the moment with it: the model
            // already sounding, and the microphone open — which the recogniser («Ακούω...») holds
            // just as much as a take does. A model spoken into a live recogniser is the phone
            // hearing itself.
            val canListen = !s.modelPlaying && !s.isRecording && !s.listening
            if (s.listening) {
                // The window is open. Everything else goes away: there is one thing to do, which is
                // to speak, and one button, which stops it when he decides he is finished.
                ListeningIndicator(level = s.listenLevel, onStop = vm::stopListening)
            } else if (s.confirmed) {
                ListenButton(onClick = vm::listenModel, enabled = canListen)
                Spacer(Modifier.height(Sizes.gapSmall))
                BigButton("Επόμενο", onClick = vm::next, tone = ButtonTone.Success)
            } else {
                // With recognition on, the green button is «Μίλα» until the phone has agreed with
                // him or has asked him twice — a take that checked nothing is what Chris found in
                // the field. After that «Το είπα!» is back and confirms exactly as it always did,
                // and another go is offered up in the content rather than as a fourth button here.
                when (GentleCheck.primaryFor(s.sttResolved, s.sttOn, s.canConfirm)) {
                    // One DataStore read long, on the first word only: the button is already the
                    // right size and in the right place, it simply cannot be pressed into the
                    // wrong mode yet.
                    GentleCheck.Primary.WAITING ->
                        BigButton("Το είπα!", onClick = {}, tone = ButtonTone.Success, enabled = false)
                    GentleCheck.Primary.SPEAK ->
                        BigButton(SPEAK, onClick = { askListen.launch(Manifest.permission.RECORD_AUDIO) }, icon = Icons.Rounded.Mic, tone = ButtonTone.Success)
                    GentleCheck.Primary.CONFIRM ->
                        BigButton("Το είπα!", onClick = vm::confirm, tone = ButtonTone.Success)
                }
                Spacer(Modifier.height(Sizes.gapSmall))
                // One «Άκου», and what it plays grows with what there is to hear: the model before
                // he has spoken, the model and then his own take afterwards. That second half used
                // to be a separate «Σύγκριση».
                ListenButton(onClick = vm::listenModel, enabled = canListen)
                Spacer(Modifier.height(Sizes.gapSmall))
                QuietButton("Παράλειψη", onClick = vm::skip)
            }
        },
    ) {
        // A big picture, a big cue and up to four buttons do not always fit a small screen at
        // his text size: scrolling is better than a button he cannot reach.
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                // The picture is «Άκου» with a picture on it, and it says the word at every level,
                // level 0 included: tapping the thing he is trying to name has to answer him.
                PictureCard(imageFile = s.item.imagePath?.let { graph.files.resolve(it) }, label = if (s.showsWord) s.item.text else null, onClick = vm::listenModel,
                    modifier = Modifier.fillMaxWidth(0.7f))
                SuccessMark(visible = s.confirmed)
            }
            Spacer(Modifier.height(Sizes.gapSmall))
            if (s.level in 1..2) {
                Text(s.cueText ?: "", style = MaterialTheme.typography.displayLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(Sizes.gapSmall))
            // «Βοήθεια» belongs with the word it is a hint about rather than in the bottom row, which
            // holds three actions and no more. It is still one tap and still never withheld.
            if (!s.confirmed && !s.listening) {
                QuietButton("Βοήθεια", onClick = vm::hint, enabled = s.canHint)
            }
            // The take button, on the fallback path only. Where the recogniser hands his own audio
            // back there is nothing for it to do that «Μίλα» has not already done, and two buttons
            // that both mean "speak now" is the confusion this phase exists to remove.
            if (!s.confirmed && !s.listening && !s.oneControl) {
                Spacer(Modifier.height(Sizes.gapSmall))
                QuietButton(
                    if (s.isRecording) "Στοπ" else if (s.sttOn) "Ηχογράφηση" else "Πες το",
                    onClick = { askMic.launch(Manifest.permission.RECORD_AUDIO) },
                    icon = if (s.isRecording) Icons.Rounded.Stop else Icons.Rounded.Mic,
                    // Not while the model is speaking: he hears «Άκου», reaches straight for the
                    // mic, and the take would be the phone's own voice — which «Άκου» then plays
                    // back to him as his. Not while the recogniser has the microphone either.
                    // «Στοπ» always stays live, or a take could not be closed.
                    enabled = s.isRecording || (!s.modelPlaying && !s.listening),
                )
            }
            if (s.sttOn && !s.listening) {
                // One nudge and no more. The cue has not moved, «Άκου» is where it was, and the
                // microphone is one tap away again: nothing has been taken away from him.
                if (s.nudge) {
                    Spacer(Modifier.height(Sizes.gapSmall))
                    Text(
                        GentleCheck.TRY_AGAIN, style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.secondary, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (s.heard != null) {
                    Spacer(Modifier.height(Sizes.gapSmall))
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            // A miss is the phone's uncertainty, never a verdict on how he said it.
                            if (s.heardMatched) "Άκουσα «${s.heard}». Μπράβο!" else "Άκουσα «${s.heard}». Το τηλέφωνο δεν είναι σίγουρο.",
                            style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                        )
                    }
                }
                // Once «Το είπα!» has come back, another go is still his to take — never asked of
                // him, and up here rather than as a fourth button in the bottom row.
                if (!s.confirmed && s.canConfirm && GentleCheck.primaryFor(s.sttResolved, s.sttOn, s.canConfirm) == GentleCheck.Primary.CONFIRM) {
                    Spacer(Modifier.height(Sizes.gapSmall))
                    QuietButton(
                        SPEAK, onClick = { askListen.launch(Manifest.permission.RECORD_AUDIO) },
                        icon = Icons.Rounded.Mic, enabled = !s.modelPlaying && !s.isRecording,
                    )
                }
            }
            if (s.error != null) {
                Spacer(Modifier.height(Sizes.gapSmall))
                Text(s.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
