package com.brushwork.paint.v19.a8

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.engine.MemoryGovernor
import com.brushwork.paint.engine.MemoryPressure
import com.brushwork.paint.ui.gallery.ThumbnailCache
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * v1.9 A8 (design 3.2 A8 step 11): the gallery's decoded thumbnails stay within 24 MB (least
 * recently used first out) and are registered with MemoryGovernor at priority 0, so memory
 * pressure frees them first: MODERATE halves the cache, LOW and CRITICAL empty it, and a dropped
 * thumbnail is decoded again.
 */
@RunWith(RobolectricTestRunner::class)
class ThumbnailCacheGovernorTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "thumbs-test").apply { deleteRecursively(); mkdirs() }
        ThumbnailCache.trim(MemoryPressure.CRITICAL)
    }

    @After
    fun tearDown() {
        ThumbnailCache.trim(MemoryPressure.CRITICAL)
        dir.deleteRecursively()
    }

    /** A [side] x [side] PNG thumbnail (decoded: side * side * 4 bytes). */
    private fun thumb(name: String, side: Int): File {
        val f = File(dir, "$name.png")
        val b = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        b.eraseColor(0xFF336699.toInt())
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        b.recycle()
        return f
    }

    private fun entry(): MemoryGovernor.Entry = MemoryGovernor.entries().single { it.name == ThumbnailCache.NAME }

    @Test
    fun theCacheIsRegisteredAtPriorityZeroWithItsBytes() {
        val a = thumb("a", 256)
        assertNotNull(ThumbnailCache.load(a, 1L))
        val e = entry()
        assertEquals("a display cache: trimmed first", 0, e.priority)
        assertEquals(256L * 256 * 4, e.bytes())
    }

    @Test
    fun itHoldsAtMost24Mb() {
        assertEquals(24 shl 20, ThumbnailCache.MAX_BYTES)
        val files = (0 until 3).map { thumb("big$it", 1600) }   // 10 MB each decoded
        for (f in files) assertNotNull(ThumbnailCache.load(f, 1L))
        assertTrue("within 24 MB: ${ThumbnailCache.bytes()}", ThumbnailCache.bytes() <= ThumbnailCache.MAX_BYTES)
        assertNull("the least recently used went first", ThumbnailCache.peek(files[0]))
        assertNotNull(ThumbnailCache.peek(files[2]))
    }

    @Test
    fun pressureFreesIt() {
        val files = (0 until 4).map { thumb("t$it", 256) }
        for (f in files) ThumbnailCache.load(f, 1L)
        val full = ThumbnailCache.bytes()
        assertEquals(4L * 256 * 256 * 4, full)
        assertEquals("NORMAL frees nothing", 0L, entry().trimmable.trim(MemoryPressure.NORMAL))
        assertEquals("MODERATE halves it", full / 2, entry().trimmable.trim(MemoryPressure.MODERATE))
        assertEquals(full / 2, ThumbnailCache.bytes())
        assertNull("the least recently used went first", ThumbnailCache.peek(files[0]))
        assertNotNull(ThumbnailCache.peek(files[3]))
        assertEquals("LOW empties it", full / 2, entry().trimmable.trim(MemoryPressure.LOW))
        assertEquals(0L, ThumbnailCache.bytes())
        assertNull(ThumbnailCache.peek(files[3]))
        assertNotNull("decoded again", ThumbnailCache.load(files[3], 1L))
        assertEquals(256L * 256 * 4, ThumbnailCache.bytes())
        assertEquals("CRITICAL empties it", 256L * 256 * 4, entry().trimmable.trim(MemoryPressure.CRITICAL))
    }
}
