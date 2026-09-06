package gr.dimitris.app.core.data

import androidx.test.core.app.ApplicationProvider
import gr.dimitris.app.core.sync.DaoSyncStore
import gr.dimitris.app.core.sync.Rows
import gr.dimitris.app.core.sync.SyncDaos
import gr.dimitris.app.core.sync.Tables
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ItemDaoTest {
    private lateinit var db: AppDatabase

    @Before fun open() { db = AppDatabase.inMemory(ApplicationProvider.getApplicationContext()) }
    @After fun close() { db.close() }

    @Test fun upsertThenGet() = runTest {
        val item = Item(text = "καφές", category = Category.FOOD, firstSound = "κ")
        db.items().upsert(item)
        assertEquals(item, db.items().get(item.id))
    }

    @Test fun softDeletedItemsLeaveObserveActive() = runTest {
        val keep = Item(text = "νερό", category = Category.FOOD)
        val drop = Item(text = "ψωμί", category = Category.FOOD)
        db.items().upsertAll(listOf(keep, drop))
        db.items().softDelete(drop.id, now())
        assertEquals(listOf(keep), db.items().observeActive().first())
        assertEquals(1, db.items().countActive())
    }

    @Test fun errorLogRoundTrip() = runTest {
        val log = ErrorLog.from("test", RuntimeException("boom"))
        db.errorLogs().insert(log)
        assertEquals("boom", db.errorLogs().all().single().message)
        assertNull(db.recordings().get("missing"))
    }

    @Test fun observePinnedReturnsOnlyPinnedActiveItems() = runTest {
        val pinned = Item(text = "νερό", category = Category.FOOD, pinned = true)
        val plain = Item(text = "ψωμί", category = Category.FOOD)
        val gone = Item(text = "τυρί", category = Category.FOOD, pinned = true, deleted = true)
        db.items().upsertAll(listOf(pinned, plain, gone))
        assertEquals(listOf(pinned), db.items().observePinned().first())
        assertEquals(setOf(pinned.id, plain.id), db.items().byIds(listOf(pinned.id, plain.id, gone.id)).map { it.id }.toSet())
    }

    /**
     * The seed importers' one query that must *not* filter deletions. A word or a dialogue the
     * caregiver removed has to keep counting as "already on this device", or the next version bump
     * hands it back to her — for ever, and with nothing said anywhere.
     */
    @Test fun deletedRowsAreStillOnTheDevice() = runTest {
        val kept = Item(text = "νερό", category = Category.FOOD)
        val removed = Item(text = "ψωμί", category = Category.FOOD, deleted = true)
        db.items().upsertAll(listOf(kept, removed))
        assertEquals(setOf("νερό", "ψωμί"), db.items().all().map { it.text }.toSet())
        assertEquals(listOf("νερό"), db.items().allActive().map { it.text })

        val live = Script(title = "Στην καφετέρια")
        val deleted = Script(title = "Με έναν φίλο", deleted = true)
        db.scripts().upsertScript(live)
        db.scripts().upsertScript(deleted)
        assertEquals(setOf("Στην καφετέρια", "Με έναν φίλο"), db.scripts().allScripts().map { it.title }.toSet())
        assertEquals(listOf("Στην καφετέρια"), db.scripts().activeScripts().map { it.title })
    }

    /**
     * «φωνή ναι/όχι» in the journey report means "a caregiver has recorded this word", never "he
     * has practised it". The word coach saves a row for every take *he* makes, so without the
     * `who` filter almost every word he had practised reported «φωνή ναι» after a fortnight — and
     * the prompt asks Claude which words the caregivers should record next.
     */
    @Test fun itemsWithVoiceCountsOnlyTheCaregiversTakes() = runTest {
        val hers = Item(text = "καφές", category = Category.FOOD)
        val his = Item(text = "ψωμί", category = Category.FOOD)
        val gone = Item(text = "νερό", category = Category.FOOD)
        db.items().upsertAll(listOf(hers, his, gone))
        db.recordings().upsert(Recording(itemId = hers.id, path = "recordings/a.m4a", who = Who.CAREGIVER, durationMs = 500))
        db.recordings().upsert(Recording(itemId = his.id, path = "recordings/b.m4a", who = Who.DIMITRIS, durationMs = 500))
        val removed = Recording(itemId = gone.id, path = "recordings/c.m4a", who = Who.CAREGIVER, durationMs = 500)
        db.recordings().upsert(removed)
        db.recordings().softDelete(removed.id, now())

        assertEquals(listOf(hers.id), db.recordings().itemsWithVoice(Who.CAREGIVER))
        assertEquals(listOf(his.id), db.recordings().itemsWithVoice(Who.DIMITRIS))
    }

    /**
     * The rotation reads "when was this module last really practised" out of the attempt rows. The
     * one row per sitting that is about the sitting is written CORRECT against whichever module was
     * planned last, so counting it would mark that module practised on days he never touched it.
     */
    @Test fun lastUsePerModuleIgnoresTheSittingsOwnSummaryRow() = runTest {
        db.attempts().insert(Attempt(itemId = "i1", module = ModuleId.WORDCOACH, startedAt = 100,
            durationMs = 1_000, outcome = Outcome.CORRECT))
        db.attempts().insert(Attempt(itemId = "session:summary", module = ModuleId.TRACE, sessionId = "s1",
            startedAt = 200, durationMs = 900_000, outcome = Outcome.CORRECT))
        db.attempts().insert(Attempt(itemId = "i1", module = ModuleId.SINGSAY, startedAt = 300,
            durationMs = 1_000, outcome = Outcome.SKIPPED))

        val used = db.attempts().lastUsePerModule(Outcome.SKIPPED, "session:summary")

        assertEquals(listOf(ModuleId.WORDCOACH to 100L), used.map { it.module to it.lastAt })
    }

    /** The two tables phase 11 added, through the real database rather than a fake. */
    @Test fun adviceAndNotesRoundTripAndSortNewestFirst() = runTest {
        db.notes().upsert(Note(id = "n1", at = 100, text = "Παλιά", author = "CAREGIVER"))
        db.notes().upsert(Note(id = "n2", at = 200, text = "Νέα", author = "DIMITRIS"))
        db.notes().softDelete("n1", now())
        assertEquals(listOf("Νέα"), db.notes().recent(10).map { it.text })

        db.advice().upsert(Advice(id = "a1", at = 100, model = "m", report = "r1", caregivers = "c1", dimitris = "d1"))
        db.advice().upsert(Advice(id = "a2", at = 200, model = "m", report = "r2", caregivers = "c2", dimitris = "d2",
            focusJson = """{"sounds":["π"]}"""))
        assertEquals("a2", db.advice().newest()?.id)
        assertEquals(listOf("a2", "a1"), db.advice().recent(10).map { it.id })
        assertEquals("""{"sounds":["π"]}""", db.advice().newest()?.focusJson)
    }

    /**
     * A pulled row, through the same path the sync uses, into the real database.
     *
     * The fakes cannot see this: `DaoSyncStore.apply` converts with Gson and then hands the result
     * to generated Room code, and only a device runs that. A row it cannot store comes back as
     * "skipped" and the caregiver gets a Greek line about it for ever.
     */
    @Test fun aPulledNoteAndAdviceRowAreStoredByTheRealSyncPath() = runTest {
        val store = DaoSyncStore { SyncDaos.of(db) }

        val note = Note(id = "n9", at = 5, text = "Είπε «καλημέρα» μόνος του.", author = "CAREGIVER")
        val advice = Advice(id = "a9", at = 5, model = "claude-opus-5", report = "Προφίλ…",
            caregivers = "- Ένα.", dimitris = "Πάει καλά.", focusJson = """{"sounds":["π"]}""")

        assertEquals(emptyList<String>(), store.apply(Tables.NOTES, listOf(Rows.of(note))))
        assertEquals(emptyList<String>(), store.apply(Tables.ADVICE, listOf(Rows.of(advice))))

        assertEquals(note, db.notes().recent(10).single())
        assertEquals(advice, db.advice().recent(10).single())
        assertEquals(mapOf("n9" to note.updatedAt), store.stamps(Tables.NOTES, listOf("n9")))
        assertEquals(listOf(Rows.of(advice)), store.changedSince(Tables.ADVICE, 0))
    }
}
