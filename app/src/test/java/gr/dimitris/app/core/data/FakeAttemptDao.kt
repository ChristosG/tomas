package gr.dimitris.app.core.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeAttemptDao : AttemptDao {
    val rows = MutableStateFlow<List<Attempt>>(emptyList())
    private fun active() = rows.value.filter { !it.deleted }

    override suspend fun insert(attempt: Attempt) { rows.value = rows.value + attempt }

    override suspend fun since(since: Long): List<Attempt> =
        active().filter { it.startedAt >= since }.sortedBy { it.startedAt }

    override suspend fun between(from: Long, to: Long): List<Attempt> =
        active().filter { it.startedAt in from..to }.sortedBy { it.startedAt }

    override suspend fun all(limit: Int): List<Attempt> =
        active().sortedByDescending { it.startedAt }.take(limit)

    override suspend fun countFor(itemId: String, module: ModuleId): Int =
        active().count { it.itemId == itemId && it.module == module }

    override fun mostUsed(module: ModuleId, limit: Int): Flow<List<ItemCount>> = rows.map { all ->
        all.filter { !it.deleted && it.module == module }
            .groupingBy { it.itemId }.eachCount()
            .map { ItemCount(it.key, it.value) }
            .sortedByDescending { it.n }
            .take(limit)
    }

    override suspend fun lastUsePerModule(skipped: Outcome, summary: String): List<ModuleUse> =
        active().filter { it.outcome != skipped && it.itemId != summary }
            .groupBy { it.module }
            .map { (module, rows) -> ModuleUse(module, rows.maxOf { it.startedAt }) }

    override suspend fun changedSince(since: Long): List<Attempt> =
        rows.value.filter { it.updatedAt > since }.sortedBy { it.updatedAt }

    override suspend fun stamps(ids: List<String>): List<RowStamp> =
        rows.value.filter { it.id in ids }.map { RowStamp(it.id, it.updatedAt) }

    /** Append-only, like the real `@Insert(IGNORE)`: an id already here keeps the row it has. */
    override suspend fun upsertFromSync(rows: List<Attempt>) {
        val known = this.rows.value.mapTo(mutableSetOf()) { it.id }
        this.rows.value = this.rows.value + rows.filter { known.add(it.id) }
    }
}
