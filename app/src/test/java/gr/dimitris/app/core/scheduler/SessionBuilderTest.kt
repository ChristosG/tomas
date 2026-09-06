package gr.dimitris.app.core.scheduler

import gr.dimitris.app.caregiver.insights.Focus
import gr.dimitris.app.core.data.FakeItemDao
import gr.dimitris.app.core.data.FakeScheduleDao
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Schedule
import gr.dimitris.app.core.data.Source
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionBuilderTest {
    private val items = FakeItemDao()
    private val schedules = FakeScheduleDao()
    private val noon = 1_700_000_000_000L  // arbitrary fixed instant
    private val builder = SessionBuilder(items, schedules, clock = { noon }, newPerDay = 3, maxItems = 5)
    private val m = ModuleId.WORDCOACH

    private suspend fun word(text: String, source: Source = Source.SEED, createdAt: Long = 1): Item =
        Item(text = text, kind = ItemKind.WORD, source = source, createdAt = createdAt).also { items.upsert(it) }

    @Test fun `due items come first, then new ones up to the daily cap`() = runTest {
        val due = word("due"); val later = word("later")
        val n1 = word("n1", createdAt = 1); val n2 = word("n2", createdAt = 2); val n3 = word("n3", createdAt = 3); val n4 = word("n4", createdAt = 4)
        schedules.upsert(Schedule(due.id, m, box = 2, nextDueAt = noon - 1, createdAt = noon - 10 * LeitnerPolicy.DAY_MS))
        schedules.upsert(Schedule(later.id, m, box = 3, nextDueAt = noon + 1, createdAt = noon - 10 * LeitnerPolicy.DAY_MS))
        val plan = builder.plan(m, listOf(ItemKind.WORD))
        assertEquals(setOf(due.id, n1.id, n2.id, n3.id), plan.map { it.id }.toSet())
        assert(n4.id !in plan.map { it.id })
    }

    @Test fun `new items introduced today count against the cap`() = runTest {
        word("old"); val a = word("a"); val b = word("b")
        val old = items.rows.value.values.first { it.text == "old" }
        schedules.upsert(Schedule(old.id, m, box = 1, nextDueAt = noon + LeitnerPolicy.DAY_MS, createdAt = noon - 1000))  // introduced today
        schedules.upsert(Schedule(a.id, m, box = 1, nextDueAt = noon + LeitnerPolicy.DAY_MS, createdAt = noon - 2000))
        val plan = builder.plan(m, listOf(ItemKind.WORD))
        assertEquals(listOf(b.id), plan.map { it.id })   // cap 3, two already introduced today, one slot left
    }

    @Test fun `personal items are introduced before seed items`() = runTest {
        word("seed", Source.SEED, createdAt = 1)
        val mine = word("mine", Source.CAREGIVER, createdAt = 2)
        val plan = SessionBuilder(items, schedules, { noon }, newPerDay = 1, maxItems = 5).plan(m, listOf(ItemKind.WORD))
        assertEquals(listOf(mine.id), plan.map { it.id })
    }

    /**
     * Chris' own report, in one plan: he adds a word in caregiver mode and then has "to use the app
     * for hours until it randomly appears".
     *
     * Personal items already came before seed ones, but among *them* the oldest went first — so a
     * word written last night queued behind every word he had ever written, and behind a shipped
     * vocabulary of hundreds waiting its turn at eight a day. The word somebody has just sat down
     * and typed is the one that matters today, so it goes in first; the seed list keeps its own
     * curated easiest-first order behind them both.
     */
    @Test fun `the word she added yesterday is introduced before the seed words, newest of hers first`() = runTest {
        // A pile of fresh seed words, every one of them written *after* hers.
        repeat(10) { i -> word("seed$i", Source.SEED, createdAt = noon - 1000 + i) }
        val lastWeek = word("περσινή", Source.CAREGIVER, createdAt = noon - 7 * LeitnerPolicy.DAY_MS)
        val yesterday = word("χθεσινή", Source.CAREGIVER, createdAt = noon - LeitnerPolicy.DAY_MS)

        val oneSlot = SessionBuilder(items, schedules, { noon }, newPerDay = 1, maxItems = 5).plan(m, listOf(ItemKind.WORD))
        assertEquals("the one place goes to the word she wrote last", listOf(yesterday.id), oneSlot.map { it.id })

        val twoSlots = SessionBuilder(items, schedules, { noon }, newPerDay = 2, maxItems = 5).plan(m, listOf(ItemKind.WORD))
        assertEquals(
            "and both of hers still come in ahead of ten fresher seed words",
            setOf(yesterday.id, lastWeek.id), twoSlots.map { it.id }.toSet(),
        )
    }

    /**
     * The one thing «Άκου» being on every screen could have broken, and the only place it shows.
     *
     * A word he asks to hear is ASSISTED, and ASSISTED holds its Leitner box instead of promoting
     * it — right, but it means a man who listens before every word keeps every item at a one-day
     * interval. The due list then saturates, and without reserved places new vocabulary would stop
     * arriving for ever, silently. Twenty overdue words must still leave room for two new ones.
     */
    @Test fun `a saturated due list still lets new words in`() = runTest {
        val big = SessionBuilder(items, schedules, { noon }, newPerDay = 8, maxItems = 12)
        repeat(20) { i ->
            val w = word("due$i", createdAt = i.toLong())
            schedules.upsert(Schedule(w.id, m, box = 1, nextDueAt = noon - 1, createdAt = noon - 10 * LeitnerPolicy.DAY_MS))
        }
        val newWords = (0 until 5).map { word("new$it", createdAt = 100L + it) }

        val plan = big.plan(m, listOf(ItemKind.WORD))

        assertEquals("the sitting is still full", 12, plan.size)
        val fresh = plan.filter { it.id in newWords.map { n -> n.id } }
        assertEquals("two places are held for words he has never seen: ${fresh.map { it.text }}", 2, fresh.size)
        assertEquals("and they are the oldest new ones, in order", listOf("new0", "new1"), fresh.map { it.text }.sorted())
    }

    /** With room to spare the reservation changes nothing: every new word still comes in. */
    @Test fun `a short due list still fills up with new words`() = runTest {
        val big = SessionBuilder(items, schedules, { noon }, newPerDay = 8, maxItems = 12)
        repeat(3) { i ->
            val w = word("due$i", createdAt = i.toLong())
            schedules.upsert(Schedule(w.id, m, box = 1, nextDueAt = noon - 1, createdAt = noon - 10 * LeitnerPolicy.DAY_MS))
        }
        repeat(5) { word("new$it", createdAt = 100L + it) }

        val plan = big.plan(m, listOf(ItemKind.WORD))

        assertEquals("three due and all five new, exactly as before", 8, plan.size)
        assertEquals(5, plan.count { it.text.startsWith("new") })
    }

    // ---- the focus (phase 11) ----------------------------------------------------------------

    private fun focus(items: List<String> = emptyList(), sounds: List<String> = emptyList()) =
        Focus(items = items, sounds = sounds, at = noon)

    /**
     * The loop phase 11 closes: Claude reads his whole journey, names a word, and the next sitting
     * puts it first. Without this the advice was a paragraph a caregiver had to act on by hand.
     */
    @Test fun `a focused word is planned first`() = runTest {
        repeat(4) { i ->
            val w = word("due$i", createdAt = i.toLong())
            schedules.upsert(Schedule(w.id, m, box = 1, nextDueAt = noon - 1, createdAt = noon - 10 * LeitnerPolicy.DAY_MS))
        }
        val wanted = word("καφές", createdAt = 50)
        schedules.upsert(Schedule(wanted.id, m, box = 1, nextDueAt = noon - 1, createdAt = noon - 10 * LeitnerPolicy.DAY_MS))

        val plan = SessionBuilder(items, schedules, { noon }, newPerDay = 0, maxItems = 5, focus = focus(listOf("Καφές")))
            .plan(m, listOf(ItemKind.WORD))

        assertEquals("matched unaccented and case-insensitively", "καφές", plan.first().text)
        assertEquals("and nothing due was dropped for it", 5, plan.size)
    }

    /** «Δούλεψε τα «π»» is speech-therapy advice, and it reaches every word that starts with π. */
    @Test fun `words that start with a focused sound come first too`() = runTest {
        repeat(4) { i ->
            val w = word("due$i", createdAt = i.toLong())
            schedules.upsert(Schedule(w.id, m, box = 1, nextDueAt = noon - 1, createdAt = noon - 10 * LeitnerPolicy.DAY_MS))
        }
        val pi = Item(text = "πόρτα", kind = ItemKind.WORD, firstSound = "π", createdAt = 60).also { items.upsert(it) }
        schedules.upsert(Schedule(pi.id, m, box = 1, nextDueAt = noon - 1, createdAt = noon - 10 * LeitnerPolicy.DAY_MS))

        val plan = SessionBuilder(items, schedules, { noon }, newPerDay = 0, maxItems = 5, focus = focus(sounds = listOf("π")))
            .plan(m, listOf(ItemKind.WORD))

        assertEquals("πόρτα", plan.first().text)
    }

    /**
     * "Work on «καφές»" that waits until καφές comes round again in nine days is not advice anybody
     * acted on. A focused word that is not due is pulled in — but only into room the due list was
     * not going to use.
     */
    @Test fun `a focused word that is not due is still planned today`() = runTest {
        val soon = word("αργότερα", createdAt = 1)
        schedules.upsert(Schedule(soon.id, m, box = 3, nextDueAt = noon + 9 * LeitnerPolicy.DAY_MS, createdAt = noon - 10 * LeitnerPolicy.DAY_MS))
        val wanted = word("καφές", createdAt = 2)
        schedules.upsert(Schedule(wanted.id, m, box = 3, nextDueAt = noon + 9 * LeitnerPolicy.DAY_MS, createdAt = noon - 10 * LeitnerPolicy.DAY_MS))

        val plan = SessionBuilder(items, schedules, { noon }, newPerDay = 0, maxItems = 5, focus = focus(listOf("καφές")))
            .plan(m, listOf(ItemKind.WORD))

        assertEquals(listOf("καφές"), plan.map { it.text })
    }

    /** The focus orders the sitting; it never shortens the list of words that are actually due. */
    @Test fun `the focus never removes a due word`() = runTest {
        val due = (0 until 5).map { i ->
            word("due$i", createdAt = i.toLong()).also { w ->
                schedules.upsert(Schedule(w.id, m, box = 1, nextDueAt = noon - 10 + i, createdAt = noon - 10 * LeitnerPolicy.DAY_MS))
            }
        }
        repeat(3) { i -> word("μακρινή$i", createdAt = 100L + i) }   // never scheduled, so "new"

        val builder = { f: Focus? -> SessionBuilder(items, schedules, { noon }, newPerDay = 0, maxItems = 5, focus = f) }
        val without = builder(null).plan(m, listOf(ItemKind.WORD)).map { it.id }.toSet()
        val with = builder(focus(listOf("μακρινή0", "μακρινή1", "μακρινή2"))).plan(m, listOf(ItemKind.WORD)).map { it.id }.toSet()

        assertEquals("every due word is still planned", due.map { it.id }.toSet(), without)
        assertEquals("and still is, with a focus asking for three others", due.map { it.id }.toSet(), with)
    }

    /** Focus, then the word she typed last night, then everything else. */
    @Test fun `the order is focus, then her new words, then the rest`() = runTest {
        val due = word("παλιά", createdAt = 1)
        schedules.upsert(Schedule(due.id, m, box = 4, nextDueAt = noon - 1, createdAt = noon - 10 * LeitnerPolicy.DAY_MS))
        val hers = word("χθεσινή", Source.CAREGIVER, createdAt = noon - LeitnerPolicy.DAY_MS)
        val seed = word("σπόρος", Source.SEED, createdAt = 2)
        val wanted = word("καφές", createdAt = 3)
        schedules.upsert(Schedule(wanted.id, m, box = 5, nextDueAt = noon - 1, createdAt = noon - 10 * LeitnerPolicy.DAY_MS))

        val plan = SessionBuilder(items, schedules, { noon }, newPerDay = 4, maxItems = 6, focus = focus(listOf("καφές")))
            .plan(m, listOf(ItemKind.WORD))

        assertEquals(listOf("καφές", "χθεσινή"), plan.take(2).map { it.text })
        assertEquals(setOf("παλιά", "σπόρος"), plan.drop(2).map { it.text }.toSet())
        assertEquals(4, plan.size)
    }

    /** No focus and no new personal word: the sitting is ordered exactly as it always was. */
    @Test fun `without a focus nothing about the order changes`() = runTest {
        val words = (0 until 5).map { i ->
            word("due$i", createdAt = i.toLong()).also { w ->
                schedules.upsert(Schedule(w.id, m, box = i, nextDueAt = noon - 10 + i, createdAt = noon - 10 * LeitnerPolicy.DAY_MS))
            }
        }
        val plan = SessionBuilder(items, schedules, { noon }, newPerDay = 0, maxItems = 5).plan(m, listOf(ItemKind.WORD))

        val boxes = words.associate { it.id to words.indexOf(it) }
        assertEquals(builder.sandwich(plan) { boxes.getValue(it.id) }.map { it.id }, plan.map { it.id })
    }

    @Test fun `sandwich puts easy at both ends and hard in the middle`() {
        val a = Item(text = "a"); val b = Item(text = "b"); val c = Item(text = "c"); val d = Item(text = "d"); val e = Item(text = "e")
        val box = mapOf(a.id to 5, b.id to 4, c.id to 3, d.id to 2, e.id to 0)
        val out = builder.sandwich(listOf(e, d, c, b, a)) { box.getValue(it.id) }
        assertEquals(listOf("a", "c", "e", "d", "b"), out.map { it.text })
    }

    @Test fun `startOfDay is midnight local time`() {
        val start = startOfDay(noon)
        assert(start <= noon && noon - start < LeitnerPolicy.DAY_MS)
        assertEquals(start, startOfDay(start))
    }
}
