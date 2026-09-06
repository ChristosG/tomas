package gr.dimitris.app.core.data

class FakeScheduleDao : ScheduleDao {
    val rows = mutableMapOf<Pair<String, ModuleId>, Schedule>()
    override suspend fun upsert(schedule: Schedule) { rows[schedule.itemId to schedule.module] = schedule }
    override suspend fun get(itemId: String, module: ModuleId): Schedule? = rows[itemId to module]?.takeIf { !it.deleted }
    override suspend fun due(module: ModuleId, now: Long): List<Schedule> =
        rows.values.filter { it.module == module && !it.deleted && it.nextDueAt <= now }.sortedBy { it.nextDueAt }
    override suspend fun all(module: ModuleId): List<Schedule> = rows.values.filter { it.module == module && !it.deleted }
    override suspend fun allRows(): List<Schedule> = rows.values.filter { !it.deleted }
    override suspend fun masteredCount(topBox: Int): Int =
        rows.values.filter { !it.deleted && it.box >= topBox }.map { it.itemId }.distinct().size

    override suspend fun changedSince(since: Long): List<Schedule> =
        rows.values.filter { it.updatedAt > since }.sortedBy { it.updatedAt }
    override suspend fun stamps(ids: List<String>): List<RowStamp> =
        rows.values.map { RowStamp("${it.itemId}:${it.module}", it.updatedAt) }.filter { it.id in ids }
    override suspend fun upsertFromSync(rows: List<Schedule>) { rows.forEach { upsert(it) } }
}
