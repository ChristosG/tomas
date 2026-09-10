package gr.dimitris.app.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // A take made by «Μίλα» on the on-device path is a `.wav`: the same PCM the recogniser was fed.
    // It has to travel and land exactly like the .m4a takes beside it.

    @Test fun `a wav take is content-addressed like any other voice`() {
        val file = files.recording("take.wav", "abc".toByteArray())
        val row = mapOf<String, Any?>("id" to "r1", "path" to "recordings/take.wav", "who" to "DIMITRIS")

        val (out, uploads) = MediaRefs.outgoing(Tables.RECORDINGS, row, files)

        assertEquals("media://$abcSha", out["path"])
        assertEquals(listOf(file.canonicalFile), uploads.map { it.file.canonicalFile })
    }

    /** A phone that has never made one still has to put it with the rest of his voice. */
    @Test fun `a wav lands in the recordings folder`() {
        assertEquals(files.recordingsDir, MediaRefs.folderFor(Tables.WAV_EXT, files))
        assertEquals(files.recordingsDir, MediaRefs.folderFor(Tables.RECORDING_EXT, files))
        assertEquals(files.photosDir, MediaRefs.folderFor(Tables.PHOTO_EXT, files))
    }

    @Test fun `both voice extensions are voices`() {
        assertEquals(setOf("m4a", "wav"), Tables.RECORDING_EXTS)
        assertTrue(Tables.RECORDING_EXT in Tables.RECORDING_EXTS)
        assertTrue(Tables.WAV_EXT in Tables.RECORDING_EXTS)
    }

    // The wire carries a hash and nothing else, so the bytes are the only thing that knows whether a
    // recording is one of his raw-PCM takes or one of the caregiver's AAC ones.

    private fun wav(): ByteArray = gr.dimitris.app.core.audio.Wav.header(0)

    @Test fun `a RIFF WAVE header is a wav`() = assertTrue(MediaRefs.isWav(wav()))

    /** `RIFF` alone is a container: a WebP photograph starts with it and is not a voice. */
    @Test fun `RIFF alone is not enough`() {
        val webp = "RIFF____WEBPVP8 ".toByteArray(Charsets.US_ASCII)
        assertFalse(MediaRefs.isWav(webp))
        assertEquals("jpg", MediaRefs.extensionOf(webp, "jpg"))
    }

    @Test fun `an m4a keeps the extension the registry gave it`() {
        // The first bytes of an MPEG-4 container: a size, then `ftyp`.
        val m4a = byteArrayOf(0, 0, 0, 0x20) + "ftypM4A ".toByteArray(Charsets.US_ASCII)
        assertFalse(MediaRefs.isWav(m4a))
        assertEquals("m4a", MediaRefs.extensionOf(m4a, Tables.RECORDING_EXT))
    }

    @Test fun `bytes too short to tell are left alone`() {
        assertFalse(MediaRefs.isWav("RIFF".toByteArray(Charsets.US_ASCII)))
        assertFalse(MediaRefs.isWav(ByteArray(0)))
        assertEquals("m4a", MediaRefs.extensionOf(ByteArray(3), Tables.RECORDING_EXT))
    }

    @Test fun `a wav downloaded under the m4a name is renamed by its bytes`() {
        val downloaded = files.recording("$abcSha.m4a", wav())
        val settled = MediaRefs.settle(downloaded)
        assertEquals("$abcSha.wav", settled.name)
        assertTrue("the bytes moved with the name", settled.isFile)
        assertFalse("and nothing is left under the old one", downloaded.exists())
    }

    @Test fun `a real m4a is left where it landed`() {
        val downloaded = files.recording("$abcSha.m4a", byteArrayOf(0, 0, 0, 0x20) + "ftypM4A ".toByteArray(Charsets.US_ASCII))
        assertEquals(downloaded, MediaRefs.settle(downloaded))
        assertTrue(downloaded.isFile)
    }

    /** A settled wav must be recognised next time, or every sync downloads it again. */
    @Test fun `a file already here is found under either name`() {
        val wavFile = files.recording("$abcSha.wav", wav())
        assertEquals(wavFile, MediaRefs.existing(files.recordingsDir, abcSha, Tables.RECORDING_EXT))
        wavFile.delete()
        assertNull(MediaRefs.existing(files.recordingsDir, abcSha, Tables.RECORDING_EXT))
        val m4aFile = files.recording("$abcSha.m4a", "abc".toByteArray())
        assertEquals(m4aFile, MediaRefs.existing(files.recordingsDir, abcSha, Tables.RECORDING_EXT))
    }

    /** An empty file is not a file it has: a download that failed halfway must be asked for again. */
    @Test fun `an empty file does not count as already here`() {
        files.recording("$abcSha.wav", ByteArray(0))
        assertNull(MediaRefs.existing(files.recordingsDir, abcSha, Tables.RECORDING_EXT))
    }

    // Whether a row arrived as a deletion decides whether its file goes with it.

    @Test fun `a deletion is read whichever way it was written`() {
        assertTrue(Rows.deleted(mapOf("deleted" to true)))
        assertTrue("a row pushed by hand may carry 1", Rows.deleted(mapOf("deleted" to 1)))
        assertTrue(Rows.deleted(mapOf("deleted" to 1.0)))
        assertFalse(Rows.deleted(mapOf("deleted" to false)))
        assertFalse(Rows.deleted(mapOf("deleted" to 0)))
        assertFalse("a row with no such column is not a deletion", Rows.deleted(mapOf("id" to "r1")))
        assertFalse(Rows.deleted(mapOf("deleted" to null)))
    }

    @Test fun `a value that only looks like a media url is not one`() {
        assertNull(MediaRefs.shaOf("media://not-a-hash"))
        assertNull(MediaRefs.shaOf("photos/x.jpg"))
        assertNull(MediaRefs.shaOf(null))
        assertEquals(abcSha, MediaRefs.shaOf("media://$abcSha"))
    }
}
