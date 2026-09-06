package gr.dimitris.app.core.sync

import gr.dimitris.app.core.data.Attempt
import gr.dimitris.app.core.data.Category
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Outcome
import gr.dimitris.app.core.data.Schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncModelTest {

    @Test fun `an item survives the round trip through a row`() {
        val item = Item(
            text = "ψωμί", kind = ItemKind.WORD, category = Category.FOOD, imagePath = "photos/x.jpg",
            firstSound = "ψ", firstSyllable = "ψω", pinned = true, priceCents = 120,
            createdAt = 1_757_000_000_000, updatedAt = 1_757_000_000_001,
        )
        val row = Rows.of(item)

        assertEquals("ψωμί", row["text"])
        assertEquals("FOOD", row["category"])
        assertEquals(1_757_000_000_001L, row["updatedAt"])
        assertEquals(item, Rows.to(row, Item::class.java))
    }

    /** The one value the whole merge turns on must not arrive as `1.757E12`. */
    @Test fun `updatedAt stays a Long`() {
        val row = Rows.of(Item(text = "νερό", updatedAt = 1_757_000_000_123))
        assertTrue("updatedAt was ${row["updatedAt"]?.javaClass}", row["updatedAt"] is Long)
        assertEquals(1_757_000_000_123L, Rows.updatedAt(row))
    }

    /**
     * Gson allocates the object and fills fields, so a field left out of the JSON would come back as
     * a JVM zero and not as the default the data class declares. Nulls have to be written.
     */
    @Test fun `null columns are written and come back null`() {
        val item = Item(text = "νερό", imagePath = null, priceCents = null, modelRecordingId = null)
        val row = Rows.of(item)
        assertTrue(row.containsKey("imagePath"))
        assertNull(row["imagePath"])
        val back = Rows.to(row, Item::class.java)
        assertNull(back.imagePath)
        assertNull(back.priceCents)
        assertEquals("νερό", back.text)
    }

    @Test fun `an attempt keeps its enums and its booleans`() {
        val attempt = Attempt(
            itemId = "i1", module = ModuleId.WORDCOACH, startedAt = 5, durationMs = 900,
            outcome = Outcome.ASSISTED, cueLevel = 2, detail = "{\"x\":1}",
        )
        val row = Rows.of(attempt)
        assertEquals("WORDCOACH", row["module"])
        assertEquals("ASSISTED", row["outcome"])
        assertEquals(false, row["deleted"])
        assertEquals(attempt, Rows.to(row, Attempt::class.java))
    }

    @Test fun `a schedule travels with the id the server keys it on`() {
        val schedule = Schedule(itemId = "i1", module = ModuleId.SINGSAY, nextDueAt = 42)
        val spec = Tables.of(Tables.SCHEDULES)!!
        val row = spec.withId(Rows.of(schedule))

        assertEquals("i1:SINGSAY", row["id"])
        assertEquals("i1:SINGSAY", spec.idOf(row))
        // The extra column is not one the entity has; Gson ignores it on the way back.
        assertEquals(schedule, Rows.to(row, Schedule::class.java))
    }

    @Test fun `every table the server knows is registered exactly once`() {
        val names = Tables.all.map { it.name }
        assertEquals(
            setOf("items", "recordings", "attempts", "schedules", "sessions", "error_logs", "scripts", "script_lines"),
            names.toSet(),
        )
        assertEquals(names.size, names.toSet().size)
    }

    @Test fun `only attempts and error logs are append-only`() {
        assertEquals(setOf("attempts", "error_logs"), Tables.all.filter { it.appendOnly }.map { it.name }.toSet())
        assertFalse(Tables.of(Tables.SCRIPT_LINES)!!.appendOnly)
    }

    @Test fun `only items and recordings carry media`() {
        assertEquals(mapOf("imagePath" to "jpg"), Tables.of(Tables.ITEMS)!!.mediaFields)
        assertEquals(mapOf("path" to "m4a"), Tables.of(Tables.RECORDINGS)!!.mediaFields)
        assertTrue(Tables.all.filter { it.mediaFields.isNotEmpty() }.size == 2)
        assertNull(Tables.of("something_else"))
    }
}
