package gr.dimitris.app.today

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import gr.dimitris.app.AppGraph
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.scheduler.ModuleRotation
import gr.dimitris.app.modules.Module
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the session runner really does with the rotation and the budget together — the join the
 * ViewModel makes and neither `ModuleRotationTest` nor `SessionBudgetTest` can see on its own.
 */
class SessionPlanTest {
    /** A module that exists only to be counted: it plans what it is told and shows nothing. */
    private class FakeModule(
        override val id: ModuleId,
        override val atomic: Boolean = false,
    ) : Module {
        override val titleGreek = id.name
        override val icon: ImageVector = Icons.Rounded.Star
        override suspend fun planFor(graph: AppGraph): List<Item> = emptyList()
        @Composable override fun Screen(items: List<Item>, sessionId: String?, onDone: () -> Unit, onLeave: () -> Unit) {}
    }

    private fun wanted(vararg ids: ModuleId, each: Int = 12): List<Pair<Module, List<Item>>> =
        ids.map { FakeModule(it) as Module to List(each) { i -> Item(text = "$i") } }

    private fun daysAgo(days: Int): Long = 10_000_000L - days * 86_400_000L

    private val all = listOf(
        ModuleId.WORDCOACH, ModuleId.NUMBERS, ModuleId.SINGSAY,
        ModuleId.SCRIPTS, ModuleId.SENTENCES, ModuleId.TRACE, ModuleId.ARCADE,
    )

    /** Seven modules with work to do is still four modules of work. */
    @Test fun `a session is planned for four modules at most`() {
        val plan = planToday(wanted(*all.toTypedArray()), emptyMap())
        assertEquals(ModuleRotation.MAX_MODULES, plan.size)
    }

    /**
     * The point of the fix: the budget is shared between the modules that are really in the session.
     * Sharing it over all seven and then dropping three would have left him a sitting of nine.
     */
    @Test fun `the sitting is shared between the modules really in it`() {
        val plan = planToday(wanted(*all.toTypedArray()), emptyMap())
        assertEquals(SessionBudget.allowance(ModuleRotation.MAX_MODULES), plan.first().second.size)
        val exercises = plan.sumOf { it.second.size }
        assertEquals(12, exercises)
        assertTrue("a sitting of $exercises is more than one sitting", exercises <= SessionBudget.MAX_SESSION_ITEMS)
    }

    /** The word coach is in the session it is enabled for, however recently he did it. */
    @Test fun `the word coach is planned even when it is the module he did last`() {
        val lastUsed = all.associateWith { daysAgo(30) } + (ModuleId.WORDCOACH to daysAgo(0))
        val plan = planToday(wanted(*all.toTypedArray()), lastUsed)
        assertEquals(ModuleId.WORDCOACH, plan.first().first.id)
    }

    /** The three others are the ones that have waited longest, and Today's order is kept. */
    @Test fun `the modules he has not done for longest are the ones planned`() {
        val lastUsed = mapOf(
            ModuleId.WORDCOACH to daysAgo(0),
            ModuleId.NUMBERS to daysAgo(1),
            ModuleId.SINGSAY to daysAgo(2),
            ModuleId.SCRIPTS to daysAgo(3),
            ModuleId.SENTENCES to daysAgo(4),
            ModuleId.TRACE to daysAgo(20),
            ModuleId.ARCADE to daysAgo(30),
        )
        val plan = planToday(wanted(*all.toTypedArray()), lastUsed)
        assertEquals(
            listOf(ModuleId.WORDCOACH, ModuleId.SENTENCES, ModuleId.TRACE, ModuleId.ARCADE),
            plan.map { it.first.id },
        )
    }

    /** Two modules with something to do share a sitting between the two of them, not four ways. */
    @Test fun `a short day gives each module more of the sitting`() {
        val plan = planToday(wanted(ModuleId.WORDCOACH, ModuleId.TRACE), emptyMap())
        assertEquals(2, plan.size)
        assertEquals(SessionBudget.allowance(2), plan.first().second.size)
    }

    /** A dialogue cannot be cut in half, and the cap does not cut it. */
    @Test fun `a module that runs as one unit keeps its whole plan inside the cap`() {
        val scripts = FakeModule(ModuleId.SCRIPTS, atomic = true) as Module to List(5) { Item(text = "$it") }
        val plan = planToday(wanted(ModuleId.WORDCOACH, ModuleId.NUMBERS) + scripts, emptyMap())
        assertEquals(5, plan.single { it.first.id == ModuleId.SCRIPTS }.second.size)
    }

    /** Nothing to do is not a session. */
    @Test fun `nothing wanted is nothing planned`() {
        assertTrue(planToday(emptyList(), emptyMap()).isEmpty())
    }

    // ------------------------------------- the difficulty he set, on its way into the sitting

    /**
     * Every module is planned at **its own** difficulty (spec §13).
     *
     * This seam had no test at all: deleting the difficulty read from `SessionViewModel` left every
     * test green, and «Λέξεις» would quietly have gone back to planning for everybody at once while
     * the row of dots went on saying otherwise.
     */
    @Test fun `each module is planned at the difficulty he set for it`() = runTest {
        val dots = mapOf(ModuleId.WORDCOACH to 1, ModuleId.NUMBERS to 4, ModuleId.TRACE to 5)
        val asked = mutableMapOf<ModuleId, Int>()
        val plan = planEach(
            modules = dots.keys.map { FakeModule(it) },
            difficultyOf = { dots.getValue(it) },
            planOf = { m, d -> asked[m.id] = d; List(2) { Item(text = "$it") } },
            onError = { where, _ -> error("nothing should have failed: $where") },
        )
        assertEquals(dots, asked)
        assertEquals(dots.keys.toList(), plan.map { it.first.id })
    }

    /** A module with nothing to do today is left out, and a module that throws never breaks the day. */
    @Test fun `a module that plans nothing or throws is left out of the sitting`() = runTest {
        val failures = mutableListOf<String>()
        val plan = planEach(
            modules = listOf(FakeModule(ModuleId.WORDCOACH), FakeModule(ModuleId.NUMBERS), FakeModule(ModuleId.TRACE)),
            difficultyOf = { if (it == ModuleId.NUMBERS) error("no store") else 3 },
            planOf = { m, _ -> if (m.id == ModuleId.TRACE) emptyList() else List(2) { Item(text = "$it") } },
            onError = { where, _ -> failures += where },
        )
        // «Αριθμοί» is still planned — a difficulty that cannot be read falls back to the default
        // rather than costing him the module.
        assertEquals(listOf(ModuleId.WORDCOACH, ModuleId.NUMBERS), plan.map { it.first.id })
        assertEquals(listOf("difficulty ${ModuleId.NUMBERS}"), failures)
    }
}
