package gr.dimitris.app.core.data

class FakeScheduleDao : ScheduleDao {
    val rows = mutableMapOf<Pair<String, ModuleId>, Schedule>()
    override suspend fun upsert(schedule: Schedule) { rows[schedule.itemId to schedule.module] = schedule }
    override suspend fun get(itemId: String, module: ModuleId): Schedule? = rows[itemId to module]?.takeIf { !it.deleted }
    override suspend fun due(module: ModuleId, now: Long): List<Schedule> =
        rows.values.filter { it.module == module && !it.deleted && it.nextDueAt <= now }.sortedBy { it.nextDueAt }
    override suspend fun all(module: ModuleId): List<Schedule> = rows.values.filter { it.module == module && !it.deleted }
    override suspend fun allActive(): List<Schedule> = rows.values.filter { !it.deleted }
}
