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

    override suspend fun countFor(itemId: String, module: ModuleId): Int =
        active().count { it.itemId == itemId && it.module == module }

    override fun mostUsed(module: ModuleId, limit: Int): Flow<List<ItemCount>> = rows.map { all ->
        all.filter { !it.deleted && it.module == module }
            .groupingBy { it.itemId }.eachCount()
            .map { ItemCount(it.key, it.value) }
            .sortedByDescending { it.n }
            .take(limit)
    }

    override suspend fun lastUsePerModule(skipped: Outcome): List<ModuleUse> =
        active().filter { it.outcome != skipped }
            .groupBy { it.module }
            .map { (module, rows) -> ModuleUse(module, rows.maxOf { it.startedAt }) }
}
