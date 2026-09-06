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
 *
 * A couple of the places are held for words he has never seen — see [NEW_SLOTS].
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

        // Due first, but never *all* the way: a couple of places are held back for a word he has
        // never seen, whenever there is one to hold them for.
        val reserved = minOf(NEW_SLOTS, fresh.size)
        val chosen = (due.take((maxItems - reserved).coerceAtLeast(0)) + fresh).take(maxItems)
        return sandwich(chosen) { boxOf[it.id] ?: 0 }
    }

    companion object {
        /**
         * Places kept for a word he has never met, however long the due list is.
         *
         * Since «Άκου» is on every screen from the first second (spec §12), a word he asks to hear
         * is written ASSISTED, and [LeitnerPolicy] holds an ASSISTED item's box rather than
         * promoting it — which is right: a word he needs the model for is not a word he has
         * retrieved. But a man who listens before every word then keeps every item at a one-day
         * interval, the due list saturates at [maxItems], and new vocabulary silently stops arriving
         * for ever. Nothing fails; he just never meets a new word again.
         *
         * The box rule is not the thing to bend — the intake is. Two places, off the *due* end, so
         * the reservation costs the most-overdue nothing it would not have lost to the cap anyway.
         * When there are fewer than [maxItems] due, this changes nothing: the rest of `fresh` still
         * follows them in, exactly as before.
         */
        const val NEW_SLOTS = 2
    }

    /** Highest box first and last, lowest in the middle. */
    internal fun sandwich(items: List<Item>, boxOf: (Item) -> Int): List<Item> {
        val sorted = items.sortedByDescending(boxOf)
        val front = sorted.filterIndexed { i, _ -> i % 2 == 0 }
        val back = sorted.filterIndexed { i, _ -> i % 2 == 1 }.reversed()
        return front + back
    }
}
