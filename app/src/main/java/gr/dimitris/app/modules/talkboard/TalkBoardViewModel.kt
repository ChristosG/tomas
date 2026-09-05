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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Shown under the strip when a tap made no sound at all. */
const val SPEECH_FAILED = "Δεν ακούγεται η φωνή. Δες τις ρυθμίσεις."

sealed class Tab(val label: String) {
    object Favourites : Tab("Αγαπημένα")
    data class Cat(val category: Category) : Tab(category.greek)
}

class TalkBoardViewModel(private val graph: AppGraph) : ViewModel() {
    val strip = SentenceStrip()

    private val all: StateFlow<List<Item>> = graph.items.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Room re-emits this on every attempt insert, so a tap re-ranks favourites with no nudging. */
    private val usage: Flow<List<ItemCount>> = graph.db.attempts().mostUsed(ModuleId.TALKBOARD, USAGE_LIMIT)
        .catch { graph.errors.record("talkboard usage", it); emit(emptyList()) }

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

    /**
     * Set when a speak attempt made no sound, cleared by the next one that does. Silence is the one
     * failure Dimitris cannot diagnose himself, so it is said on the screen instead of only logged.
     */
    private val _speechError = MutableStateFlow<String?>(null)
    val speechError: StateFlow<String?> = _speechError.asStateFlow()

    /**
     * True from a said sentence until the strip next changes. No timer: the check mark is the answer
     * to "did it come out?", and Dimitris decides when he is done looking at it.
     */
    private val _spoken = MutableStateFlow(false)
    val spoken: StateFlow<Boolean> = _spoken.asStateFlow()

    fun selectTab(t: Tab) { _tab.value = t }

    /** Grid tap: say it and add it to the sentence. */
    fun tap(item: Item) {
        // A full strip still says the word; the attempt is logged for what it was, not what was asked.
        val added = strip.add(item)
        _stripFull.value = !added
        if (added) _spoken.value = false
        viewModelScope.launch { heard(graph.speaker.speak(item)); log(item, inStrip = added) }
    }

    /** Quick row tap: say it immediately, never added to the sentence. */
    fun tapQuick(item: Item) {
        viewModelScope.launch { heard(graph.speaker.speak(item)); log(item, inStrip = false) }
    }

    fun speakStrip() {
        val snapshot = strip.items.value
        if (snapshot.isEmpty()) return
        viewModelScope.launch {
            val said = heard(graph.speaker.speakText(strip.text))
            // An interrupted utterance still resolves as success at the TTS layer, so the check mark
            // is only earned if the sentence it belongs to is still the one on the strip.
            if (said && strip.items.value == snapshot) {
                graph.feedback.success()
                _spoken.value = true
            }
        }
    }

    fun undo() { strip.removeLast(); _stripFull.value = false; _spoken.value = false }
    fun clear() { strip.clear(); _stripFull.value = false; _spoken.value = false }

    /** Records the outcome of one speak attempt and reports whether anything was actually heard. */
    private fun heard(result: Result<*>): Boolean {
        result.fold(
            onSuccess = { _speechError.value = null },
            onFailure = { graph.errors.record("talkboard speak", it); _speechError.value = SPEECH_FAILED },
        )
        return result.isSuccess
    }

    private suspend fun log(item: Item, inStrip: Boolean) {
        val t = now()
        runCatching {
            graph.db.attempts().insert(
                Attempt(itemId = item.id, module = ModuleId.TALKBOARD, startedAt = t, durationMs = 0, outcome = Outcome.CORRECT,
                    cueLevel = null, detail = if (inStrip) """{"strip":true}""" else "{}")
            )
        }.onFailure { graph.errors.record("talkboard log", it) }
    }

    private companion object {
        /** Enough most-used items to fill the favourites tab several times over. */
        const val USAGE_LIMIT = 24
    }
}
