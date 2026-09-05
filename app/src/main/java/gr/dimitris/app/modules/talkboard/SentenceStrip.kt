package gr.dimitris.app.modules.talkboard

import gr.dimitris.app.core.data.Item
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The row of chosen words at the top of the talk board. */
class SentenceStrip(private val max: Int = 6) {
    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items.asStateFlow()

    val text: String get() = _items.value.joinToString(" ") { it.text }

    fun add(item: Item): Boolean {
        if (_items.value.size >= max) return false
        _items.value = _items.value + item
        return true
    }

    fun removeLast() { _items.value = _items.value.dropLast(1) }
    fun clear() { _items.value = emptyList() }
}
