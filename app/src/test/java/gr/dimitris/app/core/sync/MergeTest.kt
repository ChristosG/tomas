package gr.dimitris.app.core.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MergeTest {
    private fun row(id: String, at: Long, text: String = "ψωμί") =
        mapOf<String, Any?>("id" to id, "updatedAt" to at, "text" to text)

    @Test fun `a row this phone has never seen is always taken`() {
        assertTrue(Merge.decide(null, row("i1", 1), appendOnly = false))
        assertTrue(Merge.decide(null, row("a1", 1), appendOnly = true))
    }

    @Test fun `the newer updatedAt wins`() {
        assertTrue(Merge.decide(row("i1", 10), row("i1", 11), appendOnly = false))
    }

    @Test fun `an older row is ignored`() {
        assertFalse(Merge.decide(row("i1", 11), row("i1", 10), appendOnly = false))
    }

    /** A row that comes back around after a round trip must not churn the database. */
    @Test fun `a tie keeps the local row`() {
        assertFalse(Merge.decide(row("i1", 10, "νερό"), row("i1", 10, "ψωμί"), appendOnly = false))
    }

    @Test fun `an append-only row is never overwritten, however new it claims to be`() {
        assertFalse(Merge.decide(row("a1", 1), row("a1", 9_999), appendOnly = true))
    }

    /** A row with no usable updatedAt reads as the oldest there is, rather than blowing up. */
    @Test fun `a missing updatedAt loses`() {
        val nothing = mapOf<String, Any?>("id" to "i1")
        assertFalse(Merge.decide(row("i1", 1), nothing, appendOnly = false))
        assertTrue(Merge.decide(nothing, row("i1", 1), appendOnly = false))
    }

    /** Gson hands numbers back as whatever fits; the merge must read them all the same way. */
    @Test fun `updatedAt is read from any number`() {
        val asDouble = mapOf<String, Any?>("id" to "i1", "updatedAt" to 1_757_000_000_001.0)
        assertTrue(Merge.decide(row("i1", 1_757_000_000_000L), asDouble, appendOnly = false))
    }
}
