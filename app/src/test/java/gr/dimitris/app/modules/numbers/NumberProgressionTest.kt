package gr.dimitris.app.modules.numbers

import org.junit.Assert.assertEquals
import org.junit.Test

class NumberProgressionTest {
    private fun results(correct: Int, total: Int = 10) = List(total) { it < correct }

    @Test fun `stays until ten results exist`() = assertEquals(3, NumberProgression.next(3, results(5, 5)))
    @Test fun `eight of ten moves up`() = assertEquals(4, NumberProgression.next(3, results(8)))
    @Test fun `fewer than five of ten moves down`() = assertEquals(2, NumberProgression.next(3, results(4)))
    @Test fun `in between stays`() = assertEquals(3, NumberProgression.next(3, results(6)))
    @Test fun `clamped to 1 and 7`() { assertEquals(1, NumberProgression.next(1, results(0))); assertEquals(7, NumberProgression.next(7, results(10))) }
    @Test fun `only the last ten count`() = assertEquals(4, NumberProgression.next(3, List(10) { false } + results(9)))
}
