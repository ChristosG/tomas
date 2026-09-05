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

    /**
     * Replaces everything, or as close to nothing as the filesystem allows. The database is staged
     * beside the live one and swapped by rename; both media folders are copied out in full before
     * either live folder is touched, and a failure puts the old folders back. Whatever happens, the
     * app has a working database again and no leftovers are left behind.
     */
    suspend fun import(uri: Uri) = withContext(Dispatchers.IO) {
        val tmp = File(graph.app.cacheDir, "import").apply { deleteRecursively(); mkdirs() }
        val dbFile = graph.app.getDatabasePath(AppDatabase.NAME)
        val staged = File(dbFile.path + ".new")
        val backup = File(dbFile.path + ".bak")
        val photosNew = File(graph.files.photosDir.path + ".new")
        val recordingsNew = File(graph.files.recordingsDir.path + ".new")
        try {
            graph.app.contentResolver.openInputStream(uri)?.use { Zips.unzip(it, tmp) }
                ?: throw BackupException("Δεν άνοιξε το αρχείο")
            val newDb = File(tmp, AppDatabase.NAME)
            if (!newDb.isFile) throw BackupException("Το αρχείο δεν είναι αντίγραφο της εφαρμογής")
            newDb.copyTo(staged, overwrite = true)              // still safe: live db untouched

            graph.db.close()
            listOf(File(dbFile.path + "-wal"), File(dbFile.path + "-shm")).forEach { it.delete() }
            if (dbFile.exists() && !dbFile.renameTo(backup)) throw BackupException("Δεν μπόρεσα να φυλάξω την παλιά βάση")
            if (!staged.renameTo(dbFile)) { backup.renameTo(dbFile); throw BackupException("Δεν μπόρεσα να τοποθετήσω τη νέα βάση") }

            // Both copies first: a half-copied import must not eat the photos already on the phone.
            copyInto(File(tmp, "photos"), photosNew)
            copyInto(File(tmp, "recordings"), recordingsNew)
            swapIn(listOf(photosNew to graph.files.photosDir, recordingsNew to graph.files.recordingsDir))
        } finally {
            graph.reopenDatabase()   // whatever happened, the app has a database again
            listOf(
                backup, staged, tmp, photosNew, recordingsNew,
                File(graph.files.photosDir.path + ".old"), File(graph.files.recordingsDir.path + ".old"),
            ).forEach { it.deleteRecursively() }
        }
    }

    /** A full copy of [from] (which may be absent, meaning "no media") into a fresh [to]. */
    private fun copyInto(from: File, to: File) {
        to.deleteRecursively()
        if (!to.mkdirs()) throw BackupException("Δεν μπόρεσα να αντιγράψω τα αρχεία")
        if (!from.isDirectory) return
        runCatching { from.copyRecursively(to, overwrite = true) }.onFailure {
            to.deleteRecursively()
            throw BackupException("Δεν μπόρεσα να αντιγράψω τα αρχεία")
        }
    }

    /** Renames the live folders aside and the staged ones in. Any failure puts every live folder back. */
    private fun swapIn(pairs: List<Pair<File, File>>) {
        val moved = mutableListOf<Pair<File, File>>()   // (parked old folder, live path)
        try {
            for ((staged, live) in pairs) {
                val old = File(live.path + ".old").apply { deleteRecursively() }
                if (live.exists() && !live.renameTo(old)) throw BackupException("Δεν μπόρεσα να αντικαταστήσω τα αρχεία")
                moved += old to live
                if (!staged.renameTo(live)) throw BackupException("Δεν μπόρεσα να αντικαταστήσω τα αρχεία")
            }
        } catch (e: Throwable) {
            moved.forEach { (old, live) -> live.deleteRecursively(); old.renameTo(live) }
            throw e
        }
    }
}
