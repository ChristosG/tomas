package gr.dimitris.app.core.audio

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/** Copies pictures into the photos dir, downscaled and rotated upright, so every Item path is stable and small. */
class ImageStore(private val files: MediaFiles) {

    /** Reads [uri] (gallery or camera), writes a ≤ MAX_SIDE JPEG into the photos dir, returns it. */
    fun import(resolver: ContentResolver, uri: Uri): File {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(resolver, uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) }
        val decoded = open(resolver, uri).use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IOException("Δεν άνοιξε η εικόνα $uri")
        val rotation = open(resolver, uri).use { rotationOf(it) }
        val out = files.newPhotoFile()
        write(rotate(scaleDown(decoded), rotation), out)
        return out
    }

    private fun open(resolver: ContentResolver, uri: Uri): InputStream =
        resolver.openInputStream(uri) ?: throw IOException("Δεν άνοιξε η εικόνα $uri")

    /** Camera photos land directly in the photos dir; shrink and straighten them in place. */
    fun shrinkInPlace(file: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return
        val rotation = file.inputStream().use { rotationOf(it) }
        write(rotate(scaleDown(decoded), rotation), file)
    }

    private fun write(bitmap: Bitmap, out: File) {
        FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
    }

    private fun scaleDown(b: Bitmap): Bitmap {
        val longest = maxOf(b.width, b.height)
        if (longest <= MAX_SIDE) return b
        val f = MAX_SIDE.toFloat() / longest
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt(), (b.height * f).toInt(), true)
    }

    private fun rotationOf(stream: InputStream): Int =
        when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }

    private fun rotate(b: Bitmap, degrees: Int): Bitmap =
        if (degrees == 0) b
        else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)

    companion object {
        const val MAX_SIDE = 1024
        const val JPEG_QUALITY = 85

        /** Largest power-of-two sample that keeps both sides ≥ MAX_SIDE, so the final scale is a small step. */
        fun sampleSize(width: Int, height: Int): Int {
            var sample = 1
            while (width / (sample * 2) >= MAX_SIDE && height / (sample * 2) >= MAX_SIDE) sample *= 2
            return sample
        }
    }
}
