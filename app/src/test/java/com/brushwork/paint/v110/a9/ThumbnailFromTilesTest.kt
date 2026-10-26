package com.brushwork.paint.v110.a9

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.graphics.asAndroidBitmap
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.budget.Budgets
import com.brushwork.paint.engine.tiles.BandAssembler
import com.brushwork.paint.engine.tiles.ChaosResidency
import com.brushwork.paint.model.PixelFacade
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeMemoryProbe
import com.brushwork.paint.testing.FakeSwapStore
import com.brushwork.paint.testing.MemoryPreset
import com.brushwork.paint.ui.layers.LayerThumbnails
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * v1.10 A9 step 6 (design 2.13 "The panel", 4.4 A9 step 6, X2): a parked layer's thumbnail is
 * made from its tiles. The panel shows a placeholder at first and requests the picture; the
 * tiles that are not resident are read off main (never on main, never made resident) under a pin
 * that is closed afterwards; nothing materializes; the picture equals the flat downscale but at
 * the tile seams; the mask's likewise. A request whose key changed meanwhile is dropped and asked
 * again; a blank store never asks.
 */
@RunWith(RobolectricTestRunner::class)
class ThumbnailFromTilesTest {
    private val swap = FakeSwapStore()

    @get:Rule
    val engine = EngineRule(
        EngineLevel.FULL,
        memory = FakeMemoryProbe(MemoryPreset.T606),
        budgets = Budgets(javaMax = 512L shl 20, nativeBudget = 256L shl 20, known = true, stepDown = 0),
        residency = ChaosResidency(),
        swap = swap,
    )

    private val made = mutableListOf<Bitmap>()
    private var threads: ExecutorService? = null

    @After
    fun tearDown() {
        A9Docs.disposeTracked()
        threads?.shutdownNow()
        made.forEach { if (!it.isRecycled) it.recycle() }
    }

    private fun keep(b: Bitmap) = b.also { made += it }

    private fun layer(d: Document, masked: Boolean): Layer =
        Layer(d.newLayerId(), "Painted", keep(A9Docs.content(3L))).apply {
            if (masked) {
                mask = keep(A9Docs.mask(4L))
                maskEnabled = true
            }
        }

    /** Parks [l] and swaps its ordinary tiles (content and mask) out. */
    private fun parkAndSwap(l: Layer) {
        assertTrue(ChaosResidency().park(l))
        for (store in listOf(l.pixels, l.maskPixels)) {
            val t = store as? TiledPixels ?: continue
            for (row in 0 until t.rows) for (col in 0 until t.cols) {
                val tile = t.tileAt(col, row) ?: continue
                if (tile.solidColor == null) swap.swapOut(tile)
            }
        }
    }

    private fun realWorker(): Executor =
        Executors.newSingleThreadExecutor { r -> Thread(r, "thumb-test-worker").apply { isDaemon = true } }.also { threads = it }

    private fun settle(thumbs: LayerThumbnails) {
        val until = System.currentTimeMillis() + 30_000L
        while (thumbs.pending > 0) {
            shadowOf(Looper.getMainLooper()).idle()
            if (thumbs.pending == 0) break
            assertTrue("the requests complete", System.currentTimeMillis() < until)
            Thread.sleep(1)
        }
    }

    /** At most [share] of the pixels differ by more than [tolerance] in a channel (the tile seams). */
    private fun assertClose(what: String, expected: Bitmap, actual: Bitmap, tolerance: Int = 24, share: Double = 0.1) {
        assertEquals("$what: size", expected.width to expected.height, actual.width to actual.height)
        val w = expected.width
        val e = IntArray(w * expected.height).also { expected.getPixels(it, 0, w, 0, 0, w, expected.height) }
        val a = IntArray(w * actual.height).also { actual.getPixels(it, 0, w, 0, 0, w, actual.height) }
        var off = 0
        for (i in e.indices) {
            val x = e[i]
            val y = a[i]
            val d = maxOf(
                abs(Color.alpha(x) - Color.alpha(y)),
                abs(Color.red(x) - Color.red(y)),
                abs(Color.green(x) - Color.green(y)),
                abs(Color.blue(x) - Color.blue(y)),
            )
            if (d > tolerance) off++
        }
        assertTrue("$what: $off of ${e.size} pixels differ by more than $tolerance", off <= e.size * share)
    }

    @Test
    fun aParkedLayersThumbnailIsMadeFromItsTilesOffMain() {
        val d = A9Docs.track(Document("thumbs", "thumbs", A9Docs.W, A9Docs.H))
        val l = layer(d, masked = true).also { d.layers += it }
        val flatContent = keep(LayerThumbnails.downscale(keep(A9Docs.content(3L)), SIZE))
        val flatMask = keep(LayerThumbnails.downscale(keep(A9Docs.mask(4L)), SIZE))
        parkAndSwap(l)
        val thumbs = LayerThumbnails(SIZE, realWorker(), Handler(Looper.getMainLooper()))
        val main = Thread.currentThread().name
        val mats = PixelFacade.materializations
        PixelFacade.strict = true
        try {
            val placeholder = thumbs.content(l)
            val maskPlaceholder = thumbs.mask(l)!!
            assertEquals(LayerThumbnails.fitSize(A9Docs.W, A9Docs.H, SIZE), placeholder.width to placeholder.height)
            assertEquals("both pictures are requested", 2, thumbs.pending)
            assertSame("the placeholder shows until the picture lands", placeholder, thumbs.content(l))
            settle(thumbs)
            assertEquals(2, thumbs.madeFromTiles)
            assertEquals("a picture from tiles is not a flat downscale", 0, thumbs.made)
            val picture = thumbs.content(l)
            assertNotSame("the picture replaces the placeholder", placeholder, picture)
            assertClose("the content from tiles", flatContent, picture.asAndroidBitmap())
            assertClose("the mask from tiles", flatMask, thumbs.mask(l)!!.asAndroidBitmap().also { assertNotSame(maskPlaceholder, it) })
            assertSame("the picture stays while the key does", picture, thumbs.content(l))
            assertEquals("nothing is requested again", 0, thumbs.pending)
        } finally {
            PixelFacade.strict = false
        }
        assertEquals("nothing materialized", mats, PixelFacade.materializations)
        assertTrue("still parked", l.pixels is TiledPixels && l.maskPixels is TiledPixels)
        assertTrue("no read on main (${swap.readThreads.toSet()})", swap.readThreads.isNotEmpty() && swap.readThreads.none { it == main })
        assertEquals("the request's pin is closed", 0, l.pins)
        for (store in listOf(l.pixels, l.maskPixels)) {
            val t = store as TiledPixels
            for (row in 0 until t.rows) for (col in 0 until t.cols) {
                val tile = t.tileAt(col, row) ?: continue
                if (tile.solidColor == null) assertTrue("a tile is never made resident", !BandAssembler.isResident(tile))
            }
        }
    }

    @Test
    fun aRequestWhoseKeyChangedIsDroppedAndAskedAgain() {
        val d = A9Docs.track(Document("thumbs", "thumbs", A9Docs.W, A9Docs.H))
        val l = layer(d, masked = false).also { d.layers += it }
        parkAndSwap(l)
        val tasks = ArrayDeque<Runnable>()
        val thumbs = LayerThumbnails(SIZE, Executor { tasks.addLast(it) }, Handler(Looper.getMainLooper()))
        thumbs.content(l)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("the read is queued", 1, tasks.size)
        l.markChanged()
        while (tasks.isNotEmpty()) {
            tasks.removeFirst().run()
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals("a stale picture never lands", 0, thumbs.madeFromTiles)
        assertEquals(0, l.pins)
        thumbs.content(l)
        shadowOf(Looper.getMainLooper()).idle()
        while (tasks.isNotEmpty()) {
            tasks.removeFirst().run()
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals("the new key's picture lands", 1, thumbs.madeFromTiles)
        assertEquals(0, thumbs.pending)
    }

    @Test
    fun aBlankLayerNeverRequests() {
        val d = A9Docs.track(Document("thumbs", "thumbs", A9Docs.W, A9Docs.H))
        val l = Layer(d.newLayerId(), "Blank", keep(A9Docs.empty())).also { d.layers += it }
        ChaosResidency().park(l)
        val thumbs = LayerThumbnails(SIZE, Executor { error("no read") }, Handler(Looper.getMainLooper()))
        val placeholder = thumbs.content(l)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, thumbs.pending)
        assertSame(placeholder, thumbs.content(l))
    }

    private companion object {
        const val SIZE = 96
    }
}
