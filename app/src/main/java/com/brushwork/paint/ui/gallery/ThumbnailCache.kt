package com.brushwork.paint.ui.gallery

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

/**
 * Decoded gallery thumbnails, keyed by file path and invalidated when the file's
 * `lastModified` or the project's modification time changes. Bounded by bytes (LRU).
 */
internal object ThumbnailCache {
    private class Entry(val lastModified: Long, val version: Long, val image: ImageBitmap, val bytes: Int)

    private val maxBytes: Int = (Runtime.getRuntime().maxMemory() / 16).coerceIn(8L shl 20, 48L shl 20).toInt()

    private val cache = object : LruCache<String, Entry>(maxBytes) {
        override fun sizeOf(key: String, value: Entry): Int = value.bytes
    }

    /** The last decoded thumbnail for [file] (possibly stale) without touching the disk. */
    fun peek(file: File): ImageBitmap? = cache.get(file.path)?.image

    /**
     * The thumbnail for [file], decoded again when it changed on disk or [version] (the
     * project's modification time) differs. BLOCKING: call off the main thread.
     */
    fun load(file: File, version: Long): ImageBitmap? {
        val lastModified = file.lastModified()
        if (lastModified == 0L) {
            cache.remove(file.path)
            return null
        }
        cache.get(file.path)?.let { if (it.lastModified == lastModified && it.version == version) return it.image }
        val bitmap = BitmapFactory.decodeFile(file.path) ?: return null
        bitmap.prepareToDraw()
        val image = bitmap.asImageBitmap()
        cache.put(file.path, Entry(lastModified, version, image, bitmap.byteCount))
        return image
    }
}
