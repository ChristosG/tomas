package gr.dimitris.app.core.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import gr.dimitris.app.core.data.Attempt
import gr.dimitris.app.core.data.FakeAttemptDao
import gr.dimitris.app.core.data.FakeItemDao
import gr.dimitris.app.core.data.FakeRecordingDao
import gr.dimitris.app.core.data.FakeScheduleDao
import gr.dimitris.app.core.data.FakeScriptDao
import gr.dimitris.app.core.data.FakeSessionDao
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Outcome
import gr.dimitris.app.core.data.Recording
import gr.dimitris.app.core.data.Schedule
import gr.dimitris.app.core.data.Who
import gr.dimitris.app.core.log.FakeErrorLogDao
import gr.dimitris.app.core.settings.Settings
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

    private inner class Phone(private val server: SyncClient = client) {
        val items = FakeItemDao()
        val recordings = FakeRecordingDao()
        val attempts = FakeAttemptDao()
        val schedules = FakeScheduleDao()
        val sessions = FakeSessionDao()
        val errorLogs = FakeErrorLogDao()
        val scripts = FakeScriptDao()
        val files = FakeMediaPaths()
        val settings = newSettings()
        val recorded = mutableListOf<Pair<String, Throwable>>()
        var bumps = 0

        private val store = DaoSyncStore {
            SyncDaos(items, recordings, attempts, schedules, sessions, errorLogs, scripts)
        }

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

    /** One row the server will not take at any size is skipped — and the mark stays below it. */
    @Test fun `a single row that is too big is skipped and reported`() = runBlocking {
        client.maxRowsPerPush = 0
        phone.items.upsert(item("i1", "ψωμί", 50))

        val report = phone.sync()

        assertEquals(0, report.pushed)
        assertTrue(report.errors.contains(SyncEngine.ROW_TOO_BIG))
        assertEquals(49L, phone.settings.syncPushedUpTo.first())
    }

    /** The mark stops below the row that did not go, so the next sync offers it again. */
    @Test fun `a push that fails holds the mark below the row it lost`() = runBlocking {
        phone.items.upsert(item("i1", "ψωμί", 50))
        client.failPush = SyncException(HttpSyncClient.OFFLINE)

        val failed = phone.sync()

        assertEquals(0, failed.pushed)
        assertEquals(49L, phone.settings.syncPushedUpTo.first())
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

    /** A file whose upload failed holds the mark below its row, so the row is offered again. */
    @Test fun `a failing upload holds the push mark back`() = runBlocking {
        phone.files.photo("x.jpg", "εικόνα".toByteArray())
        phone.items.upsert(item("i1", "ψωμί", 50, image = "photos/x.jpg"))
        client.failUpload = SyncException(HttpSyncClient.OFFLINE)

        val report = phone.sync()

        assertEquals(0, report.pushed)
        assertEquals(49L, phone.settings.syncPushedUpTo.first())
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
