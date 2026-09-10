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
    @Test fun `the feminine form changes exactly the words that have one`() {
        assertEquals("μία", GreekNumbers.feminine(1))
        assertEquals("δύο", GreekNumbers.feminine(2))
        assertEquals("τρεις", GreekNumbers.feminine(3))
        assertEquals("τέσσερις", GreekNumbers.feminine(4))
        assertEquals("πέντε", GreekNumbers.feminine(5))
        assertEquals("δεκατρείς", GreekNumbers.feminine(13))
        assertEquals("είκοσι μία", GreekNumbers.feminine(21))
        assertEquals("τριακόσιες σαράντα τρεις", GreekNumbers.feminine(343))
        // χίλια is an adjective and takes the gender of what it counts — «χίλιες μέρες»; χιλιάδες is
        // a noun and does not, so only the *first* thousand moves.
        assertEquals("χίλιες", GreekNumbers.feminine(1000))
        assertEquals("χίλιες διακόσιες", GreekNumbers.feminine(1200))
        assertEquals("δύο χιλιάδες διακόσιες", GreekNumbers.feminine(2200))
        assertEquals("εννέα χιλιάδες εννιακόσιες ενενήντα εννέα", GreekNumbers.feminine(9999))
    }

    /**
     * The drift guard, over the whole range and not just the easy end: the feminine of any number is
     * its neuter with each gendered word swapped, word for word, and nothing else touched.
     *
     * Derived rather than listed, so a table that grows a new entry in one gender and not the other
     * cannot pass — which is exactly how the thousands were missed the first time, when this walk
     * stopped at 99 and the bug lived at 1000.
     */
    @Test fun `the two genders differ only in the words Greek actually declines`() {
        (0..GreekNumbers.MAX).forEach { n ->
            val derived = w(n).split(" ").joinToString(" ") { GENDERED[it] ?: it }
            assertEquals("$n", derived, GreekNumbers.feminine(n))
        }
    }

    private companion object {
        /** Every word that has a feminine of its own: the neuter as written, the feminine to swap in. */
        val GENDERED = mapOf(
            "ένα" to "μία", "τρία" to "τρεις", "τέσσερα" to "τέσσερις",
            "δεκατρία" to "δεκατρείς", "δεκατέσσερα" to "δεκατέσσερις",
            "χίλια" to "χίλιες",
            "διακόσια" to "διακόσιες", "τριακόσια" to "τριακόσιες", "τετρακόσια" to "τετρακόσιες",
            "πεντακόσια" to "πεντακόσιες", "εξακόσια" to "εξακόσιες", "επτακόσια" to "επτακόσιες",
            "οκτακόσια" to "οκτακόσιες", "εννιακόσια" to "εννιακόσιες",
        )
    }

    @Test fun `out of range throws`() { assertThrows(IllegalArgumentException::class.java) { w(-1) }; assertThrows(IllegalArgumentException::class.java) { w(10_000) } }
}
