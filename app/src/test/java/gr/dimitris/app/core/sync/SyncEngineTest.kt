package gr.dimitris.app.core.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import gr.dimitris.app.core.data.Attempt
import gr.dimitris.app.core.data.FakeAdviceDao
import gr.dimitris.app.core.data.FakeAttemptDao
import gr.dimitris.app.core.data.FakeItemDao
import gr.dimitris.app.core.data.FakeNoteDao
import gr.dimitris.app.core.data.FakeRecordingDao
import gr.dimitris.app.core.data.FakeScheduleDao
import gr.dimitris.app.core.data.FakeScriptDao
import gr.dimitris.app.core.data.FakeSessionDao
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Outcome
import gr.dimitris.app.core.data.Recording
import gr.dimitris.app.core.data.Schedule
import gr.dimitris.app.core.data.Session
import gr.dimitris.app.core.data.Who
import gr.dimitris.app.core.log.FakeErrorLogDao
import gr.dimitris.app.core.settings.Settings
import gr.dimitris.app.core.settings.DeviceRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * The engine over the app's own fake DAOs and a server in a map.
 *
 * [Phone] is one device: its database, its photo folders, its settings and its cursors. Several
 * tests need two of them pointed at the same server, because that is the whole feature.
 */
class SyncEngineTest {

    private val client = FakeSyncClient()

    private inner class Phone(
        private val server: SyncClient = client,
        /** A chance to make the database misbehave: a full disk, a row this version cannot map. */
        wrap: (SyncStore) -> SyncStore = { it },
    ) {
        val items = FakeItemDao()
        val recordings = FakeRecordingDao()
        val attempts = FakeAttemptDao()
        val schedules = FakeScheduleDao()
        val sessions = FakeSessionDao()
        val errorLogs = FakeErrorLogDao()
        val scripts = FakeScriptDao()
        val advice = FakeAdviceDao()
        val notes = FakeNoteDao()
        val files = FakeMediaPaths()
        val settings = newSettings()
        val recorded = mutableListOf<Pair<String, Throwable>>()
        var bumps = 0

        private val store = wrap(
            DaoSyncStore { SyncDaos(items, recordings, attempts, schedules, sessions, errorLogs, scripts, advice, notes) }
        )

        fun engine(clock: () -> Long = { AT }) = SyncEngine(
            client = server, store = store, files = files, settings = settings,
            onPulled = { bumps++ },
            record = { where, e -> recorded += where to e },
            clock = clock,
        )

        fun configure() = runBlocking { settings.setSyncUrl("https://sync.example.com") }
        fun sync(): SyncReport = runBlocking { engine().syncNow().getOrThrow() }

        private fun newSettings(): Settings {
            val dir = createTempDirectory("sync-settings").toFile()
            return Settings(
                PreferenceDataStoreFactory.create(
                    scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
                    produceFile = { File(dir, "settings.preferences_pb") },
                )
            )
        }
    }

    private val phone = Phone().apply { configure() }

    private fun item(id: String, text: String, at: Long, image: String? = null) =
        Item(id = id, text = text, imagePath = image, createdAt = at, updatedAt = at)

    // ---------------------------------------------------------------- push

    @Test fun `a phone with no server never opens a socket`() = runBlocking {
        val fresh = Phone()
        fresh.items.upsert(item("i1", "ψωμί", 10))
        val result = fresh.engine().syncNow()
        assertTrue(result.isFailure)
        assertEquals(HttpSyncClient.NOT_CONFIGURED, result.exceptionOrNull()?.message)
        assertEquals(0, client.pushes)
    }

    @Test fun `push sends only the rows changed since the mark, and moves it`() = runBlocking {
        phone.items.upsert(item("old", "παλιά", 100))
        phone.settings.setSyncPushedUpTo(100)
        phone.items.upsert(item("new", "νέα", 200))

        val report = phone.sync()

        assertEquals(1, report.pushed)
        assertEquals(listOf("new"), client.pushed.map { it.row["id"] })
        assertEquals(200L, phone.settings.syncPushedUpTo.first())
    }

    @Test fun `every table travels, and a schedule takes the id the server keys it on`() = runBlocking {
        phone.items.upsert(item("i1", "ψωμί", 10))
        phone.attempts.insert(
            Attempt(id = "a1", itemId = "i1", module = ModuleId.WORDCOACH, startedAt = 1, durationMs = 5, outcome = Outcome.CORRECT, updatedAt = 11)
        )
        phone.schedules.upsert(Schedule(itemId = "i1", module = ModuleId.SINGSAY, nextDueAt = 5, updatedAt = 12))

        phone.sync()

        assertEquals("i1:SINGSAY", client.rowsOf(Tables.SCHEDULES).single()["id"])
        assertEquals("a1", client.rowsOf(Tables.ATTEMPTS).single()["id"])
        assertEquals("ψωμί", client.rowsOf(Tables.ITEMS).single()["text"])
    }

    /**
     * A caregiver trying «Λέξεις» on their own phone is not Dimitris practising. Their attempts,
     * sittings and Leitner boxes must not become his four-week counts — and the boxes especially,
     * because those decide which words he is handed next.
     */
    @Test fun `a caregiver phone keeps its own practice to itself`() = runBlocking {
        phone.settings.setDeviceRole(DeviceRole.CAREGIVER)
        phone.items.upsert(item("i1", "ψωμί", 10))
        phone.attempts.insert(
            Attempt(id = "a1", itemId = "i1", module = ModuleId.WORDCOACH, startedAt = 1, durationMs = 5, outcome = Outcome.CORRECT, updatedAt = 11)
        )
        phone.sessions.upsert(Session(id = "s1", startedAt = 1, plannedModules = "WORDCOACH", plannedItemCount = 3, updatedAt = 12))
        phone.schedules.upsert(Schedule(itemId = "i1", module = ModuleId.WORDCOACH, nextDueAt = 5, updatedAt = 13))

        val report = phone.sync()

        assertEquals(1, report.pushed)
        assertEquals("ψωμί", client.rowsOf(Tables.ITEMS).single()["text"])
        assertTrue(client.rowsOf(Tables.ATTEMPTS).isEmpty())
        assertTrue(client.rowsOf(Tables.SESSIONS).isEmpty())
        assertTrue(client.rowsOf(Tables.SCHEDULES).isEmpty())
        // Unchanged wording: nothing about this belongs on a caregiver's screen.
        assertTrue(report.errors.toString(), report.ok)
    }

    /** …and it still receives his. */
    @Test fun `a caregiver phone still takes in Dimitris' practice`() = runBlocking {
        phone.settings.setDeviceRole(DeviceRole.CAREGIVER)
        val his = Attempt(id = "a1", itemId = "i1", module = ModuleId.WORDCOACH, startedAt = 1, durationMs = 5, outcome = Outcome.CORRECT, updatedAt = 11)
        client.seed(Tables.ATTEMPTS, Rows.of(his))
        client.seed(Tables.SCHEDULES, Tables.of(Tables.SCHEDULES)!!.withId(
            Rows.of(Schedule(itemId = "i1", module = ModuleId.WORDCOACH, nextDueAt = 5, updatedAt = 13))
        ))

        val report = phone.sync()

        assertEquals(2, report.pulled)
        assertEquals(Outcome.CORRECT, phone.attempts.rows.value.single().outcome)
        assertEquals(5L, phone.schedules.get("i1", ModuleId.WORDCOACH)?.nextDueAt)
    }

    @Test fun `his own phone pushes everything, as it always did`() = runBlocking {
        phone.settings.setDeviceRole(DeviceRole.DIMITRIS)
        phone.attempts.insert(
            Attempt(id = "a1", itemId = "i1", module = ModuleId.WORDCOACH, startedAt = 1, durationMs = 5, outcome = Outcome.CORRECT, updatedAt = 11)
        )
        phone.schedules.upsert(Schedule(itemId = "i1", module = ModuleId.WORDCOACH, nextDueAt = 5, updatedAt = 13))

        phone.sync()

        assertEquals("a1", client.rowsOf(Tables.ATTEMPTS).single()["id"])
        assertEquals("i1:WORDCOACH", client.rowsOf(Tables.SCHEDULES).single()["id"])
    }

    /** A soft delete is an ordinary row change, or a word removed here would live for ever there. */
    @Test fun `a deleted row is pushed like any other`() = runBlocking {
        phone.items.upsert(item("i1", "ψωμί", 10).copy(deleted = true))
        phone.sync()
        assertEquals(true, client.rowsOf(Tables.ITEMS).single()["deleted"])
    }

    @Test fun `a batch too big for the server is halved rather than retried`() = runBlocking {
        client.maxRowsPerPush = 2
        for (n in 1..4) phone.items.upsert(item("i$n", "λέξη $n", n.toLong()))

        val report = phone.sync()

        assertEquals(4, report.pushed)
        assertEquals(4, client.rowsOf(Tables.ITEMS).size)
        assertTrue(report.errors.toString(), report.ok)
    }

    /**
     * The window this exists for: eight table reads and a photo upload each take seconds, and a
     * caregiver saving a word in the middle of them must not fall below the mark.
     *
     * `items` is read first; the write lands while the push is in flight, stamped 1500; a row a
     * later table holds is stamped 2000 and is what the mark would otherwise follow. The clock read
     * *before* the first query is 1000, so the mark stops there and the word goes next time.
     */
    @Test fun `a word saved while the push is in flight is sent on the next sync`() = runBlocking {
        var now = 1000L
        phone.attempts.insert(
            Attempt(id = "a1", itemId = "i0", module = ModuleId.WORDCOACH, startedAt = 1, durationMs = 5, outcome = Outcome.CORRECT, updatedAt = 2000)
        )
        client.duringPush = {
            now = 3000                                     // the sync has been running a while
            phone.items.upsert(item("late", "ψωμί", 1500))  // …and she saves a word
            client.duringPush = null
        }

        val first = phone.engine { now }.syncNow().getOrThrow()

        assertEquals(1, first.pushed)                       // the attempt only
        assertEquals(1000L, phone.settings.syncPushedUpTo.first())
        assertTrue(client.rowsOf(Tables.ITEMS).isEmpty())

        val second = phone.sync()

        // Two: the word, and the attempt from above — it sits over the mark as well, so it is
        // offered once more and the server ignores it as a tie.
        assertEquals(2, second.pushed)
        assertEquals("ψωμί", client.rowsOf(Tables.ITEMS).single()["text"])
    }

    /** One row the server will not take at any size is skipped, and the mark does not move past it. */
    @Test fun `a single row that is too big is skipped and reported`() = runBlocking {
        client.maxRowsPerPush = 0
        phone.items.upsert(item("i1", "ψωμί", 50))

        val report = phone.sync()

        assertEquals(0, report.pushed)
        assertTrue(report.errors.contains(SyncEngine.ROW_TOO_BIG))
        // Nothing was accepted, so the mark does not move at all and the row is offered again.
        assertEquals(0L, phone.settings.syncPushedUpTo.first())
    }

    /** The mark stops below the row that did not go, so the next sync offers it again. */
    /**
     * A row an older server will not take must not stop everything behind it. The batch is halved
     * until the offender is alone, and then only that row is held back.
     */
    @Test fun `a row the server refuses is skipped by name and the rest still goes`() = runBlocking {
        for (n in 1..4) phone.items.upsert(item("i$n", "λέξη $n", n.toLong()))
        client.refuses = "items/i3"

        val report = phone.sync()

        assertEquals(3, report.pushed)
        assertEquals(setOf("i1", "i2", "i4"), client.rowsOf(Tables.ITEMS).map { it["id"] }.toSet())
        assertEquals(listOf(SyncEngine.rowRefused("items/i3")), report.errors)
        // The mark stops below the refused row, so a server that learns the table later still gets it.
        assertEquals(2L, phone.settings.syncPushedUpTo.first())
    }

    /** The refused row's own words never reach the error log — only where to find it. */
    @Test fun `a refused row is named, never quoted`() = runBlocking {
        phone.items.upsert(item("i1", "μυστικό", 10))
        client.refuses = "items/i1"

        val report = phone.sync()

        assertEquals(1, report.errors.size)
        assertTrue(report.errors.single(), report.errors.single().contains("items/i1"))
        assertFalse(report.errors.single(), report.errors.single().contains("μυστικό"))
        assertFalse(phone.recorded.single().second.stackTraceToString().contains("μυστικό"))
    }

    @Test fun `a push that fails holds the mark below the row it lost`() = runBlocking {
        phone.items.upsert(item("i1", "ψωμί", 50))
        client.failPush = SyncException(HttpSyncClient.OFFLINE)

        val failed = phone.sync()

        assertEquals(0, failed.pushed)
        assertEquals(0L, phone.settings.syncPushedUpTo.first())
        assertTrue(failed.errors.contains(HttpSyncClient.OFFLINE))
        assertEquals(listOf("sync push"), phone.recorded.map { it.first })

        val again = phone.sync()

        assertEquals(1, again.pushed)
        assertEquals("ψωμί", client.rowsOf(Tables.ITEMS).single()["text"])
        assertEquals(50L, phone.settings.syncPushedUpTo.first())
    }

    // ---------------------------------------------------------------- pull

    @Test fun `pull applies a row this phone has never seen and advances the cursor`() = runBlocking {
        client.seed(Tables.ITEMS, Rows.of(item("i1", "ψωμί", 10)))

        val report = phone.sync()

        assertEquals(1, report.pulled)
        assertEquals("ψωμί", phone.items.get("i1")?.text)
        assertEquals(1L, phone.settings.syncCursor.first())
        assertEquals(1, phone.bumps)
    }

    @Test fun `pull keeps the newer row and never the older one`() = runBlocking {
        phone.items.upsert(item("i1", "εδώ", 100))
        phone.items.upsert(item("i2", "νεότερο", 2))
        client.seed(Tables.ITEMS, Rows.of(item("i1", "εκεί", 200)))
        client.seed(Tables.ITEMS, Rows.of(item("i2", "παλιό", 1)))
        phone.settings.setSyncPushedUpTo(200)   // nothing of ours is due to go up

        phone.sync()

        assertEquals("εκεί", phone.items.get("i1")?.text)
        assertEquals("νεότερο", phone.items.get("i2")?.text)
    }

    /** A tie keeps what is here, so a row that comes back around does not churn the database. */
    @Test fun `a tie changes nothing`() = runBlocking {
        phone.items.upsert(item("i1", "εδώ", 100))
        phone.settings.setSyncPushedUpTo(100)
        client.seed(Tables.ITEMS, Rows.of(item("i1", "εκεί", 100)))

        val report = phone.sync()

        assertEquals("εδώ", phone.items.get("i1")?.text)
        assertEquals(0, report.pulled)
        assertEquals(0, phone.bumps)
    }

    @Test fun `an attempt that is already here is never overwritten`() = runBlocking {
        val mine = Attempt(id = "a1", itemId = "i1", module = ModuleId.WORDCOACH, startedAt = 1, durationMs = 5, outcome = Outcome.CORRECT, updatedAt = 10)
        phone.attempts.insert(mine)
        phone.settings.setSyncPushedUpTo(10)
        client.seed(Tables.ATTEMPTS, Rows.of(mine.copy(outcome = Outcome.SKIPPED, updatedAt = 999)))

        val report = phone.sync()

        assertEquals(Outcome.CORRECT, phone.attempts.rows.value.single().outcome)
        // Not counted either: an attempt of ours coming back around is not a row that arrived, and
        // the number on the sync screen has to mean what it says.
        assertEquals(0, report.pulled)
        assertEquals(0, phone.bumps)
    }

    /**
     * A page smaller than the one asked for is not the end — a proxy or a later server may clamp it
     * — so the loop stops only on an empty page.
     */
    @Test fun `pull keeps asking until a page comes back empty`() = runBlocking {
        val small = FakeSyncClient(cap = 2)
        for (n in 1..5) small.seed(Tables.ITEMS, Rows.of(item("i$n", "λέξη $n", n.toLong())))
        val other = Phone(small).apply { configure() }

        val report = other.sync()

        assertEquals(5, report.pulled)
        assertEquals(5L, other.settings.syncCursor.first())
        assertTrue("pulled in ${small.pulls} pages", small.pulls >= 3)
    }

    /**
     * The cursor follows the rows received, never the number the server calls its own top: the
     * second page is refused here, and the cursor must sit at the end of the first one.
     */
    @Test fun `the cursor never jumps past a capped page`() = runBlocking {
        val small = FakeSyncClient(cap = 2)
        for (n in 1..5) small.seed(Tables.ITEMS, Rows.of(item("i$n", "λέξη $n", n.toLong())))
        var pulls = 0
        val flaky = object : SyncClient by small {
            override suspend fun pull(since: Long, limit: Int): PullPage {
                if (pulls++ == 1) throw SyncException(HttpSyncClient.OFFLINE)
                return small.pull(since, limit)
            }
        }
        val other = Phone(flaky).apply { configure() }

        val report = other.sync()

        assertEquals(2, report.pulled)
        assertEquals(2L, other.settings.syncCursor.first())
        assertTrue(report.errors.contains(HttpSyncClient.OFFLINE))
    }

    /**
     * The cursor is what makes a row un-askable. A page that could not be written must stay in
     * front of it — a phone whose storage filled up would otherwise lose those words for ever, and
     * freeing space afterwards would not bring them back.
     */
    @Test fun `a page that could not be written is asked for again next sync`() = runBlocking {
        client.seed(Tables.ITEMS, Rows.of(item("i1", "ψωμί", 10)))
        var full = true
        val other = Phone(wrap = { inner ->
            object : SyncStore by inner {
                override suspend fun apply(table: String, rows: List<Map<String, Any?>>): List<String> {
                    if (full) throw java.io.IOException("no space left on device")
                    return inner.apply(table, rows)
                }
            }
        }).apply { configure() }

        val failed = other.sync()

        assertEquals(0, failed.pulled)
        assertEquals(0L, other.settings.syncCursor.first())
        assertTrue(failed.errors.contains(SyncEngine.WRITE_FAILED))

        full = false
        val again = other.sync()

        assertEquals(1, again.pulled)
        assertEquals("ψωμί", other.items.get("i1")?.text)
        assertEquals(1L, other.settings.syncCursor.first())
    }

    /**
     * One row the database will not take must not hold the page — and the page holds the cursor, so
     * "will not take" would otherwise mean this phone never pulls anything again.
     */
    @Test fun `one row that will not write is skipped by name and the page still lands`() = runBlocking {
        for (n in 1..3) client.seed(Tables.ITEMS, Rows.of(item("i$n", "λέξη $n", n.toLong())))
        val other = Phone(wrap = { inner ->
            object : SyncStore by inner {
                override suspend fun apply(table: String, rows: List<Map<String, Any?>>): List<String> {
                    if (rows.any { it["id"] == "i2" }) throw IllegalStateException("constraint failed")
                    return inner.apply(table, rows)
                }
            }
        }).apply { configure() }

        val report = other.sync()

        assertEquals(2, report.pulled)
        assertEquals("λέξη 1", other.items.get("i1")?.text)
        assertEquals("λέξη 3", other.items.get("i3")?.text)
        assertNull(other.items.get("i2"))
        assertTrue(report.errors.toString(), report.errors.contains(SyncEngine.rowSkipped("items/i2")))
        // The cursor moved past the page, so the two good words are not asked for again.
        assertEquals(3L, other.settings.syncCursor.first())
    }

    /**
     * The README's own setup check produces this row. §4 tells the reader to `curl` a push of
     * `{"id":"i1","updatedAt":…,"deleted":false,"text":"ψωμί"}` — four of `items`' fifteen columns —
     * and the server takes it, because it validates `table`, `id` and `updatedAt` and nothing else.
     * The father does that against his fresh server to check it works and then types the address
     * into the phones; if that one row could hold the cursor, no phone would ever pull anything.
     */
    @Test fun `the README's own curl row is passed over and the cursor still moves`() = runBlocking {
        client.seed(Tables.ITEMS, mapOf(
            "id" to "i1", "updatedAt" to 1_757_000_000_000L, "deleted" to false, "text" to "ψωμί",
        ))

        val report = phone.sync()

        assertEquals(0, report.pulled)
        assertNull(phone.items.get("i1"))
        assertEquals(listOf(SyncEngine.rowSkipped("items/i1")), report.errors)
        assertEquals(1L, phone.settings.syncCursor.first())

        // And it stays moved on, which is the whole point.
        val again = phone.sync()
        assertTrue(again.errors.toString(), again.ok)
        assertEquals(1L, phone.settings.syncCursor.first())
    }

    /** The same for a row a later app version would write and this one cannot complete. */
    @Test fun `a row missing a column this version needs is passed over, not handed to Room`() {
        val spec = Tables.of(Tables.ITEMS)!!
        val whole = Rows.of(item("i1", "ψωμί", 10))

        assertTrue(spec.missing(whole).isEmpty())
        assertEquals(listOf("kind"), spec.missing(whole - "kind"))
        // Nullable columns are not required: a word with no photo is a whole row.
        assertTrue(spec.missing(whole - "imagePath" - "priceCents").isEmpty())
        assertTrue(spec.required.containsAll(listOf("id", "text", "kind", "category", "source", "updatedAt", "deleted")))
    }

    /**
     * A row the database refuses on its own is that row's problem, even when it is the only row of
     * its table in the page — and even when it is the only row in the page at all. Holding the
     * cursor for it would be the wedge all over again.
     */
    @Test fun `a lone row the database refuses is skipped and the cursor still moves`() = runBlocking {
        client.seed(Tables.ITEMS, Rows.of(item("i1", "ψωμί", 10)))
        val other = Phone(wrap = { inner ->
            object : SyncStore by inner {
                override suspend fun apply(table: String, rows: List<Map<String, Any?>>): List<String> =
                    throw IllegalStateException("UNIQUE constraint failed")
            }
        }).apply { configure() }

        val report = other.sync()

        assertEquals(0, report.pulled)
        assertEquals(listOf(SyncEngine.rowSkipped("items/i1")), report.errors)
        assertEquals(1L, other.settings.syncCursor.first())
    }

    /** A row this version cannot read at all is not a database failure and must not hold the page. */
    @Test fun `a row this app cannot map is skipped and the cursor moves on`() = runBlocking {
        client.seed(Tables.ITEMS, Rows.of(item("i1", "ψωμί", 10)))
        // A row hand-pushed with curl from the README's example: a word where a number belongs.
        client.seed(Tables.ITEMS, Rows.of(item("i2", "νερό", 11)) + ("createdAt" to "χθες"))

        val report = phone.sync()

        assertEquals(1, report.pulled)
        assertEquals("ψωμί", phone.items.get("i1")?.text)
        assertNull(phone.items.get("i2"))
        assertTrue(report.errors.toString(), report.errors.contains(SyncEngine.rowSkipped("items/i2")))
        assertEquals(2L, phone.settings.syncCursor.first())

        // And it stays moved on: the next sync is quiet rather than stuck on the same row.
        val again = phone.sync()
        assertEquals(0, again.pulled)
        assertTrue(again.errors.toString(), again.ok)
    }

    @Test fun `a cursor the server refuses goes back to zero and pulls again`() = runBlocking {
        phone.settings.setSyncCursor(9_999)
        client.seed(Tables.ITEMS, Rows.of(item("i1", "ψωμί", 10)))
        client.failPull = SyncException(HttpSyncClient.REFUSED, 400)

        val report = phone.sync()

        assertEquals(1, report.pulled)
        assertEquals("ψωμί", phone.items.get("i1")?.text)
        assertTrue(report.errors.contains(SyncEngine.CURSOR_RESET))
        assertEquals(1L, phone.settings.syncCursor.first())
    }

    // ---------------------------------------------------------------- media

    @Test fun `a photo goes up as its hash and comes down as a file on the other phone`() = runBlocking {
        val photo = phone.files.photo("x.jpg", "εικόνα".toByteArray())
        val sha = MediaRefs.sha256(photo)
        phone.items.upsert(item("i1", "ψωμί", 10, image = "photos/x.jpg"))

        val mine = phone.sync()

        assertEquals(1, mine.mediaUp)
        assertEquals("media://$sha", client.rowsOf(Tables.ITEMS).single()["imagePath"])

        val other = Phone().apply { configure() }
        val theirs = other.sync()

        assertEquals(1, theirs.mediaDown)
        assertEquals("ψωμί", other.items.get("i1")?.text)
        assertEquals("photos/$sha.jpg", other.items.get("i1")?.imagePath)
        assertEquals("εικόνα", File(other.files.photosDir, "$sha.jpg").readText())
    }

    @Test fun `a recording travels the same way and lands in the recordings folder`() = runBlocking {
        val voice = phone.files.recording("v.m4a", "φωνή".toByteArray())
        phone.recordings.upsert(
            Recording(id = "r1", itemId = "i1", path = "recordings/v.m4a", who = Who.CAREGIVER, durationMs = 900, updatedAt = 10)
        )
        val sha = MediaRefs.sha256(voice)

        phone.sync()
        val other = Phone().apply { configure() }
        val theirs = other.sync()

        assertEquals(1, theirs.mediaDown)
        assertEquals("recordings/$sha.m4a", other.recordings.rows["r1"]?.path)
        assertEquals(Who.CAREGIVER, other.recordings.rows["r1"]?.who)
    }

    /**
     * A deletion that arrives takes the file with it.
     *
     * Retention keeps only the newest three of *his* takes of each word — since «Μίλα» began keeping
     * the audio of every recognition window a practised word would gather a raw-PCM file a minute of
     * speech long every time — and the soft-deleted rows travel. Without this sweep only the phone
     * that pruned ever freed the disk: the caregiver's kept every file it had ever pulled.
     */
    @Test fun `a recording deleted on one phone loses its file on the other`() = runBlocking {
        // Real WAV bytes: the receiver names a downloaded recording by what is in it, so this also
        // walks the RIFF sniff end to end.
        val voice = phone.files.recording("v.wav", gr.dimitris.app.core.audio.Wav.header(0))
        val sha = MediaRefs.sha256(voice)
        phone.recordings.upsert(
            Recording(id = "r1", itemId = "i1", path = "recordings/v.wav", who = Who.DIMITRIS, durationMs = 900, updatedAt = 10)
        )
        phone.sync()
        val other = Phone().apply { configure() }
        other.sync()
        val landed = File(other.files.recordingsDir, "$sha.wav")
        assertTrue("the take arrived first", landed.isFile)

        // Pruned on his phone: the row is soft-deleted and stamped, and travels as a deletion.
        phone.recordings.softDelete("r1", 20)
        phone.sync()
        val swept = other.sync()

        assertEquals(true, other.recordings.rows["r1"]?.deleted)
        assertFalse("the file went with the row", landed.exists())
        assertTrue("and nothing was said about it", swept.errors.isEmpty())
    }

    /** Two rows can name one take — a dialogue line re-saved hands its file back in. */
    @Test fun `a file another live row still names is left alone`() = runBlocking {
        val voice = phone.files.recording("v.wav", gr.dimitris.app.core.audio.Wav.header(0))
        val sha = MediaRefs.sha256(voice)
        for (id in listOf("r1", "r2")) {
            phone.recordings.upsert(
                Recording(id = id, itemId = "i1", path = "recordings/v.wav", who = Who.DIMITRIS, durationMs = 900, updatedAt = 10)
            )
        }
        phone.sync()
        val other = Phone().apply { configure() }
        other.sync()
        val landed = File(other.files.recordingsDir, "$sha.wav")

        phone.recordings.softDelete("r1", 20)
        phone.sync()
        other.sync()

        assertEquals(true, other.recordings.rows["r1"]?.deleted)
        assertTrue("the surviving row would have played nothing", landed.isFile)
    }

    /**
     * Only what is under `recordings/`. A row whose path came from another phone's data directory,
     * or points anywhere else at all, is written and its file left exactly where it is: deleting a
     * file is the one thing here that cannot be taken back.
     */
    @Test fun `a deleted row outside the recordings folder takes nothing with it`() = runBlocking {
        val stray = File(phone.files.filesDir, "stray.wav").apply { writeBytes("φωνή".toByteArray()) }
        client.seed(
            Tables.RECORDINGS,
            Rows.of(
                Recording(
                    id = "r1", itemId = "i1", path = stray.absolutePath, who = Who.DIMITRIS,
                    durationMs = 900, updatedAt = 30, deleted = true,
                )
            ),
        )

        phone.sync()

        assertEquals(true, phone.recordings.rows["r1"]?.deleted)
        assertTrue("a path outside the media folders is not ours to delete", stray.isFile)
    }

    /** A deleted photograph keeps its file: a caregiver deleting a word is not pruning. */
    @Test fun `a deleted item does not sweep its photograph`() = runBlocking {
        val photo = phone.files.photo("p.jpg", "εικόνα".toByteArray())
        client.seed(Tables.ITEMS, Rows.of(item("i1", "ψωμί", 30, image = "photos/p.jpg").copy(deleted = true)))

        phone.sync()

        assertEquals("the word arrived deleted", true, phone.items.get("i1")?.deleted)
        assertTrue("but a caregiver deleting a word is not pruning his takes", photo.isFile)
    }

    /** A picture that will not download must never hold up the word it belongs to. */
    @Test fun `a failing download still lands the row`() = runBlocking {
        val sha = "0".repeat(64)
        client.seed(Tables.ITEMS, Rows.of(item("i1", "ψωμί", 10, image = "media://$sha")))

        val report = phone.sync()

        assertEquals("ψωμί", phone.items.get("i1")?.text)
        // Kept as it arrived, so the next sync asks for the file again.
        assertEquals("media://$sha", phone.items.get("i1")?.imagePath)
        assertTrue(report.errors.contains(SyncEngine.DOWNLOAD_FAILED))
    }

    /**
     * «Θα ξαναδοκιμάσω.» kept. The server has moved past the row — the cursor went with it — so
     * nobody but this phone will ever ask for the file again.
     */
    @Test fun `a photo that failed to download is fetched by the repair pass`() = runBlocking {
        val photo = phone.files.photo("x.jpg", "εικόνα".toByteArray())
        val sha = MediaRefs.sha256(photo)
        client.media[sha] = photo.readBytes()
        client.seed(Tables.ITEMS, Rows.of(item("i1", "ψωμί", 10, image = "media://$sha")))
        // One refusal, then the connection is back: the pull's fetch fails, the repair pass at the
        // end of the same run succeeds.
        client.failDownload = SyncException(HttpSyncClient.OFFLINE)

        val report = phone.sync()

        assertTrue(report.errors.contains(SyncEngine.DOWNLOAD_FAILED))
        assertEquals("photos/$sha.jpg", phone.items.get("i1")?.imagePath)
        assertEquals(1, report.mediaDown)
        assertEquals(1, phone.bumps)
    }

    /** The same for a voice, and across syncs when the connection stays down for the whole run. */
    @Test fun `a voice that failed to download is fetched by a later sync`() = runBlocking {
        val voice = phone.files.recording("v.m4a", "φωνή".toByteArray())
        val sha = MediaRefs.sha256(voice)
        client.seed(Tables.RECORDINGS, Rows.of(
            Recording(id = "r1", itemId = "i1", path = "media://$sha", who = Who.CAREGIVER, durationMs = 900, updatedAt = 10)
        ))

        val first = phone.sync()                       // the server has no such blob yet: 404
        assertEquals("media://$sha", phone.recordings.rows["r1"]?.path)
        assertTrue(first.errors.contains(SyncEngine.DOWNLOAD_FAILED))

        client.media[sha] = voice.readBytes()          // the other phone finished its upload
        val second = phone.sync()

        assertEquals("recordings/$sha.m4a", phone.recordings.rows["r1"]?.path)
        assertEquals(1, second.mediaDown)
        assertEquals(0, second.pulled)                 // nothing arrived; a row here was mended
        assertTrue(second.errors.toString(), second.ok)
    }

    /** Nothing to mend, nothing said: the repair pass must be silent on a healthy phone. */
    @Test fun `the repair pass does nothing when every file is here`() = runBlocking {
        phone.items.upsert(item("i1", "ψωμί", 10))
        phone.sync()
        val settled = phone.sync()
        assertEquals(0, settled.mediaDown)
        assertTrue(settled.errors.toString(), settled.ok)
    }

    /** A file whose upload failed holds the mark below its row, so the row is offered again. */
    @Test fun `a failing upload holds the push mark back`() = runBlocking {
        phone.files.photo("x.jpg", "εικόνα".toByteArray())
        phone.items.upsert(item("i1", "ψωμί", 50, image = "photos/x.jpg"))
        client.failUpload = SyncException(HttpSyncClient.OFFLINE)

        val report = phone.sync()

        assertEquals(0, report.pushed)
        assertEquals(0L, phone.settings.syncPushedUpTo.first())
        assertTrue(report.errors.contains(SyncEngine.UPLOAD_FAILED))
    }

    @Test fun `a file the server already has is not sent twice`() = runBlocking {
        val photo = phone.files.photo("x.jpg", "εικόνα".toByteArray())
        client.media[MediaRefs.sha256(photo)] = photo.readBytes()
        phone.items.upsert(item("i1", "ψωμί", 10, image = "photos/x.jpg"))

        val report = phone.sync()

        assertEquals(0, report.mediaUp)
        assertEquals(0, client.uploads)
        assertEquals(1, report.pushed)
    }

    /** A path whose file is gone travels as it is: the other phone shows a placeholder. */
    @Test fun `a missing photo does not stop the word`() = runBlocking {
        phone.items.upsert(item("i1", "ψωμί", 10, image = "photos/gone.jpg"))
        val report = phone.sync()
        assertEquals(1, report.pushed)
        assertEquals("photos/gone.jpg", client.rowsOf(Tables.ITEMS).single()["imagePath"])
        assertTrue(report.ok)
    }

    // ---------------------------------------------------------------- the rest

    @Test fun `a round trip changes nothing the second time`() = runBlocking {
        phone.items.upsert(item("i1", "ψωμί", 10))
        val first = phone.sync()
        val second = phone.sync()

        assertEquals(1, first.pushed)
        assertEquals(0, first.pulled)
        assertEquals(0, second.pushed)
        assertEquals(0, second.pulled)
        assertTrue(second.ok)
    }

    @Test fun `the last result and the time are kept for the screen`() = runBlocking {
        phone.items.upsert(item("i1", "ψωμί", 10))
        val engine = phone.engine()
        engine.syncNow().getOrThrow()

        assertEquals(AT, phone.settings.lastSyncAt.first())
        assertFalse(engine.state.value.running)
        assertTrue(engine.state.value.line, engine.state.value.line.contains("Έστειλα 1 γραμμή"))
        assertEquals(1, engine.state.value.report?.pushed)
    }

    @Test fun `nothing is recorded and nothing is claimed when there is nothing to do`() = runBlocking {
        val report = phone.sync()
        assertEquals(SyncReport(), report)
        assertTrue(phone.recorded.isEmpty())
        assertEquals(0, phone.bumps)
    }

    /**
     * A wrong token fails the upload of every photo on the phone. Two hundred identical rows would
     * bury the one thing a caregiver opened «Σφάλματα» to read.
     */
    @Test fun `the same failure is written down once, however many times it happens`() = runBlocking {
        for (n in 1..3) {
            phone.files.photo("p$n.jpg", "εικόνα $n".toByteArray())
            phone.items.upsert(item("i$n", "λέξη $n", n.toLong(), image = "photos/p$n.jpg"))
        }
        val always = object : SyncClient by client {
            override suspend fun hasMedia(sha: String) = throw SyncException(HttpSyncClient.BAD_TOKEN, 401)
        }
        val other = Phone(always).apply { configure() }
        for (n in 1..3) {
            other.files.photo("p$n.jpg", "εικόνα $n".toByteArray())
            other.items.upsert(item("i$n", "λέξη $n", n.toLong(), image = "photos/p$n.jpg"))
        }

        val report = other.sync()

        assertEquals(listOf(SyncEngine.UPLOAD_FAILED), report.errors)
        assertEquals(listOf("sync media up"), other.recorded.map { it.first })
    }

    @Test fun `a recorded failure never carries the request`() = runBlocking {
        phone.items.upsert(item("i1", "ψωμί", 10))
        client.failPush = SyncException(HttpSyncClient.BAD_TOKEN, 401)

        phone.sync()

        val logged = phone.recorded.single().second
        assertTrue(logged is SyncException)
        assertNull("the cause would drag the request into error_logs", logged.cause)
        assertEquals(HttpSyncClient.BAD_TOKEN, logged.message)
    }

    @Test fun `the Greek line counts one and many differently`() {
        val one = SyncEngine.line(SyncReport(pushed = 1, pulled = 1), AT)
        val many = SyncEngine.line(SyncReport(pushed = 2, pulled = 0, mediaUp = 1, mediaDown = 3, errors = listOf("α", "β")), AT)
        assertTrue(one, one.contains("Έστειλα 1 γραμμή, πήρα 1 γραμμή."))
        assertTrue(many, many.contains("Έστειλα 2 γραμμές, πήρα 0 γραμμές."))
        assertTrue(many, many.contains("Αρχεία: 1 πάνω, 3 κάτω."))
        assertTrue(many, many.contains("2 προβλήματα."))
    }

    private companion object {
        const val AT = 1_757_000_000_000L
    }
}
