package com.brushwork.paint.ui.gallery

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.brushwork.paint.engine.MemoryGovernor
import com.brushwork.paint.engine.MemoryPressure
import java.io.File

/**
 * Decoded gallery thumbnails, keyed by file path and invalidated when the file's
 * `lastModified` or the project's modification time changes. Bounded by bytes (LRU).
 *
 * v1.9 A8 (design 3.2 A8 step 11): at most [MAX_BYTES], registered with [MemoryGovernor] at
 * priority 0 (a display cache, trimmed first) when the gallery first uses it. [trim]: MODERATE
 * pressure halves it, LOW and CRITICAL empty it; a dropped thumbnail is decoded again when the
 * gallery shows it.
 */
internal object ThumbnailCache {
    /** The cache's size cap: 24 MB of decoded bitmaps. */
    const val MAX_BYTES: Int = 24 shl 20

    /** Its name in [MemoryGovernor.entries]. */
    const val NAME = "Gallery thumbnails"

    private class Entry(val lastModified: Long, val version: Long, val image: ImageBitmap, val bytes: Int)

    private val cache = object : LruCache<String, Entry>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Entry): Int = value.bytes
    }

    @Suppress("unused")
    private val registration = MemoryGovernor.register(NAME, 0, { bytes() }) { trim(it) }

    /** The bytes held now. */
    fun bytes(): Long = cache.size().toLong()

    /** Frees what [pressure] asks for (see the class) and returns the bytes freed. */
    fun trim(pressure: MemoryPressure): Long {
        val before = cache.size()
        when (pressure) {
            MemoryPressure.NORMAL -> Unit
            MemoryPressure.MODERATE -> cache.trimToSize(before / 2)
            MemoryPressure.LOW, MemoryPressure.CRITICAL -> cache.evictAll()
        }
        return (before - cache.size()).toLong()
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
