package com.brushwork.paint.v19.a7b

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Looper
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Layer
import com.brushwork.paint.snap.DetectedLine
import com.brushwork.paint.snap.LineDetector
import com.brushwork.paint.tools.transform.ContentBounds
import com.brushwork.paint.tools.transform.LayerBoundsCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v1.9 A7b step 6 (design 3.2 A7b, R9, risk H4): the smart-guide cache's background scans read
 * immutable copies of a layer and its mask, taken on the main thread and recycled when the scan
 * ends, never the layer's own bitmaps: a layer removed and recycled meanwhile (undo history
 * trimmed: `LayerTreeAction.dispose`) is never read by another thread. The results are those of
 * the layer's own pixels; a copy there is no memory for means nothing to snap to in that content.
 */
@RunWith(RobolectricTestRunner::class)
class LayerBoundsCacheCopyTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Runs the main looper (and gives the background scan real time) until [done]. */
    private fun waitUntil(done: () -> Boolean) {
        repeat(4000) {
            if (done()) return
            idle()
            Thread.sleep(5)
        }
        idle()
        assertTrue("timed out", done())
    }

    /** A box of black lines (a frame and a cross) on a transparent [w] x [h] layer. */
    private fun frameLayer(w: Int, h: Int): Layer {
        val bmp = BitmapUtils.createLayerBitmap(w, h)
        val p = Paint().apply { color = BLACK }
        val c = Canvas(bmp)
        c.drawRect(40f, 30f, w - 40f, 34f, p)
        c.drawRect(40f, h - 34f, w - 40f, h - 30f, p)
        c.drawRect(40f, 30f, 44f, h - 30f, p)
        c.drawRect(w - 44f, 30f, w - 40f, h - 30f, p)
        c.drawRect(w / 2f - 2f, 30f, w / 2f + 2f, h - 30f, p)
        c.drawRect(40f, h / 2f - 2f, w - 40f, h / 2f + 2f, p)
        return Layer(1, "Frame", bmp).also { it.markChanged() }
    }

    /** A mask of [w] x [h] that hides the band of rows [top] until [bottom]. */
    private fun bandMask(w: Int, h: Int, top: Float, bottom: Float): Bitmap =
        BitmapUtils.createMaskBitmap(w, h).also { Canvas(it).drawRect(0f, top, w.toFloat(), bottom, Paint().apply { color = BLACK }) }

    @Test
    fun theBackgroundScansCopiesAndRecyclesThemWithTheLayersOwnResults() {
        // 600 x 480 (over SYNC_PIXELS): bounds and lines are both found in the background.
        val layer = frameLayer(600, 480)
        layer.mask = bandMask(600, 480, 200f, 280f)
        layer.markChanged()
        assertTrue(600L * 480 > LayerBoundsCache.SYNC_PIXELS)
        // What the layer's own pixels give, read here on the main thread.
        val ownBounds = ContentBounds.of(layer.bitmap)!!.let { ContentBounds.of(layer.mask!!, BLACK, region = it)!! }
        val ownLines = LineDetector.detect(layer.bitmap, ownBounds, layer.mask)
        assertTrue("the frame has lines", ownLines.size >= 4)

        val cache = LayerBoundsCache(scope, detectLines = true) {}
        val main = Looper.getMainLooper().thread
        val copied = Collections.synchronizedList(ArrayList<Bitmap>())
        val made = Collections.synchronizedList(ArrayList<Bitmap>())
        val copyThreads = Collections.synchronizedList(ArrayList<Thread>())
        val copy = cache.copyPixels
        cache.copyPixels = { b ->
            copyThreads += Thread.currentThread()
            copied += b
            copy(b)?.also { made += it }
        }
        val read = Collections.synchronizedList(ArrayList<Bitmap?>())
        val readThreads = Collections.synchronizedList(ArrayList<Thread>())
        cache.findLines = { b, r, m, c ->
            readThreads += Thread.currentThread()
            read += b
            read += m
            LineDetector.detect(b, r, m, c)
        }
        cache.request(listOf(layer))
        waitUntil { !cache.isBusy }

        assertTrue("copies are taken on the main thread", copyThreads.all { it === main })
        assertTrue("and read on another", readThreads.isNotEmpty() && readThreads.none { it === main })
        assertEquals("the layer and its mask were copied", listOf(layer.bitmap, layer.mask), copied.toList())
        assertEquals(2, made.size)
        assertEquals("the scan read the copies", made.toList(), read.toList())
        assertTrue("never the layer's own bitmaps", read.none { it === layer.bitmap || it === layer.mask })
        assertTrue("the copies were recycled", made.all { it.isRecycled })
        assertFalse(layer.bitmap.isRecycled)
        assertFalse(layer.mask!!.isRecycled)
        assertTrue(cache.isKnown(layer))
        assertEquals("the layer's own bounds", ownBounds, cache.bounds(layer))
        assertEquals("the layer's own lines", ownLines.sorted(), cache.lines(layer).sorted())
    }

    @Test
    fun aLayerRecycledWhileItIsScannedIsNeverReadAndItsResultIsDropped() {
        // 400 x 400: bounds at once on the main thread, the lines in the background.
        val layer = frameLayer(400, 400)
        val live = layer.bitmap
        val cache = LayerBoundsCache(scope, detectLines = true) {}
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var scanned: Bitmap? = null
        var readAfterRecycle: Throwable? = IllegalStateException("not read")
        cache.findLines = { b, r, m, c ->
            scanned = b
            entered.countDown()
            release.await(20, TimeUnit.SECONDS)
            // v1.8 read the layer's own bitmap here, recycled by now (a native crash on the phone).
            val result = runCatching { LineDetector.detect(b, r, m, c) }
            readAfterRecycle = result.exceptionOrNull()
            result.getOrDefault(emptyList())
        }
        cache.request(listOf(layer))
        assertNotNull("bounds at once", cache.bounds(layer))
        assertTrue(cache.isBusy)
        waitUntil { entered.count == 0L }

        // The undo history drops the step that removed this layer: its bitmap is recycled on main.
        live.recycle()
        release.countDown()
        waitUntil { !cache.isBusy }

        assertNull("the copy was read in full", readAfterRecycle)
        assertNotSame(live, scanned)
        assertTrue("the copy was recycled", scanned!!.isRecycled)
        assertFalse("a recycled layer's lines are dropped", cache.isKnown(layer))
        assertTrue(cache.lines(layer).isEmpty())
    }

    @Test
    fun noMemoryForTheCopyMeansNothingToSnapToUntilTheContentChanges() {
        val layer = frameLayer(600, 480)
        layer.mask = bandMask(600, 480, 200f, 280f)
        layer.markChanged()
        val cache = LayerBoundsCache(scope, detectLines = true) {}
        val copy = cache.copyPixels
        val made = ArrayList<Bitmap>()
        var scans = 0
        cache.findLines = { b, r, m, c -> scans++; LineDetector.detect(b, r, m, c) }

        // The layer is copied, its mask is not: the layer's copy goes at once, nothing is scanned.
        cache.copyPixels = { b -> if (b === layer.mask) throw OutOfMemoryError("test") else copy(b)?.also { made += it } }
        cache.request(listOf(layer))
        waitUntil { !cache.isBusy }
        assertEquals(1, made.size)
        assertTrue(made.single().isRecycled)
        assertEquals(0, scans)
        assertTrue("known: nothing to snap to", cache.isKnown(layer))
        assertNull(cache.bounds(layer))
        assertTrue(cache.lines(layer).isEmpty())
        // Not tried again on every gesture.
        cache.request(listOf(layer))
        assertFalse(cache.isBusy)

        // No copy at all (Bitmap.copy returns null): the same.
        cache.copyPixels = { null }
        layer.markChanged()
        cache.request(listOf(layer))
        waitUntil { !cache.isBusy }
        assertTrue(cache.isKnown(layer))
        assertNull(cache.bounds(layer))
        assertEquals(0, scans)

        // New content, memory again: looked at.
        cache.copyPixels = copy
        layer.markChanged()
        cache.request(listOf(layer))
        waitUntil { !cache.isBusy }
        assertEquals(1, scans)
        assertNotNull(cache.bounds(layer))
        assertTrue(cache.lines(layer).isNotEmpty())
    }

    private fun List<DetectedLine>.sorted(): List<DetectedLine> = sortedWith(compareBy({ it.axis }, { it.pos }, { it.start }))

    private companion object {
        const val BLACK = 0xFF000000.toInt()
    }
}
