package com.brushwork.paint.storage

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.resume

/** Image encoding and the three export destinations (MediaStore, legacy Pictures dir, share cache). */
internal object ImageExport {
    const val ALBUM = "Brushwork"
    private const val JPEG_QUALITY = 95
    private const val SHARE_DIR = "exports"
    private const val SHARE_MAX_AGE_MS = 24L * 60 * 60 * 1000

    /** True when saving to the public gallery first needs the (legacy) storage permission. */
    fun needsLegacyPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

    /** Encodes [bitmap] as [format] into [out]. JPEG is flattened onto white first. */
    fun encode(bitmap: Bitmap, format: ExportFormat, out: OutputStream) {
        val ok = when (format) {
            ExportFormat.PNG -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            ExportFormat.JPEG -> {
                val flat = flattenOnWhite(bitmap)
                try { flat.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out) } finally { flat.recycle() }
            }
            ExportFormat.WEBP -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)
            } else {
                @Suppress("DEPRECATION")
                bitmap.compress(Bitmap.CompressFormat.WEBP, 100, out)
            }
        }
        if (!ok) throw IOException("Could not encode the image as ${format.name}")
    }

    /** An opaque copy of [src] composited over white (JPEG has no alpha channel). */
    fun flattenOnWhite(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        c.drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** A file-name-safe version of [name] (keeps letters, digits, spaces and a few symbols). */
    fun safeFileName(name: String): String {
        val cleaned = name.trim().map { ch ->
            if (ch.isLetterOrDigit() || ch == ' ' || ch == '-' || ch == '_' || ch == '(' || ch == ')' || ch == '.') ch else '_'
        }.joinToString("").trim('.', ' ').take(80).trim()
        return cleaned.ifEmpty { "Brushwork" }
    }

    /** Saves to Pictures/Brushwork (call on IO). Returns null if the legacy permission is missing. */
    suspend fun saveToGallery(context: Context, bitmap: Bitmap, name: String, format: ExportFormat): Uri? {
        val base = safeFileName(name)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveWithMediaStore(context, bitmap, "$base.${format.extension}", format)
        } else {
            if (needsLegacyPermission(context)) return null
            saveToLegacyPictures(context, bitmap, base, format)
        }
    }

    private fun saveWithMediaStore(context: Context, bitmap: Bitmap, displayName: String, format: ExportFormat): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, format.mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + ALBUM)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IOException("The gallery refused the new image")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("Could not open the gallery file")
            out.use { encode(bitmap, format, it) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (e: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    private suspend fun saveToLegacyPictures(context: Context, bitmap: Bitmap, base: String, format: ExportFormat): Uri? {
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ALBUM)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Could not create ${dir.path}")
        var file = File(dir, "$base.${format.extension}")
        var n = 2
        while (file.exists()) file = File(dir, "$base ($n).${format.extension}").also { n++ }
        try {
            FileOutputStream(file).use { fos ->
                val out = fos.buffered(64 * 1024)
                encode(bitmap, format, out)
                out.flush()
            }
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
        // Make the picture visible to gallery apps; the scanner hands back its content Uri.
        val scanned = withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine { cont ->
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(format.mimeType)) { _, uri ->
                    if (cont.isActive) cont.resume(uri)
                }
            }
        }
        return scanned ?: Uri.fromFile(file)
    }

    /** Writes a share file under cacheDir/exports and returns its FileProvider Uri. BLOCKING (IO). */
    fun saveForShare(context: Context, bitmap: Bitmap, name: String, format: ExportFormat): Uri {
        val dir = File(context.cacheDir, SHARE_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Could not create the share folder")
        pruneOldShares(dir)
        val file = File(dir, "${safeFileName(name)}.${format.extension}")
        ProjectFormat.writeAtomically(file) { encode(bitmap, format, it) }
        return FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    }

    /** Deletes share files older than a day so the cache doesn't grow forever. */
    private fun pruneOldShares(dir: File) {
        val cutoff = System.currentTimeMillis() - SHARE_MAX_AGE_MS
        dir.listFiles()?.forEach { if (it.isFile && it.lastModified() < cutoff) it.delete() }
    }
}
