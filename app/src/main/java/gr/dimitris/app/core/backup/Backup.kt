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

class BackupException(message: String) : Exception(message)

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
        graph.app.contentResolver.openInputStream(uri)?.use { Zips.unzip(it, tmp) } ?: throw BackupException("Δεν άνοιξε το αρχείο")
        val newDb = File(tmp, AppDatabase.NAME)
        if (!newDb.isFile) throw BackupException("Το αρχείο δεν είναι αντίγραφο της εφαρμογής")

        val dbFile = graph.app.getDatabasePath(AppDatabase.NAME)
        val staged = File(dbFile.path + ".new")
        newDb.copyTo(staged, overwrite = true)              // still safe: live db untouched

        val backup = File(dbFile.path + ".bak")
        graph.db.close()
        try {
            listOf(File(dbFile.path + "-wal"), File(dbFile.path + "-shm")).forEach { it.delete() }
            if (dbFile.exists() && !dbFile.renameTo(backup)) throw BackupException("Δεν μπόρεσα να φυλάξω την παλιά βάση")
            if (!staged.renameTo(dbFile)) { backup.renameTo(dbFile); throw BackupException("Δεν μπόρεσα να τοποθετήσω τη νέα βάση") }
            replaceDir(File(tmp, "photos"), graph.files.photosDir)
            replaceDir(File(tmp, "recordings"), graph.files.recordingsDir)
            backup.delete()
        } finally {
            graph.reopenDatabase()   // whatever happened, the app has a database again
            tmp.deleteRecursively()
        }
    }

    /** Old content is kept until the new content is fully in place. */
    private fun replaceDir(from: File, to: File) {
        val old = File(to.path + ".old").apply { deleteRecursively() }
        if (to.exists() && !to.renameTo(old)) throw BackupException("Δεν μπόρεσα να αντικαταστήσω τα αρχεία")
        to.mkdirs()
        if (from.isDirectory) {
            runCatching { from.copyRecursively(to, overwrite = true) }.onFailure {
                to.deleteRecursively(); old.renameTo(to)
                throw BackupException("Δεν μπόρεσα να αντιγράψω τα αρχεία")
            }
        }
        old.deleteRecursively()
    }
}
