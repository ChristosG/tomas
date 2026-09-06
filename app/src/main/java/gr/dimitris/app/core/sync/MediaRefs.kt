package gr.dimitris.app.core.sync

import java.io.File
import java.security.MessageDigest

/**
 * The two folders a synced file may come from or go to, and the app's path convention.
 * [gr.dimitris.app.core.audio.MediaFiles] is the real one; a JVM test hands in temp folders.
 */
interface MediaPaths {
    val photosDir: File
    val recordingsDir: File

    /** The path to store in the database: relative to the files dir, e.g. `photos/abc.jpg`. */
    fun relativize(file: File): String

    /** The file a stored path points at. */
    fun resolve(path: String): File
}

/**
 * Photos and voices on the wire.
 *
 * A path means nothing on another phone, so a row that carries one travels with `media://<sha256>`
 * instead and the bytes go up separately, named by their own hash. Coming back the other way the
 * hash is downloaded into the right folder and the row is rewritten to point at it.
 *
 * Two paths are deliberately left alone:
 *
 * * a value that is not a file this phone actually has under `photos/` or `recordings/` — a path
 *   from an older row, a file the caregiver deleted — travels as it is. The other phone shows a
 *   placeholder, which is what it already does for a missing picture (spec §8);
 * * a `media://` value whose bytes could not be fetched stays a `media://` value, so the row still
 *   lands and the next sync tries the file again. A missing photo never blocks a word.
 */
object MediaRefs {
    const val SCHEME = "media://"

    /** `media://<sha>` → `<sha>`, anything else → null. */
    fun shaOf(value: Any?): String? {
        val text = value as? String ?: return null
        if (!text.startsWith(SCHEME)) return null
        return text.removePrefix(SCHEME).takeIf { it.length == SHA_HEX && it.all(::isHex) }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * The row as it should be pushed, plus the files that have to be uploaded before it is. Only a
     * file that really exists under [MediaPaths.photosDir] or [MediaPaths.recordingsDir] is
     * content-addressed; everything else — a null, a path outside those folders, a file that is
     * gone, a value that is already `media://` — is handed back untouched.
     */
    fun outgoing(table: String, row: Map<String, Any?>, files: MediaPaths): Pair<Map<String, Any?>, List<File>> {
        val fields = Tables.of(table)?.mediaFields.orEmpty()
        if (fields.isEmpty()) return row to emptyList()
        var out = row
        val uploads = mutableListOf<File>()
        for (field in fields.keys) {
            val path = out[field] as? String ?: continue
            if (path.startsWith(SCHEME)) continue
            val file = runCatching { files.resolve(path) }.getOrNull() ?: continue
            if (!file.isFile || !isSynced(file, files)) continue
            val sha = runCatching { sha256(file) }.getOrNull() ?: continue
            out = out + (field to SCHEME + sha)
            uploads += file
        }
        return out to uploads
    }

    /**
     * The row as it should be stored here. [resolve] is given the hash and the extension its bytes
     * get locally and answers with the file it downloaded, or null when it could not — in which
     * case the `media://` value stays put and the next sync asks again.
     */
    fun incoming(
        table: String,
        row: Map<String, Any?>,
        files: MediaPaths,
        resolve: (String, String) -> File?,
    ): Map<String, Any?> {
        val fields = Tables.of(table)?.mediaFields.orEmpty()
        if (fields.isEmpty()) return row
        var out = row
        for ((field, extension) in fields) {
            val sha = shaOf(out[field]) ?: continue
            val file = resolve(sha, extension) ?: continue
            // The same relative form the rest of the app stores, so a restored backup on another
            // phone — where the data dir has a different absolute path — still finds the file.
            out = out + (field to files.relativize(file))
        }
        return out
    }

    /**
     * The hashes a row needs before it can be stored, each with the extension its bytes get here.
     * Asked first so the fetching — which is a network call — happens outside [incoming], and
     * [incoming] itself stays a plain function anyone can test.
     */
    fun needed(table: String, row: Map<String, Any?>): List<Pair<String, String>> =
        Tables.of(table)?.mediaFields.orEmpty().mapNotNull { (field, extension) ->
            shaOf(row[field])?.let { it to extension }
        }

    /** Where a downloaded file belongs: pictures with the pictures, voices with the voices. */
    fun folderFor(extension: String, files: MediaPaths): File =
        if (extension == Tables.RECORDING_EXT) files.recordingsDir else files.photosDir

    private fun isSynced(file: File, files: MediaPaths): Boolean =
        under(file, files.photosDir) || under(file, files.recordingsDir)

    private fun under(file: File, dir: File): Boolean {
        val root = runCatching { dir.canonicalPath }.getOrNull() ?: dir.absolutePath
        val path = runCatching { file.canonicalPath }.getOrNull() ?: file.absolutePath
        return path.startsWith(root + File.separator)
    }

    private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f'

    private const val SHA_HEX = 64
    private const val BUFFER = 64 * 1024
}
