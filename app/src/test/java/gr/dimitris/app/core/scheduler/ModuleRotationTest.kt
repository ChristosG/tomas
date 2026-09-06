package gr.dimitris.app.core.scheduler

import gr.dimitris.app.core.data.Attempt
import gr.dimitris.app.core.data.FakeAttemptDao
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Outcome
import gr.dimitris.app.today.SessionBudget
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleRotationTest {
    private val all = listOf(
        ModuleId.WORDCOACH, ModuleId.NUMBERS, ModuleId.SINGSAY,
        ModuleId.SCRIPTS, ModuleId.SENTENCES, ModuleId.TRACE, ModuleId.ARCADE,
    )

    /** Yesterday, the day before, and so on: the bigger the number of days, the longer ago. */
    private fun daysAgo(days: Int): Long = 10_000_000L - days * 86_400_000L

    @Test fun `a day is four modules at most`() {
        val chosen = ModuleRotation.choose(all, all.associateWith { daysAgo(1) })
        assertEquals(ModuleRotation.MAX_MODULES, chosen.size)
    }

    /** Everything he can do today, when there is little of it, is what he does. */
    @Test fun `fewer than four modules are all kept, in the order they came in`() {
        val few = listOf(ModuleId.TRACE, ModuleId.WORDCOACH, ModuleId.NUMBERS)
        assertEquals(few, ModuleRotation.choose(few, emptyMap()))
    }

    /** Finding his words is the point of the app: it is never the module the rotation drops. */
    @Test fun `the word coach is in the day even when it is the one he did last`() {
        val lastUsed = all.associateWith { daysAgo(30) } + (ModuleId.WORDCOACH to daysAgo(0))
        assertTrue(ModuleId.WORDCOACH in ModuleRotation.choose(all, lastUsed))
    }

    @Test fun `the three others are the ones he has not done for longest`() {
        val lastUsed = mapOf(
            ModuleId.WORDCOACH to daysAgo(0),
            ModuleId.NUMBERS to daysAgo(1),
            ModuleId.SINGSAY to daysAgo(9),
            ModuleId.SCRIPTS to daysAgo(2),
            ModuleId.SENTENCES to daysAgo(8),
            ModuleId.TRACE to daysAgo(7),
            ModuleId.ARCADE to daysAgo(3),
        )
        assertEquals(
            listOf(ModuleId.WORDCOACH, ModuleId.SINGSAY, ModuleId.SENTENCES, ModuleId.TRACE),
            ModuleRotation.choose(all, lastUsed),
        )
    }

    /** A module switched on yesterday and never opened is the first one he is given. */
    @Test fun `a module he has never done goes before every module he has`() {
        val lastUsed = all.associateWith { daysAgo(100) } - ModuleId.ARCADE
        val chosen = ModuleRotation.choose(all, lastUsed)
        assertTrue("a module he has never done was left out", ModuleId.ARCADE in chosen)
    }

    /** With the word coach off, the day is still four modules — the four that have waited longest. */
    @Test fun `without the word coach four others take the day`() {
        val others = all - ModuleId.WORDCOACH
        val chosen = ModuleRotation.choose(others, others.associateWith { daysAgo(1) })
        assertEquals(ModuleRotation.MAX_MODULES, chosen.size)
    }

    /** Chosen by rotation, run in the order Today shows them: the day opens the same way every day. */
    @Test fun `the chosen modules keep the order they came in`() {
        val lastUsed = mapOf(
            ModuleId.WORDCOACH to daysAgo(0),
            ModuleId.NUMBERS to daysAgo(1),
            ModuleId.SINGSAY to daysAgo(2),
            ModuleId.SCRIPTS to daysAgo(3),
            ModuleId.SENTENCES to daysAgo(4),
            ModuleId.TRACE to daysAgo(20),
            ModuleId.ARCADE to daysAgo(30),
        )
        val chosen = ModuleRotation.choose(all, lastUsed)
        assertEquals(chosen.sortedBy { all.indexOf(it) }, chosen)
    }

    /** No rows at all — a phone on its first day — is every module never done, and the first four. */
    @Test fun `a device with no attempts yet takes the first four`() {
        assertEquals(all.take(4), ModuleRotation.choose(all, emptyMap()))
    }

    @Test fun `last use is read from the attempt rows themselves`() = runBlocking {
        val attempts = FakeAttemptDao()
        attempts.insert(row(ModuleId.NUMBERS, daysAgo(5)))
        attempts.insert(row(ModuleId.NUMBERS, daysAgo(2)))
        attempts.insert(row(ModuleId.TRACE, daysAgo(9)))
        val lastUsed = ModuleRotation.lastUsedAt(attempts)
        assertEquals(mapOf(ModuleId.NUMBERS to daysAgo(2), ModuleId.TRACE to daysAgo(9)), lastUsed)
    }

    /**
     * A module he opened and passed straight through is one he has not done. Counting the skip would
     * send it to the back of the queue for as long as a module he had worked at, and the fastest way
     * to never see an exercise again would be to skip it.
     */
    @Test fun `a module he skipped his way through is not a module he practised`() = runBlocking {
        val attempts = FakeAttemptDao()
        attempts.insert(row(ModuleId.NUMBERS, daysAgo(6)))
        attempts.insert(row(ModuleId.NUMBERS, daysAgo(0), Outcome.SKIPPED))
        attempts.insert(row(ModuleId.SINGSAY, daysAgo(0), Outcome.SKIPPED))
        val lastUsed = ModuleRotation.lastUsedAt(attempts)
        assertEquals("the skip counted as practice", mapOf(ModuleId.NUMBERS to daysAgo(6)), lastUsed)
        // And a module with nothing but skips is still one he has never done: it goes first.
        assertTrue(ModuleId.SINGSAY in ModuleRotation.choose(all, lastUsed))
    }

    /**
     * The cap and the budget together: four modules of three exercises is twelve, which is a sitting
     * a tired man finishes. Fifteen was the ceiling before the cap and six modules made it the floor.
     */
    @Test fun `four modules still fit inside one sitting`() {
        val allowance = SessionBudget.allowance(ModuleRotation.MAX_MODULES)
        assertEquals(3, allowance)
        assertTrue(allowance * ModuleRotation.MAX_MODULES <= SessionBudget.MAX_SESSION_ITEMS)
    }

    private fun row(module: ModuleId, at: Long, outcome: Outcome = Outcome.CORRECT) = Attempt(
        itemId = "x", module = module, startedAt = at, durationMs = 1, outcome = outcome,
    )
}
