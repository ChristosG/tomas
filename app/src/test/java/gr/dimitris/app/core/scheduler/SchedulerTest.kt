package gr.dimitris.app.core.scheduler

import gr.dimitris.app.core.data.FakeScheduleDao
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Outcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SchedulerTest {
    private val dao = FakeScheduleDao()
    private var clock = 1_000_000L
    private val scheduler = Scheduler(dao) { clock }

    @Test fun `first record creates box 1 row then moves up on success`() = runTest {
        val s = scheduler.record("a", ModuleId.WORDCOACH, Outcome.CORRECT, 0)
        assertEquals(2, s.box)
        assertEquals(clock + 2 * LeitnerPolicy.DAY_MS, s.nextDueAt)
        assertEquals(1, s.streak)
        assertEquals(clock, s.lastSeenAt)
    }

    @Test fun `skip resets streak and comes back tomorrow`() = runTest {
        scheduler.record("a", ModuleId.WORDCOACH, Outcome.CORRECT, 0)
        scheduler.record("a", ModuleId.WORDCOACH, Outcome.CORRECT, 1)
        val s = scheduler.record("a", ModuleId.WORDCOACH, Outcome.SKIPPED, null)
        assertEquals(2, s.box)
        assertEquals(0, s.streak)
        assertEquals(clock + 2 * LeitnerPolicy.DAY_MS, s.nextDueAt)
    }

    @Test fun `due lists only rows whose time has come`() = runTest {
        scheduler.record("a", ModuleId.WORDCOACH, Outcome.CORRECT, 0)   // due in 2 days
        scheduler.record("b", ModuleId.WORDCOACH, Outcome.ASSISTED, 4)  // box 1, due in 1 day
        clock += LeitnerPolicy.DAY_MS + 1
        assertEquals(listOf("b"), scheduler.due(ModuleId.WORDCOACH).map { it.itemId })
    }
}
