package gr.dimitris.app.caregiver.insights

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One question at a time, and never an orphaned one. Every request here costs the caregiver money
 * and carries their key, so "the button was tapped twice" must not mean "two requests".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdviceSessionTest {

    private val advice = Advice("- Δούλεψε τα ψώνια.", "Πάει καλά.")

    /** A stand-in for the advisor that answers only when the test says so, and counts its calls. */
    private class FakeAdvisor(private val answer: Result<Advice>) {
        var calls = 0
            private set
        val released = CompletableDeferred<Unit>()

        suspend fun ask(@Suppress("UNUSED_PARAMETER") summary: String): Result<Advice> {
            calls++
            released.await()
            return answer
        }
    }

    @Test fun `a second question while one is running is refused, not sent`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fake = FakeAdvisor(Result.success(advice))
        val session = AdviceSession(TestScope(dispatcher), fake::ask) { _, _ -> }

        session.ask("περίληψη")
        advanceUntilIdle()
        assertTrue(session.state.value.asking)
        assertEquals(1, fake.calls)

        session.ask("περίληψη")
        advanceUntilIdle()
        assertEquals("one question at a time", 1, fake.calls)
        assertEquals(AdviceSession.BUSY, session.state.value.notice)

        fake.released.complete(Unit)
        advanceUntilIdle()
        assertFalse(session.state.value.asking)
        assertEquals(advice, session.state.value.advice)
    }

    /** Once the first answer is in, the next tap really does ask again. */
    @Test fun `after the answer a new question is allowed`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fake = FakeAdvisor(Result.success(advice))
        val session = AdviceSession(TestScope(dispatcher), fake::ask) { _, _ -> }

        session.ask("περίληψη")
        fake.released.complete(Unit)
        advanceUntilIdle()

        session.ask("περίληψη")
        advanceUntilIdle()
        assertEquals(2, fake.calls)
    }

    /**
     * The answer lives here, not in a view model, so a caregiver who backs out while it is thinking
     * and comes back finds it waiting. The screen's own state object is gone by then; this one is not.
     */
    @Test fun `the answer outlives the screen that asked for it`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fake = FakeAdvisor(Result.success(advice))
        val session = AdviceSession(TestScope(dispatcher), fake::ask) { _, _ -> }

        session.ask("περίληψη")
        advanceUntilIdle()
        fake.released.complete(Unit)
        advanceUntilIdle()

        assertEquals(advice, session.state.value.advice)
    }

    @Test fun `a failure becomes a Greek line and is written down`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fake = FakeAdvisor(Result.failure(AdviceException(ClaudeAdvisor.BAD_KEY)))
        val recorded = mutableListOf<String>()
        val session = AdviceSession(TestScope(dispatcher), fake::ask) { where, _ -> recorded += where }

        session.ask("περίληψη")
        fake.released.complete(Unit)
        advanceUntilIdle()

        assertEquals(ClaudeAdvisor.BAD_KEY, session.state.value.error)
        assertNull(session.state.value.advice)
        assertEquals(listOf("claude advice"), recorded)
    }

    /** "No key" is a state the disabled button already prevents; the error list should stay clean. */
    @Test fun `a missing key is not written to the error log`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fake = FakeAdvisor(Result.failure(AdviceException(ClaudeAdvisor.NO_KEY)))
        val recorded = mutableListOf<String>()
        val session = AdviceSession(TestScope(dispatcher), fake::ask) { where, _ -> recorded += where }

        session.ask("περίληψη")
        fake.released.complete(Unit)
        advanceUntilIdle()

        assertEquals(ClaudeAdvisor.NO_KEY, session.state.value.error)
        assertEquals(emptyList<String>(), recorded)
    }

    @Test fun `nothing is sent for an empty summary`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fake = FakeAdvisor(Result.success(advice))
        val session = AdviceSession(TestScope(dispatcher), fake::ask) { _, _ -> }

        session.ask("   ")
        advanceUntilIdle()

        assertEquals(0, fake.calls)
        assertFalse(session.state.value.asking)
    }
}
