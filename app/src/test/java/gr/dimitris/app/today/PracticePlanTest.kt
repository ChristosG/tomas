package gr.dimitris.app.today

import gr.dimitris.app.core.data.Item
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a tap on the five dots does to the sitting he is already in (spec §13).
 *
 * The whole promise of the row is that it answers him *now*: he taps a harder dot on the first screen
 * of «Λέξεις», and the words in front of him are the harder words. That happens because the practice
 * plan watches the setting instead of reading it once, and this is the test of it — the seam is
 * outside the ViewModel because an `AppGraph` needs a `Context` and cannot be built on the JVM at all.
 */
class PracticePlanTest {
    private fun words(vararg text: String) = text.map { Item(text = it) }

    /** A plan per difficulty, each built at the number the dots were on when it was asked for. */
    @Test fun `moving the dots asks the module for a new plan`() = runTest {
        val asked = mutableListOf<Int>()
        val plans = practicePlans(
            difficulty = flowOf(2, 4),
            plan = { d -> asked += d; words("λέξη $d") },
            onError = { error("nothing should have failed") },
        ).toList()
        assertEquals(listOf(2, 4), asked)
        assertEquals(listOf(listOf("λέξη 2"), listOf("λέξη 4")), plans.map { p -> p.map { it.text } })
    }

    /**
     * And only when they really move.
     *
     * DataStore re-emits its whole map on every write to any key, so without this a caregiver saving
     * a speech rate — or the module's own level write at the end of a sitting — would re-plan the
     * sitting he is in the middle of and hand him a fresh set of words.
     */
    @Test fun `a re-emission of the same difficulty plans nothing new`() = runTest {
        val asked = mutableListOf<Int>()
        practicePlans(
            difficulty = flowOf(2, 2, 2, 3, 3),
            plan = { d -> asked += d; words("λέξη") },
            onError = { error("nothing should have failed") },
        ).toList()
        assertEquals(listOf(2, 3), asked)
    }

    /** A module that throws leaves the screen with an empty plan and a recorded error, never a crash. */
    @Test fun `a module that throws is an empty plan and a written-down error`() = runTest {
        val failures = mutableListOf<Throwable>()
        val plans = practicePlans(
            difficulty = flowOf(2),
            plan = { error("no vocabulary") },
            onError = { failures += it },
        ).toList()
        assertEquals(listOf(emptyList<Item>()), plans)
        assertEquals(1, failures.size)
    }

    /** The flow stays live: the practice route is on the back stack for as long as he is in it. */
    @Test fun `the plan keeps following the dots for as long as the screen lives`() = runTest {
        val dots = MutableStateFlow(1)
        val seen = mutableListOf<Int>()
        backgroundScope.launch {
            practicePlans(dots, plan = { d -> seen += d; words("λέξη") }, onError = {}).collect {}
        }
        runCurrent()
        dots.value = 5
        runCurrent()
        assertEquals(listOf(1, 5), seen)
    }
}
