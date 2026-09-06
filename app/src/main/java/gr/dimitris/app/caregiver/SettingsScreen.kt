package gr.dimitris.app.caregiver

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gr.dimitris.app.LocalAppGraph
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.secrets.SecretStore
import gr.dimitris.app.core.settings.Settings
import gr.dimitris.app.ui.components.DimitrisScreen
import gr.dimitris.app.ui.components.QuietButton
import gr.dimitris.app.ui.theme.Sizes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One of the two hands, as a 72dp target: a chip the size of a chip is not a caregiver's tap either. */
@Composable
private fun HandChip(label: String, value: String, chosen: String, modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    FilterChip(
        selected = chosen == value,
        onClick = { onPick(value) },
        label = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        shape = RoundedCornerShape(Sizes.corner),
        modifier = modifier.heightIn(min = Sizes.touchMin),
    )
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val graph = LocalAppGraph.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rate by graph.settings.speechRate.collectAsStateWithLifecycle(initialValue = Settings.DEFAULT_RATE)
    val lock by graph.settings.caregiverLock.collectAsStateWithLifecycle(initialValue = false)
    var draftRate by remember(rate) { mutableFloatStateOf(rate) }
    val lockAvailable = remember { canAuthenticate(context) }
    // A PackageManager query: asked once, not on every recomposition.
    val sttAvailable = remember { graph.stt.isAvailable }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }

    DimitrisScreen(title = "Ρυθμίσεις", onBack = onBack) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Ταχύτητα φωνής", style = MaterialTheme.typography.titleLarge)
            Slider(
                value = draftRate,
                onValueChange = { draftRate = it },
                onValueChangeFinished = { scope.launch { graph.settings.setSpeechRate(draftRate) } },
                valueRange = Settings.MIN_RATE..Settings.MAX_RATE,
                steps = 7,
            )
            Text(String.format(java.util.Locale.US, "%.1f", draftRate), style = MaterialTheme.typography.bodyLarge)
            QuietButton("Δοκίμασε", onClick = { scope.launch { graph.voice.speak("Καλημέρα Δημήτρη. Πάμε για καφέ;", draftRate) } }, icon = Icons.Rounded.VolumeUp)
            Spacer(Modifier.height(Sizes.gap))

            Text("Κλείδωμα φροντιστή", style = MaterialTheme.typography.titleLarge)
            // The whole row is the target, not just the switch: one thumb, 72dp.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchMin)
                    .clickable(enabled = lockAvailable) { scope.launch { graph.settings.setCaregiverLock(!lock) } },
            ) {
                Text(
                    if (lockAvailable) "Ζητά δακτυλικό αποτύπωμα, πρόσωπο ή το PIN της συσκευής." else "Η συσκευή δεν έχει κλείδωμα οθόνης.",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                )
                Switch(checked = lock && lockAvailable, enabled = lockAvailable,
                    onCheckedChange = { on -> scope.launch { graph.settings.setCaregiverLock(on) } })
            }
            Spacer(Modifier.height(Sizes.gap))

            Text("Ασκήσεις", style = MaterialTheme.typography.titleLarge)
            val enabled by graph.settings.enabledModules.collectAsStateWithLifecycle(initialValue = emptySet())
            graph.modules.forEach { m ->
                val moduleOn = m.id in enabled
                // Whole row, 72dp, like the lock above: one thumb, no aiming at the switch.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchMin)
                        .clickable { scope.launch { graph.settings.setModuleEnabled(m.id, !moduleOn) } },
                ) {
                    Text(m.titleGreek, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = moduleOn, onCheckedChange = { on -> scope.launch { graph.settings.setModuleEnabled(m.id, on) } })
                }
                // The one module that is off until someone says so. The hand it exercises is the one
                // the stroke took, and how hard to push it is not an app's decision.
                if (m.id == ModuleId.ARCADE) {
                    Text(
                        "Ενεργοποίησέ το αφού μιλήσεις με τον φυσιοθεραπευτή.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(Sizes.gapSmall))

            // Which hand «Γράψε» tells him to use. It is the one thing on the writing screen he
            // cannot work out for himself, and the wrong answer sends a hemiplegic hand at the glass.
            Text("Χέρι για γράψιμο", style = MaterialTheme.typography.bodyLarge)
            val hand by graph.settings.traceHand.collectAsStateWithLifecycle(initialValue = Settings.HAND_LEFT)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchMin),
            ) {
                HandChip("Αριστερό", Settings.HAND_LEFT, hand, Modifier.weight(1f)) { scope.launch { graph.settings.setTraceHand(it) } }
                Spacer(Modifier.width(Sizes.gapSmall))
                HandChip("Δεξί", Settings.HAND_RIGHT, hand, Modifier.weight(1f)) { scope.launch { graph.settings.setTraceHand(it) } }
            }
            Spacer(Modifier.height(Sizes.gap))

            Text("Αναγνώριση ομιλίας (δοκιμαστικό)", style = MaterialTheme.typography.titleLarge)
            val stt by graph.settings.sttEnabled.collectAsStateWithLifecycle(initialValue = false)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchMin)
                    .clickable(enabled = sttAvailable) { scope.launch { graph.settings.setSttEnabled(!stt) } },
            ) {
                Text(
                    if (sttAvailable) "Δείχνει τι άκουσε το τηλέφωνο. Ποτέ δεν τον κόβει." else "Η συσκευή δεν έχει αναγνώριση ομιλίας.",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                )
                Switch(checked = stt && sttAvailable, enabled = sttAvailable, onCheckedChange = { on -> scope.launch { graph.settings.setSttEnabled(on) } })
            }
            Spacer(Modifier.height(Sizes.gap))

            ClaudeSection()
            Spacer(Modifier.height(Sizes.gap))

            Text("Σχετικά", style = MaterialTheme.typography.titleLarge)
            Text("Η εφαρμογή του Δημήτρη, έκδοση $version. Φτιαγμένη από φίλους, για έναν φίλο.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(Sizes.gapSmall))
            Text(
                "Εικονογράμματα: ARASAAC (arasaac.org), δημιουργός Sergio Palao, Κυβέρνηση της Αραγονίας, άδεια CC BY-NC-SA.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * The optional advisor's key and model. Everything else in this app works with the phone in flight
 * mode; this section is the one place that turns that off, so it says what it does and it is the
 * last thing before «Σχετικά» rather than the first thing a caregiver meets.
 *
 * The key is never shown. What is on screen is [SecretStore.mask] of it — enough to tell two keys
 * apart, useless to anyone reading over a shoulder — and the field it is typed into is a password
 * field. Reading and writing the encrypted file both happen off the main thread.
 */
@Composable
private fun ClaudeSection() {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    val storedModel by graph.settings.claudeModel.collectAsStateWithLifecycle(initialValue = Settings.DEFAULT_CLAUDE_MODEL)
    var saved by remember { mutableStateOf<String?>(null) }   // the masked stored key, null = none
    var draft by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    // Null until the field is touched, and after that the field owns itself: a text field fed
    // straight from a DataStore flow fights the keyboard, because the value that comes back is
    // trimmed and arrives a frame late, and the caret jumps. Emptying it is how you get the default
    // back, which is why an empty draft must not be refilled from the flow either.
    var modelDraft by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        saved = withContext(Dispatchers.IO) { runCatching { graph.secrets.getClaudeKey() }.getOrNull() }
            ?.let { SecretStore.mask(it) }
    }

    Text("Claude", style = MaterialTheme.typography.titleLarge)
    Text(
        "Προαιρετικό. Με κλειδί, η οθόνη «Πρόοδος» μπορεί να ζητήσει συμβουλές. Στέλνονται μόνο λόγια — ποτέ φωνή ή φωτογραφίες.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(Sizes.gapSmall))
    Text(
        saved?.let { "Αποθηκευμένο κλειδί: $it" } ?: "Δεν υπάρχει κλειδί.",
        style = MaterialTheme.typography.bodyLarge,
    )
    Spacer(Modifier.height(Sizes.gapSmall))
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it; note = "" },
        label = { Text("Κλειδί") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchMin),
    )
    Spacer(Modifier.height(Sizes.gapSmall))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        QuietButton("Αποθήκευση κλειδιού", enabled = draft.isNotBlank(), modifier = Modifier.weight(1f), onClick = {
            val typed = draft
            scope.launch {
                val ok = withContext(Dispatchers.IO) { runCatching { graph.secrets.setClaudeKey(typed) }.isSuccess }
                if (ok) {
                    saved = SecretStore.mask(typed)
                    draft = ""
                    note = "Το κλειδί αποθηκεύτηκε."
                } else {
                    note = "Δεν μπόρεσα να αποθηκεύσω το κλειδί."
                }
            }
        })
        Spacer(Modifier.width(Sizes.gapSmall))
        QuietButton("Διαγραφή", enabled = saved != null, modifier = Modifier.weight(1f), onClick = {
            scope.launch {
                val ok = withContext(Dispatchers.IO) { runCatching { graph.secrets.setClaudeKey(null) }.isSuccess }
                if (ok) {
                    saved = null
                    draft = ""
                    note = "Το κλειδί διαγράφηκε."
                } else {
                    note = "Δεν μπόρεσα να διαγράψω το κλειδί."
                }
            }
        })
    }
    if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodyMedium)

    Spacer(Modifier.height(Sizes.gapSmall))
    OutlinedTextField(
        value = modelDraft ?: storedModel,
        onValueChange = { typed ->
            modelDraft = typed
            scope.launch { graph.settings.setClaudeModel(typed) }
        },
        label = { Text("Μοντέλο") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchMin),
    )
    Text(
        "Άφησέ το κενό για ${Settings.DEFAULT_CLAUDE_MODEL}.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
