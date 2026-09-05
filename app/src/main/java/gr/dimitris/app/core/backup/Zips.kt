package gr.dimitris.app.core.backup

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object Zips {
    /** A file, or a directory whose contents go under [name]/. */
    data class Entry(val name: String, val file: File)

    fun zip(out: File, entries: List<Entry>) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { z ->
            entries.forEach { add(z, it.name, it.file) }
        }
    }

    private fun add(z: ZipOutputStream, name: String, file: File) {
        when {
            file.isDirectory -> file.listFiles()?.sortedBy { it.name }?.forEach { add(z, "$name/${it.name}", it) }
            file.isFile -> {
                z.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
    }

    /** Extracts into [dir]. Refuses entries that would land outside it. */
    fun unzip(input: InputStream, dir: File) {
        val root = dir.canonicalFile
        ZipInputStream(BufferedInputStream(input)).use { z ->
            generateSequence { z.nextEntry }.forEach { entry ->
                val target = File(root, entry.name).canonicalFile
                require(target.path == root.path || target.path.startsWith(root.path + File.separator)) { "Μη έγκυρο αρχείο: ${entry.name}" }
                if (entry.isDirectory) target.mkdirs()
                else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { z.copyTo(it) }
                }
                z.closeEntry()
            }
        }
    }
}
