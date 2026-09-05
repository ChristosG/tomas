package gr.dimitris.app.core.greek

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EuroTest {
    @Test fun `formats cents with comma`() { assertEquals("3,50 €", Euro.format(350)); assertEquals("0,50 €", Euro.format(50)); assertEquals("12,00 €", Euro.format(1200)) }
    @Test fun `parses comma dot and whole numbers`() { assertEquals(350, Euro.parse("3,50")); assertEquals(350, Euro.parse("3.5")); assertEquals(300, Euro.parse(" 3 ")); assertEquals(1299, Euro.parse("12,99€")) }
    @Test fun `rejects garbage`() { assertNull(Euro.parse("")); assertNull(Euro.parse("abc")); assertNull(Euro.parse("3,505")) }
    @Test fun `denominations are the notes and the half coin`() = assertEquals(listOf(50, 100, 200, 500, 1000, 2000, 5000), Euro.denominations)

    /** The brief's three cases, spelled out: a dot is a comma, one decimal is tenths, empty is no price. */
    @Test fun `the editor's three shapes`() {
        assertEquals(350, Euro.parse("3.50"))
        assertEquals(350, Euro.parse("3,5"))
        assertNull(Euro.parse(""))
    }

    @Test fun `a number too long to be a price is rejected, not thrown`() {
        assertNull(Euro.parse("12345678901234567890"))
        assertNull(Euro.parse("111111111111"))
        // Where Int arithmetic used to wrap negative.
        assertNull(Euro.parse("21474837"))
        assertNull(Euro.parse("1000000"))
        assertEquals(Euro.MAX_CENTS, Euro.parse("999999,99"))
    }

    @Test fun `amounts are spoken as words, not punctuation`() {
        assertEquals("δέκα ευρώ", Euro.spoken(1000))
        assertEquals("πενήντα λεπτά", Euro.spoken(50))
        assertEquals("δύο ευρώ και πενήντα λεπτά", Euro.spoken(250))
        assertEquals("μηδέν ευρώ", Euro.spoken(0))
        // Above what GreekNumbers can say, it falls back to the written form instead of throwing.
        assertEquals("100000,00 €", Euro.spoken(10_000_000))
    }
}
