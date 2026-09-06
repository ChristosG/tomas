package gr.dimitris.app.caregiver.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import gr.dimitris.app.AppGraph
import gr.dimitris.app.caregiver.insights.InsightRules
import gr.dimitris.app.core.data.now
import gr.dimitris.app.modules.numbers.NumberProgression
import gr.dimitris.app.modules.sentences.SentenceTemplates
import gr.dimitris.app.modules.trace.TraceViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ProgressState(
    val loading: Boolean = true,
    val progress: Progress? = null,
    val insights: List<String> = emptyList(),
    val numbersLevel: Int = NumberProgression.MIN_LEVEL,
    val sentencesLevel: Int = SentenceTemplates.MIN_LEVEL,
    val traceLevel: Int = TraceViewModel.MIN_LEVEL,
)

/**
 * The caregiver dashboard's one reader of the database. Four weeks of history, counted once, on a
 * background thread — a month of attempt rows is not something to group on the frame the screen is
 * trying to draw.
 *
 * Twice the window of attempts is read, because one of the insight rules compares this period with
 * the one of the same length before it. Everything narrower than that is cut by
 * [ProgressStats.compute] itself, so the dashboard's own numbers are still four weeks.
 */
class ProgressViewModel(private val graph: AppGraph) : ViewModel() {
    private val _state = MutableStateFlow(ProgressState())
    val state: StateFlow<ProgressState> = _state.asStateFlow()

    init {
        // Keyed on the generation: a restored backup is a different database, and the old one's
        // numbers are not his any more.
        viewModelScope.launch { graph.dbGeneration.collectLatest { load() } }
        viewModelScope.launch { graph.settings.numbersLevel.collectLatest { v -> _state.update { it.copy(numbersLevel = v) } } }
        viewModelScope.launch { graph.settings.sentencesLevel.collectLatest { v -> _state.update { it.copy(sentencesLevel = v) } } }
        viewModelScope.launch { graph.settings.traceLevel.collectLatest { v -> _state.update { it.copy(traceLevel = v) } } }
    }

    /** Both ends clamped here as well as in [gr.dimitris.app.core.settings.Settings]: a stepper is a hand. */
    fun setNumbersLevel(level: Int) = write("numbers level") {
        graph.settings.setNumbersLevel(level.coerceIn(NumberProgression.MIN_LEVEL, NumberProgression.MAX_LEVEL))
    }

    fun setSentencesLevel(level: Int) = write("sentences level") {
        graph.settings.setSentencesLevel(level.coerceIn(SentenceTemplates.MIN_LEVEL, SentenceTemplates.MAX_LEVEL))
    }

    fun setTraceLevel(level: Int) = write("trace level") {
        graph.settings.setTraceLevel(level.coerceIn(TraceViewModel.MIN_LEVEL, TraceViewModel.MAX_LEVEL))
    }

    fun refresh() {
        viewModelScope.launch { load() }
    }

    private fun write(where: String, block: suspend () -> Unit) {
        viewModelScope.launch { runCatching { block() }.onFailure { graph.errors.record(where, it) } }
    }

    private suspend fun load() {
        val to = now()
        val from = ProgressStats.from(to)
        val earlier = ProgressStats.from(to, ProgressStats.DEFAULT_DAYS * 2)
        val read = runCatching {
            val db = graph.db
            Triple(
                db.attempts().between(earlier, to),
                db.sessions().between(from, to),
                db.schedules().allActive(),
            ) to db.items().allActive().associateBy { it.id }
        }.onFailure { graph.errors.record("progress read", it) }.getOrNull()

        if (read == null) {
            _state.update { it.copy(loading = false) }
            return
        }
        val (rows, items) = read
        val (attempts, sessions, schedules) = rows
        val computed = withContext(Dispatchers.Default) {
            val p = ProgressStats.compute(attempts, sessions, schedules, items, from, to)
            p to InsightRules.generate(p, attempts, items)
        }
        _state.update { it.copy(loading = false, progress = computed.first, insights = computed.second) }
    }
}
