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
 * round today — including a word that is due but so far down a saturated due list that the cap
 * would have cut it. That is the whole of what a focus does to his morning: it decides what is *in*
 * the sitting, never what order he meets it in — the sandwich still does that, so an advice cannot
 * hand him the hardest word in the list first thing.
 *
 * Both reservations together stop at half of [maxItems], so what he is actually due always keeps
 * at least half the sitting.
 */
class SessionBuilder(
    private val items: ItemDao,
    private val schedules: ScheduleDao,
    private val clock: () -> Long = ::now,
    private val newPerDay: Int = 8,
    private val maxItems: Int = 12,
    /** The live advice's focus, or null — which is the app as it was before phase 11. */
    private val focus: Focus? = null,
    /**
     * What the 1..5 difficulty he set admits into the pool, where a module grades its items by
     * something the [ItemKind] cannot say — «Τραγούδα και πες το» by how many syllables a phrase is
     * (see [gr.dimitris.app.core.difficulty.Difficulty.syllables]). Everything before this line still
     * decides *which* of the admitted items he meets today: the filter narrows the vocabulary, it
     * never reorders the sitting or overrules a due word.
     *
     * A filter that admits nothing is the caller's problem, not this class's: it returns an empty
     * plan, and the module decides whether to widen or to say there is nothing to do.
     */
    private val filter: (Item) -> Boolean = { true },
) {
    suspend fun plan(module: ModuleId, kinds: List<ItemKind>): List<Item> {
        val t = clock()
        val pool = items.activeOfKinds(kinds).filter(filter)
        val byId = pool.associateBy { it.id }
        val rows = schedules.all(module).filter { it.itemId in byId }
        val boxOf = rows.associate { it.itemId to it.box }

        val due = rows.filter { it.nextDueAt <= t }.sortedBy { it.nextDueAt }.mapNotNull { byId[it.itemId] }
        val introducedToday = rows.count { it.createdAt >= startOfDay(t) }
        val scheduledIds = rows.map { it.itemId }.toSet()
        val fresh = pool.filter { it.id !in scheduledIds }
            .sortedWith(NEWEST_OF_HERS_FIRST)
            .take((newPerDay - introducedToday).coerceAtLeast(0))

        // A focused word is worth today whatever else it is. "Work on «καφές»" that waits until
        // καφές comes round again in nine days is not advice anybody acted on — and neither is one
        // for a word that *is* due but sits past the cap on a saturated due list, which is the very
        // state the reservation was written for. So the candidates are the whole pool: due, new and
        // settled alike, and a word that was already coming simply spends no reservation.
        val wanted = focus?.let { f -> pool.filter { f.matches(it) } }.orEmpty()

        // Due first, but never *all* the way. Two kinds of word have places held for them off the
        // due end, which costs the most-overdue nothing it would not have lost to the cap anyway:
        // a word he has never seen ([NEW_SLOTS]), and a word the focus named ([FOCUS_SLOTS]). Both
        // are *guaranteed* rather than "whatever room is left" — a reservation that a full due list
        // can eat is not a reservation, and this is the one mechanism by which an advice reaches
        // his morning at all.
        //
        // Between them they never take more than half the sitting. «Τραγούδα και πες το» plans
        // three items, and there two new phrases and a focused one would have left no due place at
        // all: what he is due is the exercise, and the intake is the thing that gives way, not the
        // Leitner schedule that has been right for eleven phases. Half, rounded down — with three
        // items that is one held place and two due.
        val reservable = maxItems / 2
        // The front of the due list, which no reservation can reach. A focused word standing there
        // is already coming today and needs nothing held for it — and one standing further down,
        // where a place is held that turns out to be unnecessary, costs the sitting nothing either:
        // see the backfill below.
        val safe = due.take((maxItems - reservable).coerceAtLeast(0)).mapTo(mutableSetOf()) { it.id }
        val focusSlots = minOf(FOCUS_SLOTS, wanted.count { it.id !in safe }, reservable)
        val newSlots = minOf(NEW_SLOTS, fresh.size, reservable - focusSlots)
        val core = due.take((maxItems - newSlots - focusSlots).coerceAtLeast(0))
        val inCore = core.mapTo(mutableSetOf()) { it.id }
        val room = (maxItems - core.size - newSlots).coerceAtLeast(0)

        // The focus into what the due end left, then the new words, and then the due list again.
        //
        // That last one is the backfill, and it is what makes a *held* place cost nothing when it
        // turns out not to be needed. A focused word standing between [safe] and the cut is already
        // inside `core`, so the place held for it is never spent — and without the backfill the due
        // word it displaced was simply lost and he was handed an eleven-word sitting for having a
        // focus at all. A reservation nobody used goes back to the due list it came from.
        //
        // `distinctBy` because the same word can arrive by more than one road — a focused word that
        // is also a new one, or one of the due words the backfill offers again — and he is not
        // asked for the same word twice.
        val chosen = (core + wanted.filter { it.id !in inCore }.take(room) + fresh + due)
            .distinctBy { it.id }
            .take(maxItems)
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
         *
         * With [FOCUS_SLOTS] it is bounded by half the sitting — see `plan` — so a module that
         * plans three items always keeps two of them for what he is due.
         */
        const val NEW_SLOTS = 2

        /**
         * Places *held* for the words a live focus named, on the same terms as [NEW_SLOTS] and for
         * the same reason: an advice that only takes effect on a quiet day is an advice the
         * caregivers would have to carry out by hand, which is the thing phase 11 exists to stop.
         *
         * Two held, which is not the same as two admitted. What this number promises is that a full
         * due list can never squeeze the focus out altogether; on a quiet day, where the due words
         * and the new intake leave places over, the focus fills them before the rest of the new
         * words do, and more than two of its words can get in. What it can never do is empty the
         * due list: the two reservations together stop at half the sitting, and everything past
         * them is room nobody else wanted.
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
