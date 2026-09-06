package gr.dimitris.app.caregiver

import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gr.dimitris.app.LocalAppGraph
import gr.dimitris.app.core.secrets.SecretStore
import gr.dimitris.app.core.sync.SyncState
import gr.dimitris.app.ui.components.BigButton
import gr.dimitris.app.ui.components.DimitrisScreen
import gr.dimitris.app.ui.components.QuietButton
import gr.dimitris.app.ui.theme.Sizes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where the two caregiver phones are pointed at the family's own server, and where one of them is
 * told to go and fetch. Everything here is behind the caregiver gate already; Dimitris' own screens
 * never mention any of it.
 *
 * The token is treated exactly like the Claude key: a password field so the keyboard does not learn
 * it, masked on screen so a shoulder cannot read it, and kept in the encrypted store rather than in
 * the settings — the backup zip is handed to other machines and must not carry it.
 */
@Composable
fun SyncScreen(onBack: () -> Unit) {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    val url by graph.settings.syncUrl.collectAsStateWithLifecycle(initialValue = "")
    val cursor by graph.settings.syncCursor.collectAsStateWithLifecycle(initialValue = 0L)
    val lastAt by graph.settings.lastSyncAt.collectAsStateWithLifecycle(initialValue = 0L)
    val state by graph.sync.state.collectAsStateWithLifecycle(initialValue = SyncState())

    // Null until the field is touched, and after that the field owns itself: a text field fed
    // straight from a DataStore flow fights the keyboard and the caret jumps.
    var urlDraft by remember { mutableStateOf<String?>(null) }
    var savedToken by remember { mutableStateOf<String?>(null) }
    var tokenDraft by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        savedToken = withContext(Dispatchers.IO) { runCatching { graph.secrets.getSyncToken() }.getOrNull() }
            ?.let { SecretStore.mask(it) }
    }

    DimitrisScreen(
        title = "Συγχρονισμός",
        onBack = onBack,
        bottom = {
            // Enabled on what is *typed*, and the draft is saved by the button itself. The address
            // field only writes when it loses focus, and a caregiver who types the address and
            // reaches straight for the button used to find it greyed out with nothing said.
            val typedUrl = (urlDraft ?: url).trim()
            BigButton(
                if (state.running) "Συγχρονίζω…" else "Συγχρόνισε τώρα",
                enabled = !state.running && typedUrl.isNotBlank(),
                icon = Icons.Rounded.Sync,
                onClick = {
                    scope.launch {
                        if (typedUrl != url) graph.settings.setSyncUrl(typedUrl)
                        graph.sync.syncNow()
                    }
                },
                modifier = Modifier.semantics { testTag = "syncNow" },
            )
        },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(
                "Η διεύθυνση και το κλειδί του δικού μας διακομιστή. Τα ίδια ακριβώς και στα δύο τηλέφωνα.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Sizes.gapSmall))

            OutlinedTextField(
                value = urlDraft ?: url,
                onValueChange = { typed -> urlDraft = typed },
                label = { Text("Διεύθυνση") },
                placeholder = { Text("https://sync.example.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                // Written when the field is left, not on every keystroke: a half-typed address
                // stored is a sync that fails for a reason nobody can see.
                modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchMin)
                    .semantics { testTag = "syncUrl" }
                    .onFocusChanged { focus ->
                        if (!focus.isFocused) urlDraft?.let { typed -> scope.launch { graph.settings.setSyncUrl(typed) } }
                    },
            )
            Spacer(Modifier.height(Sizes.gapSmall))

            Text(
                savedToken?.let { "Αποθηκευμένο κλειδί: $it" } ?: "Δεν υπάρχει κλειδί.",
                style = MaterialTheme.typography.bodyLarge,
            )
            OutlinedTextField(
                value = tokenDraft,
                onValueChange = { tokenDraft = it; note = "" },
                label = { Text("Κλειδί") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                // A password keyboard, not just a masked one: otherwise the token is learned into
                // the personal dictionary and offered as a suggestion in other apps.
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchMin).semantics { testTag = "syncToken" },
            )
            Spacer(Modifier.height(Sizes.gapSmall))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                QuietButton("Αποθήκευση κλειδιού", enabled = tokenDraft.isNotBlank(), modifier = Modifier.weight(1f), onClick = {
                    val typed = tokenDraft
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            runCatching { graph.secrets.setSyncToken(typed) }
                                .onFailure { graph.errors.record("sync token save", it) }
                                .isSuccess
                        }
                        if (ok) {
                            savedToken = SecretStore.mask(typed)
                            tokenDraft = ""
                            note = "Το κλειδί αποθηκεύτηκε."
                        } else {
                            note = "Δεν μπόρεσα να αποθηκεύσω το κλειδί."
                        }
                    }
                })
                Spacer(Modifier.width(Sizes.gapSmall))
                QuietButton("Διαγραφή", enabled = savedToken != null, modifier = Modifier.weight(1f), onClick = {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            runCatching { graph.secrets.setSyncToken(null) }
                                .onFailure { graph.errors.record("sync token delete", it) }
                                .isSuccess
                        }
                        if (ok) {
                            savedToken = null
                            tokenDraft = ""
                            note = "Το κλειδί διαγράφηκε."
                        } else {
                            note = "Δεν μπόρεσα να διαγράψω το κλειδί."
                        }
                    }
                })
            }
            if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(Sizes.gap))

            Text("Κατάσταση", style = MaterialTheme.typography.titleLarge)
            Text(
                if (lastAt == 0L) "Δεν έχει γίνει συγχρονισμός ακόμα." else "Τελευταίος συγχρονισμός: ${stamp(lastAt)}.",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.semantics { testTag = "syncStatus" },
            )
            Text("Δείκτης διακομιστή: $cursor.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (state.line.isNotBlank()) {
                Spacer(Modifier.height(Sizes.gapSmall))
                Text(state.line, style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.semantics { testTag = "syncResult" })
            }
            val errors = state.report?.errors.orEmpty()
            if (errors.isNotEmpty()) {
                Spacer(Modifier.height(Sizes.gapSmall))
                Text("Προβλήματα", style = MaterialTheme.typography.titleLarge)
                errors.forEach { line ->
                    Text("• $line", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private fun stamp(at: Long): String = SimpleDateFormat("d/M HH:mm", Locale.US).format(Date(at))
