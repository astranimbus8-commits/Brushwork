package com.brushwork.paint.v110.a9

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.graphics.asAndroidBitmap
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.budget.Budgets
import com.brushwork.paint.engine.tiles.BandAssembler
import com.brushwork.paint.engine.tiles.ChaosResidency
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.FolderSpec
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.model.PixelFacade
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeMemoryProbe
import com.brushwork.paint.testing.FakeSwapStore
import com.brushwork.paint.testing.MemoryPreset
import com.brushwork.paint.ui.layers.FolderThumbnails
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * v1.10 A9 (the lead's gap "FolderThumbnails renders folder blocks synchronously on main"): a
 * folder's picture never reads a tile on main. Tiles that are not resident are read off main under
 * pins and composited later with the copies lent (`BandAssembler.lend`); a block over
 * `FolderThumbnails.readLimit` keeps its last picture. The ledger limit here is far above any
 * test's need: the JVM's ledger is shared by every test class of the fork, and a sibling that drops
 * tiles without giving their bytes back (`FakeSwapStore.swapOut`) must not take the band's room.
 */
@RunWith(RobolectricTestRunner::class)
class FolderThumbnailReadsTest {
    private val swap = FakeSwapStore()

    @get:Rule
    val engine = EngineRule(
        EngineLevel.FULL,
        memory = FakeMemoryProbe(MemoryPreset.T606),
        budgets = Budgets(javaMax = 512L shl 20, nativeBudget = 1L shl 40, known = true, stepDown = 0),
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
                if (tile.solidColor == null) A9Docs.swapOut(swap, tile)
            }
        }
    }

    private fun realWorker(): ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "folder-thumb-test-worker").apply { isDaemon = true } }.also { threads = it }

    /**
     * A folder's picture never reads on main: with its children parked and swapped out, the tiles
     * are read off main under pins and composited later with the copies lent; the picture equals
     * the one made while flat, the pins are closed and no tile is made resident. A block over the
     * read limit keeps its last picture and reads nothing.
     */
    @Test
    fun aFolderOfSwappedLayersIsMadeFromReadsOffMain() {
        val d = A9Docs.track(Document("thumbs", "thumbs", A9Docs.W, A9Docs.H))
        val folder = Layer.newFolder(d.newLayerId(), "Folder", FolderSpec(passThrough = false))
        val a = layer(d, masked = false).apply { parentId = folder.id }
        val b = layer(d, masked = true).apply { parentId = folder.id; opacity = 0.7f; blendMode = LayerBlendMode.MULTIPLY }
        d.layers += a
        d.layers += b
        d.layers += folder
        val compositor = Compositor(d) { null }
        val flat = FolderThumbnails()
        assertTrue("flat: rendered at once", flat.render(compositor, d, folder))
        val expected = keep(flat.image(folder.id)!!.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false))
        parkAndSwap(a)
        parkAndSwap(b)
        val main = Thread.currentThread().name
        val thumbs = FolderThumbnails(worker = realWorker(), main = Handler(Looper.getMainLooper()))
        val mats = PixelFacade.materializations
        PixelFacade.strict = true
        try {
            assertFalse("the reads go off main first", thumbs.render(compositor, d, folder))
            assertEquals(1, thumbs.pending)
            assertFalse("asked again while reading: nothing more", thumbs.render(compositor, d, folder))
            val until = System.currentTimeMillis() + 30_000L
            while (thumbs.pending > 0) {
                shadowOf(Looper.getMainLooper()).idle()
                if (thumbs.pending == 0) break
                assertTrue("the reads complete", System.currentTimeMillis() < until)
                Thread.sleep(1)
            }
            val ledger = EngineConfig.budget.ledger
            assertEquals("the picture landed (last failure: ${thumbs.lastFailure}; ledger ${ledger.native shr 20} MiB of ${ledger.limit shr 20} MiB)", 1, thumbs.landedAfterReads)
            A9Docs.assertBitEqual("the folder's picture from reads off main", expected, thumbs.image(folder.id)!!.asAndroidBitmap())
            assertTrue("current now", thumbs.stale(d.layers, d.layers.mapTo(HashSet()) { it.id }).isEmpty())
        } finally {
            PixelFacade.strict = false
        }
        assertEquals("nothing materialized", mats, PixelFacade.materializations)
        assertTrue("no read on main (${swap.readThreads.toSet()})", swap.readThreads.isNotEmpty() && swap.readThreads.none { it == main })
        assertEquals("the pins are closed", 0, a.pins + b.pins)
        for (l in listOf(a, b)) for (store in listOf(l.pixels, l.maskPixels)) {
            val t = store as? TiledPixels ?: continue
            for (row in 0 until t.rows) for (col in 0 until t.cols) {
                val tile = t.tileAt(col, row) ?: continue
                if (tile.solidColor == null) assertTrue("a tile is never made resident", !BandAssembler.isResident(tile))
            }
        }
        // Over the limit: the last picture stays and nothing is read.
        val reads = swap.readThreads.size
        val picture = thumbs.image(folder.id)
        b.markChanged()
        thumbs.readLimit = 1L
        assertFalse(thumbs.render(compositor, d, folder))
        assertEquals("nothing is read", 0, thumbs.pending)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(reads, swap.readThreads.size)
        assertSame("the last picture stays", picture, thumbs.image(folder.id))
        assertEquals(0, a.pins + b.pins)
    }

    /**
     * Review 7/7: while a folder's tiles are read off main, every layer its picture draws is
     * pinned, not only the ones read: a sibling with resident tiles is then never packed or swapped
     * out (Tile rule (2)) and a flat sibling never parked before the composite on main, which
     * would otherwise read on main under the same key. Every pin closes when the picture lands.
     */
    @Test
    fun everyLayerThePictureDrawsIsPinnedWhileTheReadsRun() {
        val d = A9Docs.track(Document("pins", "pins", A9Docs.W, A9Docs.H))
        val folder = Layer.newFolder(d.newLayerId(), "Folder", FolderSpec(passThrough = false))
        val resident = layer(d, masked = true).apply { parentId = folder.id }
        val swapped = layer(d, masked = false).apply { parentId = folder.id; opacity = 0.8f; blendMode = LayerBlendMode.MULTIPLY }
        val flat = layer(d, masked = false).apply { parentId = folder.id; opacity = 0.6f; blendMode = LayerBlendMode.SCREEN }
        d.layers += resident
        d.layers += swapped
        d.layers += flat
        d.layers += folder
        val compositor = Compositor(d) { null }
        val first = FolderThumbnails()
        assertTrue("flat: rendered at once", first.render(compositor, d, folder))
        val expected = keep(first.image(folder.id)!!.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false))
        assertTrue(ChaosResidency().park(resident))
        parkAndSwap(swapped)
        assertTrue("the resident sibling holds tiles", (resident.pixels as TiledPixels).tiles.any { it != null && BandAssembler.isResident(it) })
        val gate = CountDownLatch(1)
        val pool = realWorker()
        val thumbs = FolderThumbnails(worker = Executor { r -> pool.execute { gate.await(30, TimeUnit.SECONDS); r.run() } }, main = Handler(Looper.getMainLooper()))
        try {
            assertFalse("the reads go off main first", thumbs.render(compositor, d, folder))
            assertEquals(1, thumbs.pending)
            assertTrue("the layer with resident tiles is pinned while the reads run", resident.pins > 0)
            assertTrue("the layer read is pinned", swapped.pins > 0)
            assertTrue("the flat layer is pinned", flat.pins > 0)
            assertFalse("so it is never parked meanwhile", ChaosResidency().park(flat))
        } finally {
            gate.countDown()
        }
        val until = System.currentTimeMillis() + 30_000L
        while (thumbs.pending > 0) {
            shadowOf(Looper.getMainLooper()).idle()
            if (thumbs.pending == 0) break
            assertTrue("the reads complete", System.currentTimeMillis() < until)
            Thread.sleep(1)
        }
        assertEquals("the picture landed (last failure: ${thumbs.lastFailure})", 1, thumbs.landedAfterReads)
        A9Docs.assertBitEqual("the folder's picture", expected, thumbs.image(folder.id)!!.asAndroidBitmap())
        assertEquals("every pin is closed", 0, resident.pins + swapped.pins + flat.pins)
        assertTrue("the flat layer parks again once the picture landed", ChaosResidency().park(flat))
    }
}
