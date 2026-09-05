package gr.dimitris.app.modules.talkboard

import gr.dimitris.app.core.data.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceStripTest {
    private val want = Item(text = "Θέλω")
    private val coffee = Item(text = "καφέ")

    @Test fun `joins texts with spaces`() {
        val s = SentenceStrip()
        assertTrue(s.add(want)); assertTrue(s.add(coffee))
        assertEquals("Θέλω καφέ", s.text)
    }

    @Test fun `removeLast and clear`() {
        val s = SentenceStrip().apply { add(want); add(coffee) }
        s.removeLast()
        assertEquals(listOf(want), s.items.value)
        s.clear()
        assertEquals("", s.text)
        s.removeLast()   // no-op on empty
        assertEquals(emptyList<Item>(), s.items.value)
    }

    @Test fun `refuses beyond max`() {
        val s = SentenceStrip(max = 2).apply { add(want); add(coffee) }
        assertFalse(s.add(Item(text = "τώρα")))
        assertEquals(2, s.items.value.size)
    }
}
