package gr.dimitris.app.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import gr.dimitris.app.AppGraph
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.Outcome
import gr.dimitris.app.core.data.Session
import gr.dimitris.app.core.data.now
import gr.dimitris.app.modules.Module
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

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
            // One sitting, shared out evenly. What is cut was never done, so it is due again
            // tomorrow — except in a module that runs as one unit, which keeps its whole list so
            // the planned count below is the number of exercises it will really run.
            val allowance = SessionBudget.allowance(wanted.size)
            plans = wanted.map { (m, items) -> m to SessionBudget.share(items, allowance, m.atomic) }
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

    /**
     * Read back from the attempts this session wrote — the back arrow ends a session early, and
     * skipped words were never said, so neither may be counted as work.
     */
    private suspend fun practised(s: Session): Done {
        val rows = runCatching { graph.db.attempts().since(s.startedAt).filter { it.sessionId == s.id && it.outcome != Outcome.SKIPPED } }
            .getOrElse { graph.errors.record("session count", it); emptyList() }
        val modules = rows.map { it.module }.toSet()
        return Done(rows.size, plans.map { it.first }.filter { it.id in modules }.map { it.titleGreek })
    }

    /** Closes the session row with the honest count. Runs once, whichever way the session ends. */
    private suspend fun finalize(s: Session): Done {
        val done = practised(s)
        if (finalized.compareAndSet(false, true)) {
            runCatching { graph.db.sessions().upsert(s.copy(endedAt = now(), completedItemCount = done.count, updatedAt = now())) }
                .onFailure { graph.errors.record("session end", it) }
        }
        return done
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
        /** Everything due is done. A good ending, said as one. */
        const val NOTHING_TODAY = "Τίποτα για σήμερα. Τα λέμε αύριο!"

        /** Not his doing: every module is switched off, and only the settings can undo that. */
        const val ALL_MODULES_OFF = "Όλες οι ασκήσεις είναι κλειστές. Άνοιξέ τες από τις ρυθμίσεις."
    }
}
