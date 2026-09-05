package gr.dimitris.app.modules.talkboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import gr.dimitris.app.AppGraph
import gr.dimitris.app.core.data.Attempt
import gr.dimitris.app.core.data.Category
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemCount
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Outcome
import gr.dimitris.app.core.data.now
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class Tab(val label: String) {
    object Favourites : Tab("Αγαπημένα")
    data class Cat(val category: Category) : Tab(category.greek)
}

class TalkBoardViewModel(private val graph: AppGraph) : ViewModel() {
    val strip = SentenceStrip()

    private val all: StateFlow<List<Item>> = graph.items.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val usage = MutableStateFlow<List<ItemCount>>(emptyList())
    private val pinnedIds: StateFlow<Set<String>> = graph.db.items().observePinned().map { l -> l.map { it.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val _tab = MutableStateFlow<Tab>(Tab.Favourites)
    val tab: StateFlow<Tab> = _tab.asStateFlow()

    /** Tabs: favourites, then every category that has at least one item, QUICK excluded (it has its own row). */
    val tabs: StateFlow<List<Tab>> = all.map { items ->
        listOf(Tab.Favourites) + Category.entries.filter { c -> c != Category.QUICK && items.any { it.category == c } }.map { Tab.Cat(it) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), listOf(Tab.Favourites))

    val quick: StateFlow<List<Item>> = all.map { l -> l.filter { it.category == Category.QUICK } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val shown: StateFlow<List<Item>> = combine(all, pinnedIds, usage, _tab) { items, pinned, use, tab ->
        when (tab) {
            Tab.Favourites -> Favourites.rank(items, pinned, use)
            is Tab.Cat -> items.filter { it.category == tab.category }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _stripFull = MutableStateFlow(false)
    val stripFull: StateFlow<Boolean> = _stripFull.asStateFlow()

    init { refreshUsage() }

    fun selectTab(t: Tab) { _tab.value = t }

    /** Grid tap: say it and add it to the sentence. */
    fun tap(item: Item) {
        _stripFull.value = !strip.add(item)
        viewModelScope.launch { graph.speaker.speak(item); log(item, inStrip = true) }
    }

    /** Quick row tap: say it immediately, never added to the sentence. */
    fun tapQuick(item: Item) {
        viewModelScope.launch { graph.speaker.speak(item); log(item, inStrip = false) }
    }

    fun speakStrip() {
        val items = strip.items.value
        if (items.isEmpty()) return
        viewModelScope.launch {
            graph.speaker.speakText(strip.text)
            graph.feedback.success()
        }
    }

    fun undo() { strip.removeLast(); _stripFull.value = false }
    fun clear() { strip.clear(); _stripFull.value = false }

    private suspend fun log(item: Item, inStrip: Boolean) {
        val t = now()
        runCatching {
            graph.db.attempts().insert(
                Attempt(itemId = item.id, module = ModuleId.TALKBOARD, startedAt = t, durationMs = 0, outcome = Outcome.CORRECT,
                    cueLevel = null, detail = if (inStrip) """{"strip":true}""" else "{}")
            )
        }.onFailure { graph.errors.record("talkboard log", it) }
        refreshUsage()
    }

    private fun refreshUsage() {
        viewModelScope.launch { runCatching { usage.value = graph.db.attempts().mostUsed(ModuleId.TALKBOARD, 24) } }
    }
}
