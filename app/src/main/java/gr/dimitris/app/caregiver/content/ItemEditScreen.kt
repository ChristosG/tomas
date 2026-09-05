package gr.dimitris.app.caregiver.content

import android.Manifest
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import gr.dimitris.app.LocalAppGraph
import gr.dimitris.app.core.data.Category
import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.ui.components.BigButton
import gr.dimitris.app.ui.components.ButtonTone
import gr.dimitris.app.ui.components.DimitrisScreen
import gr.dimitris.app.ui.components.QuietButton
import gr.dimitris.app.ui.theme.Sizes
import java.io.File

/** Derived, not spelled out, so a renamed package or a build suffix cannot break the camera. */
fun fileAuthority(context: Context): String = "${context.packageName}.files"

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ItemEditScreen(itemId: String?, onClose: () -> Unit) {
    val graph = LocalAppGraph.current
    val context = LocalContext.current
    val vm: ItemEditViewModel = viewModel(key = itemId ?: "new") { ItemEditViewModel(graph, itemId) }
    val s by vm.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? -> uri?.let(vm::photoPicked) }
    // Survives the process death that taking a photo can cause, so the picture is not lost.
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        cameraPath?.let { vm.photoTaken(File(it), ok) }
    }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.toggleRecording() else vm.micDenied()
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val f = graph.files.newPhotoFile()
            cameraPath = f.absolutePath
            takePhoto.launch(FileProvider.getUriForFile(context, fileAuthority(context), f))
        } else {
            vm.cameraDenied()
        }
    }

    // Leaving the editor stops whatever it started saying or playing.
    DisposableEffect(Unit) { onDispose { graph.voice.quiet() } }

    DimitrisScreen(
        title = if (s.isNew) "Νέα λέξη" else "Επεξεργασία",
        onBack = onClose,
        bottom = {
            // Beside the button that was refused, not at the far end of a long scrolling form: a save
            // that does nothing and says why several hundred dp below is a save that says nothing.
            if (s.error != null) {
                Text(s.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(Sizes.gapSmall))
            }
            BigButton("Αποθήκευση", onClick = { vm.save(onClose) }, tone = ButtonTone.Success, enabled = !s.saving)
        },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            OutlinedTextField(
                value = s.text, onValueChange = vm::setText, singleLine = true,
                label = { Text("Λέξη ή φράση") }, textStyle = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Sizes.gapSmall))
            Row {
                QuietButton("Άκου", onClick = vm::speakWithTts, icon = Icons.Rounded.VolumeUp, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(Sizes.gap))

            Text("Είδος", style = MaterialTheme.typography.titleLarge)
            Row {
                KindChip("Λέξη", s.kind == ItemKind.WORD) { vm.setKind(ItemKind.WORD) }
                Spacer(Modifier.width(8.dp))
                KindChip("Φράση", s.kind == ItemKind.PHRASE) { vm.setKind(ItemKind.PHRASE) }
            }
            Spacer(Modifier.height(Sizes.gap))

            Text("Κατηγορία", style = MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Category.entries.forEach { c -> KindChip(c.greek, s.category == c) { vm.setCategory(c) } }
            }
            Spacer(Modifier.height(Sizes.gap))

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Στα αγαπημένα του πίνακα", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = s.pinned, onCheckedChange = vm::setPinned)
            }
            Spacer(Modifier.height(Sizes.gap))

            OutlinedTextField(
                value = s.priceText, onValueChange = vm::setPriceText, singleLine = true,
                label = { Text("Τιμή (€), π.χ. 3,50") }, supportingText = { Text("Για τις ασκήσεις με ευρώ. Άφησέ το κενό αν δεν έχει.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Sizes.gap))

            Text("Φωτογραφία", style = MaterialTheme.typography.titleLarge)
            if (s.imagePath != null) {
                AsyncImage(model = graph.files.resolve(s.imagePath!!), contentDescription = null, contentScale = ContentScale.Fit,
                    error = rememberVectorPainter(Icons.Rounded.Image),
                    modifier = Modifier.fillMaxWidth().height(220.dp))
                Spacer(Modifier.height(8.dp))
            }
            Row {
                QuietButton("Κάμερα", onClick = { askCamera.launch(Manifest.permission.CAMERA) }, icon = Icons.Rounded.CameraAlt, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                QuietButton("Γκαλερί", onClick = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    icon = Icons.Rounded.PhotoLibrary, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(Sizes.gap))

            Text("Φωνή (η δική σου, ως πρότυπο)", style = MaterialTheme.typography.titleLarge)
            Row {
                BigButton(
                    if (s.isRecording) "Στοπ" else "Ηχογράφηση",
                    onClick = { askMic.launch(Manifest.permission.RECORD_AUDIO) },
                    icon = if (s.isRecording) Icons.Rounded.Stop else Icons.Rounded.Mic,
                    tone = if (s.isRecording) ButtonTone.Secondary else ButtonTone.Primary,
                    modifier = Modifier.weight(1f),
                )
                if (s.recordingPath != null && !s.isRecording) {
                    Spacer(Modifier.width(8.dp))
                    QuietButton("Άκου", onClick = vm::playRecording, icon = Icons.Rounded.PlayArrow, modifier = Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(Sizes.gap))

            Text("Πρώτη συλλαβή", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = s.firstSyllableOverride, onValueChange = vm::setOverride, singleLine = true,
                label = { Text(s.autoSyllable?.let { "Αυτόματα: $it" } ?: "Αυτόματα: (άγνωστη)") },
                supportingText = { Text("Συμπλήρωσε μόνο αν το αυτόματο είναι λάθος.") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Sizes.gap))

            if (!s.isNew) {
                QuietButton("Διαγραφή", onClick = { confirmDelete = true }, icon = Icons.Rounded.Delete)
                Spacer(Modifier.height(Sizes.gap))
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Διαγραφή της λέξης;") },
            text = { Text("Θα φύγει από τις ασκήσεις. Το ιστορικό της μένει.") },
            confirmButton = { TextButton(modifier = Modifier.heightIn(min = Sizes.touchMin), onClick = { confirmDelete = false; vm.delete(onClose) }) { Text("Διαγραφή", style = MaterialTheme.typography.labelLarge) } },
            dismissButton = { TextButton(modifier = Modifier.heightIn(min = Sizes.touchMin), onClick = { confirmDelete = false }) { Text("Άκυρο", style = MaterialTheme.typography.labelLarge) } },
        )
    }
}

@Composable
private fun KindChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        modifier = Modifier.heightIn(min = Sizes.touchMin))
}
