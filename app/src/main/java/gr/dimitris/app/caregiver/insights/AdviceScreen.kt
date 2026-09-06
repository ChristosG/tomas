package gr.dimitris.app.caregiver.insights

import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import gr.dimitris.app.AppGraph
import gr.dimitris.app.LocalAppGraph
import gr.dimitris.app.caregiver.progress.ProgressStats
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.now
import gr.dimitris.app.core.scheduler.LeitnerPolicy
import gr.dimitris.app.ui.components.BigButton
import gr.dimitris.app.ui.components.DimitrisScreen
import gr.dimitris.app.ui.components.QuietButton
import gr.dimitris.app.ui.theme.Sizes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AdviceState(
    val loading: Boolean = true,
    /** Exactly the text that will be sent, or "" while it is still being built. */
    val summary: String = "",
    val hasKey: Boolean = false,
)

/**
 * Builds the summary. It does **not** own the question: that lives in
 * [gr.dimitris.app.AppGraph.adviceSession], on the application scope, so one request runs at a time
 * and an answer is not thrown away because the caregiver pressed back while it was thinking. This
 * class only decides *what* would be sent, and never sends anything by itself.
 *
 * The dashboard's own reader is [gr.dimitris.app.caregiver.progress.ProgressViewModel]; this one
 * repeats the read rather than sharing it, because the advice screen is its own destination and has
 * to stand on its own when it is opened from a restored back stack.
 */
class AdviceViewModel(private val graph: AppGraph) : ViewModel() {
    private val _state = MutableStateFlow(AdviceState())
    val state: StateFlow<AdviceState> = _state.asStateFlow()

    /** The question and the answer, outliving this screen. */
    val session: StateFlow<AdviceSession.State> = graph.adviceSession.state

    init {
        viewModelScope.launch { load() }
    }

    fun ask() = graph.adviceSession.ask(_state.value.summary)

    fun noticeSeen() = graph.adviceSession.noticeSeen()

    private suspend fun load() {
        val to = now()
        val from = ProgressStats.from(to)
        val earlier = ProgressStats.from(to, ProgressStats.DEFAULT_DAYS * 2)
        val names = graph.modules.associate { it.id to it.titleGreek } + (ModuleId.TALKBOARD to "Μίλα")
        // The same builder the dashboard uses, so the two screens cannot disagree about the labels.
        val levels = AdviceSummary.levels(
            graph.settings.numbersLevel.first(),
            graph.settings.sentencesLevel.first(),
            graph.settings.traceLevel.first(),
        )
        val summary = try {
            val db = graph.db
            val attempts = db.attempts().between(earlier, to)
            val sessions = db.sessions().between(from, to)
            val mastered = db.schedules().masteredCount(LeitnerPolicy.MAX_BOX)
            val items = db.items().allActive().associateBy { it.id }
            withContext(Dispatchers.Default) {
                val p = ProgressStats.compute(attempts, sessions, emptyList(), items, from, to, mastered = mastered)
                AdviceSummary.build(p, InsightRules.generate(p, attempts, items), levels, names)
            }
        } catch (ce: CancellationException) {
            throw ce                           // leaving the screen is not a failure to write down
        } catch (e: Throwable) {
            graph.errors.record("advice summary", e)
            null
        }

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
        // A summary that could not be read stays empty, which disables the button and makes the
        // «Τι θα σταλεί» section say [COULD_NOT_READ]. There is nothing to ask with either way.
        _state.update { it.copy(loading = false, summary = summary.orEmpty(), hasKey = has) }
    }

    companion object {
        const val COULD_NOT_READ = "Δεν μπόρεσα να διαβάσω την πρόοδο."
        const val SPEECH_FAILED = "Δεν ακούστηκε. Δοκίμασε ξανά."
    }
}

/**
 * «Ρώτα τον Claude». The one screen in the app that can send anything anywhere, and it shows what
 * it would send before it sends it.
 *
 * The order on the screen is the order a caregiver thinks in: what is going to be sent, then the
 * button, then the answer — the part for them as text they can read at their own pace, and the part
 * for Dimitris behind a button, because it is meant to be heard by him and not read at him.
 */
@Composable
fun AdviceScreen(onBack: () -> Unit) {
    val graph = LocalAppGraph.current
    val vm: AdviceViewModel = viewModel { AdviceViewModel(graph) }
    val state by vm.state.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showSummary by remember { mutableStateOf(false) }
    var speechError by remember { mutableStateOf<String?>(null) }

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
                enabled = state.hasKey && state.summary.isNotBlank(),
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
            if (state.loading) {
                Text("Υπολογίζω…", style = MaterialTheme.typography.bodyLarge)
                return@Column
            }

            val advice = session.advice
            if (advice == null) {
                Text(
                    "Στέλνει μόνο τα λόγια που βλέπεις πιο κάτω. Ποτέ φωνή, ποτέ φωτογραφίες.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(Sizes.gap))
            } else {
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
                Spacer(Modifier.height(Sizes.gap))
            }

            // What is on screen decides what the label can honestly say. With an answer showing,
            // the text below it is the one that produced it — not the summary this screen rebuilt
            // from newer numbers when it was reopened.
            val shown = if (advice == null) state.summary else session.sent.orEmpty()
            QuietButton(
                if (advice == null) "Τι θα σταλεί" else "Τι στάλθηκε",
                onClick = { showSummary = !showSummary },
                icon = if (showSummary) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            )
            if (showSummary) {
                Spacer(Modifier.height(Sizes.gapSmall))
                Text(
                    shown.ifBlank { AdviceViewModel.COULD_NOT_READ },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().semantics { testTag = "advice-summary" },
                )
            }
            Spacer(Modifier.height(Sizes.gap))
        }
    }

    // The «Περίμενε» line answers one tap; it is not a state the screen should keep.
    LaunchedEffect(session.notice, session.asking) {
        if (session.notice != null && !session.asking) vm.noticeSeen()
    }
}
