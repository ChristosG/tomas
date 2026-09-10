package gr.dimitris.app.core.greek

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GreekNumbersTest {
    private fun w(n: Int) = GreekNumbers.words(n)
    @Test fun `zero to nineteen`() { assertEquals("μηδέν", w(0)); assertEquals("επτά", w(7)); assertEquals("δεκαπέντε", w(15)); assertEquals("δεκαεννέα", w(19)) }
    @Test fun `tens`() { assertEquals("είκοσι", w(20)); assertEquals("είκοσι ένα", w(21)); assertEquals("ενενήντα εννέα", w(99)) }
    @Test fun `hundreds`() { assertEquals("εκατό", w(100)); assertEquals("εκατόν ένα", w(101)); assertEquals("εκατόν είκοσι τρία", w(123)); assertEquals("διακόσια πενήντα", w(250)); assertEquals("εννιακόσια ενενήντα εννέα", w(999)) }
    @Test fun `thousand`() = assertEquals("χίλια", w(1000))

    /**
     * Past a thousand the multiplier counts χιλιάδες, which is feminine: «δύο χιλιάδες», and
     * «τρεις χιλιάδες» with the feminine three. «τρία χιλιάδες» is the mistake this exists to stop.
     */
    @Test fun `thousands are counted in the feminine`() {
        assertEquals("δύο χιλιάδες", w(2000))
        assertEquals("τρεις χιλιάδες", w(3000))
        assertEquals("τέσσερις χιλιάδες", w(4000))
        assertEquals("πέντε χιλιάδες", w(5000))
        assertEquals("εννέα χιλιάδες", w(9000))
    }

    @Test fun `four digits, to the last one`() {
        assertEquals("χίλια ένα", w(1001))
        assertEquals("χίλια εκατό", w(1100))
        assertEquals("χίλια εννιακόσια ογδόντα τέσσερα", w(1984))
        assertEquals("δύο χιλιάδες τριακόσια σαράντα πέντε", w(2345))
        assertEquals("τρεις χιλιάδες τέσσερα", w(3004))
        assertEquals("τέσσερις χιλιάδες τετρακόσια", w(4400))
        assertEquals("εννέα χιλιάδες εννιακόσια ενενήντα εννέα", w(9999))
    }

    /** Every number the app may ever have to say, said: nothing blank, nothing with a gap in it. */
    @Test fun `nothing from zero to 9999 is left without words`() {
        (0..GreekNumbers.MAX).forEach { n ->
            val said = w(n)
            assertTrue("$n came back blank", said.isNotBlank())
            assertTrue("$n has a double space: '$said'", !said.contains("  "))
            assertEquals("$n is padded: '$said'", said.trim(), said)
        }
    }

    /** ένα, τρία and τέσσερα are the three that move; the clock, the week and the χιλιάδες need them. */
    @Test fun `the feminine form changes exactly the three words that have one`() {
        assertEquals("μία", GreekNumbers.feminine(1))
        assertEquals("δύο", GreekNumbers.feminine(2))
        assertEquals("τρεις", GreekNumbers.feminine(3))
        assertEquals("τέσσερις", GreekNumbers.feminine(4))
        assertEquals("πέντε", GreekNumbers.feminine(5))
        assertEquals("δεκατρείς", GreekNumbers.feminine(13))
        assertEquals("είκοσι μία", GreekNumbers.feminine(21))
        assertEquals("τριακόσιες σαράντα τρεις", GreekNumbers.feminine(343))
        // The rest are the same word in both genders, and must not be invented twice.
        (0..99).filter { it % 10 !in listOf(1, 3, 4) }.forEach { assertEquals(w(it), GreekNumbers.feminine(it)) }
    }

    @Test fun `out of range throws`() { assertThrows(IllegalArgumentException::class.java) { w(-1) }; assertThrows(IllegalArgumentException::class.java) { w(10_000) } }
}
