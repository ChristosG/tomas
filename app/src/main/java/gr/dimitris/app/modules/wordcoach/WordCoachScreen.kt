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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
fun WordCoachScreen(items: List<Item>, sessionId: String?, onDone: () -> Unit) {
    val graph = LocalAppGraph.current
    val vm: WordCoachViewModel = viewModel(key = "wordcoach-${sessionId ?: "practice"}-${items.size}") { WordCoachViewModel(graph, items, sessionId) }
    val s by vm.state.collectAsStateWithLifecycle()
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) vm.toggleRecording() }

    LaunchedEffect(s.done) { if (s.done) onDone() }

    DimitrisScreen(
        title = "Λέξεις ${s.index + 1}/${s.total}",
        onBack = onDone,
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
        Row {
            QuietButton("Άκου", onClick = vm::repeatCue, icon = Icons.Rounded.VolumeUp, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(Sizes.gapSmall))
            QuietButton(
                if (s.isRecording) "Στοπ" else "Πες το",
                onClick = { askMic.launch(Manifest.permission.RECORD_AUDIO) },
                icon = if (s.isRecording) Icons.Rounded.Stop else Icons.Rounded.Mic,
                modifier = Modifier.weight(1f),
            )
        }
        if (s.selfRecordingPath != null && !s.isRecording) {
            Spacer(Modifier.height(Sizes.gapSmall))
            QuietButton("Σύγκριση", onClick = vm::playComparison, icon = Icons.Rounded.Compare)
        }
        if (s.sttOn) {
            Spacer(Modifier.height(Sizes.gapSmall))
            QuietButton(if (s.listening) "Ακούω..." else "Άκουσέ με", onClick = vm::listen, icon = Icons.Rounded.Hearing)
            if (s.heard != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (s.heardMatched) "Άκουσα «${s.heard}». Μπράβο!" else "Άκουσα «${s.heard}». Δοκίμασε ξανά αν θέλεις.",
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
