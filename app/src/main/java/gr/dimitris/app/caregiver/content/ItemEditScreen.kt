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
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircle
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
import gr.dimitris.app.core.difficulty.Difficulty
import gr.dimitris.app.core.greek.Gender
import gr.dimitris.app.ui.components.BigButton
import gr.dimitris.app.ui.components.ButtonTone
import gr.dimitris.app.ui.components.DimitrisScreen
import gr.dimitris.app.ui.components.QuietButton
import gr.dimitris.app.ui.theme.Sizes
import java.io.File

/** Derived, not spelled out, so a renamed package or a build suffix cannot break the camera. */
fun fileAuthority(context: Context): String = "${context.packageName}.files"

/**
 * [onTry] is «Δοκίμασέ το»: it is handed the saved item's id and opens the word coach on that one
 * word. Defaulted to nothing so a preview or a test that hosts the editor alone still compiles;
 * [gr.dimitris.app.AppNav] is the only caller that passes it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ItemEditScreen(itemId: String?, onClose: () -> Unit, onTry: (String) -> Unit = {}) {
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
    val askSungMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.toggleSungRecording() else vm.micDenied()
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
        // From the route she opened, not from whether the row exists yet. «Δοκίμασέ το» saves a
        // draft under her thumb so the word coach has something to run, and a header that changed
        // to «Επεξεργασία» while she was still filling the form in would read as a different screen.
        title = if (itemId == null) "Νέα λέξη" else "Επεξεργασία",
        onBack = onClose,
        bottom = {
            // Beside the button that was refused, not at the far end of a long scrolling form: a save
            // that does nothing and says why several hundred dp below is a save that says nothing.
            if (s.error != null) {
                Text(s.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(Sizes.gapSmall))
            }
            // Chris added a word and had no way of seeing it in use: «he would have to use the app
            // for hours until it randomly appears». One tap runs it, and back comes here.
            QuietButton(
                "Δοκίμασέ το", onClick = { vm.tryIt(onTry) }, icon = Icons.Rounded.PlayCircle,
                enabled = s.canTry,
            )
            Spacer(Modifier.height(Sizes.gapSmall))
            BigButton("Αποθήκευση", onClick = { vm.save { onClose() } }, tone = ButtonTone.Success, enabled = !s.saving)
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
            // «Τραγούδι» is the one category that takes the item *out* of two places he uses every
            // day, and nothing on this form said so: a caregiver who filed «θέλω καφέ» under it would
            // have watched it vanish from the talk board with no explanation. It is also why
            // «Δοκίμασέ το» is dead on a sung sentence ([ItemEditState.canTry]).
            if (s.category == Category.SINGING) {
                Text(SINGING_HINT, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(Sizes.gap))

            // Only a word is ever a noun: a phrase takes no article, and a row of chips under
            // «θέλω καφέ» would be asking her a question with no answer.
            if (s.kind == ItemKind.WORD) {
                Text("Γένος", style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // The chip that is already on clears itself: a wrong tap is taken back here,
                    // not by leaving the form.
                    GENDERS.forEach { (code, label) ->
                        KindChip(label, s.gender == code) { vm.setGender(if (s.gender == code) null else code) }
                    }
                }
                Text(
                    "Αρσενικό (ο), θηλυκό (η), ουδέτερο (το). Για τις προτάσεις με άρθρα — άφησέ το κενό αν δεν είναι ουσιαστικό.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(Sizes.gap))
            }

            Text("Δυσκολία", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (Difficulty.MIN..Difficulty.MAX).forEach { t -> KindChip("$t", s.tier == t) { vm.setTier(t) } }
            }
            Text(
                "1 εύκολη, 5 δύσκολη. Ο Δημήτρης βλέπει στις «Λέξεις» ό,τι είναι μέχρι τη δυσκολία που έχει διαλέξει.",
                style = MaterialTheme.typography.bodyMedium,
            )
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
                    // One microphone: starting the other take would be refused in Greek by Voice
                    // while the first one kept running, which reads as a broken button.
                    enabled = !s.isRecordingSung,
                    modifier = Modifier.weight(1f),
                )
                if (s.recordingPath != null && !s.isRecording) {
                    Spacer(Modifier.width(8.dp))
                    QuietButton("Άκου", onClick = vm::playRecording, icon = Icons.Rounded.PlayArrow, modifier = Modifier.weight(1f))
                }
            }
            // Only phrases are sung: the module works on whole phrases, and a sung single word
            // would be a button that records something nothing ever plays.
            if (s.kind == ItemKind.PHRASE) {
                Spacer(Modifier.height(Sizes.gapSmall))
                Text("Τραγουδισμένο (για το «Τραγούδα και πες το»)", style = MaterialTheme.typography.bodyLarge)
                Row {
                    BigButton(
                        if (s.isRecordingSung) "Στοπ" else "Τραγούδησέ το",
                        onClick = { askSungMic.launch(Manifest.permission.RECORD_AUDIO) },
                        icon = if (s.isRecordingSung) Icons.Rounded.Stop else Icons.Rounded.MusicNote,
                        tone = if (s.isRecordingSung) ButtonTone.Secondary else ButtonTone.Primary,
                        enabled = !s.isRecording,
                        modifier = Modifier.weight(1f),
                    )
                    if (s.sungPath != null && !s.isRecordingSung) {
                        Spacer(Modifier.width(8.dp))
                        QuietButton("Άκου", onClick = vm::playSung, icon = Icons.Rounded.PlayArrow, modifier = Modifier.weight(1f))
                    }
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

/**
 * The three genders as a caregiver writes them down — «Α» αρσενικό, «Θ» θηλυκό, «Ο» ουδέτερο —
 * beside the one letter the column really holds ([gr.dimitris.app.core.greek.Gender.code]).
 *
 * Greek initials on the screen and Latin ones in the database on purpose: what she taps is Greek
 * because everything she and he read is Greek, and what is stored is the enum's own code, which two
 * phones and a sync server have to agree on letter for letter.
 */
private val GENDERS: List<Pair<String, String>> =
    listOf(Gender.MASCULINE.code to "Α", Gender.FEMININE.code to "Θ", Gender.NEUTER.code to "Ο")

/**
 * What «Τραγούδι» costs, said once, under the chips.
 *
 * One line, because it is the answer to one question — "why did the card disappear?" — and the
 * caregiver reading it is not looking for a paragraph. «Λέξεις» and «ο πίνακας» are the two places it
 * is kept out of (`WordCoachModule.asks`, `TalkBoardViewModel`); the sung sentences of phase 13 are
 * the whole of what the category is for.
 */
internal const val SINGING_HINT =
    "Μόνο για το «Τραγούδα και πες το» — δεν μπαίνει στις Λέξεις ούτε στον πίνακα."

@Composable
private fun KindChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        modifier = Modifier.heightIn(min = Sizes.touchMin))
}
