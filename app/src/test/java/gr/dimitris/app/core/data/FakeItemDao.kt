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
    override fun observeActive(): Flow<List<Item>> = rows.map { m -> m.values.filter { !it.deleted }.sortedWith(compareBy({ it.category.name }, { it.text })) }
    override fun observeByCategory(category: Category): Flow<List<Item>> = observeActive().map { l -> l.filter { it.category == category } }
    override suspend fun activeOfKinds(kinds: List<ItemKind>): List<Item> = active().filter { it.kind in kinds }
    override suspend fun activeOfSource(source: Source): List<Item> = active().filter { it.source == source }
    override suspend fun allActive(): List<Item> = active()
    override suspend fun all(): List<Item> = rows.value.values.toList()
    override suspend fun countActive(): Int = active().size
    override suspend fun softDelete(id: String, now: Long) { rows.value[id]?.let { upsert(it.copy(deleted = true, updatedAt = now)) } }
    override fun observePinned(): Flow<List<Item>> = observeActive().map { l -> l.filter { it.pinned }.sortedBy { it.text } }
    override suspend fun byIds(ids: List<String>): List<Item> = active().filter { it.id in ids }
    override suspend fun withPrices(): List<Item> = active().filter { it.priceCents != null }

    override suspend fun changedSince(since: Long): List<Item> =
        rows.value.values.filter { it.updatedAt > since }.sortedBy { it.updatedAt }
    override suspend fun stamps(ids: List<String>): List<RowStamp> =
        rows.value.values.filter { it.id in ids }.map { RowStamp(it.id, it.updatedAt) }
    override suspend fun upsertFromSync(rows: List<Item>) { rows.forEach { upsert(it) } }
    override suspend fun awaitingMedia(): List<Item> = rows.value.values.filter { it.imagePath?.startsWith("media://") == true }
}
