package gr.dimitris.app.core.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import gr.dimitris.app.core.data.AppDatabase
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Recording
import gr.dimitris.app.core.data.Schedule
import gr.dimitris.app.core.data.Who
import gr.dimitris.app.core.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * The real thing: two databases, two media folders, and the Node server from `server/` running on
 * the development machine. The emulator reaches it at `10.0.2.2`.
 *
 * Start it before running this, and **give it an empty `DATA_DIR` every time**:
 *
 * ```
 * SYNC_TOKEN=test PORT=18787 DATA_DIR=$(mktemp -d) node server/server.mjs
 * ```
 *
 * The empty directory is a requirement, not a flourish. Nothing here can clean the server up
 * afterwards — the protocol has no delete — so a directory reused from an earlier run carries that
 * run's rows and blobs into this one. The tests themselves are order-independent (fresh UUIDs, an
 * in-memory database and its own media folder per phone) and they assert on their own rows rather
 * than on what a pull as a whole did, so leftovers cannot fail them; but a shared directory grows
 * for ever and makes a failure much harder to read.
 *
 * The address and the token can be overridden with instrumentation arguments `syncBase` and
 * `syncToken`. Without a server the tests are skipped rather than failed — the rest of the suite
 * has to be runnable on a machine that is not hosting one.
 */
class SyncRoundTripTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val base: String = InstrumentationRegistry.getArguments().getString("syncBase") ?: "http://10.0.2.2:18787"
    private val token: String = InstrumentationRegistry.getArguments().getString("syncToken") ?: "test"

    /** One device: its own database, its own photo and recording folders, its own cursors. */
    private inner class Phone(private val secret: String = token) {
        val db: AppDatabase = AppDatabase.inMemory(context)
        val files = TempMediaPaths(File(context.cacheDir, "sync-${UUID.randomUUID()}"))

        private val storeFile = File(context.cacheDir, "sync-${UUID.randomUUID()}.preferences_pb")

        /** Kept, so a test can write a cursor the settings themselves would refuse to store. */
        val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { storeFile },
        )
        val settings = Settings(store)
        val recorded = mutableListOf<Pair<String, Throwable>>()
        val client = HttpSyncClient(baseUrl = { base }, token = { secret })
        val engine = SyncEngine(
            client = client,
            store = DaoSyncStore { SyncDaos.of(db) },
            files = files,
            settings = settings,
            record = { where, e -> recorded += where to e },
        )

        init {
            runBlocking { settings.setSyncUrl(base) }
        }

        fun close() {
            db.close()
            files.root.deleteRecursively()
            // The DataStore file too: one per phone per test, in the app's cache dir, and the
            // instrumented app is not reinstalled between test classes.
            storeFile.delete()
        }
    }

    private val phones = mutableListOf<Phone>()
    private fun phone(secret: String = token) = Phone(secret).also { phones += it }

    @Before fun serverIsUp() {
        val up = runCatching { runBlocking { HttpSyncClient({ base }, { token }).health() } }
        assumeTrue("no sync server at $base — start server/server.mjs to run this", up.isSuccess)
    }

    @After fun closeAll() {
        phones.forEach { it.close() }
        phones.clear()
    }

    /** `seq` is the count of rows the server has accepted, so a push has to move it by exactly one. */
    @Test fun healthCountsTheRowsTheServerHasTaken() = runTest {
        val client = HttpSyncClient({ base }, { token })
        val before = client.health()

        val id = UUID.randomUUID().toString()
        val accepted = client.push(listOf(SyncRow(Tables.ITEMS, Rows.of(Item(id = id, text = "υγεία-$id")))))

        assertEquals(1, accepted.accepted)
        assertEquals(before + 1, client.health())
        assertEquals(before + 1, accepted.seq)
    }

    /**
     * The whole feature in one test: a word with a photo and a voice, added on one phone, arriving
     * on another with both files playable — which is what the caregivers actually asked for.
     */
    @Test fun aWordItsPhotoAndItsVoiceCrossOver() = runTest {
        val here = phone()
        val id = UUID.randomUUID().toString()
        val photoBytes = "εικόνα-${UUID.randomUUID()}".toByteArray()
        val voiceBytes = "φωνή-${UUID.randomUUID()}".toByteArray()
        val photo = File(here.files.photosDir, "$id.jpg").apply { writeBytes(photoBytes) }
        val voice = File(here.files.recordingsDir, "$id.m4a").apply { writeBytes(voiceBytes) }
        val recordingId = UUID.randomUUID().toString()

        here.db.items().upsert(Item(id = id, text = "ψωμί-$id", imagePath = here.files.relativize(photo)))
        here.db.recordings().upsert(
            Recording(id = recordingId, itemId = id, path = here.files.relativize(voice), who = Who.CAREGIVER, durationMs = 900)
        )
        here.db.schedules().upsert(Schedule(itemId = id, module = ModuleId.WORDCOACH, nextDueAt = 5))

        val sent = here.engine.syncNow().getOrThrow()
        assertTrue(sent.errors.toString(), sent.ok)
        assertTrue("pushed ${sent.pushed}", sent.pushed >= 3)
        assertEquals(2, sent.mediaUp)

        val there = phone()
        // Deliberately not asserting `got.ok`: this pull takes in everything the server holds,
        // which on a shared DATA_DIR includes other runs' rows. What this test is about is the
        // three rows and two files below.
        there.engine.syncNow().getOrThrow()

        val arrived = there.db.items().get(id)
        assertNotNull("the word did not arrive", arrived)
        assertEquals("ψωμί-$id", arrived!!.text)

        val sha = MediaRefs.sha256(photo)
        assertEquals("photos/$sha.jpg", arrived.imagePath)
        assertTrue(photoBytes.contentEquals(there.files.resolve(arrived.imagePath!!).readBytes()))

        val voiceRow = there.db.recordings().get(recordingId)
        assertNotNull("the recording did not arrive", voiceRow)
        assertEquals("recordings/${MediaRefs.sha256(voice)}.m4a", voiceRow!!.path)
        assertTrue(voiceBytes.contentEquals(there.files.resolve(voiceRow.path).readBytes()))

        assertNotNull("the schedule did not arrive", there.db.schedules().get(id, ModuleId.WORDCOACH))
    }

    /** The one thing a caregiver will get wrong: a mistyped token, and a Greek sentence about it. */
    @Test fun aWrongTokenIsOneGreekSentenceAndNothingElse() = runTest {
        val wrong = phone(secret = "not-the-token-$token")
        wrong.db.items().upsert(Item(text = "ψωμί"))

        val report = wrong.engine.syncNow().getOrThrow()

        assertTrue(report.errors.toString(), report.errors.contains(HttpSyncClient.BAD_TOKEN))
        assertEquals(0, report.pushed)
        assertEquals(0L, wrong.settings.syncCursor.first())
        // Everything written down is a SyncException with no cause: the token travelled in those
        // requests' headers and must not reach error_logs.
        assertTrue(wrong.recorded.isNotEmpty())
        val secret = "not-the-token-$token"
        wrong.recorded.forEach { (_, e) ->
            assertTrue("$e", e is SyncException)
            assertEquals(null, e.cause)
            assertTrue("the token must never be written down", !e.stackTraceToString().contains(secret))
        }
    }

    /** A cursor past the end of the log: the server refuses it, and the phone starts again. */
    @Test fun aCursorTheServerRefusesIsResetAndTheSyncStillWorks() = runTest {
        val here = phone()
        val id = UUID.randomUUID().toString()
        here.db.items().upsert(Item(id = id, text = "νερό-$id"))
        here.engine.syncNow().getOrThrow()

        val there = phone()
        // Written past the settings, which clamp: a preference file that a restore or a bad write
        // left holding a negative cursor is exactly what the server answers 400 to.
        there.store.edit { it[longPreferencesKey("sync_cursor")] = -1L }
        val report = there.engine.syncNow().getOrThrow()

        assertTrue(report.errors.toString(), report.errors.contains(SyncEngine.CURSOR_RESET))
        assertNotNull("the word did not arrive after the reset", there.db.items().get(id))
        assertTrue("the cursor stayed broken", there.settings.syncCursor.first() > 0)
    }

    /**
     * It settles. The first sync sends this phone's word and takes everything the server had; the
     * second offers those rows back once (the mark is one number, and they landed above it) and the
     * server ignores every one as a tie; the third has nothing left to say.
     */
    @Test fun aRoundTripSettlesAndStaysSettled() = runTest {
        val here = phone()
        here.db.items().upsert(Item(text = "καφές-${UUID.randomUUID()}"))
        here.engine.syncNow().getOrThrow()
        here.engine.syncNow().getOrThrow()

        val settled = here.engine.syncNow().getOrThrow()

        assertEquals(0, settled.pushed)
        assertEquals(0, settled.pulled)
        assertEquals(0, settled.mediaUp)
        assertEquals(0, settled.mediaDown)
        assertTrue(settled.errors.toString(), settled.ok)
    }
}

/** [MediaPaths] over a folder of its own, so two phones in one process never share a photo. */
class TempMediaPaths(val root: File) : MediaPaths {
    override val photosDir: File = File(root, "photos").apply { mkdirs() }
    override val recordingsDir: File = File(root, "recordings").apply { mkdirs() }

    override fun relativize(file: File): String {
        val base = root.canonicalPath
        val path = file.canonicalPath
        return if (path.startsWith(base + File.separator)) path.substring(base.length + 1) else path
    }

    override fun resolve(path: String): File = if (path.startsWith("/")) File(path) else File(root, path)
}
