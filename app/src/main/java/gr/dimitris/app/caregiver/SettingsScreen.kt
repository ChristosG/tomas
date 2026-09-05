package gr.dimitris.app.caregiver

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gr.dimitris.app.LocalAppGraph
import gr.dimitris.app.core.settings.Settings
import gr.dimitris.app.ui.components.DimitrisScreen
import gr.dimitris.app.ui.components.QuietButton
import gr.dimitris.app.ui.theme.Sizes
import kotlinx.coroutines.launch

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
