package gr.dimitris.app.caregiver.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import gr.dimitris.app.AppGraph
import gr.dimitris.app.caregiver.insights.InsightRules
import gr.dimitris.app.core.data.Attempt
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.Session
import gr.dimitris.app.core.data.now as systemClock
import gr.dimitris.app.core.scheduler.LeitnerPolicy
import gr.dimitris.app.modules.numbers.NumberProgression
import gr.dimitris.app.modules.sentences.SentenceTemplates
import gr.dimitris.app.modules.trace.TraceViewModel
import kotlinx.coroutines.CancellationException
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
class ProgressViewModel(
    private val graph: AppGraph,
    /** The clock, so a test can hand in a fixed day instead of whichever one it runs on. */
    private val now: () -> Long = ::systemClock,
) : ViewModel() {
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

    /**
     * Both ends clamped here as well as in [gr.dimitris.app.core.settings.Settings]: a stepper is a
     * hand. The state moves first and the write follows, because the screen computes the next level
     * from the state: a second tap that arrives before DataStore has echoed the first one would
     * otherwise be computed from the old number and silently write the same level twice — the
     * caregiver taps `+` twice, and the exercises Dimitris is handed move one step, not two.
     */
    fun setNumbersLevel(level: Int) {
        val clamped = level.coerceIn(NumberProgression.MIN_LEVEL, NumberProgression.MAX_LEVEL)
        _state.update { it.copy(numbersLevel = clamped) }
        write("numbers level") { graph.settings.setNumbersLevel(clamped) }
    }

    fun setSentencesLevel(level: Int) {
        val clamped = level.coerceIn(SentenceTemplates.MIN_LEVEL, SentenceTemplates.MAX_LEVEL)
        _state.update { it.copy(sentencesLevel = clamped) }
        write("sentences level") { graph.settings.setSentencesLevel(clamped) }
    }

    fun setTraceLevel(level: Int) {
        val clamped = level.coerceIn(TraceViewModel.MIN_LEVEL, TraceViewModel.MAX_LEVEL)
        _state.update { it.copy(traceLevel = clamped) }
        write("trace level") { graph.settings.setTraceLevel(clamped) }
    }

    private fun write(where: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (ce: CancellationException) {
                throw ce                       // leaving the screen is not a failure to write down
            } catch (e: Throwable) {
                graph.errors.record(where, e)
            }
        }
    }

    private suspend fun load() {
        val to = now()
        val from = ProgressStats.from(to)
        val earlier = ProgressStats.from(to, ProgressStats.DEFAULT_DAYS * 2)
        val read = try {
            val db = graph.db
            Read(
                attempts = db.attempts().between(earlier, to),
                sessions = db.sessions().between(from, to),
                mastered = db.schedules().masteredCount(LeitnerPolicy.MAX_BOX),
                items = db.items().allActive().associateBy { it.id },
            )
        } catch (ce: CancellationException) {
            throw ce                           // the screen is gone; there is nobody to tell
        } catch (e: Throwable) {
            graph.errors.record("progress read", e)
            null
        }

        if (read == null) {
            _state.update { it.copy(loading = false) }
            return
        }
        val computed = withContext(Dispatchers.Default) {
            // No schedules: the mastered count came from SQLite, which is the only thing they were for.
            val p = ProgressStats.compute(
                read.attempts, read.sessions, emptyList(), read.items, from, to, mastered = read.mastered,
            )
            p to InsightRules.generate(p, read.attempts, read.items)
        }
        _state.update { it.copy(loading = false, progress = computed.first, insights = computed.second) }
    }

    private class Read(
        val attempts: List<Attempt>,
        val sessions: List<Session>,
        val mastered: Int,
        val items: Map<String, Item>,
    )
}
