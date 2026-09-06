package gr.dimitris.app.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import gr.dimitris.app.AppGraph
import gr.dimitris.app.core.data.Adapt
import gr.dimitris.app.core.data.Attempt
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Outcome
import gr.dimitris.app.core.data.Session
import gr.dimitris.app.core.data.now
import gr.dimitris.app.core.scheduler.ModuleRotation
import gr.dimitris.app.modules.Module
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Today's sitting out of everything that has something to do: at most four modules, taken in turn,
 * each contributing its share of one sitting.
 *
 * [wanted] is every enabled module with a non-empty plan, in Today order; [lastUsedAt] is when each
 * was last really practised (see [ModuleRotation]). Everything he has enabled turning up every day
 * is three exercises apiece and a day of nothing much, so the rotation picks four — and the budget
 * is then shared between the four that are really in the session, not between all of them. A module
 * left out was never marked done, so it is still due tomorrow, when it will be the one that has
 * waited longest.
 *
 * Free of the ViewModel so it can be tested for what it is: the join between the rotation and the
 * budget, which is where a session gets its length.
 */
internal fun planToday(
    wanted: List<Pair<Module, List<Item>>>,
    lastUsedAt: Map<ModuleId, Long>,
): List<Pair<Module, List<Item>>> {
    val chosen = ModuleRotation.choose(wanted.map { it.first.id }, lastUsedAt).toSet()
    val today = wanted.filter { it.first.id in chosen }
    // One sitting, shared out evenly between the modules that are really in it — except in a module
    // that runs as one unit, which keeps its whole list so the session's planned count is the number
    // of exercises it will really run.
    val allowance = SessionBudget.allowance(today.size)
    return today.map { (m, items) -> m to SessionBudget.share(items, allowance, m.atomic) }
}

/**
 * The sitting itself, as the one synthetic row at the end of it will carry it.
 *
 * Every module writes down what its own exercises cost him; nothing wrote down what the *sitting*
 * cost him, and that is the number the session's length is set from. It goes into an attempt row
 * with [SessionViewModel.SESSION_SUMMARY] as its item id rather than into a new column: [gr.dimitris.app.core.data.Session]
 * is synced and backed up as it stands, and a schema change for one JSON object would be a migration
 * on every phone for nothing.
 *
 * Free of the ViewModel so that what is written down can be argued with in a unit test rather than
 * on a phone: see `TelemetryTest`.
 *
 * [leftEarly] is the honest half of it: a sitting he walked out of is not a short sitting, it is a
 * sitting that lost him, and telling the two apart is what any rule about session length needs.
 */
internal fun sessionSummaryDetail(
    plannedModules: List<String>,
    plannedCount: Int,
    completed: Int,
    leftEarly: Boolean,
    ms: Long,
    msPerModule: Map<String, Long>,
): String = Adapt.detail {
    words("plannedModules", plannedModules)
    put("plannedCount", plannedCount)
    put("completed", completed)
    put("leftEarly", leftEarly)
    put("ms", ms)
    times("msPerModule", msPerModule)
}

sealed class SessionStep {
    object Loading : SessionStep()

    /** Nothing to do. [allOff] tells "everything is done for today" apart from "everything is off". */
    data class Empty(val allOff: Boolean) : SessionStep()
    data class Run(val index: Int, val module: Module, val items: List<Item>, val sessionId: String) : SessionStep()
    /** [titles] are the modules he actually practised, in session order. */
    data class Summary(val completed: Int, val planned: Int, val titles: List<String>) : SessionStep()
}

/** Builds today's mixed session from the enabled modules and walks through them one by one. */
class SessionViewModel(private val graph: AppGraph) : ViewModel() {
    private val _state = MutableStateFlow<SessionStep>(SessionStep.Loading)
    val state: StateFlow<SessionStep> = _state.asStateFlow()

    private var plans: List<Pair<Module, List<Item>>> = emptyList()
    private var session: Session? = null

    /**
     * Atomic because two threads race for it: the summary finalizes on viewModelScope while
     * [onCleared] finalizes on the app scope, and the session row must be closed exactly once.
     */
    private val finalized = AtomicBoolean(false)

    /** Set the moment the session starts winding down, so "done" and "back" cannot both end it. */
    private var ending = false

    /**
     * True only when the last planned module said it was finished. Everything else — the back arrow
     * inside a module, the screen going away — is him leaving early, and the summary row says which
     * it was.
     */
    private var ranToTheEnd = false

    init {
        viewModelScope.launch {
            val enabled = graph.settings.enabledModules.first()
            // Switched everything off is a settings mistake, not a finished day: say which it is.
            val allOff = graph.modules.none { it.id in enabled }
            val wanted = graph.modules.filter { it.id in enabled }
                .mapNotNull { m -> runCatching { m.planFor(graph) }.getOrElse { graph.errors.record("plan ${m.id}", it); emptyList() }.takeIf { it.isNotEmpty() }?.let { m to it } }
            if (wanted.isEmpty()) {
                _state.value = SessionStep.Empty(allOff)
                say(if (allOff) ALL_MODULES_OFF else NOTHING_TODAY)
                return@launch
            }
            val lastUsed = runCatching { ModuleRotation.lastUsedAt(graph.db.attempts()) }
                .getOrElse { graph.errors.record("session rotation", it); emptyMap() }
            plans = planToday(wanted, lastUsed)
            val s = Session(startedAt = now(), plannedModules = plans.joinToString(",") { it.first.id.name }, plannedItemCount = plans.sumOf { it.second.size })
            runCatching { graph.db.sessions().upsert(s) }.onFailure { graph.errors.record("session start", it) }
            session = s
            _state.value = SessionStep.Run(0, plans[0].first, plans[0].second, s.id)
        }
    }

    /** A module ran out of exercises: on to the next one, or the summary if it was the last. */
    fun moduleDone() {
        // A module finishing as the session is already winding down changes nothing.
        if (ending) return
        val step = _state.value as? SessionStep.Run ?: return
        val next = step.index + 1
        if (next < plans.size) {
            _state.value = SessionStep.Run(next, plans[next].first, plans[next].second, step.sessionId)
            return
        }
        ranToTheEnd = true
        endSession()
    }

    /**
     * He pressed back inside a module. That ends the whole session, not just this module: being
     * handed the next exercise after asking to leave is the opposite of what the button said.
     */
    fun leaveSession() {
        if (_state.value !is SessionStep.Run) return
        endSession()
    }

    private fun endSession() {
        if (ending) return
        ending = true
        val s = session
        val planned = plans.sumOf { it.second.size }
        viewModelScope.launch {
            val done = if (s == null) Done() else finalize(s)
            _state.value = SessionStep.Summary(done.count, planned, done.titles)
            // Nothing done is not a failure and never gets the Μπράβο: he is invited back instead.
            if (done.count > 0) {
                graph.feedback.success()
                say("Μπράβο Δημήτρη! " + SessionWording.summary(done.count, done.titles))
            } else {
                say(SessionWording.NOTHING_DONE)
            }
        }
    }

    /** What he actually did: how many exercises, and which modules they were in. */
    private data class Done(val count: Int = 0, val titles: List<String> = emptyList())

    /** Every row this session wrote, skips included: the timings are about his time, not his score. */
    private suspend fun rowsOf(s: Session): List<Attempt> =
        runCatching { graph.db.attempts().since(s.startedAt).filter { it.sessionId == s.id } }
            .getOrElse { graph.errors.record("session count", it); emptyList() }

    /**
     * Read back from the attempts this session wrote — the back arrow ends a session early, and
     * skipped words were never said, so neither may be counted as work.
     */
    private fun practised(rows: List<Attempt>): Done {
        val real = rows.filter { it.outcome != Outcome.SKIPPED }
        val modules = real.map { it.module }.toSet()
        return Done(real.size, plans.map { it.first }.filter { it.id in modules }.map { it.titleGreek })
    }

    /** Closes the session row with the honest count. Runs once, whichever way the session ends. */
    private suspend fun finalize(s: Session): Done {
        val rows = rowsOf(s)
        val done = practised(rows)
        if (finalized.compareAndSet(false, true)) {
            val ended = now()
            runCatching { graph.db.sessions().upsert(s.copy(endedAt = ended, completedItemCount = done.count, updatedAt = ended)) }
                .onFailure { graph.errors.record("session end", it) }
            writeSummary(s, rows, done, ended)
        }
        return done
    }

    /**
     * The one row that is about the sitting rather than about a word.
     *
     * Written after [practised] has counted, and only once, so it can never be counted as an
     * exercise he did: `session:summary` is not an item id anything can look up, which is the same
     * rule `numbers:level:3` and `arcade:tap` already live by — the caregiver's word lists find it
     * absent from the vocabulary and leave it out. Its module is the last one that was planned,
     * because a row has to have one; see the note in `docs/ADAPTATION.md` about what that costs.
     */
    private suspend fun writeSummary(s: Session, rows: List<Attempt>, done: Done, ended: Long) {
        val last = plans.lastOrNull()?.first?.id ?: return
        val detail = sessionSummaryDetail(
            plannedModules = plans.map { it.first.id.name },
            plannedCount = s.plannedItemCount,
            completed = done.count,
            leftEarly = !ranToTheEnd,
            ms = (ended - s.startedAt).coerceAtLeast(0L),
            // Time on exercises, module by module: the gaps between them are his, not the app's.
            msPerModule = rows.groupBy { it.module.name }
                .mapValues { (_, r) -> r.sumOf { it.durationMs } }
                .toSortedMap(),
        )
        runCatching {
            graph.db.attempts().insert(
                Attempt(
                    itemId = SESSION_SUMMARY, module = last, sessionId = s.id, startedAt = s.startedAt,
                    durationMs = (ended - s.startedAt).coerceAtLeast(0L), outcome = Outcome.CORRECT,
                    cueLevel = null, detail = detail,
                )
            )
        }.onFailure { graph.errors.record("session summary", it) }
    }

    /** Walking away also ends the session. viewModelScope is already cancelled, so the app scope writes it. */
    override fun onCleared() {
        val s = session
        if (s == null || finalized.get()) return
        graph.scope.launch { finalize(s) }
    }

    private suspend fun say(text: String) =
        graph.voice.speak(text, graph.settings.speechRate.first())
            .onFailure { graph.errors.record("session speak", it) }

    companion object {
        /**
         * The item id of the one row per sitting that is about the sitting itself. Not a word, not a
         * level, not a game: nothing can look it up in the vocabulary, and nothing should try.
         */
        const val SESSION_SUMMARY = "session:summary"

        /** Everything due is done. A good ending, said as one. */
        const val NOTHING_TODAY = "Τίποτα για σήμερα. Τα λέμε αύριο!"

        /** Not his doing: every module is switched off, and only the settings can undo that. */
        const val ALL_MODULES_OFF = "Όλες οι ασκήσεις είναι κλειστές. Άνοιξέ τες από τις ρυθμίσεις."
    }
}
