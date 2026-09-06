package gr.dimitris.app.core.seed

import gr.dimitris.app.core.data.FakeItemDao
import gr.dimitris.app.core.data.FakeRecordingDao
import gr.dimitris.app.core.data.FakeScriptDao
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemRepository
import gr.dimitris.app.core.data.LineDraft
import gr.dimitris.app.core.data.ScriptRepository
import gr.dimitris.app.core.data.Source
import gr.dimitris.app.core.data.Speaker
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule the second caregiver's phone depends on: **two installs importing the same manifest must
 * write the same rows** — the same ids *and* the same timestamps.
 *
 * If they do not, everything the family did to the bundled words and dialogues is undone the first
 * time the second phone syncs. Chris photographs «ψωμί», records his voice on «Ναι», pins what
 * Dimitris reaches for, types the real price on «καφές», deletes twenty words nobody uses; the
 * father installs the APK a month later; his fresh copies of those same rows carry his install time,
 * which really is newer, so last-write-wins takes them on the server and every phone pulls the
 * reverted rows back. Silently, in one tap, on all three phones.
 *
 * The ids were fixed in the last wave; these are the timestamps.
 */
class SeedRowsTest {

    private fun entry(text: String, image: String? = null) =
        SeedEntry(text = text, kind = "WORD", category = "FOOD", image = image, arasaacId = null)

    private val manifest = SeedManifest(version = 4, items = listOf(
        entry("ψωμί", "8359.png"), entry("νερό", "8486.png"), entry("καφές"),
    ))

    /** One phone's vocabulary import, minus the asset copy (which is a file, not a row). */
    private suspend fun importVocabulary(m: SeedManifest): List<Item> {
        val dao = FakeItemDao()
        val items = ItemRepository(dao, FakeRecordingDao()) { error("the clock must not be read") }
        for (e in m.items) {
            items.save(
                SeedImporter.row(e, m.version, e.image?.let { "photos/$it" }),
                at = SeedIds.stamp(m.version),
            )
        }
        return dao.all().sortedBy { it.id }
    }

    @Test fun `two phones importing the same vocabulary write byte-identical rows`() = runTest {
        val chris = importVocabulary(manifest)
        val father = importVocabulary(manifest)

        assertEquals(3, chris.size)
        assertEquals(chris, father)
        assertEquals(chris.map { it.id }, father.map { it.id })
        assertEquals(chris.map { it.updatedAt }, father.map { it.updatedAt })
        assertEquals(chris.map { it.createdAt }, father.map { it.createdAt })
    }

    @Test fun `a seeded word is stamped from the manifest, not from the clock`() = runTest {
        val rows = importVocabulary(manifest)
        rows.forEach {
            assertEquals(SeedIds.stamp(4), it.updatedAt)
            assertEquals(SeedIds.stamp(4), it.createdAt)
            assertEquals(Source.SEED, it.source)
        }
    }

    /** A bumped manifest is a newer row; anything the family typed is newer still. */
    @Test fun `a later manifest version out-ranks an earlier one, and a real edit out-ranks both`() = runTest {
        val v4 = importVocabulary(manifest).first()
        val v5 = importVocabulary(manifest.copy(version = 5)).first()

        assertTrue(v5.updatedAt > v4.updatedAt)
        assertNotEquals(v4, v5)

        // What a caregiver's edit looks like: the same row, stamped with a real clock.
        val edited = ItemRepository(FakeItemDao(), FakeRecordingDao()) { 1_788_000_000_000L }
            .save(v4.copy(pinned = true, priceCents = 150))
        assertTrue(edited.updatedAt > v5.updatedAt)
    }

    /** The same, for the bundled dialogues: script, lines and the items behind the turns. */
    @Test fun `two phones importing the same dialogue write byte-identical rows`() = runTest {
        val title = "Στην καφετέρια"
        val lines = listOf(
            LineDraft(Speaker.OTHER, "Καλημέρα! Τι θα πάρετε;"),
            LineDraft(Speaker.DIMITRIS, "Έναν καφέ, παρακαλώ."),
        )

        suspend fun importDialogue(version: Int): Triple<Any, List<Any>, List<Any>> {
            val scriptDao = FakeScriptDao()
            val itemDao = FakeItemDao()
            val items = ItemRepository(itemDao, FakeRecordingDao()) { error("the clock must not be read") }
            val scripts = ScriptRepository(scriptDao, items) { error("the clock must not be read") }
            scripts.save(
                SeedIds.script(title), title, lines, source = Source.SEED,
                seedIds = { i -> SeedIds.line(title, i) to SeedIds.lineItem(title, i) },
                at = SeedIds.stamp(version),
            )
            val loaded = scripts.load(SeedIds.script(title))!!
            return Triple(loaded.script, loaded.lines.map { it.first }, loaded.lines.map { it.second })
        }

        val chris = importDialogue(4)
        val father = importDialogue(4)

        assertEquals(chris, father)
        assertTrue(importDialogue(5).first != chris.first)
    }
}
