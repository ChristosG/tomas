package gr.dimitris.app.core.audio

import android.content.Context
import gr.dimitris.app.core.sync.MediaPaths
import java.io.File
import java.util.UUID

/** Where photos and recordings live: app-private, included in backups, never on shared storage. */
class MediaFiles(context: Context) : MediaPaths {
    val filesDir: File = context.filesDir
    override val photosDir: File = File(filesDir, "photos").apply { mkdirs() }
    override val recordingsDir: File = File(filesDir, "recordings").apply { mkdirs() }
    val exportDir: File = File(context.cacheDir, "export").apply { mkdirs() }

    fun newPhotoFile(): File = File(photosDir, "${UUID.randomUUID()}.jpg")
    fun newRecordingFile(): File = File(recordingsDir, "${UUID.randomUUID()}.m4a")

    /**
     * The path to store in the database: relative to the files dir ("photos/abc.jpg"), so a backup
     * restored on another phone — where the data dir has a different absolute path — still finds its
     * media. Anything outside the files dir keeps its absolute path.
     */
    override fun relativize(file: File): String {
        under(filesDir.absolutePath, file.absolutePath)?.let { return it }
        val root = runCatching { filesDir.canonicalPath }.getOrNull()
        val path = runCatching { file.canonicalPath }.getOrNull()
        if (root != null && path != null) under(root, path)?.let { return it }
        return file.absolutePath
    }

    /** The file a stored path points at. Absolute paths (rows written before this change) pass through. */
    override fun resolve(path: String): File = if (path.startsWith("/")) File(path) else File(filesDir, path)

    private fun under(root: String, path: String): String? =
        if (path.startsWith(root + File.separator)) path.substring(root.length + 1).replace(File.separatorChar, '/') else null
}
