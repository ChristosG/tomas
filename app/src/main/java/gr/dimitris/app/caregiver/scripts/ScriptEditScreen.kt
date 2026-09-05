package gr.dimitris.app.caregiver.scripts

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import gr.dimitris.app.LocalAppGraph
import gr.dimitris.app.core.data.Speaker
import gr.dimitris.app.ui.components.BigButton
import gr.dimitris.app.ui.components.ButtonTone
import gr.dimitris.app.ui.components.DimitrisScreen
import gr.dimitris.app.ui.components.QuietButton
import gr.dimitris.app.ui.theme.Sizes

@Composable
fun ScriptEditScreen(scriptId: String?, onClose: () -> Unit) {
    val graph = LocalAppGraph.current
    val vm: ScriptEditViewModel = viewModel(key = scriptId ?: "new-script") { ScriptEditViewModel(graph, scriptId) }
    val s by vm.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    // Which line asked for the microphone: the permission answer comes back without one.
    var pending by remember { mutableStateOf<Int?>(null) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val index = pending
        pending = null
        if (granted && index != null) vm.toggleRecording(index) else vm.micDenied()
    }

    // Leaving the editor stops whatever it was playing.
    DisposableEffect(Unit) { onDispose { graph.voice.quiet() } }

    DimitrisScreen(
        title = if (s.isNew) "Νέος διάλογος" else "Επεξεργασία",
        onBack = onClose,
        bottom = {
            // Beside the button that was refused, not at the far end of a long scrolling form.
            if (s.error != null) {
                Text(s.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(Sizes.gapSmall))
            }
            // A dialogue that is not there any more cannot be saved: the button would write a second
            // copy of the one she opened.
            BigButton("Αποθήκευση", onClick = { vm.save(onClose) }, tone = ButtonTone.Success, enabled = !s.saving && !s.loading && !s.notFound)
        },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (s.loading) {
                Text("Ετοιμάζω...", style = MaterialTheme.typography.headlineMedium)
            } else {
                OutlinedTextField(
                    value = s.title, onValueChange = vm::setTitle, singleLine = true,
                    label = { Text("Τίτλος, π.χ. Στην καφετέρια") }, textStyle = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(Sizes.gap))

                s.lines.forEachIndexed { i, line ->
                    LineCard(
                        line = line,
                        index = i,
                        last = i == s.lines.lastIndex,
                        recording = s.recordingIndex == i,
                        // One microphone: every other line's buttons wait until this take is closed.
                        locked = s.recordingIndex != null && s.recordingIndex != i,
                        onSpeaker = { vm.toggleSpeaker(i) },
                        onText = { vm.setLineText(i, it) },
                        // Stopping is not a permission question: it goes straight to the ViewModel,
                        // so a second launcher round trip can never close the take onto another line.
                        onRecord = {
                            if (s.recordingIndex == i) vm.toggleRecording(i)
                            else { pending = i; askMic.launch(Manifest.permission.RECORD_AUDIO) }
                        },
                        onPlay = { vm.playLine(i) },
                        onUp = { vm.moveUp(i) },
                        onDown = { vm.moveDown(i) },
                        onRemove = { vm.removeLine(i) },
                    )
                    Spacer(Modifier.height(Sizes.gapSmall))
                }

                // Twelve turns is one sitting. The button stays live past the cap on purpose: a tap
                // that says «Έως 12 γραμμές» explains itself, where a dead button would not.
                QuietButton("Προσθήκη γραμμής", onClick = vm::addLine, icon = Icons.Rounded.Add, enabled = s.recordingIndex == null)
                Spacer(Modifier.height(Sizes.gap))

                if (!s.isNew) {
                    QuietButton("Διαγραφή διαλόγου", onClick = { confirmDelete = true }, icon = Icons.Rounded.Delete, enabled = s.recordingIndex == null)
                    Spacer(Modifier.height(Sizes.gap))
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Διαγραφή του διαλόγου;") },
            text = { Text("Θα φύγει από τις ασκήσεις. Το ιστορικό του μένει.") },
            confirmButton = {
                TextButton(modifier = Modifier.heightIn(min = Sizes.touchMin), onClick = { confirmDelete = false; vm.delete(onClose) }) {
                    Text("Διαγραφή", style = MaterialTheme.typography.labelLarge)
                }
            },
            dismissButton = {
                TextButton(modifier = Modifier.heightIn(min = Sizes.touchMin), onClick = { confirmDelete = false }) {
                    Text("Άκυρο", style = MaterialTheme.typography.labelLarge)
                }
            },
        )
    }
}

/**
 * One turn. The recording row is drawn for both speakers on purpose: on the other person's line the
 * take *is* the voice Dimitris hears, and on his own line it is the model the cue ladder plays at
 * its top two levels — the same "η φωνή σου, ως πρότυπο" the word editor offers. Hiding it on his
 * lines would also mean a take could survive a speaker toggle with no way left to hear or replace it.
 */
@Composable
private fun LineCard(
    line: EditLine,
    index: Int,
    last: Boolean,
    recording: Boolean,
    locked: Boolean,
    onSpeaker: () -> Unit,
    onText: (String) -> Unit,
    onRecord: () -> Unit,
    onPlay: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(Sizes.corner),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row {
                SpeakerChip("Άλλος", line.speaker == Speaker.OTHER, enabled = !locked, onClick = { if (line.speaker != Speaker.OTHER) onSpeaker() })
                Spacer(Modifier.width(Sizes.gapSmall))
                SpeakerChip("Δημήτρης", line.speaker == Speaker.DIMITRIS, enabled = !locked, onClick = { if (line.speaker != Speaker.DIMITRIS) onSpeaker() })
            }
            Spacer(Modifier.height(Sizes.gapSmall))
            OutlinedTextField(
                value = line.text, onValueChange = onText,
                label = { Text("Γραμμή ${index + 1}") }, textStyle = MaterialTheme.typography.bodyLarge,
                enabled = !locked, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Sizes.gapSmall))
            Text(
                if (line.speaker == Speaker.OTHER) "Η φωνή σου, όπως θα την ακούσει" else "Η φωνή σου, ως πρότυπο για τη σειρά του",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row {
                QuietButton(
                    if (recording) "Στοπ" else "Ηχογράφηση",
                    onClick = onRecord,
                    icon = if (recording) Icons.Rounded.Stop else Icons.Rounded.Mic,
                    enabled = !locked,
                    modifier = Modifier.weight(1f),
                )
                if (line.hasVoice && !recording) {
                    Spacer(Modifier.width(Sizes.gapSmall))
                    QuietButton("Άκου", onClick = onPlay, icon = Icons.Rounded.PlayArrow, enabled = !locked, modifier = Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(Sizes.gapSmall))
            Row {
                LineIconButton(Icons.Rounded.ArrowUpward, "Πιο πάνω", enabled = !locked && !recording && index > 0, onClick = onUp)
                LineIconButton(Icons.Rounded.ArrowDownward, "Πιο κάτω", enabled = !locked && !recording && !last, onClick = onDown)
                Spacer(Modifier.weight(1f))
                LineIconButton(Icons.Rounded.Delete, "Διαγραφή γραμμής", enabled = !locked, onClick = onRemove)
            }
        }
    }
}

@Composable
private fun SpeakerChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick, enabled = enabled,
        label = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        modifier = Modifier.heightIn(min = Sizes.touchMin),
    )
}

@Composable
private fun LineIconButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(Sizes.touchMin)) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(Sizes.icon))
    }
}
