package gr.dimitris.app.core.audio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ImageStoreTest {
    @Test fun importDownscalesTo1024() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val files = MediaFiles(context)
        val big = File(context.cacheDir, "big.jpg")
        big.outputStream().use { Bitmap.createBitmap(3000, 2000, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 80, it) }

        val out = ImageStore(files).import(context.contentResolver, Uri.fromFile(big))

        assertTrue(out.parentFile == files.photosDir)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(out.absolutePath, bounds)
        assertEquals(1024, maxOf(bounds.outWidth, bounds.outHeight))
    }
}
