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
    private val repo = ItemRepository(items, recordings, { it.absolutePath }, ::File) { clock }

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

    /** The sung take is a second model voice, not a replacement: the talk board must keep speaking. */
    @Test fun `sung recordings are kept apart from spoken ones`() = runTest {
        val item = repo.save(Item(text = "θέλω καφέ"))
        val spoken = repo.addRecording(item.id, File("/tmp/s.m4a"), 900, Who.CAREGIVER)
        val sung = repo.addRecording(item.id, File("/tmp/g.m4a"), 1800, Who.CAREGIVER, RecordingStyle.SUNG)
        assertEquals(spoken.id, repo.get(item.id)?.modelRecordingId)
        assertEquals(sung, repo.sungRecording(repo.get(item.id)!!))
        assertEquals(spoken, repo.modelRecording(repo.get(item.id)!!))
    }

    @Test fun `blank override is stored as null and a real one trimmed`() = runTest {
        assertEquals(null, repo.save(Item(text = "καφές", firstSyllableOverride = "  ")).firstSyllableOverride)
        assertEquals("κα", repo.save(Item(text = "καφές", firstSyllableOverride = " κα ")).firstSyllableOverride)
    }

    @Test fun `modelRecording falls back to the latest caregiver recording`() = runTest {
        val item = repo.save(Item(text = "ψωμί"))
        val older = Recording(itemId = item.id, path = "/tmp/a.m4a", who = Who.CAREGIVER, durationMs = 500, recordedAt = 10)
        val newer = Recording(itemId = item.id, path = "/tmp/b.m4a", who = Who.CAREGIVER, durationMs = 500, recordedAt = 20)
        recordings.upsert(older); recordings.upsert(newer)
        assertEquals(newer, repo.modelRecording(item))   // item.modelRecordingId is null
    }

    @Test fun `saving an existing item keeps id createdAt and model recording`() = runTest {
        val item = repo.save(Item(text = "γάλα", createdAt = 42))
        val rec = repo.addRecording(item.id, File("/tmp/x.m4a"), 700, Who.CAREGIVER)
        val again = repo.save(repo.get(item.id)!!.copy(text = "γάλα φρέσκο"))
        assertEquals(item.id, again.id)
        assertEquals(42L, again.createdAt)
        assertEquals(rec.id, again.modelRecordingId)
        assertEquals("γ", again.firstSound)
    }

    // How many of his own takes of one word survive. Since spec §13 folded the two microphones into
    // one, a take is no longer a deliberate «Ηχογράφηση» tap: it is every «Μίλα» window, in raw PCM,
    // and a word he practises daily would gather hundreds of megabytes on his phone and — through
    // sync — on his caregiver's.

    /** One recording per file, as the modules make them: a new take is always a new file. */
    private suspend fun take(itemId: String, name: String, who: Who = Who.DIMITRIS): Recording {
        clock += 1_000
        val file = File(takes, name).apply { parentFile?.mkdirs(); writeBytes(ByteArray(64)) }
        return repo.addRecording(itemId, file, durationMs = 1_000, who = who)
    }

    private val takes = File(System.getProperty("java.io.tmpdir"), "takes-${System.nanoTime()}")

    @Test fun `only the newest three of his takes of a word are kept`() = runTest {
        val item = repo.save(Item(text = "νερό"))
        val first = take(item.id, "1.wav")
        val second = take(item.id, "2.wav")
        val third = take(item.id, "3.wav")
        val fourth = take(item.id, "4.wav")

        assertEquals("the oldest is gone", true, recordings.rows[first.id]?.deleted)
        assertEquals("and its file with it", false, File(takes, "1.wav").exists())
        for (kept in listOf(second, third, fourth)) {
            assertEquals("the newest three stay", false, recordings.rows[kept.id]?.deleted)
        }
        assertEquals("three files on disk", 3, takes.listFiles()!!.size)
    }

    /**
     * The delete is soft and stamped, which is what makes it travel: the sync pushes rows by
     * `updatedAt` and never filters on `deleted`, so the caregiver's phone loses the file too rather
     * than keeping a copy of everything he ever said.
     */
    @Test fun `a pruned take travels as a deletion`() = runTest {
        val item = repo.save(Item(text = "νερό"))
        val first = take(item.id, "1.wav")
        repeat(3) { take(item.id, "${it + 2}.wav") }

        val row = recordings.rows[first.id]!!
        assertEquals(true, row.deleted)
        assertEquals("stamped, or the other phone never asks for it", clock, row.updatedAt)
        assertEquals("and it is in what the next sync pushes", true, recordings.changedSince(0).any { it.id == first.id })
    }

    /** Each word keeps its own three: pruning one must not touch another. */
    @Test fun `the cap is per word`() = runTest {
        val water = repo.save(Item(text = "νερό"))
        val bread = repo.save(Item(text = "ψωμί"))
        repeat(4) { take(water.id, "w${it}.wav") }
        repeat(2) { take(bread.id, "b${it}.wav") }

        assertEquals(3, recordings.allFor(water.id, Who.DIMITRIS, RecordingStyle.SPOKEN).size)
        assertEquals(2, recordings.allFor(bread.id, Who.DIMITRIS, RecordingStyle.SPOKEN).size)
    }

    /**
     * A caregiver's model voice is never pruned. Hers is the thing being practised against, and a
     * word she has recorded four different ways is four things she chose to keep.
     */
    @Test fun `the caregiver's voices are never pruned`() = runTest {
        val item = repo.save(Item(text = "νερό"))
        repeat(4) { take(item.id, "c${it}.m4a", who = Who.CAREGIVER) }

        assertEquals(4, recordings.allFor(item.id, Who.CAREGIVER, RecordingStyle.SPOKEN).size)
        assertEquals("nothing of hers was deleted", 4, takes.listFiles()!!.size)
    }

    /** Three is the number, and it is said in one place. */
    @Test fun `three is what anyone listens back to`() = assertEquals(3, ItemRepository.HIS_TAKES)
}
