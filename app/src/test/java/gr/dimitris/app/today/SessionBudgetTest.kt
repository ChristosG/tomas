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
}
