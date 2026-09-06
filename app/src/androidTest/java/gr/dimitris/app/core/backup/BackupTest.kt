package gr.dimitris.app.core.backup

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import gr.dimitris.app.DimitrisApp
import gr.dimitris.app.core.data.AppDatabase
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.secrets.SecretStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/** Uses the app's own graph, because import swaps the database the whole app is holding. */
class BackupTest {
    private val app = ApplicationProvider.getApplicationContext<DimitrisApp>()
    private val graph get() = app.graph
    private val backup get() = Backup(graph)

    /** The bundled seed vocabulary loads on first launch; let it settle so counts do not move mid-test. */
    @Before fun waitForSeedImport() {
        var last = -1
        var stable = 0
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline && stable < 3) {
            val count = runBlocking { graph.db.items().countActive() }
            stable = if (count == last) stable + 1 else 0
            last = count
            Thread.sleep(300)
        }
    }

    @Test fun aZipWithoutTheDatabaseIsRefusedAndTheAppKeepsWorking() = runTest {
        val note = File(app.cacheDir, "note.txt").apply { writeText("δεν είμαι αντίγραφο") }
        val junk = File(app.cacheDir, "junk.zip")
        Zips.zip(junk, listOf(Zips.Entry("note.txt", note)))

        val thrown = runCatching { backup.import(Uri.fromFile(junk)) }.exceptionOrNull()

        assertTrue("expected a BackupException, got $thrown", thrown is BackupException)
        assertTrue("the database must still answer after a refused import", graph.db.items().countActive() >= 0)
    }

    /**
     * The backup zip is handed to another machine — the father's server, a laptop, whatever app the
     * caregiver picks — so the one secret on the phone must not be inside it. It lives in
     * `shared_prefs`, which [Backup] never packs; this is the test that says so out loud.
     */
    @Test fun theBackupCarriesNoSecret() = runTest {
        val secrets = SecretStore(app)
        val before = secrets.getClaudeKey()
        try {
            secrets.setClaudeKey("sk-ant-backup-needle-0123456789")

            val zip = backup.export()

            // By entry name, not by scanning the bytes: the zip is DEFLATE'd (Zips.kt), so a key
            // inside a packed file would not appear as plaintext and a byte scan could never fail.
            // What the zip contains is exactly its list of entries, so that is what is asserted.
            ZipFile(zip).use { z ->
                val names = z.entries().toList().map { it.name }
                assertFalse("$names", names.any { it.contains(SecretStore.FILE) || it.contains("shared_prefs") })
                assertTrue(
                    "the zip should hold the database and the two media folders only: $names",
                    names.all { it == AppDatabase.NAME || it.startsWith("photos/") || it.startsWith("recordings/") },
                )
            }
        } finally {
            secrets.setClaudeKey(before)
        }
    }

    @Test fun exportThenImportKeepsTheItems() = runTest {
        graph.items.save(Item(text = "καφές δοκιμής"))
        val before = graph.db.items().countActive()

        val zip = backup.export()
        backup.import(Uri.fromFile(zip))

        assertEquals(before, graph.db.items().countActive())
        assertTrue(graph.files.photosDir.isDirectory)
        assertTrue(graph.files.recordingsDir.isDirectory)
        listOf(
            File(graph.files.photosDir.path + ".new"), File(graph.files.photosDir.path + ".old"),
            File(graph.files.recordingsDir.path + ".new"), File(graph.files.recordingsDir.path + ".old"),
            File(app.getDatabasePath(AppDatabase.NAME).path + ".bak"),
            File(app.getDatabasePath(AppDatabase.NAME).path + ".new"),
            File(app.cacheDir, "import"),
        ).forEach { assertFalse("${it.name} was left behind", it.exists()) }
    }
}
