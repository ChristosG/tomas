package gr.dimitris.app.core.audio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MediaFilesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val files = MediaFiles(context)

    @Test fun photoPathsAreStoredRelativeToTheFilesDir() {
        val photo = files.newPhotoFile()
        val stored = files.relativize(photo)

        assertTrue("expected photos/… but got $stored", stored.startsWith("photos/"))
        assertEquals(photo.name, stored.substringAfterLast('/'))
        assertEquals(photo.absolutePath, files.resolve(stored).absolutePath)
    }

    @Test fun recordingPathsRoundTrip() {
        val recording = files.newRecordingFile()
        val stored = files.relativize(recording)

        assertTrue("expected recordings/… but got $stored", stored.startsWith("recordings/"))
        assertEquals(recording.absolutePath, files.resolve(stored).absolutePath)
    }

    /** Rows written before paths went relative still hold absolute paths; they must keep working. */
    @Test fun absolutePathsPassThrough() {
        val outside = File(context.cacheDir, "elsewhere.m4a")

        assertEquals(outside.absolutePath, files.relativize(outside))
        assertEquals(outside.absolutePath, files.resolve(outside.absolutePath).absolutePath)
        assertEquals("/tmp/x.jpg", files.resolve("/tmp/x.jpg").path)
    }
}
