package gr.dimitris.app.modules.talkboard

import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemCount

/** Pinned items first (alphabetical), then the most-used ones, up to [limit]. Only items in [all] count. */
object Favourites {
    fun rank(all: List<Item>, pinnedIds: Set<String>, usage: List<ItemCount>, limit: Int = 12): List<Item> {
        val byId = all.associateBy { it.id }
        val pinned = all.filter { it.id in pinnedIds }.sortedBy { it.text }
        val used = usage.sortedByDescending { it.n }.mapNotNull { byId[it.itemId] }.filter { it.id !in pinnedIds }
        // Nothing pinned and nothing used yet: an empty favourites tab is the first thing Dimitris
        // would see, so it opens on the start of the vocabulary instead — quick phrases, then food.
        if (pinned.isEmpty() && used.isEmpty()) {
            return all.sortedWith(compareBy({ it.category.ordinal }, { it.text })).take(limit)
        }
        return (pinned + used).take(limit)
    }
}
