package gr.dimitris.app.core.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class ScriptRepositoryTest {
    private val itemDao = FakeItemDao()
    private val recDao = FakeRecordingDao()
    private val scriptDao = FakeScriptDao()
    private var clock = 5_000L
    private val items = ItemRepository(itemDao, recDao) { clock }
    private val repo = ScriptRepository(scriptDao, items) { clock }

    private val coffee = listOf(
        LineDraft(Speaker.OTHER, "Καλημέρα! Τι θα πάρετε;"),
        LineDraft(Speaker.DIMITRIS, "Έναν καφέ, παρακαλώ."),
        LineDraft(Speaker.OTHER, "Ζάχαρη;", recordingFile = File("/tmp/z.m4a"), recordingMs = 700),
        LineDraft(Speaker.DIMITRIS, "Μέτριο."),
    )

    @Test fun `save creates script lines and items in order`() = runTest {
        val s = repo.save(null, "Καφές", coffee)
        val loaded = repo.load(s.id)!!
        assertEquals("Καφές", loaded.script.title)
        assertEquals(listOf(0, 1, 2, 3), loaded.lines.map { it.first.position })
        assertEquals(listOf(Speaker.OTHER, Speaker.DIMITRIS, Speaker.OTHER, Speaker.DIMITRIS), loaded.lines.map { it.first.speaker })
        assertEquals("Έναν καφέ, παρακαλώ.", loaded.lines[1].second.text)
        assertEquals(ItemKind.SCRIPT_LINE, loaded.lines[1].second.kind)
        // Greek.firstSound strips the tonos, so the cue for «Έναν» is ε — the sound, not the letter as typed.
        assertEquals("ε", loaded.lines[1].second.firstSound)
        assertEquals("/tmp/z.m4a", items.modelRecording(loaded.lines[2].second)?.path)
    }

    @Test fun `saving again replaces lines and keeps the script id`() = runTest {
        val s = repo.save(null, "Καφές", coffee)
        val again = repo.save(s.id, "Καφές το πρωί", coffee.take(2))
        assertEquals(s.id, again.id)
        assertEquals("Καφές το πρωί", again.title)
        assertEquals(2, repo.load(s.id)!!.lines.size)
        assertEquals(2, scriptDao.lines.values.count { !it.deleted })
    }

    /**
     * A turn she did not touch keeps everything it had. Rebuilding every line on every save was
     * invisible and unbounded: ten passes over an eight-line dialogue left eighty items nothing
     * pointed at — in the database, in the backup zip, and in the seed importer's "already here"
     * set — plus a second recording row per pass for one file on disk.
     */
    @Test fun `an unchanged line keeps its row, its item and its take`() = runTest {
        val s = repo.save(null, "Καφές", coffee)
        val before = repo.load(s.id)!!.lines
        val itemsAfterFirst = itemDao.rows.value.size
        val recordingsAfterFirst = recDao.rows.size

        repo.save(s.id, "Καφές", coffee)

        val after = repo.load(s.id)!!.lines
        assertEquals("the rows themselves", before.map { it.first.id }, after.map { it.first.id })
        assertEquals("the items behind them", before.map { it.second.id }, after.map { it.second.id })
        assertEquals("no orphan items", itemsAfterFirst, itemDao.rows.value.size)
        assertEquals("no duplicate recording rows", recordingsAfterFirst, recDao.rows.size)
        assertEquals("/tmp/z.m4a", items.modelRecording(after[2].second)?.path)
    }

    /** Reordering is not editing: the turns keep their items, and with them their recorded voices. */
    @Test fun `reordering keeps every line's item and moves only its position`() = runTest {
        val s = repo.save(null, "Καφές", coffee)
        val before = repo.load(s.id)!!.lines.associate { it.first.speaker to it.second.id }
        val itemsAfterFirst = itemDao.rows.value.size

        repo.save(s.id, "Καφές", listOf(coffee[2], coffee[3], coffee[0], coffee[1]))

        val after = repo.load(s.id)!!.lines
        assertEquals(listOf(0, 1, 2, 3), after.map { it.first.position })
        assertEquals(listOf("Ζάχαρη;", "Μέτριο.", "Καλημέρα! Τι θα πάρετε;", "Έναν καφέ, παρακαλώ."), after.map { it.second.text })
        assertEquals("no new items for a reorder", itemsAfterFirst, itemDao.rows.value.size)
        assertEquals("the take travels with its turn", "/tmp/z.m4a", items.modelRecording(after[0].second)?.path)
        assertEquals(before[Speaker.OTHER], after.first { it.first.speaker == Speaker.OTHER }.second.id)
    }

    /** A turn she reworded, or gave to the other speaker, is a new turn: its history stays readable. */
    @Test fun `only the lines she really changed get new items`() = runTest {
        val s = repo.save(null, "Καφές", coffee)
        val before = repo.load(s.id)!!.lines
        val itemsAfterFirst = itemDao.rows.value.size

        repo.save(s.id, "Καφές", listOf(
            coffee[0],
            coffee[1].copy(text = "Έναν καφέ χωρίς ζάχαρη."),
            coffee[2].copy(speaker = Speaker.DIMITRIS),
            coffee[3],
        ))

        val after = repo.load(s.id)!!.lines
        assertEquals("the untouched first turn", before[0].second.id, after[0].second.id)
        assertEquals("the untouched last turn", before[3].second.id, after[3].second.id)
        assertTrue("a reworded turn is a new item", before[1].second.id != after[1].second.id)
        assertTrue("a turn given to the other speaker is a new item", before[2].second.id != after[2].second.id)
        assertEquals("exactly two new items, nothing more", itemsAfterFirst + 2, itemDao.rows.value.size)
        // The replaced items are still there, undeleted: the attempts written against them stay readable.
        assertEquals(before[1].second, itemDao.get(before[1].second.id))
    }

    @Test fun `delete is soft and hides the script`() = runTest {
        val s = repo.save(null, "Ταξί", coffee.take(1))
        repo.delete(s.id)
        assertEquals(emptyList<Script>(), repo.observeAll().first())
        assertNull(repo.load(s.id))
    }

    /** Task 4 has no "current script" to consult: a session item is asked which script it belongs to. */
    @Test fun `a line item finds its own script back`() = runTest {
        val s = repo.save(null, "Καφές", coffee)
        val line = repo.load(s.id)!!.lines[1]
        assertEquals(s.id, repo.scriptOf(line.second.id)?.script?.id)
        assertNull(repo.scriptOf("δεν-είναι-γραμμή"))
        // The item of a replaced line is history now, and history is not the live script any more.
        repo.save(s.id, "Καφές", coffee.take(1))
        assertNull(repo.scriptOf(line.second.id))
    }

    /** An empty row in the editor is a row the caregiver has not filled in yet, not a silent turn. */
    @Test fun `blank lines are dropped and the rest keep contiguous positions`() = runTest {
        val s = repo.save(null, "Καφές", listOf(
            LineDraft(Speaker.OTHER, "Γεια"),
            LineDraft(Speaker.DIMITRIS, "   "),
            LineDraft(Speaker.DIMITRIS, "Γεια σου"),
        ))
        val loaded = repo.load(s.id)!!
        assertEquals(listOf(0, 1), loaded.lines.map { it.first.position })
        assertEquals(listOf("Γεια", "Γεια σου"), loaded.lines.map { it.second.text })
    }

    /**
     * The whole point of the transaction. The soft-delete of the old lines and the write of the new
     * ones are one commit, so a failure in the middle — a full disk, a process death — leaves the
     * dialogue she had. Before this, the same failure left a script with no lines at all and said
     * nothing about it.
     */
    @Test fun `a save that fails halfway leaves the dialogue it was replacing intact`() = runTest {
        val dao = FakeScriptDao()
        val working = ScriptRepository(dao, items, rollingBack(dao)) { clock }
        val s = working.save(null, "Καφές", coffee)
        val lineIds = dao.lines.values.filter { !it.deleted }.map { it.id }.toSet()
        assertEquals(4, lineIds.size)

        // The same dao, but the write of the new lines throws — which is what the runner rolls back.
        val breaking = ScriptRepository(FailingLinesDao(dao), items, rollingBack(dao)) { clock }
        val attempt = runCatching { breaking.save(s.id, "Καφές το πρωί", coffee.take(2)) }
        assertTrue("the save should have failed", attempt.isFailure)

        val after = working.load(s.id)!!
        assertEquals("Καφές", after.script.title)
        assertEquals(4, after.lines.size)
        assertEquals(lineIds, dao.lines.values.filter { !it.deleted }.map { it.id }.toSet())
    }

    /** What Room's withTransaction does for real: nothing the block wrote survives it throwing. */
    private fun rollingBack(dao: FakeScriptDao): suspend (suspend () -> Unit) -> Unit = { block ->
        val scriptsBefore = dao.scripts.value
        val linesBefore = dao.lines.toMap()
        try {
            block()
        } catch (e: Throwable) {
            dao.scripts.value = scriptsBefore
            dao.lines.clear()
            dao.lines.putAll(linesBefore)
            throw e
        }
    }

    /** A disk that fills up exactly when the new lines are written. */
    private class FailingLinesDao(delegate: FakeScriptDao) : ScriptDao by delegate {
        override suspend fun upsertLines(lines: List<ScriptLine>): Unit = error("δεν υπάρχει χώρος στη συσκευή")
    }

    /**
     * Re-saving a dialogue must not cost the caregiver the takes she already recorded: the file she
     * hands back in is the one already in the recordings dir, and it has to still be there
     * afterwards, under the same name, once.
     */
    @Test fun `re-saving a line that keeps its take never moves or deletes the file`() = runTest {
        val filesDir = createTempDirectory("files").toFile()
        val recordingsDir = File(filesDir, "recordings").apply { mkdirs() }
        val take = File(recordingsDir, "take.m4a")
        take.writeText("ηχογράφηση")
        // What MediaFiles.relativize does on the phone: a path under the files dir is stored relative.
        val relativize: (File) -> String = { f ->
            f.absolutePath.removePrefix(filesDir.absolutePath + File.separator).replace(File.separatorChar, '/')
        }
        val dao = FakeScriptDao()
        val recordings = FakeRecordingDao()
        val itemRepo = ItemRepository(FakeItemDao(), recordings, relativize) { clock }
        val scriptRepo = ScriptRepository(dao, itemRepo) { clock }

        val first = scriptRepo.save(null, "Καφές", listOf(LineDraft(Speaker.OTHER, "Ζάχαρη;", take, 700)))
        val stored = itemRepo.modelRecording(scriptRepo.load(first.id)!!.lines[0].second)!!
        assertEquals("recordings/take.m4a", stored.path)

        // The caregiver reopens the script and keeps the take: the same file comes back in.
        val again = scriptRepo.save(first.id, "Καφές", listOf(LineDraft(Speaker.OTHER, "Ζάχαρη;", File(filesDir, stored.path), 700)))
        val kept = itemRepo.modelRecording(scriptRepo.load(again.id)!!.lines[0].second)!!
        assertEquals("recordings/take.m4a", kept.path)
        assertTrue("the take must still be on disk", take.exists())
        assertEquals("ηχογράφηση", take.readText())
        assertEquals(1, recordingsDir.listFiles()!!.size)
    }

    /**
     * Two phones importing the same bundled dialogue have to write the same rows, or the first sync
     * merges both copies onto both of them. The ids come from the dialogue, not from a fresh UUID —
     * see [gr.dimitris.app.core.seed.SeedIds] — and this is the seam that carries them through.
     */
    @Test fun `a seeded dialogue writes the ids it was given, and writes them the same twice`() = runTest {
        val ids: (Int) -> Pair<String, String> = { i -> "line-$i" to "line-item-$i" }
        val first = repo.save("script-1", "Καφές", coffee, source = Source.SEED, seedIds = ids)

        assertEquals("script-1", first.id)
        val loaded = repo.load("script-1")!!
        assertEquals(listOf("line-0", "line-1", "line-2", "line-3"), loaded.lines.map { it.first.id })
        assertEquals(listOf("line-item-0", "line-item-1", "line-item-2", "line-item-3"), loaded.lines.map { it.second.id })

        // The other phone: its own empty database, the same manifest, the same rows.
        val otherItems = ItemRepository(FakeItemDao(), FakeRecordingDao()) { clock }
        val other = ScriptRepository(FakeScriptDao(), otherItems) { clock }
        val theirs = other.save("script-1", "Καφές", coffee, source = Source.SEED, seedIds = ids)

        assertEquals(first.id, theirs.id)
        assertEquals(loaded.lines.map { it.first.id }, other.load("script-1")!!.lines.map { it.first.id })
    }

    /** A dialogue a caregiver typed still gets fresh ids, and two of them are never the same. */
    @Test fun `a caregiver's own dialogue gets ids of its own`() = runTest {
        val one = repo.save(null, "Δικό της", coffee)
        val two = repo.save(null, "Δικό της 2", coffee)
        assertTrue(one.id != two.id)
        assertEquals(4, repo.load(one.id)!!.lines.map { it.first.id }.distinct().size)
    }
}
