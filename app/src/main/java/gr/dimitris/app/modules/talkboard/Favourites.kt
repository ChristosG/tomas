package gr.dimitris.app.modules.talkboard

import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemCount

/** Pinned items first (alphabetical), then the most-used ones, up to [limit]. Only items in [all] count. */
object Favourites {
    fun rank(all: List<Item>, pinnedIds: Set<String>, usage: List<ItemCount>, limit: Int = 12): List<Item> {
        val byId = all.associateBy { it.id }
        val pinned = all.filter { it.id in pinnedIds }.sortedBy { it.text }
        val used = usage.sortedByDescending { it.n }.mapNotNull { byId[it.itemId] }.filter { it.id !in pinnedIds }
        return (pinned + used).take(limit)
    }
}
