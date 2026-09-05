package gr.dimitris.app.core.scheduler

import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Outcome
import gr.dimitris.app.core.data.Schedule
import gr.dimitris.app.core.data.ScheduleDao
import gr.dimitris.app.core.data.now

/** Keeps one Schedule row per (item, module) and moves it through the Leitner boxes. */
class Scheduler(private val schedules: ScheduleDao, private val clock: () -> Long = ::now) {

    suspend fun due(module: ModuleId): List<Schedule> = schedules.due(module, clock())

    suspend fun record(itemId: String, module: ModuleId, outcome: Outcome, cueLevel: Int?): Schedule {
        val t = clock()
        val current = schedules.get(itemId, module)
            ?: Schedule(itemId = itemId, module = module, box = LeitnerPolicy.MIN_BOX, nextDueAt = t, createdAt = t, updatedAt = t)
        val box = LeitnerPolicy.nextBox(current.box, outcome, cueLevel)
        val next = current.copy(
            box = box,
            nextDueAt = t + LeitnerPolicy.intervalMillis(box),
            lastSeenAt = t,
            streak = if (outcome == Outcome.CORRECT) current.streak + 1 else 0,
            updatedAt = t,
        )
        schedules.upsert(next)
        return next
    }
}
