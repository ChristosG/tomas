package gr.dimitris.app.core.greek

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        // Four-digit prices are sayable since phase 12, which is what the words go up to.
        assertEquals("δύο χιλιάδες ευρώ", Euro.spoken(200_000))
        // One λεπτό, not «ένα λεπτά»: a caregiver types 3,01 € and the phone says it back to a man
        // who is relearning these words and would hear that it was wrong without knowing why.
        assertEquals("ένα λεπτό", Euro.spoken(1))
        assertEquals("τρία ευρώ και ένα λεπτό", Euro.spoken(301))
        assertEquals("δύο λεπτά", Euro.spoken(2))
        assertEquals("ένα ευρώ", Euro.spoken(100))
        // Above what GreekNumbers can say, it falls back to the written form instead of throwing.
        assertEquals("100000,00 €", Euro.spoken(10_000_000))
    }

    // --------------------------------------------------------------------- ρέστα

    /** The brief's own example: 13,40 € out of a twenty is 6,60 € back, and these are the coins. */
    @Test fun `change from a note comes back as coins and notes, biggest first`() {
        assertEquals(listOf(500, 100, 50, 10), Euro.change(2000, 1340))
        assertEquals(660, Euro.changeCents(2000, 1340))
        assertEquals(listOf(2000, 1000, 500, 200, 100, 50, 20, 10, 5, 2, 1), Euro.change(3888, 0))
        assertEquals("descending, the way a till counts it out", Euro.change(5000, 137).sortedDescending(), Euro.change(5000, 137))
    }

    /**
     * The euro denominations are a canonical set, so taking the biggest coin that still fits always
     * lands exactly on the amount. Every change from every note to every cent price: the coins add up.
     */
    @Test fun `the coins always add up to the change, to the cent`() {
        listOf(500, 1000, 2000, 5000).forEach { note ->
            (0..note).forEach { price ->
                val pieces = Euro.change(note, price)
                assertEquals("$note − $price", note - price, pieces.sum())
                assertTrue("a coin that does not exist in $pieces", pieces.all { it in Euro.changeDenominations })
            }
        }
    }

    /**
     * Paying too little is a question with no change in it, not an error to report: the exercise that
     * asks it must never throw, and a number that is not a till's change is answered with no coins at
     * all rather than with a hundred thousand of them.
     */
    @Test fun `change never throws and never goes negative`() {
        assertEquals(emptyList<Int>(), Euro.change(500, 1340))
        assertEquals(emptyList<Int>(), Euro.change(500, 500))
        assertEquals(0, Euro.changeCents(500, 1340))
        assertEquals(emptyList<Int>(), Euro.change(Int.MAX_VALUE, 0))
        assertEquals(emptyList<Int>(), Euro.change(Int.MIN_VALUE, Int.MAX_VALUE))
        assertEquals(Euro.MAX_CENTS, Euro.changeCents(Int.MAX_VALUE, Int.MIN_VALUE))
        assertEquals(listOf(1), Euro.change(1, 0))
    }

    @Test fun `a till holds every coin, not only the ones he pays with`() {
        assertEquals(listOf(5000, 2000, 1000, 500, 200, 100, 50, 20, 10, 5, 2, 1), Euro.changeDenominations)
        assertTrue("every note he pays with is one a till can hand back", Euro.denominations.all { it in Euro.changeDenominations })
    }
}
