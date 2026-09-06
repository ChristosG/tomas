package gr.dimitris.app.core.sync

import java.io.File
import kotlin.io.path.createTempDirectory

/** [MediaPaths] over two temp folders: the same convention as MediaFiles, without an Android context. */
class FakeMediaPaths(val filesDir: File = createTempDirectory("media").toFile()) : MediaPaths {
    override val photosDir: File = File(filesDir, "photos").apply { mkdirs() }
    override val recordingsDir: File = File(filesDir, "recordings").apply { mkdirs() }

    override fun relativize(file: File): String {
        val root = filesDir.canonicalPath
        val path = file.canonicalPath
        return if (path.startsWith(root + File.separator)) path.substring(root.length + 1) else path
    }

    override fun resolve(path: String): File = if (path.startsWith("/")) File(path) else File(filesDir, path)

    fun photo(name: String, bytes: ByteArray): File = File(photosDir, name).apply { writeBytes(bytes) }
    fun recording(name: String, bytes: ByteArray): File = File(recordingsDir, name).apply { writeBytes(bytes) }
}
