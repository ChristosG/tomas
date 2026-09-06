package gr.dimitris.app.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import gr.dimitris.app.AppGraph
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.modules.Module
import gr.dimitris.app.modules.scripts.ScriptsModule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The plan behind one free-practice sitting, held for as long as the practice screen is on the back
 * stack.
 *
 * It exists because the plan used to live in a `remember` inside the screen: tapping «Μίλα» took the
 * screen out of composition, and coming back asked the module for a *new* plan — a different random
 * dialogue, a re-shuffled set of words — so he lost his place in the middle of the exercise he was
 * doing. A ViewModel scoped to the practice route survives that detour, exactly as
 * [SessionViewModel] does for the mixed session.
 *
 * [items] is null while the plan is being built and empty when the module has nothing to offer;
 * the screen has a different thing to say for each.
 *
 * [itemId] and [scriptId] are «Δοκίμασέ το» and «Παίξ' το» from the caregiver's editors: the route
 * names the one thing to run, and the module gets exactly that instead of a plan of its own.
 */
class PracticeViewModel(
    private val graph: AppGraph,
    moduleId: ModuleId,
    private val itemId: String? = null,
    private val scriptId: String? = null,
) : ViewModel() {
    /** Null only if the route outlived the module registry; the empty screen is the answer either way. */
    val module: Module? = graph.modules.firstOrNull { it.id == moduleId }

    private val _items = MutableStateFlow<List<Item>?>(null)
    val items: StateFlow<List<Item>?> = _items.asStateFlow()

    init {
        viewModelScope.launch {
            _items.value = module?.let { m ->
                runCatching { plan(m) }.getOrElse { graph.errors.record("practice ${m.id}", it); emptyList() }
            } ?: emptyList()
        }
    }

    /**
     * What this sitting runs. Named on the route, or the module's own free-practice plan.
     *
     * Chris added a word in caregiver mode and had no way to see it in use — "I would have to use
     * the app for hours until it randomly appears". A named item is therefore never a hint to the
     * module's chooser: it is the whole list, so the word she just wrote is the word that opens.
     * A word or a dialogue that has been deleted between the tap and here comes back empty, and the
     * screen already knows how to say so.
     */
    private suspend fun plan(m: Module): List<Item> = when {
        itemId != null -> listOfNotNull(graph.items.get(itemId))
        scriptId != null -> ScriptsModule.turnsOf(graph, scriptId)
        else -> m.practiceFor(graph)
    }
}
