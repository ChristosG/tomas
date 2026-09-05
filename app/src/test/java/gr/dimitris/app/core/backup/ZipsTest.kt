package gr.dimitris.app.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory

class ZipsTest {
    @Test fun `round trips a file and a directory`() {
        val src = createTempDirectory("src").toFile()
        val db = File(src, "dimitris.db").apply { writeText("db-bytes") }
        val photos = File(src, "photos").apply { mkdirs() }
        File(photos, "a.jpg").writeText("jpeg-a")
        val zip = File(src, "out.zip")

        Zips.zip(zip, listOf(Zips.Entry("dimitris.db", db), Zips.Entry("photos", photos)))
        val dst = createTempDirectory("dst").toFile()
        zip.inputStream().use { Zips.unzip(it, dst) }

        assertEquals("db-bytes", File(dst, "dimitris.db").readText())
        assertEquals("jpeg-a", File(dst, "photos/a.jpg").readText())
    }

    @Test fun `rejects entries that escape the target directory`() {
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z -> z.putNextEntry(ZipEntry("../evil.txt")); z.write("x".toByteArray()); z.closeEntry() }
        }.toByteArray()
        val dst = createTempDirectory("dst").toFile()
        assertThrows(IllegalArgumentException::class.java) { Zips.unzip(bytes.inputStream(), dst) }
    }
}
