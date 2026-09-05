package gr.dimitris.app.core.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeItemDao : ItemDao {
    val rows = MutableStateFlow<Map<String, Item>>(emptyMap())
    private fun active() = rows.value.values.filter { !it.deleted }

    override suspend fun upsert(item: Item) { rows.value = rows.value + (item.id to item) }
    override suspend fun upsertAll(items: List<Item>) { items.forEach { upsert(it) } }
    override suspend fun get(id: String): Item? = rows.value[id]
    override fun observeActive(): Flow<List<Item>> = rows.map { m -> m.values.filter { !it.deleted }.sortedWith(compareBy({ it.category }, { it.text })) }
    override fun observeByCategory(category: Category): Flow<List<Item>> = observeActive().map { l -> l.filter { it.category == category } }
    override suspend fun activeOfKinds(kinds: List<ItemKind>): List<Item> = active().filter { it.kind in kinds }
    override suspend fun activeOfSource(source: Source): List<Item> = active().filter { it.source == source }
    override suspend fun countActive(): Int = active().size
    override suspend fun softDelete(id: String, now: Long) { rows.value[id]?.let { upsert(it.copy(deleted = true, updatedAt = now)) } }
}
