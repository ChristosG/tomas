package gr.dimitris.app.caregiver

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gr.dimitris.app.LocalAppGraph
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.difficulty.Difficulty
import gr.dimitris.app.core.secrets.SecretStore
import gr.dimitris.app.core.settings.DeviceRole
import gr.dimitris.app.core.settings.Settings
import gr.dimitris.app.core.speech.OnDeviceSupport
import gr.dimitris.app.modules.singsay.Key
import gr.dimitris.app.modules.singsay.Tempo
import gr.dimitris.app.modules.trace.TraceStrictness
import gr.dimitris.app.ui.components.BigButton
import gr.dimitris.app.ui.components.DimitrisScreen
import gr.dimitris.app.ui.components.QuietButton
import gr.dimitris.app.ui.theme.Sizes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

/** The same 72dp chip, for the two answers to "whose phone is this?". */
@Composable
private fun RoleChip(label: String, value: DeviceRole, chosen: DeviceRole, modifier: Modifier = Modifier, onPick: (DeviceRole) -> Unit) {
    FilterChip(
        selected = chosen == value,
        onClick = { onPick(value) },
        label = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        shape = RoundedCornerShape(Sizes.corner),
        modifier = modifier.heightIn(min = Sizes.touchMin),
    )
}

/**
 * The same 72dp chip again, for how hard «Γράψε» marks him — three of them across one row, so the
 * label is a size down and stays on one line: «Κανονικό» broken over two lines reads as two words.
 */
@Composable
private fun StrictnessChip(label: String, value: TraceStrictness, chosen: TraceStrictness, modifier: Modifier = Modifier, onPick: (TraceStrictness) -> Unit) {
    FilterChip(
        selected = chosen == value,
        onClick = { onPick(value) },
        label = {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        shape = RoundedCornerShape(Sizes.corner),
        modifier = modifier.heightIn(min = Sizes.touchMin),
    )
}

/** The same 72dp chip, for how fast the sing-then-say melody moves. */
@Composable
private fun TempoChip(label: String, value: Tempo, chosen: Tempo, modifier: Modifier = Modifier, onPick: (Tempo) -> Unit) {
    FilterChip(
        selected = chosen == value,
        onClick = { onPick(value) },
        label = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        shape = RoundedCornerShape(Sizes.corner),
        modifier = modifier.heightIn(min = Sizes.touchMin),
    )
}

/** The same 72dp chip, for which key the sing-then-say melody sings in. */
@Composable
private fun KeyChip(label: String, value: Key, chosen: Key, modifier: Modifier = Modifier, onPick: (Key) -> Unit) {
    FilterChip(
        selected = chosen == value,
        onClick = { onPick(value) },
        label = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        shape = RoundedCornerShape(Sizes.corner),
        modifier = modifier.heightIn(min = Sizes.touchMin),
    )
}

/**
 * What this phone can do about Greek without a connection, and the one tap that fixes it.
 *
 * The whole row exists because of Chris' field report. His phone answered `ERROR_LANGUAGE_NOT_SUPPORTED`
 * for a day and then `ERROR_NETWORK`, and the only thing the app ever said was «Η αναγνώριση δεν
 * λειτούργησε. Δες τις ρυθμίσεις.» — which sent him to a settings screen that said nothing about
 * either and offered nothing to do. Now the screen he is sent to answers the question: the Greek model
 * is installed, or missing and one button away, or being fetched, or not something this phone can do.
 *
 * [LISTEN_STATE_TAG] is how the instrumented test finds the line. The emulator has no speech engine
 * at all, so what it proves is the honest bottom of the ladder: «δεν υποστηρίζεται», and no button.
 */
@Composable
private fun GreekModelRow() {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()

    // Null until the engine has been asked. The line is not drawn at all until then: a row that said
    // «δεν υποστηρίζεται» for a beat on a phone that does support it would be a lie with a timer on it.
    var engine by remember { mutableStateOf<OnDeviceSupport.Engine?>(null) }

    /** A download this screen started, or one the engine was already running when we looked. */
    var downloading by remember { mutableStateOf(false) }

    suspend fun reread() {
        engine = graph.stt.engine(fresh = true)
        downloading = graph.stt.greekPending()
    }

    LaunchedEffect(Unit) { reread() }

    // While it says «λήψη…», ask again every few seconds. The listener on Android 14 reports the
    // finish and this poll is the belt to its braces; on Android 13 there is no listener at all and
    // the poll is the only way the row ever stops saying «λήψη…».
    LaunchedEffect(downloading) {
        var rounds = 0
        while (downloading && rounds < DOWNLOAD_POLLS) {
            delay(DOWNLOAD_POLL_MS)
            rounds++
            engine = graph.stt.engine(fresh = true)
            if (engine == OnDeviceSupport.Engine.ON_DEVICE) break
            downloading = graph.stt.greekPending()
        }
        // A download nobody can see the end of must not leave the row claiming for ever that one is
        // running: it goes back to what the engine says, which is «δεν υπάρχουν» and the button again.
        if (rounds >= DOWNLOAD_POLLS) downloading = false
    }

    val resolved = engine ?: return
    val greek = OnDeviceSupport.greekFor(resolved, downloading)
    Text(
        OnDeviceSupport.lineFor(greek),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(LISTEN_STATE_TAG),
    )
    // Offered only when there is something to fetch and nothing already fetching it. On-device Greek
    // is what makes transcription free and offline, which is the whole of spec §13.
    if (greek == OnDeviceSupport.Greek.MISSING) {
        Spacer(Modifier.height(Sizes.gapSmall))
        BigButton(
            OnDeviceSupport.DOWNLOAD,
            onClick = {
                downloading = true
                scope.launch {
                    try {
                        graph.stt.downloadGreek()
                    } finally {
                        // Whatever it answered, the engine is the authority on what happened.
                        engine = graph.stt.engine(fresh = true)
                        downloading = graph.stt.greekPending()
                    }
                }
            },
        )
    }
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
                // The two modules that are off until someone says so ([Settings.DEFAULT_OFF]). The
                // hand the arcade exercises is the one the stroke took, and how hard to push it is
                // not an app's decision; sing-then-say is for a phrase that will not come out at
                // all, which is not where he is on most days any more (spec §13).
                val note = when (m.id) {
                    ModuleId.ARCADE -> "Ενεργοποίησέ το αφού μιλήσεις με τον φυσιοθεραπευτή."
                    ModuleId.SINGSAY -> "Για μεγάλες φράσεις που δεν βγαίνουν ακόμα."
                    else -> null
                }
                if (note != null) {
                    Text(
                        note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(Sizes.gapSmall))

            DifficultyBoundsSection()
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
            Spacer(Modifier.height(Sizes.gapSmall))

            // How near the letter his writing has to be. «Κανονικό» is the line the app is built
            // around: the letter he was asked for passes, another letter does not — a wrong shape
            // marked right is a wrong movement practised.
            Text("Αυστηρότητα γραψίματος", style = MaterialTheme.typography.bodyLarge)
            val strictness by graph.settings.traceStrictness.collectAsStateWithLifecycle(initialValue = TraceStrictness.DEFAULT)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchMin),
            ) {
                StrictnessChip("Χαλαρό", TraceStrictness.LOOSE, strictness, Modifier.weight(1f)) { scope.launch { graph.settings.setTraceStrictness(it) } }
                Spacer(Modifier.width(Sizes.gapSmall))
                StrictnessChip("Κανονικό", TraceStrictness.NORMAL, strictness, Modifier.weight(1f)) { scope.launch { graph.settings.setTraceStrictness(it) } }
                Spacer(Modifier.width(Sizes.gapSmall))
                StrictnessChip("Αυστηρό", TraceStrictness.STRICT, strictness, Modifier.weight(1f)) { scope.launch { graph.settings.setTraceStrictness(it) } }
            }
            Text(
                "Πόσο κοντά στο γράμμα πρέπει να γράψει. Άλλο γράμμα δεν περνά σε καμία ρύθμιση.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Sizes.gapSmall))

            // How fast, and in which key, «Τραγούδα και πες το» sings. His voice is not always at
            // its steadiest — a bad day wants the tune slower, or lower, and that is a caregiver's
            // call to make once, not something he has to ask for on the screen he cannot read well.
            Text("Τραγούδα", style = MaterialTheme.typography.bodyLarge)
            val tempo by graph.settings.melodyTempo.collectAsStateWithLifecycle(initialValue = Tempo.DEFAULT)
            Text(
                "Ρυθμός: Κανονικός / Αργός",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchMin),
            ) {
                TempoChip("Κανονικός", Tempo.NORMAL, tempo, Modifier.weight(1f)) { scope.launch { graph.settings.setMelodyTempo(it) } }
                Spacer(Modifier.width(Sizes.gapSmall))
                TempoChip("Αργός", Tempo.SLOW, tempo, Modifier.weight(1f)) { scope.launch { graph.settings.setMelodyTempo(it) } }
            }
            Spacer(Modifier.height(Sizes.gapSmall))

            val melodyKey by graph.settings.melodyKey.collectAsStateWithLifecycle(initialValue = Key.DEFAULT)
            Text(
                "Τόνος: Κανονικός / Χαμηλός",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchMin),
            ) {
                KeyChip("Κανονικός", Key.NORMAL, melodyKey, Modifier.weight(1f)) { scope.launch { graph.settings.setMelodyKey(it) } }
                Spacer(Modifier.width(Sizes.gapSmall))
                KeyChip("Χαμηλός", Key.LOW, melodyKey, Modifier.weight(1f)) { scope.launch { graph.settings.setMelodyKey(it) } }
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
                    // What it now does, in the order he meets it: the wait, the comparison, the one
                    // gentle retry, and the promise that «Το είπα!» always comes back.
                    if (sttAvailable) {
                        "Περιμένει να μιλήσει, δείχνει ότι ακούει και συγκρίνει με τη λέξη. " +
                            "Σε αστοχία προτείνει μία ακόμη προσπάθεια· μετά το «Το είπα!» επιστρέφει. Ποτέ δεν τον κόβει."
                    } else {
                        "Η συσκευή δεν έχει αναγνώριση ομιλίας."
                    },
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                )
                Switch(checked = stt && sttAvailable, enabled = sttAvailable, onCheckedChange = { on -> scope.launch { graph.settings.setSttEnabled(on) } })
            }
            GreekModelRow()
            Spacer(Modifier.height(Sizes.gap))

            // Asked once on the very first launch, and changed here when the answer was wrong or
            // when a phone changes hands. It decides where the app opens and nothing else.
            Text("Τίνος είναι αυτό το τηλέφωνο;", style = MaterialTheme.typography.titleLarge)
            val role by graph.settings.deviceRole.collectAsStateWithLifecycle(initialValue = DeviceRole.DIMITRIS)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchMin),
            ) {
                RoleChip("Του Δημήτρη", DeviceRole.DIMITRIS, role, Modifier.weight(1f)) { scope.launch { graph.settings.setDeviceRole(it) } }
                Spacer(Modifier.width(Sizes.gapSmall))
                RoleChip("Φροντιστή", DeviceRole.CAREGIVER, role, Modifier.weight(1f)) { scope.launch { graph.settings.setDeviceRole(it) } }
            }
            Text(
                "Ισχύει από το επόμενο άνοιγμα της εφαρμογής.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
 * «Όρια δυσκολίας»: the fence the caregivers put around the five dots he sets himself (spec §13).
 *
 * Wide open by default — 1 to 5 for every module — because the dots exist so that nobody has to be
 * asked. The fence is for the cases where that is not true, and there are real ones: the arcade
 * exercises the hand the stroke took, and how small its targets may get is a physiotherapist's call
 * rather than his on a good morning; a speech therapist working through two-word sentences this month
 * does not want that month skipped.
 *
 * A bound that would cross the other one is impossible rather than refused — the buttons go dead at
 * the meeting point — and moving a bound past where he has set himself brings his own setting with
 * it, so the dots he is looking at and the exercises he gets can never disagree.
 */
@Composable
private fun DifficultyBoundsSection() {
    val graph = LocalAppGraph.current
    Text("Όρια δυσκολίας", style = MaterialTheme.typography.titleLarge)
    Text(
        "Ο Δημήτρης ρυθμίζει μόνος του τη δυσκολία στην πρώτη οθόνη κάθε άσκησης. Εδώ μπαίνουν τα όρια.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    graph.modules.forEach { m -> BoundsRow(m.id, m.titleGreek) }
}

/** One module's pair of bounds, under its Greek name: a row of numbers alone names nothing. */
@Composable
private fun BoundsRow(module: ModuleId, titleGreek: String) {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    val floor by graph.settings.difficultyFloor(module).collectAsStateWithLifecycle(initialValue = Difficulty.MIN)
    val ceiling by graph.settings.difficultyCeiling(module).collectAsStateWithLifecycle(initialValue = Difficulty.MAX)
    Spacer(Modifier.height(Sizes.gapSmall))
    Text(titleGreek, style = MaterialTheme.typography.bodyLarge)
    Stepper("Κάτω όριο", floor, canDown = floor > Difficulty.MIN, canUp = floor < ceiling) { n ->
        scope.launch { graph.settings.setDifficultyFloor(module, n) }
    }
    Stepper("Πάνω όριο", ceiling, canDown = ceiling > floor, canUp = ceiling < Difficulty.MAX) { n ->
        scope.launch { graph.settings.setDifficultyCeiling(module, n) }
    }
}

/**
 * One bound, as a word, a number and two 72 dp buttons. A stepper and not a slider: a caregiver
 * setting a limit on somebody else's practice should have to mean each step of it, and five values
 * do not need a drag.
 */
@Composable
private fun Stepper(label: String, value: Int, canDown: Boolean, canUp: Boolean, onPick: (Int) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchMin),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        QuietButton("−", onClick = { onPick(value - 1) }, enabled = canDown, iconOnly = true, modifier = Modifier.width(Sizes.touchMin))
        Text(
            value.toString(), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
            modifier = Modifier.width(Sizes.touchMin),
        )
        QuietButton("+", onClick = { onPick(value + 1) }, enabled = canUp, iconOnly = true, modifier = Modifier.width(Sizes.touchMin))
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
        // A password keyboard, not just a masked one. Without this the field is ordinary text to
        // the IME: the key would be learned into the personal dictionary and offered as a
        // suggestion in other apps, and autocorrect would be free to rewrite it on the way in.
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchMin),
    )
    Spacer(Modifier.height(Sizes.gapSmall))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        QuietButton("Αποθήκευση κλειδιού", enabled = draft.isNotBlank(), modifier = Modifier.weight(1f), onClick = {
            val typed = draft
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching { graph.secrets.setClaudeKey(typed) }
                        // A keystore that refuses is exactly the case where someone will be asked
                        // "what does Σφάλματα say?". The throwable is a keystore or IO error and
                        // carries no secret, so recording it is safe and silence is not.
                        .onFailure { graph.errors.record("claude key save", it) }
                        .isSuccess
                }
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
                val ok = withContext(Dispatchers.IO) {
                    runCatching { graph.secrets.setClaudeKey(null) }
                        .onFailure { graph.errors.record("claude key delete", it) }
                        .isSuccess
                }
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

    // Per-turn judging (spec §13). Its own consent, separate from the key: a caregiver may well want
    // the weekly advice and not want every sentence he speaks leaving the phone, and the key alone
    // must not be read as having agreed to both.
    Spacer(Modifier.height(Sizes.gapSmall))
    val judging by graph.settings.claudeJudging.collectAsStateWithLifecycle(initialValue = false)
    // Null until the encrypted store has been read, which reads as "no key" — the same first frame
    // the line above shows «Δεν υπάρχει κλειδί.» for. A switch that was live before the key is known
    // could be turned on by a caregiver who has not saved one yet.
    val hasKey = saved != null
    Row(
        verticalAlignment = Alignment.CenterVertically,
        // The whole row is the target, not just the switch: one thumb, 72dp, like the lock above.
        modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchMin)
            .clickable(enabled = hasKey) { scope.launch { graph.settings.setClaudeJudging(!judging) } },
    ) {
        Text("Έλεγχος με Claude", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        // `judging && hasKey`: the stored flag survives a deleted key, and the judge falls back to
        // local matching without one. Drawing it on while nothing is being sent would be a lie.
        Switch(checked = judging && hasKey, enabled = hasKey,
            onCheckedChange = { on -> scope.launch { graph.settings.setClaudeJudging(on) } })
    }
    Text(
        // Exactly what leaves the phone, in words a caregiver can picture. «Μόνο κείμενο» is the
        // whole of the promise: no recording, no photo, nothing about his health.
        if (hasKey) "Στέλνει μόνο κείμενο: την ερώτηση, τον στόχο και ό,τι είπε." else "Βάλε κλειδί πρώτα.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(Sizes.gapSmall))
    OutlinedTextField(
        value = modelDraft ?: storedModel,
        onValueChange = { typed -> modelDraft = typed },
        label = { Text("Μοντέλο") },
        singleLine = true,
        // Written when the field is left, not on every keystroke. Typing `claude-opus-5` used to
        // store thirteen partial model ids, and walking away mid-word left one of them stored — a
        // Greek «(σφάλμα 404)» whose cause was invisible.
        // On the graph's scope, not the screen's: losing focus is what a caregiver does by tapping
        // «Πίσω», and that same tap tears the composition down. A write launched on the screen's
        // scope was cancelled by it about half the time, and the model quietly stayed what it was.
        modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchMin)
            .onFocusChanged { focus ->
                if (!focus.isFocused) modelDraft?.let { typed -> graph.scope.launch { graph.settings.setClaudeModel(typed) } }
            },
    )
    Text(
        // Which model this field is, now that there are two. The judge's is fixed in code
        // (TurnJudge.MODEL) and deliberately not a setting: a caregiver has no way to tell a model
        // that is good at judging one Greek sentence from one that is not.
        "Μοντέλο για τις συμβουλές. Άφησέ το κενό για ${Settings.DEFAULT_CLAUDE_MODEL}.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** How a test finds the one line that says what this phone can do about Greek offline. */
const val LISTEN_STATE_TAG = "greek-model-state"

/**
 * How often the row re-asks the engine while it says «λήψη…», and for how long it keeps asking.
 * Three seconds for twenty rounds is a minute of patience — long enough for a language model over a
 * phone connection, short enough that a download nobody will ever see the end of stops being claimed.
 */
private const val DOWNLOAD_POLL_MS = 3_000L
private const val DOWNLOAD_POLLS = 20
