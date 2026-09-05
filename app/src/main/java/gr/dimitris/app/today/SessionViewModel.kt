package gr.dimitris.app.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import gr.dimitris.app.AppGraph
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.Session
import gr.dimitris.app.core.data.now
import gr.dimitris.app.modules.Module
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed class SessionStep {
    object Loading : SessionStep()
    object Empty : SessionStep()
    data class Run(val index: Int, val module: Module, val items: List<Item>, val sessionId: String) : SessionStep()
    data class Summary(val completed: Int, val planned: Int) : SessionStep()
}

/** Builds today's mixed session from the enabled modules and walks through them one by one. */
class SessionViewModel(private val graph: AppGraph) : ViewModel() {
    private val _state = MutableStateFlow<SessionStep>(SessionStep.Loading)
    val state: StateFlow<SessionStep> = _state.asStateFlow()

    private var plans: List<Pair<Module, List<Item>>> = emptyList()
    private var session: Session? = null
    private var completed = 0

    init {
        viewModelScope.launch {
            val enabled = graph.settings.enabledModules.first()
            plans = graph.modules.filter { it.id in enabled }
                .mapNotNull { m -> runCatching { m.planFor(graph) }.getOrElse { graph.errors.record("plan ${m.id}", it); emptyList() }.takeIf { it.isNotEmpty() }?.let { m to it } }
            if (plans.isEmpty()) {
                _state.value = SessionStep.Empty
                graph.voice.speak("Τίποτα για σήμερα. Τα λέμε αύριο!", graph.settings.speechRate.first())
                return@launch
            }
            val s = Session(startedAt = now(), plannedModules = plans.joinToString(",") { it.first.id.name }, plannedItemCount = plans.sumOf { it.second.size })
            runCatching { graph.db.sessions().upsert(s) }.onFailure { graph.errors.record("session start", it) }
            session = s
            _state.value = SessionStep.Run(0, plans[0].first, plans[0].second, s.id)
        }
    }

    fun moduleDone() {
        val step = _state.value as? SessionStep.Run ?: return
        completed += step.items.size
        val next = step.index + 1
        if (next < plans.size) {
            _state.value = SessionStep.Run(next, plans[next].first, plans[next].second, step.sessionId)
            return
        }
        val s = session
        val planned = plans.sumOf { it.second.size }
        viewModelScope.launch {
            if (s != null) runCatching { graph.db.sessions().upsert(s.copy(endedAt = now(), completedItemCount = completed, updatedAt = now())) }
                .onFailure { graph.errors.record("session end", it) }
            _state.value = SessionStep.Summary(completed, planned)
            graph.feedback.success()
            graph.voice.speak("Μπράβο Δημήτρη! Έκανες $completed ασκήσεις σήμερα.", graph.settings.speechRate.first())
        }
    }
}
