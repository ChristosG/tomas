package gr.dimitris.app.core.scheduler

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
