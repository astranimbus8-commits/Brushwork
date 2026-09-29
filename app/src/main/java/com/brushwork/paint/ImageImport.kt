package com.brushwork.paint

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlin.math.max

/** Decodes user-picked images with EXIF rotation and memory-safe downsampling. */
object ImageImport {
    /**
     * Decodes [uri] so that its longest side is at most [maxDim] (keeps aspect), applies EXIF
     * orientation, returns a mutable ARGB_8888 bitmap. BLOCKING — call off the main thread.
     */
    fun decode(context: Context, uri: Uri, maxDim: Int = 4096): Bitmap {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported image" }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("Could not decode image")
        val orientation = runCatching {
            cr.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        }
        val longest = max(bmp.width, bmp.height)
        if (longest > maxDim) { val s = maxDim.toFloat() / longest; m.postScale(s, s) }
        if (!m.isIdentity) {
            val t = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (t !== bmp) bmp.recycle()
            bmp = t
        }
        return if (bmp.config == Bitmap.Config.ARGB_8888 && bmp.isMutable) bmp else bmp.copy(Bitmap.Config.ARGB_8888, true)
    }
}
