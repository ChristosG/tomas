package gr.dimitris.app.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The rewrite both ways: a phone's own paths out, content hashes back. (The controller's list calls
 * this `MediaPathsTest`; the class under test is [MediaRefs] and the file is named after it.)
 */
class MediaRefsTest {
    private val files = FakeMediaPaths()

    /** sha-256 of the three bytes below, from `printf 'abc' | sha256sum`. */
    private val abcSha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test fun `sha256 is the lowercase hex the server names files by`() {
        val file = files.photo("x.jpg", "abc".toByteArray())
        assertEquals(abcSha, MediaRefs.sha256(file))
    }

    @Test fun `outgoing replaces imagePath with the media url and lists the file`() {
        val file = files.photo("x.jpg", "abc".toByteArray())
        val row = mapOf<String, Any?>("id" to "i1", "text" to "ψωμί", "imagePath" to "photos/x.jpg")

        val (out, uploads) = MediaRefs.outgoing(Tables.ITEMS, row, files)

        assertEquals("media://$abcSha", out["imagePath"])
        assertEquals(listOf(file.canonicalFile), uploads.map { it.file.canonicalFile })
        // The hash comes back with the file: the caller must not read a 20 MB recording twice.
        assertEquals(listOf(abcSha), uploads.map { it.sha })
        assertEquals("ψωμί", out["text"])
    }

    @Test fun `outgoing leaves a null alone`() {
        val row = mapOf<String, Any?>("id" to "i1", "imagePath" to null)
        val (out, uploads) = MediaRefs.outgoing(Tables.ITEMS, row, files)
        assertNull(out["imagePath"])
        assertTrue(uploads.isEmpty())
    }

    /** A file the caregiver deleted must not stop the word from travelling. */
    @Test fun `outgoing leaves a path whose file is gone`() {
        val row = mapOf<String, Any?>("id" to "i1", "imagePath" to "photos/missing.jpg")
        val (out, uploads) = MediaRefs.outgoing(Tables.ITEMS, row, files)
        assertEquals("photos/missing.jpg", out["imagePath"])
        assertTrue(uploads.isEmpty())
    }

    /** Only what lives under photos/ or recordings/ is ours to upload. */
    @Test fun `outgoing leaves a file outside the media folders`() {
        val stray = File(files.filesDir, "stray.jpg").apply { writeBytes("abc".toByteArray()) }
        val row = mapOf<String, Any?>("id" to "i1", "imagePath" to stray.absolutePath)
        val (out, uploads) = MediaRefs.outgoing(Tables.ITEMS, row, files)
        assertEquals(stray.absolutePath, out["imagePath"])
        assertTrue(uploads.isEmpty())
    }

    @Test fun `outgoing content-addresses a recording by its own column`() {
        files.recording("v.m4a", "abc".toByteArray())
        val row = mapOf<String, Any?>("id" to "r1", "path" to "recordings/v.m4a")
        val (out, uploads) = MediaRefs.outgoing(Tables.RECORDINGS, row, files)
        assertEquals("media://$abcSha", out["path"])
        assertEquals(1, uploads.size)
    }

    @Test fun `a table with no media is handed straight back`() {
        val row = mapOf<String, Any?>("id" to "a1", "itemId" to "i1")
        val (out, uploads) = MediaRefs.outgoing(Tables.ATTEMPTS, row, files)
        assertEquals(row, out)
        assertTrue(uploads.isEmpty())
    }

    @Test fun `incoming rewrites a media url to the relative path the database stores`() {
        val landed = File(files.photosDir, "$abcSha.jpg").apply { writeBytes("abc".toByteArray()) }
        val row = mapOf<String, Any?>("id" to "i1", "imagePath" to "media://$abcSha")

        val out = MediaRefs.incoming(Tables.ITEMS, row, files) { sha, extension ->
            assertEquals(abcSha, sha)
            assertEquals("jpg", extension)
            landed
        }

        assertEquals("photos/$abcSha.jpg", out["imagePath"])
    }

    @Test fun `incoming asks for m4a for a recording`() {
        val landed = File(files.recordingsDir, "$abcSha.m4a").apply { writeBytes("abc".toByteArray()) }
        val row = mapOf<String, Any?>("id" to "r1", "path" to "media://$abcSha")
        val out = MediaRefs.incoming(Tables.RECORDINGS, row, files) { _, extension ->
            assertEquals("m4a", extension)
            landed
        }
        assertEquals("recordings/$abcSha.m4a", out["path"])
    }

    @Test fun `incoming leaves a plain path alone`() {
        val row = mapOf<String, Any?>("id" to "i1", "imagePath" to "photos/x.jpg")
        val out = MediaRefs.incoming(Tables.ITEMS, row, files) { _, _ -> error("must not be asked") }
        assertEquals("photos/x.jpg", out["imagePath"])
    }

    /** A photo that would not download must not hold up the word it belongs to. */
    @Test fun `incoming keeps the media url when the file could not be fetched`() {
        val row = mapOf<String, Any?>("id" to "i1", "text" to "ψωμί", "imagePath" to "media://$abcSha")
        val out = MediaRefs.incoming(Tables.ITEMS, row, files) { _, _ -> null }
        assertEquals("media://$abcSha", out["imagePath"])
        assertEquals("ψωμί", out["text"])
    }

    @Test fun `a value that only looks like a media url is not one`() {
        assertNull(MediaRefs.shaOf("media://not-a-hash"))
        assertNull(MediaRefs.shaOf("photos/x.jpg"))
        assertNull(MediaRefs.shaOf(null))
        assertEquals(abcSha, MediaRefs.shaOf("media://$abcSha"))
    }
}
