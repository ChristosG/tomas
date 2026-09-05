package gr.dimitris.app.core.greek

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EuroTest {
    @Test fun `formats cents with comma`() { assertEquals("3,50 €", Euro.format(350)); assertEquals("0,50 €", Euro.format(50)); assertEquals("12,00 €", Euro.format(1200)) }
    @Test fun `parses comma dot and whole numbers`() { assertEquals(350, Euro.parse("3,50")); assertEquals(350, Euro.parse("3.5")); assertEquals(300, Euro.parse(" 3 ")); assertEquals(1299, Euro.parse("12,99€")) }
    @Test fun `rejects garbage`() { assertNull(Euro.parse("")); assertNull(Euro.parse("abc")); assertNull(Euro.parse("3,505")) }
    @Test fun `denominations are the notes and the half coin`() = assertEquals(listOf(50, 100, 200, 500, 1000, 2000, 5000), Euro.denominations)
}
