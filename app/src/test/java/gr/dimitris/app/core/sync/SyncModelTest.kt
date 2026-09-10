package gr.dimitris.app.core.sync

import gr.dimitris.app.core.data.Advice
import gr.dimitris.app.core.data.Attempt
import gr.dimitris.app.core.data.Category
import gr.dimitris.app.core.data.Note
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Outcome
import gr.dimitris.app.core.data.Schedule
import gr.dimitris.app.core.data.ScriptLine
import gr.dimitris.app.core.data.Speaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

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
            setOf(
                "items", "recordings", "attempts", "schedules", "sessions", "error_logs", "scripts", "script_lines",
                "advice", "notes",
            ),
            names.toSet(),
        )
        assertEquals(names.size, names.toSet().size)
    }

    /**
     * The same list, read out of the server rather than repeated here.
     *
     * The test above is named "every table **the server knows**" and it was once edited to agree
     * with the client instead — which is how `advice` and `notes` shipped registered on one side
     * only. A row for a table the server does not know is rejected with the *whole batch*, and the
     * phone's push watermark can then never advance past the first note: the father writes one
     * sentence and nothing leaves his phone again. So the two lists are compared for real.
     *
     * Skipped rather than failed when `server/store.mjs` is not beside the app — the suite has to
     * run on a checkout of the app alone.
     */
    @Test fun `the app and the server agree on the list, letter for letter`() {
        val file = File("../server/store.mjs")
        assumeTrue("no server/store.mjs beside the app", file.isFile)
        val block = Regex("export const TABLES = Object\\.freeze\\(\\[(.*?)]", RegexOption.DOT_MATCHES_ALL)
            .find(file.readText())?.groupValues?.get(1)
        assertNotNull("could not find TABLES in server/store.mjs", block)
        val server = Regex("'([a-z_]+)'").findAll(block!!).map { it.groupValues[1] }.toList()

        assertEquals("server/store.mjs and Tables.all have drifted apart", Tables.all.map { it.name }.toSet(), server.toSet())
    }

    /** The same contract for the other half of the rule, also read out of the server. */
    @Test fun `the app and the server agree on which tables are append-only`() {
        val file = File("../server/store.mjs")
        assumeTrue("no server/store.mjs beside the app", file.isFile)
        val block = Regex("export const APPEND_ONLY_TABLES = Object\\.freeze\\(\\[(.*?)]", RegexOption.DOT_MATCHES_ALL)
            .find(file.readText())?.groupValues?.get(1)
        assertNotNull("could not find APPEND_ONLY_TABLES in server/store.mjs", block)
        val server = Regex("'([a-z_]+)'").findAll(block!!).map { it.groupValues[1] }.toSet()

        assertEquals(Tables.all.filter { it.appendOnly }.map { it.name }.toSet(), server)
    }

    /**
     * Phase 11's two tables. Both are last-write-wins: an advice and a note are things people
     * wrote, and a person may correct or take back what they wrote — unlike an attempt, which is
     * something that happened.
     */
    @Test fun `an advice survives the round trip and carries its focus`() {
        val advice = Advice(
            id = "a1", at = 1_757_000_000_000, model = "claude-opus-5",
            report = "Προφίλ\nΟ Δημήτρης…", caregivers = "- Δούλεψε τα ψώνια.", dimitris = "Πάει καλά.",
            focusJson = """{"items":["καφές"],"sounds":["κ"]}""",
            createdAt = 1_757_000_000_000, updatedAt = 1_757_000_000_001,
        )
        val row = Rows.of(advice)

        assertEquals("a1", Tables.of(Tables.ADVICE)!!.idOf(row))
        assertEquals(1_757_000_000_001L, Rows.updatedAt(row))
        assertEquals(advice, Rows.to(row, Advice::class.java))
        assertFalse(Tables.of(Tables.ADVICE)!!.appendOnly)
    }

    @Test fun `a note survives the round trip and keeps who wrote it`() {
        val note = Note(id = "n1", at = 5, text = "Είπε «καλημέρα» μόνος του.", author = "CAREGIVER", updatedAt = 9)
        val row = Rows.of(note)

        assertEquals("n1", Tables.of(Tables.NOTES)!!.idOf(row))
        assertEquals("CAREGIVER", row["author"])
        assertEquals(note, Rows.to(row, Note::class.java))
        assertFalse(Tables.of(Tables.NOTES)!!.appendOnly)
    }

    /**
     * The validator's whole basis: a sample entity is turned into a row and read for its shape, so
     * a partial row pushed by hand is recognised and passed over instead of reaching Room as an
     * entity with a null where a non-null Kotlin property should be.
     */
    @Test fun `the new tables know which of their columns are required`() {
        val advice = Tables.of(Tables.ADVICE)!!
        assertTrue(advice.required.containsAll(listOf("id", "at", "model", "report", "caregivers", "dimitris", "updatedAt")))
        assertEquals(listOf("report"), advice.missing(mapOf("id" to "a", "at" to 1L, "model" to "m", "caregivers" to "c", "dimitris" to "d", "focusJson" to "", "createdAt" to 1L, "updatedAt" to 1L, "deleted" to false)))

        val notes = Tables.of(Tables.NOTES)!!
        assertTrue(notes.required.containsAll(listOf("id", "at", "text", "author", "updatedAt")))
        assertEquals(listOf("author"), notes.missing(mapOf("id" to "n", "at" to 1L, "text" to "x", "createdAt" to 1L, "updatedAt" to 1L, "deleted" to false)))
    }

    /**
     * The two columns phase 12 added to a table that was already syncing.
     *
     * A dialogue line pushed by a phone that predates them is still a whole line, and has to be
     * taken: the rows on the father's server are the caregiver's own work, and setting them aside as
     * unreadable would mean her dialogue simply never arriving on the phone that pulls them next.
     * `intent` is nullable and excuses itself; `tier` is not, and is excused by hand — the schema's
     * own default is under it.
     */
    @Test fun `a dialogue line pushed before tiers existed is still a whole line`() {
        val spec = Tables.of(Tables.SCRIPT_LINES)!!
        val whole = Rows.of(ScriptLine(id = "l1", scriptId = "s1", position = 1, speaker = Speaker.DIMITRIS, itemId = "i1", tier = 3))
        assertTrue(spec.missing(whole).isEmpty())
        assertTrue(spec.required.containsAll(listOf("id", "scriptId", "position", "speaker", "itemId", "updatedAt")))
        assertFalse("tier has a default under it", "tier" in spec.required)
        assertFalse("an intent nobody wrote is not a missing column", "intent" in spec.required)
        assertTrue(spec.missing(whole - "tier" - "intent").isEmpty())
        assertEquals(listOf("itemId"), spec.missing(whole - "itemId"))
        // And what does arrive is read back as it was written.
        assertEquals(3, Rows.to(whole, ScriptLine::class.java).tier)
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
