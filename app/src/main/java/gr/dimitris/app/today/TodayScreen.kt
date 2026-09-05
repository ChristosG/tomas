package gr.dimitris.app.today

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gr.dimitris.app.LocalAppGraph
import gr.dimitris.app.caregiver.authenticateCaregiver
import gr.dimitris.app.caregiver.canAuthenticate
import gr.dimitris.app.core.speech.openTtsInstaller
import gr.dimitris.app.ui.components.BigButton
import gr.dimitris.app.ui.components.DimitrisScreen
import gr.dimitris.app.ui.components.QuietButton
import gr.dimitris.app.ui.components.longHold
import gr.dimitris.app.ui.theme.Sizes
import kotlinx.coroutines.launch

const val CAREGIVER_HOLD_MS = 2000L

@Composable
fun TodayScreen(onStart: () -> Unit, onCaregiver: () -> Unit) {
    val graph = LocalAppGraph.current
    val context = LocalContext.current
    // Null in a preview or a bare-Activity host: the caregiver area then opens without the lock.
    val activity = LocalActivity.current as? FragmentActivity
    val scope = rememberCoroutineScope()
    var greekVoice by remember { mutableStateOf(true) }
    var askCaregiver by remember { mutableStateOf(false) }
    val lock by graph.settings.caregiverLock.collectAsStateWithLifecycle(initialValue = false)

    // Re-checked on every resume, so coming back from the voice installer clears the card.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        scope.launch { greekVoice = graph.tts.isGreekAvailable() }
    }

    DimitrisScreen(
        bottom = { BigButton("Ξεκίνα", onClick = onStart, icon = Icons.Rounded.PlayArrow) },
    ) {
        Text(
            "Δημήτρης",
            style = MaterialTheme.typography.displayLarge,
            modifier = Modifier
                .semantics { testTag = "title" }
                .longHold(CAREGIVER_HOLD_MS) { askCaregiver = true },
        )
        Spacer(Modifier.height(Sizes.gapSmall))
        Text("Καλώς ήρθες. Πάτα «Ξεκίνα» όταν είσαι έτοιμος.", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(Sizes.gap))
        if (!greekVoice) {
            TtsMissingCard(onInstall = { openTtsInstaller(activity ?: context) })
        }
    }

    if (askCaregiver) {
        AlertDialog(
            onDismissRequest = { askCaregiver = false },
            title = { Text("Λειτουργία φροντιστή;", style = MaterialTheme.typography.titleLarge) },
            confirmButton = {
                TextButton(modifier = Modifier.heightIn(min = Sizes.touchMin), onClick = {
                    askCaregiver = false
                    scope.launch {
                        // No FragmentActivity means no BiometricPrompt; the area still opens.
                        val allowed = activity == null || !lock || !canAuthenticate(activity) || authenticateCaregiver(activity)
                        if (allowed) onCaregiver()
                    }
                }) { Text("Ναι", style = MaterialTheme.typography.labelLarge) }
            },
            dismissButton = {
                TextButton(modifier = Modifier.heightIn(min = Sizes.touchMin), onClick = { askCaregiver = false }) {
                    Text("Όχι", style = MaterialTheme.typography.labelLarge)
                }
            },
        )
    }
}

@Composable
private fun TtsMissingCard(onInstall: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Λείπει η ελληνική φωνή", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text("Χωρίς αυτή το τηλέφωνο δεν μπορεί να μιλήσει. Πάτα για να την εγκαταστήσεις.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            QuietButton("Εγκατάσταση φωνής", onClick = onInstall)
        }
    }
}
