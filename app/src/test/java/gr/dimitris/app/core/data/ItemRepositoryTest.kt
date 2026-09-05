package gr.dimitris.app.core.data

import gr.dimitris.app.core.greek.Syllabifier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class ItemRepositoryTest {
    private val items = FakeItemDao()
    private val recordings = FakeRecordingDao()
    private var clock = 1_000L
    private val repo = ItemRepository(items, recordings) { clock }

    @Test fun `save trims text and derives first sound`() = runTest {
        val saved = repo.save(Item(text = "  Καφές ", category = Category.FOOD))
        assertEquals("Καφές", saved.text)
        assertEquals("κ", saved.firstSound)
        assertEquals(1_000L, saved.updatedAt)
    }

    @Test fun `syllable override wins over the splitter`() = runTest {
        val saved = repo.save(Item(text = "καφές", firstSyllableOverride = "κα"))
        assertEquals("κα", saved.firstSyllable)
        val plain = repo.save(Item(text = "καφές", firstSyllableOverride = "  "))
        assertEquals(Syllabifier.firstSyllable("καφές"), plain.firstSyllable)
    }

    @Test fun `delete is soft and leaves observeAll`() = runTest {
        val a = repo.save(Item(text = "νερό"))
        val b = repo.save(Item(text = "ψωμί"))
        clock = 2_000L
        repo.delete(b.id)
        assertEquals(listOf(a), repo.observeAll().first())
        assertEquals(true, items.get(b.id)?.deleted)
        assertEquals(2_000L, items.get(b.id)?.updatedAt)
    }

    @Test fun `a caregiver recording becomes the model voice`() = runTest {
        val item = repo.save(Item(text = "γάλα"))
        val rec = repo.addRecording(item.id, File("/tmp/x.m4a"), 1200, Who.CAREGIVER)
        assertEquals(rec.id, repo.get(item.id)?.modelRecordingId)
        assertEquals(rec, repo.modelRecording(repo.get(item.id)!!))
        val self = repo.addRecording(item.id, File("/tmp/y.m4a"), 900, Who.DIMITRIS)
        assertEquals(rec.id, repo.get(item.id)?.modelRecordingId)
        assertNull(recordings.get("nope"))
        assertEquals(Who.DIMITRIS, self.who)
    }
}
