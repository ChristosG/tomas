package gr.dimitris.app.core.greek

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GreekNumbersTest {
    private fun w(n: Int) = GreekNumbers.words(n)
    @Test fun `zero to nineteen`() { assertEquals("μηδέν", w(0)); assertEquals("επτά", w(7)); assertEquals("δεκαπέντε", w(15)); assertEquals("δεκαεννέα", w(19)) }
    @Test fun `tens`() { assertEquals("είκοσι", w(20)); assertEquals("είκοσι ένα", w(21)); assertEquals("ενενήντα εννέα", w(99)) }
    @Test fun `hundreds`() { assertEquals("εκατό", w(100)); assertEquals("εκατόν ένα", w(101)); assertEquals("εκατόν είκοσι τρία", w(123)); assertEquals("διακόσια πενήντα", w(250)); assertEquals("εννιακόσια ενενήντα εννέα", w(999)) }
    @Test fun `thousand`() = assertEquals("χίλια", w(1000))
    @Test fun `out of range throws`() { assertThrows(IllegalArgumentException::class.java) { w(-1) }; assertThrows(IllegalArgumentException::class.java) { w(1001) } }
}
