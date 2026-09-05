package gr.dimitris.app.core.audio

import android.content.Context
import java.io.File
import java.util.UUID

/** Where photos and recordings live: app-private, included in backups, never on shared storage. */
class MediaFiles(context: Context) {
    val photosDir: File = File(context.filesDir, "photos").apply { mkdirs() }
    val recordingsDir: File = File(context.filesDir, "recordings").apply { mkdirs() }
    val exportDir: File = File(context.cacheDir, "export").apply { mkdirs() }

    fun newPhotoFile(): File = File(photosDir, "${UUID.randomUUID()}.jpg")
    fun newRecordingFile(): File = File(recordingsDir, "${UUID.randomUUID()}.m4a")
}
