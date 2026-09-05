package gr.dimitris.app.core.scheduler

import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemDao
import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.ScheduleDao
import gr.dimitris.app.core.data.Source
import gr.dimitris.app.core.data.now
import java.time.Instant
import java.time.ZoneId

fun startOfDay(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
    Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

/**
 * Picks today's items for one module: everything due, then new items (personal before seed)
 * up to [newPerDay] introduced per day, capped at [maxItems], ordered easy–hard–easy.
 */
class SessionBuilder(
    private val items: ItemDao,
    private val schedules: ScheduleDao,
    private val clock: () -> Long = ::now,
    private val newPerDay: Int = 8,
    private val maxItems: Int = 12,
) {
    suspend fun plan(module: ModuleId, kinds: List<ItemKind>): List<Item> {
        val t = clock()
        val pool = items.activeOfKinds(kinds)
        val byId = pool.associateBy { it.id }
        val rows = schedules.all(module).filter { it.itemId in byId }
        val boxOf = rows.associate { it.itemId to it.box }

        val due = rows.filter { it.nextDueAt <= t }.sortedBy { it.nextDueAt }.mapNotNull { byId[it.itemId] }
        val introducedToday = rows.count { it.createdAt >= startOfDay(t) }
        val scheduledIds = rows.map { it.itemId }.toSet()
        val fresh = pool.filter { it.id !in scheduledIds }
            .sortedWith(compareBy<Item> { it.source != Source.CAREGIVER }.thenBy { it.createdAt })
            .take((newPerDay - introducedToday).coerceAtLeast(0))

        val chosen = (due + fresh).take(maxItems)
        return sandwich(chosen) { boxOf[it.id] ?: 0 }
    }

    /** Highest box first and last, lowest in the middle. */
    internal fun sandwich(items: List<Item>, boxOf: (Item) -> Int): List<Item> {
        val sorted = items.sortedByDescending(boxOf)
        val front = sorted.filterIndexed { i, _ -> i % 2 == 0 }
        val back = sorted.filterIndexed { i, _ -> i % 2 == 1 }.reversed()
        return front + back
    }
}
