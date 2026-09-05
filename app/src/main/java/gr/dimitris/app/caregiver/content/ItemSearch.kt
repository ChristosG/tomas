package gr.dimitris.app.caregiver.content

import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.greek.Greek

object ItemSearch {
    private fun key(s: String) = Greek.stripAccents(Greek.normalize(s))

    fun filter(items: List<Item>, query: String): List<Item> {
        val q = key(query)
        if (q.isEmpty()) return items
        return items.filter { key(it.text).contains(q) }
    }
}
