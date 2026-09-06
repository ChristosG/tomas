package gr.dimitris.app.caregiver.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import gr.dimitris.app.AppGraph
import gr.dimitris.app.LocalAppGraph
import gr.dimitris.app.core.data.Note
import gr.dimitris.app.core.data.now
import gr.dimitris.app.ui.components.BigButton
import gr.dimitris.app.ui.components.DimitrisScreen
import gr.dimitris.app.ui.components.QuietButton
import gr.dimitris.app.ui.theme.LocalFeedback
import gr.dimitris.app.ui.theme.Sizes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import gr.dimitris.app.core.data.Advice as AdviceRow

data class AdviceState(
    val loading: Boolean = true,
    /** Exactly the text that will be sent, or "" while it is still being built. */
    val report: String = "",
    val hasKey: Boolean = false,
    val notes: List<Note> = emptyList(),
    val history: List<AdviceRow> = emptyList(),
    /** The live focus, filtered to words that still exist. Null when there is none to act on. */
    val focus: Focus? = null,
    val noteError: String? = null,
    /**
     * The advice whose levels have been applied, not a boolean. A second question brings a second
     * focus with its own levels, and a flag that never resets told the caregiver they had already
     * gone in when they had not — on the one control that decides what Dimitris is handed tomorrow.
     */
    val levelsAppliedFor: String? = null,
)

/**
 * Builds the report and owns the notes. It does **not** own the question: that lives in
 * [gr.dimitris.app.AppGraph.adviceSession], on the application scope, so one request runs at a time
 * and an answer is not thrown away because the caregiver pressed back while it was thinking. This
 * class only decides *what* would be sent, and never sends anything by itself.
 *
 * The history and the notes are read as Flows rather than once: an answer is stored the moment it
 * arrives, so the newest advice — and with it the focus the app is about to act on — appears here
 * by the same route a synced one from the other phone would.
 */
class AdviceViewModel(private val graph: AppGraph) : ViewModel() {
    private val _state = MutableStateFlow(AdviceState())
    val state: StateFlow<AdviceState> = _state.asStateFlow()

    /** The question and the answer, outliving this screen. */
    val session: StateFlow<AdviceSession.State> = graph.adviceSession.state

    init {
        // Keyed on the generation: a restored backup is a different database, and a Flow from the
        // old one never emits again once it is closed.
        viewModelScope.launch { graph.dbGeneration.collectLatest { load() } }
        viewModelScope.launch {
            graph.dbGeneration.collectLatest {
                graph.db.notes().observeRecent(NOTES_SHOWN).collect { rows -> _state.update { it.copy(notes = rows) } }
            }
        }
        viewModelScope.launch {
            graph.dbGeneration.collectLatest {
                graph.db.advice().observeRecent(HISTORY_SHOWN).collect { rows ->
                    val known = runCatching { graph.db.items().allActive().map { i -> i.text } }.getOrNull()
                    _state.update { it.copy(history = rows, focus = Focus.active(rows, known, now())) }
                }
            }
        }
    }

    fun ask() = graph.adviceSession.ask(_state.value.report)

    fun noticeSeen() = graph.adviceSession.noticeSeen()

    /**
     * A note is written as it is typed, trimmed and capped, and the report is rebuilt behind it so
     * that «Τι θα σταλεί» is true again the moment the note is saved. Notes sync like everything
     * else: what the father noticed on Tuesday reaches Chris' phone, and both reach Claude.
     */
    fun saveNote(text: String) {
        val trimmed = text.trim().take(Note.MAX_TEXT)
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(noteError = null) }
            try {
                val author = graph.settings.deviceRole.first().name
                graph.db.notes().upsert(Note(text = trimmed, author = author))
                refreshReport()
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                graph.errors.record("save note", e)
                _state.update { it.copy(noteError = NOTE_FAILED) }
            }
        }
    }

    /**
     * The one part of an answer that changes the app on a tap, and the reason it is behind a
     * confirmation: a level decides which exercises Dimitris is handed tomorrow morning, and the
     * caregivers — not the model, and not this screen — are the ones who get to decide that.
     *
     * The word and sound focus needs no button. It is stored with the advice and the session
     * builder reads it, which is the difference between advice and advice that happens.
     */
    fun applyLevels(adviceId: String, levels: Map<String, Int>) {
        if (levels.isEmpty()) return
        viewModelScope.launch {
            try {
                levels[Focus.NUMBERS]?.let { graph.settings.setNumbersLevel(it) }
                levels[Focus.SENTENCES]?.let { graph.settings.setSentencesLevel(it) }
                levels[Focus.TRACE]?.let { graph.settings.setTraceLevel(it) }
                _state.update { it.copy(levelsAppliedFor = adviceId) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                graph.errors.record("apply levels", e)
            }
        }
    }

    private suspend fun load() {
        refreshReport()
        // The encrypted file is opened here, off the main thread, and never on the way to drawing.
        val has = withContext(Dispatchers.IO) {
            try {
                graph.advisor.hasKey
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                false
            }
        }
        _state.update { it.copy(loading = false, hasKey = has) }
    }

    /**
     * A report that could not be read stays empty, which disables the button and makes the «Τι θα
     * σταλεί» section say [COULD_NOT_READ]. There is nothing to ask with either way.
     */
    private suspend fun refreshReport() {
        val report = try {
            journeyReport(graph)
        } catch (ce: CancellationException) {
            throw ce                           // leaving the screen is not a failure to write down
        } catch (e: Throwable) {
            graph.errors.record("journey report", e)
            null
        }
        _state.update { it.copy(report = report.orEmpty()) }
    }

    companion object {
        const val COULD_NOT_READ = "Δεν μπόρεσα να διαβάσω την πρόοδο."
        const val SPEECH_FAILED = "Δεν ακούστηκε. Δοκίμασε ξανά."
        const val NOTE_FAILED = "Η σημείωση δεν αποθηκεύτηκε."

        /** Notes on the screen. The report sends [JourneyReport.MAX_NOTES]; this keeps it readable. */
        const val NOTES_SHOWN = 5

        /** Previous advices listed at the bottom. */
        const val HISTORY_SHOWN = 20
    }
}

/**
 * «Ρώτα τον Claude». The one screen in the app that can send anything anywhere, and it shows what
 * it would send before it sends it.
 *
 * The order is the order a caregiver works in: write down what you noticed this week, look at what
 * is going to be sent, send it, read the answer — the part for them as text they can read at their
 * own pace, the part for Dimitris behind a button because it is meant to be heard by him and not
 * read at him, and the focus as chips, which is the app telling them what it is going to do about
 * it. Everything Claude has ever said is underneath, so «τι είχε πει τον Αύγουστο;» is a scroll.
 */
@Composable
fun AdviceScreen(onBack: () -> Unit) {
    val graph = LocalAppGraph.current
    val vm: AdviceViewModel = viewModel { AdviceViewModel(graph) }
    val state by vm.state.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val feedback = LocalFeedback.current
    val zone = remember { ZoneId.systemDefault() }
    var noteDraft by remember { mutableStateOf("") }
    var showReport by remember { mutableStateOf(false) }
    var speechError by remember { mutableStateOf<String?>(null) }
    var pendingLevels by remember { mutableStateOf<Pair<String, Map<String, Int>>?>(null) }
    var openAdvice by remember { mutableStateOf<String?>(null) }

    DimitrisScreen(
        title = "Ρώτα τον Claude",
        // Back just leaves. A question already asked is not cancelled — the SDK's call is a
        // blocking one and cancelling the coroutine would abandon the request rather than stop it —
        // so it finishes on the application scope and its answer is here when the caregiver
        // comes back.
        onBack = onBack,
        bottom = {
            // The button is always here, so a caregiver can see what the screen is for while it
            // thinks; it simply refuses a second question until the first one is answered.
            BigButton(
                when {
                    session.asking -> "Ρωτάω τον Claude…"
                    session.advice == null -> "Ρώτα τον Claude"
                    else -> "Ρώτα ξανά"
                },
                onClick = vm::ask,
                enabled = state.hasKey && state.report.isNotBlank(),
                icon = Icons.Rounded.AutoAwesome,
                modifier = Modifier.semantics { testTag = "ask-claude" },
            )
            if (session.asking) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchMin),
                ) {
                    CircularProgressIndicator(Modifier.size(Sizes.icon))
                    Spacer(Modifier.width(Sizes.gap))
                    Text(
                        "Μπορείς να φύγεις· η απάντηση θα σε περιμένει.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!state.hasKey) {
                Text(
                    "Βάλε κλειδί στις ρυθμίσεις",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            session.notice?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.semantics { testTag = "advice-notice" },
                )
            }
            session.error?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { testTag = "advice-error" },
                )
            }
        },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            // ---- What the people around him noticed -------------------------------------------
            Text("Σημειώσεις", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(Sizes.gapSmall))
            Text(
                "Ό,τι πρόσεξες και δεν το ξέρουν οι αριθμοί. Πάνε μαζί με την αναφορά.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Sizes.gapSmall))
            OutlinedTextField(
                value = noteDraft,
                onValueChange = { noteDraft = it },
                label = { Text("Τι πρόσεξες;") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchMin)
                    .semantics { testTag = "note-field" },
            )
            Spacer(Modifier.height(Sizes.gapSmall))
            QuietButton(
                "Αποθήκευση σημείωσης",
                enabled = noteDraft.isNotBlank(),
                icon = Icons.Rounded.Save,
                onClick = {
                    vm.saveNote(noteDraft)
                    noteDraft = ""
                },
                modifier = Modifier.semantics { testTag = "save-note" },
            )
            state.noteError?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            if (state.notes.isEmpty()) {
                Text("Καμία σημείωση ακόμα.", style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchMin))
            }
            state.notes.forEach { note ->
                Column(Modifier.fillMaxWidth().heightIn(min = Sizes.touchMin)) {
                    Text(
                        "${date(note.at, zone)} · ${JourneyReport.author(note.author)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(note.text, style = MaterialTheme.typography.bodyLarge)
                }
            }

            // ---- What is going to be sent ------------------------------------------------------
            Spacer(Modifier.height(Sizes.gap))
            // Always the report that would go out *now*, never the one that went out before.
            //
            // This section's whole contract is "the caregiver sees exactly what is sent", and it
            // used to switch to the sent copy as soon as an answer arrived — so a note typed after
            // reading the answer («έκλαψε στον οδοντίατρο») went to Anthropic on the next «Ρώτα
            // ξανά» without ever having appeared here. The report that *was* sent is not lost: it
            // is stored whole on its own advice row and quoted back in the next report.
            val shown = state.report
            QuietButton(
                "Τι θα σταλεί",
                onClick = { showReport = !showReport },
                icon = if (showReport) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                modifier = Modifier.semantics { testTag = "toggle-report" },
            )
            Text(
                if (state.loading) "Υπολογίζω…" else "${shown.length} χαρακτήρες",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { testTag = "report-size" },
            )
            Text(
                "Στέλνει μόνο λόγια. Ποτέ φωνή, ποτέ φωτογραφίες.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (showReport) {
                Spacer(Modifier.height(Sizes.gapSmall))
                Text(
                    shown.ifBlank { AdviceViewModel.COULD_NOT_READ },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().semantics { testTag = "advice-summary" },
                )
            }

            // ---- The answer --------------------------------------------------------------------
            val advice = session.advice
            if (advice != null) {
                Spacer(Modifier.height(Sizes.gap))
                Text("Για τους φροντιστές", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(Sizes.gapSmall))
                Text(advice.caregivers, style = MaterialTheme.typography.bodyLarge)

                if (advice.dimitris.isNotBlank()) {
                    Spacer(Modifier.height(Sizes.gap))
                    Text("Για τον Δημήτρη", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(Sizes.gapSmall))
                    Text(advice.dimitris, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(Sizes.gapSmall))

                    BigButton(
                        "Πες το στον Δημήτρη",
                        onClick = {
                            speechError = null
                            scope.launch {
                                val rate = graph.settings.speechRate.first()
                                graph.voice.speak(advice.dimitris, rate).onFailure {
                                    graph.errors.record("advice speak", it)
                                    speechError = AdviceViewModel.SPEECH_FAILED
                                }
                            }
                        },
                        icon = Icons.Rounded.VolumeUp,
                        modifier = Modifier.semantics { testTag = "speak-dimitris" },
                    )
                    speechError?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                }
                if (advice.truncated) {
                    Spacer(Modifier.height(Sizes.gapSmall))
                    Text(
                        ClaudeAdvisor.TRUNCATED,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { testTag = "advice-truncated" },
                    )
                }
            }

            // ---- What the app is going to do about it -------------------------------------------
            state.focus?.let { focus ->
                Spacer(Modifier.height(Sizes.gap))
                Text("Εστίαση", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(Sizes.gapSmall))
                if (focus.why.isNotBlank()) {
                    Text(focus.why, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(Sizes.gapSmall))
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.semantics { testTag = "focus-chips" },
                ) {
                    focus.items.forEach { FocusChip(it) }
                    focus.sounds.forEach { FocusChip("ήχος «$it»") }
                    focus.modules.forEach { m -> FocusChip(AdviceSummary.MODULE_NAMES[m] ?: m.name) }
                }
                Spacer(Modifier.height(Sizes.gapSmall))
                Text(
                    "Η εφαρμογή κρατάει θέσεις για αυτές τις λέξεις στις Λέξεις και στο «Τραγούδα " +
                        "και πες το», και δίνει σειρά στις ασκήσεις που ζητήθηκαν.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (focus.levels.isNotEmpty()) {
                    // Per advice, not per screen: the newest advice is the one the chips came from.
                    val adviceId = state.history.firstOrNull()?.id
                    val applied = adviceId != null && adviceId == state.levelsAppliedFor
                    Spacer(Modifier.height(Sizes.gapSmall))
                    Text(levelsLine(focus.levels), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(Sizes.gapSmall))
                    QuietButton(
                        if (applied) "Τα επίπεδα μπήκαν" else "Εφάρμοσε τα επίπεδα",
                        enabled = !applied && adviceId != null,
                        icon = Icons.Rounded.Tune,
                        onClick = { pendingLevels = adviceId?.let { it to focus.levels } },
                        modifier = Modifier.semantics { testTag = "apply-levels" },
                    )
                }
            }

            // ---- Everything it has ever said ----------------------------------------------------
            if (state.history.isNotEmpty()) {
                Spacer(Modifier.height(Sizes.gap))
                Text("Προηγούμενες συμβουλές", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(Sizes.gapSmall))
                state.history.forEach { row ->
                    val open = openAdvice == row.id
                    QuietButton(
                        "${date(row.at, zone)} · ${firstLine(row.caregivers)}",
                        icon = if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        onClick = { openAdvice = if (open) null else row.id },
                        modifier = Modifier.semantics { testTag = "advice-history" },
                    )
                    if (open) {
                        Spacer(Modifier.height(Sizes.gapSmall))
                        Text(row.caregivers, style = MaterialTheme.typography.bodyMedium)
                        if (row.dimitris.isNotBlank()) {
                            Spacer(Modifier.height(Sizes.gapSmall))
                            Text(row.dimitris, style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(Modifier.height(Sizes.gapSmall))
                        // The report that produced this answer is kept whole on the row; saying how
                        // big it was is what makes "we still have it" visible without putting tens
                        // of thousands of characters on a phone screen.
                        Text(
                            "Στάλθηκαν ${row.report.length} χαρακτήρες. Μοντέλο: ${row.model.ifBlank { "—" }}.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(Sizes.gapSmall))
                }
            }
            Spacer(Modifier.height(Sizes.gap))
        }
    }

    pendingLevels?.let { (adviceId, levels) ->
        AlertDialog(
            onDismissRequest = { pendingLevels = null },
            title = { Text("Αλλαγή επιπέδων;") },
            text = { Text("${levelsLine(levels)}\n\nΑυτό αλλάζει τις ασκήσεις που θα πάρει ο Δημήτρης.") },
            confirmButton = {
                TextButton(modifier = Modifier.heightIn(min = Sizes.touchMin), onClick = {
                    feedback.tap()
                    vm.applyLevels(adviceId, levels)
                    pendingLevels = null
                }) { Text("Ναι, άλλαξέ τα", style = MaterialTheme.typography.labelLarge) }
            },
            dismissButton = {
                TextButton(modifier = Modifier.heightIn(min = Sizes.touchMin), onClick = {
                    feedback.tap(); pendingLevels = null
                }) { Text("Άκυρο", style = MaterialTheme.typography.labelLarge) }
            },
        )
    }

    // The «Περίμενε» line answers one tap; it is not a state the screen should keep.
    LaunchedEffect(session.notice, session.asking) {
        if (session.notice != null && !session.asking) vm.noticeSeen()
    }
}

/** A word, a sound or an exercise the focus names. It says something; it does not do anything. */
@Composable
private fun FocusChip(label: String) {
    AssistChip(
        onClick = {},
        label = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        modifier = Modifier.heightIn(min = Sizes.touchMin),
    )
}

/** «Αριθμοί 3, Προτάσεις 2, Γράψε 2» — the same three names the dashboard's steppers carry. */
internal fun levelsLine(levels: Map<String, Int>): String = listOfNotNull(
    levels[Focus.NUMBERS]?.let { "Αριθμοί $it" },
    levels[Focus.SENTENCES]?.let { "Προτάσεις $it" },
    levels[Focus.TRACE]?.let { "Γράψε $it" },
).joinToString(", ")

/** Enough of an old advice to recognise it by, on one line. */
internal fun firstLine(text: String): String =
    text.lineSequence().map { it.trim().trimStart('-', '·', ' ') }.firstOrNull { it.isNotBlank() }
        ?.take(60).orEmpty().ifBlank { "—" }

private fun date(at: Long, zone: ZoneId): String {
    val d = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    return "${d.dayOfMonth}/${d.monthValue}/${d.year}"
}
