package gr.dimitris.app.today

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionBudgetTest {
    @Test fun `one module keeps the whole sitting`() = assertEquals(15, SessionBudget.allowance(1))

    @Test fun `two modules split the sitting`() = assertEquals(7, SessionBudget.allowance(2))

    @Test fun `five modules land exactly on the floor`() = assertEquals(3, SessionBudget.allowance(5))

    /** Eight modules would be under two exercises each: the floor wins and the sitting grows. */
    @Test fun `many modules never drop below the floor`() = assertEquals(3, SessionBudget.allowance(8))
}
