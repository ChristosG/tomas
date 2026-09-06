package gr.dimitris.app.core.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeAdviceDao : AdviceDao {
    val rows = MutableStateFlow<Map<String, Advice>>(emptyMap())
    private fun live() = rows.value.values.filter { !it.deleted }.sortedByDescending { it.at }

    override suspend fun upsert(advice: Advice) { rows.value = rows.value + (advice.id to advice) }
    override suspend fun recent(limit: Int): List<Advice> = live().take(limit)
    override fun observeRecent(limit: Int): Flow<List<Advice>> =
        rows.map { m -> m.values.filter { !it.deleted }.sortedByDescending { it.at }.take(limit) }
    override suspend fun newest(): Advice? = live().firstOrNull()

    override suspend fun changedSince(since: Long): List<Advice> =
        rows.value.values.filter { it.updatedAt > since }.sortedBy { it.updatedAt }
    override suspend fun stamps(ids: List<String>): List<RowStamp> =
        rows.value.values.filter { it.id in ids }.map { RowStamp(it.id, it.updatedAt) }
    override suspend fun upsertFromSync(rows: List<Advice>) { rows.forEach { upsert(it) } }
}
