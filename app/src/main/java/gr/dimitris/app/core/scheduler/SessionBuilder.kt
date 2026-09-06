package gr.dimitris.app.core.scheduler

import gr.dimitris.app.caregiver.insights.Focus
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
 * Picks today's items for one module: everything due, then new items (the caregiver's own first and
 * newest of those first of all — see [NEWEST_OF_HERS_FIRST] — then seed) up to [newPerDay]
 * introduced per day, capped at [maxItems], ordered easy–hard–easy.
 *
 * A couple of the places are held for words he has never seen — see [NEW_SLOTS].
 *
 * When Claude has been asked for advice and named a [focus], the words it named are guaranteed a
 * couple of places in the sitting ([FOCUS_SLOTS]) even when nothing else would have brought them
 * round today. That is the whole of what a focus does to his morning: it decides what is *in* the
 * sitting, never what order he meets it in — the sandwich still does that, so an advice cannot hand
 * him the hardest word in the list first thing.
 */
class SessionBuilder(
    private val items: ItemDao,
    private val schedules: ScheduleDao,
    private val clock: () -> Long = ::now,
    private val newPerDay: Int = 8,
    private val maxItems: Int = 12,
    /** The live advice's focus, or null — which is the app as it was before phase 11. */
    private val focus: Focus? = null,
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
            .sortedWith(NEWEST_OF_HERS_FIRST)
            .take((newPerDay - introducedToday).coerceAtLeast(0))

        // A focused word that is neither due nor new is still worth today: "work on «καφές»" that
        // waits until καφές comes round again in nine days is not advice anybody acted on.
        val settled = (due + fresh).mapTo(mutableSetOf()) { it.id }
        val wanted = focus?.let { f -> pool.filter { it.id !in settled && f.matches(it) } }.orEmpty()

        // Due first, but never *all* the way. Two kinds of word have places held for them off the
        // due end, which costs the most-overdue nothing it would not have lost to the cap anyway:
        // a word he has never seen ([NEW_SLOTS]), and a word the focus named ([FOCUS_SLOTS]). Both
        // are *guaranteed* rather than "whatever room is left" — a reservation that a full due list
        // can eat is not a reservation, and this is the one mechanism by which an advice reaches
        // his morning at all.
        val newSlots = minOf(NEW_SLOTS, fresh.size)
        val focusSlots = minOf(FOCUS_SLOTS, wanted.size)
        val core = due.take((maxItems - newSlots - focusSlots).coerceAtLeast(0))
        val room = (maxItems - core.size - newSlots).coerceAtLeast(0)

        val chosen = (core + wanted.take(room) + fresh).take(maxItems)
        // And then the sandwich orders the whole sitting, focus and new words included.
        //
        // They are *not* pushed to the front. Easy–hard–easy is how he is handed a session — start
        // on something he has, meet the hard ones in the middle, end on something he has — and a
        // brand-new word or a word Claude has flagged as difficult is precisely the hardest thing
        // in the list. Getting into the sitting is what the focus is for; opening it is not.
        return sandwich(chosen) { boxOf[it.id] ?: 0 }
    }

    companion object {
        /**
         * The order new items are taken in: everything a caregiver wrote first, newest of hers at
         * the front, and the seed vocabulary after it in the order it ships.
         *
         * The newest-first half is Chris' own report. He would add a word in caregiver mode and
         * then have "to use the app for hours until it randomly appears": personal items already
         * came before seed ones, but *within* them the oldest went first, so a word added tonight
         * queued behind every earlier one he had ever written and could be weeks away. The word
         * somebody has just sat down and typed is the word that matters today — it is almost always
         * about something happening now — so it goes in first, and «Δοκίμασέ το» in the editor is
         * the other half of the same answer.
         *
         * The seed list keeps its own ascending order: it is curated easiest-first, and reversing
         * it would hand him the hardest words the app ships with on day one.
         */
        internal val NEWEST_OF_HERS_FIRST: Comparator<Item> = Comparator { a, b ->
            val hersA = a.source == Source.CAREGIVER
            val hersB = b.source == Source.CAREGIVER
            when {
                hersA != hersB -> if (hersA) -1 else 1
                hersA -> b.createdAt.compareTo(a.createdAt)
                else -> a.createdAt.compareTo(b.createdAt)
            }
        }

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

        /**
         * Places kept for the words a live focus named, on the same terms as [NEW_SLOTS] and for
         * the same reason: an advice that only takes effect on a quiet day is an advice the
         * caregivers would have to carry out by hand, which is the thing phase 11 exists to stop.
         *
         * Two, not more. A focus of eight words that emptied the due list would be Claude planning
         * his morning instead of advising on it, and the Leitner schedule is the part of this app
         * that has been right for eleven phases.
         */
        const val FOCUS_SLOTS = 2
    }

    /** Highest box first and last, lowest in the middle. */
    internal fun sandwich(items: List<Item>, boxOf: (Item) -> Int): List<Item> {
        val sorted = items.sortedByDescending(boxOf)
        val front = sorted.filterIndexed { i, _ -> i % 2 == 0 }
        val back = sorted.filterIndexed { i, _ -> i % 2 == 1 }.reversed()
        return front + back
    }
}
