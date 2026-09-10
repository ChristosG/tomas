package gr.dimitris.app.core.greek

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level 13's two questions, as words rather than as a screenshot: a face he has to read and a day he
 * has to count forward from.
 */
class GreekTimeTest {
    private fun at(hour: Int, minute: Int) = hour % 12 * 60 + minute

    @Test fun `the hours are feminine, and twelve is at the top`() {
        assertEquals("δώδεκα ακριβώς", GreekTime.words(at(12, 0)))
        assertEquals("μία ακριβώς", GreekTime.words(at(1, 0)))
        assertEquals("τρεις ακριβώς", GreekTime.words(at(3, 0)))
        assertEquals("τέσσερις ακριβώς", GreekTime.words(at(4, 0)))
    }

    @Test fun `the quarters have their own words`() {
        assertEquals("τρεις και τέταρτο", GreekTime.words(at(3, 15)))
        assertEquals("τρεις και μισή", GreekTime.words(at(3, 30)))
        assertEquals("τέσσερις παρά τέταρτο", GreekTime.words(at(3, 45)))
    }

    /** Past the half hour Greek counts down to the *next* hour, which is the whole trick of a face. */
    @Test fun `after the half hour the next hour is the one that is named`() {
        assertEquals("τρεις και είκοσι", GreekTime.words(at(3, 20)))
        assertEquals("τρεις και είκοσι πέντε", GreekTime.words(at(3, 25)))
        assertEquals("τέσσερις παρά είκοσι", GreekTime.words(at(3, 40)))
        assertEquals("τέσσερις παρά πέντε", GreekTime.words(at(3, 55)))
        // And over the top of the face: twelve comes after eleven, not thirteen.
        assertEquals("δώδεκα παρά δέκα", GreekTime.words(at(11, 50)))
        assertEquals("μία παρά τέταρτο", GreekTime.words(at(12, 45)))
    }

    @Test fun `digits are a twelve-hour face with a padded minute`() {
        assertEquals("12:00", GreekTime.digits(at(12, 0)))
        assertEquals("3:05", GreekTime.digits(at(3, 5)))
        assertEquals("11:55", GreekTime.digits(at(11, 55)))
    }

    @Test fun `a time from anywhere lands on the face`() {
        assertEquals(0, GreekTime.normalise(GreekTime.MINUTES))
        assertEquals(GreekTime.MINUTES - 5, GreekTime.normalise(-5))
        assertEquals(3, GreekTime.hourOf(at(15, 30)))
        assertEquals(30, GreekTime.minuteOf(at(15, 30)))
    }

    /** Every time on a five-minute face has words and digits, and no two of them are the same words. */
    @Test fun `every time on the face is sayable and distinct`() {
        val said = (0 until GreekTime.MINUTES step 5).map { GreekTime.words(it) }
        said.forEach { assertTrue("blank time", it.isNotBlank()) }
        assertEquals("two times with the same name", said.size, said.toSet().size)
    }

    @Test fun `the week starts on Monday and wraps`() {
        assertEquals("Δευτέρα", GreekTime.day(0))
        assertEquals("Κυριακή", GreekTime.day(6))
        assertEquals("Δευτέρα", GreekTime.day(7))
        assertEquals("Κυριακή", GreekTime.day(-1))
    }

    @Test fun `three days after Monday is Thursday`() {
        assertEquals(3, GreekTime.dayAfter(0, 3))
        assertEquals("Πέμπτη", GreekTime.day(GreekTime.dayAfter(0, 3)))
        // Round the corner of the week, and round it twice.
        assertEquals("Τρίτη", GreekTime.day(GreekTime.dayAfter(5, 3)))
        assertEquals("Σάββατο", GreekTime.day(GreekTime.dayAfter(5, 7)))
        assertEquals("Παρασκευή", GreekTime.day(GreekTime.dayAfter(0, -3)))
    }
}
