package gr.dimitris.app.today

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionBudgetTest {
    @Test fun `one module keeps the whole sitting`() = assertEquals(15, SessionBudget.allowance(1))

    @Test fun `two modules split the sitting`() = assertEquals(7, SessionBudget.allowance(2))

    @Test fun `five modules land exactly on the floor`() = assertEquals(3, SessionBudget.allowance(5))

    /** Eight modules would be under two exercises each: the floor wins and the sitting grows. */
    @Test fun `many modules never drop below the floor`() = assertEquals(3, SessionBudget.allowance(8))

    @Test fun `an ordinary module takes its share and no more`() =
        assertEquals(listOf(1, 2, 3), SessionBudget.share(listOf(1, 2, 3, 4, 5), allowance = 3, atomic = false))

    /**
     * A conversation cannot be cut in half. The dialogue module ignored the truncation anyway — it
     * runs the whole script from the first item it is handed — so all the cut ever did was write a
     * `plannedItemCount` the module was about to overshoot: four turns done against three planned.
     * The length of a dialogue is held by the editor's own cap instead.
     */
    @Test fun `a module that runs as one unit keeps its whole plan`() =
        assertEquals(listOf(1, 2, 3, 4, 5), SessionBudget.share(listOf(1, 2, 3, 4, 5), allowance = 3, atomic = true))

    /**
     * **A module whose exercise is two items gets a whole number of exercises.**
     *
     * «Βήματα» is the one: a task is the ordering and the telling, two rows
     * ([gr.dimitris.app.modules.steps.StepsModule.ITEMS_PER_TASK]). A four-module day hands every
     * module three items ([MIN_PER_MODULE]) — so the session promised three exercises, the module ran
     * one task, and the row read 3 planned / 2 completed on every mixed sitting that tile was in. Two
     * is the honest promise for a budget of three.
     */
    @Test fun `a module with two-item exercises is cut to whole exercises`() {
        val plan = listOf(1, 2, 3, 4, 5, 6, 7, 8)
        assertEquals(listOf(1, 2), SessionBudget.share(plan, allowance = 3, atomic = false, granularity = 2))
        assertEquals(listOf(1, 2, 3, 4), SessionBudget.share(plan, allowance = 5, atomic = false, granularity = 2))
        assertEquals("an exact share is not cut", listOf(1, 2, 3, 4), SessionBudget.share(plan, allowance = 4, atomic = false, granularity = 2))
        // Never nothing: a module the session opens at all is owed an exercise, and then the planned
        // count is the truth about it. Unreachable today, because the floor is three.
        assertEquals(listOf(1, 2), SessionBudget.share(plan, allowance = 1, atomic = false, granularity = 2))
    }
}
