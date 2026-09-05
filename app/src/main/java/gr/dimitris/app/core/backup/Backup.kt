package gr.dimitris.app.core.backup

import android.net.Uri
import gr.dimitris.app.AppGraph
import gr.dimitris.app.core.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One zip: the database plus photos and recordings. The phase-0 way to move Dimitris' data and back it up. */
class Backup(private val graph: AppGraph) {

    suspend fun export(): File = withContext(Dispatchers.IO) {
        // Fold the write-ahead log into the main file so the copy is complete.
        graph.db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        val dbFile = graph.app.getDatabasePath(AppDatabase.NAME)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val out = File(graph.files.exportDir, "dimitris-$stamp.zip")
        Zips.zip(out, listOf(
            Zips.Entry(AppDatabase.NAME, dbFile),
            Zips.Entry("photos", graph.files.photosDir),
            Zips.Entry("recordings", graph.files.recordingsDir),
        ))
        out
    }

    suspend fun import(uri: Uri) = withContext(Dispatchers.IO) {
        val tmp = File(graph.app.cacheDir, "import").apply { deleteRecursively(); mkdirs() }
        graph.app.contentResolver.openInputStream(uri)?.use { Zips.unzip(it, tmp) } ?: error("Δεν άνοιξε το αρχείο")
        val newDb = File(tmp, AppDatabase.NAME)
        require(newDb.isFile) { "Το αρχείο δεν είναι αντίγραφο της εφαρμογής" }

        graph.db.close()
        val dbFile = graph.app.getDatabasePath(AppDatabase.NAME)
        listOf(dbFile, File(dbFile.path + "-wal"), File(dbFile.path + "-shm")).forEach { it.delete() }
        newDb.copyTo(dbFile, overwrite = true)
        replaceDir(File(tmp, "photos"), graph.files.photosDir)
        replaceDir(File(tmp, "recordings"), graph.files.recordingsDir)
        tmp.deleteRecursively()
        graph.reopenDatabase()
    }

    private fun replaceDir(from: File, to: File) {
        to.deleteRecursively(); to.mkdirs()
        if (from.isDirectory) from.copyRecursively(to, overwrite = true)
    }
}
