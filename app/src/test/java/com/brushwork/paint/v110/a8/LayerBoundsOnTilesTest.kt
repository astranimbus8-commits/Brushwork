package com.brushwork.paint.v110.a8

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.budget.LedgerCategory
import com.brushwork.paint.engine.tiles.BlankPixels
import com.brushwork.paint.engine.tiles.ClassicResidency
import com.brushwork.paint.engine.tiles.ResidencyPolicy
import com.brushwork.paint.engine.tiles.ResidencyState
import com.brushwork.paint.engine.tiles.TileOps
import com.brushwork.paint.engine.tiles.TileState
import com.brushwork.paint.engine.tiles.TileWorker
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.PixelFacade
import com.brushwork.paint.snap.DetectedLine
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeSwapStore
import com.brushwork.paint.tools.transform.LayerBoundsCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 A8 step 5 (design 2.2 "occupiedBounds and pins", J5, I28): the smart-guide cache over
 * parked layers. A parked layer larger than `SYNC_PIXELS` gets its bounds at once from its
 * occupied tiles refined by its edge tiles, and its lines in the background, equal to what its
 * FLAT self gives (content and mask); a smaller one the same from a copy of its tiles. Nothing is
 * materialized, no tile changes state (a swapped one is read where it is and stays swapped, only
 * off main: a layer with one is read in the background from a snapshot, 7/7), and the TRANSIENT
 * bytes of the copies come back. An entry follows the content version and the pixel
 * generation, not a bitmap: a park, an unpark or a materialization keeps it, a replacement or a
 * mask turned off does not. A blank layer has nothing to snap to and is not materialized.
 */
@RunWith(RobolectricTestRunner::class)
class LayerBoundsOnTilesTest {
    private val swap = FakeSwapStore()

    @get:Rule
    val engine = EngineRule(EngineLevel.FULL, residency = ClassicResidency, swap = swap)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val made = ArrayList<Bitmap>()
    private val docs = ArrayList<Document>()
    private lateinit var baseline: A8Kit.Baseline

    @Before
    fun setUp() {
        baseline = A8Kit.Baseline()
    }

    @After
    fun tearDown() {
        scope.cancel()
        (EngineConfig.residency as? ResidencyPolicy)?.stop()
        A8Kit.drain()
        baseline.assertBack(docs)
        made.forEach { if (!it.isRecycled) it.recycle() }
    }

    /** Runs the main looper (and gives the background scan real time) until [done]. */
    private fun waitUntil(done: () -> Boolean) {
        repeat(4000) {
            if (done()) return
            A8Kit.idle()
            Thread.sleep(5)
        }
        A8Kit.idle()
        assertTrue("timed out", done())
    }

    private val black = Paint().apply { color = 0xFF000000.toInt() }

    /** A box of black lines (a frame and a cross) on a transparent [w] x [h] bitmap. */
    private fun frame(w: Int, h: Int): Bitmap = BitmapUtils.createLayerBitmap(w, h).also { b ->
        made += b
        val c = Canvas(b)
        c.drawRect(40f, 30f, w - 40f, 34f, black)
        c.drawRect(40f, h - 34f, w - 40f, h - 30f, black)
        c.drawRect(40f, 30f, 44f, h - 30f, black)
        c.drawRect(w - 44f, 30f, w - 40f, h - 30f, black)
        c.drawRect(w / 2f - 2f, 30f, w / 2f + 2f, h - 30f, black)
        c.drawRect(40f, h / 2f - 2f, w - 40f, h / 2f + 2f, black)
    }

    /** Three dots, each in another tile of an 800 x 400 grid: bounds (5, 10) - (702, 382). */
    private fun dots(): Bitmap = BitmapUtils.createLayerBitmap(800, 400).also { b ->
        made += b
        val c = Canvas(b)
        c.drawRect(5f, 200f, 7f, 202f, black)
        c.drawRect(700f, 10f, 702f, 12f, black)
        c.drawRect(300f, 380f, 302f, 382f, black)
    }

    /** A mask of [w] x [h] that hides the band of rows [top] until [bottom]. */
    private fun bandMask(w: Int, h: Int, top: Float, bottom: Float): Bitmap =
        BitmapUtils.createMaskBitmap(w, h).also { m ->
            made += m
            Canvas(m).drawRect(0f, top, w.toFloat(), bottom, black)
        }

    /** A [w] x [h] document of a frame, a masked frame (rows 0 - 60 hidden), the dots when 800 x 400, and an active plain layer. */
    private fun doc(w: Int, h: Int): Document {
        val d = Document("a8", "a8", w, h).also { docs += it }
        d.layers += Layer(d.newLayerId(), "Frame", frame(w, h))
        d.layers += Layer(d.newLayerId(), "Masked", frame(w, h)).apply { mask = bandMask(w, h, 0f, 60f) }
        if (w == 800) d.layers += Layer(d.newLayerId(), "Dots", dots())
        d.layers += Layer(d.newLayerId(), "Active", BitmapUtils.createLayerBitmap(w, h).also { made += it })
        d.activeLayerIndex = d.layers.size - 1
        return d
    }

    private class Known(val bounds: List<Rect?>, val lines: List<List<DetectedLine>>)

    /** What a cache over [layers] gives once it is done. */
    private fun scan(cache: LayerBoundsCache, layers: List<Layer>): Known {
        cache.request(layers)
        waitUntil { !cache.isBusy }
        layers.forEach { assertTrue("${it.name} known", cache.isKnown(it)) }
        return Known(layers.map { cache.bounds(it) }, layers.map { l -> cache.lines(l).sortedWith(compareBy({ it.axis }, { it.pos }, { it.start })) })
    }

    /** Parks every layer of [d] but the active one (its policy's own rule, 2 s after it was left). */
    private fun parkAllButActive(d: Document): List<Layer> {
        val (p, _) = A8Kit.policy(d)
        A8Kit.idle()
        p.idleTick()
        A8Kit.advance(5000)
        p.idleTick()
        A8Kit.drain()
        val parked = d.layers.filter { it !== d.activeLayer }
        parked.forEach { assertEquals("${it.name} parked", ResidencyState.PARKED, it.residency) }
        return parked
    }

    private fun transient(): Long = EngineConfig.budget.ledger.of(LedgerCategory.TRANSIENT)

    @Test
    fun aLargeParkedLayerGetsItsFlatBoundsAtOnceAndItsLinesFromItsTiles() {
        val d = doc(800, 400)
        assertTrue(800L * 400 > LayerBoundsCache.SYNC_PIXELS)
        val layers = d.layers.take(3)
        val flat = scan(LayerBoundsCache(scope, detectLines = true) {}, layers)
        assertEquals(Rect(5, 10, 702, 382), flat.bounds[2])
        assertEquals("the mask trims the masked frame", 60, flat.bounds[1]!!.top)
        assertTrue("the frame has lines", flat.lines[0].size >= 4)

        parkAllButActive(d)
        val materialized = PixelFacade.materializations
        val states = layers.map { l -> A8Kit.tilesOf(l).map { it.state } }
        val tiles = A8Kit.tiles()
        val transient = transient()
        val cache = LayerBoundsCache(scope, detectLines = true) {}
        cache.request(layers)
        assertEquals("the bounds at once", flat.bounds, layers.map { cache.bounds(it) })
        val parked = scan(cache, layers)
        assertEquals(flat.bounds, parked.bounds)
        assertEquals("the lines of the FLAT layers", flat.lines, parked.lines)

        assertEquals("nothing materialized", materialized, PixelFacade.materializations)
        layers.forEach { assertEquals(ResidencyState.PARKED, it.residency) }
        assertEquals("no tile changed state", states, layers.map { l -> A8Kit.tilesOf(l).map { it.state } })
        assertEquals("no tile made resident or copied", tiles, A8Kit.tiles())
        assertEquals("the copies' TRANSIENT bytes are back", transient, transient())
    }

    @Test
    fun aSwappedEdgeTileIsReadWhereItIsAndStaysSwapped() {
        val d = doc(800, 400)
        val masked = d.layers[1]
        val flat = scan(LayerBoundsCache(scope, detectLines = true) {}, listOf(masked))
        parkAllButActive(d)
        val movable = A8Kit.tilesOf(masked).filter { it.solid == null }
        assertTrue(movable.isNotEmpty())
        TileOps.nextEpoch()
        movable.forEach { assertTrue("queued", TileWorker.writeOut(it)) }
        A8Kit.drain()
        assertTrue(movable.all { it.state == TileState.SWAPPED })
        val reads = swap.reads
        val transient = transient()

        val cache = LayerBoundsCache(scope, detectLines = true) {}
        cache.request(listOf(masked))
        assertFalse("not read on main: known only from the background", cache.isKnown(masked))
        assertEquals("nothing read yet", reads, swap.reads)
        val parked = scan(cache, listOf(masked))
        assertEquals(flat.bounds, parked.bounds)
        assertEquals(flat.lines, parked.lines)
        assertTrue("read from the swap", swap.reads > reads)
        val threads = swap.readThreads.drop(reads)
        assertFalse("no slot read on main: $threads", threads.contains(mainName))
        assertTrue("still swapped: ${movable.map { it.state }}", movable.all { it.state == TileState.SWAPPED })
        assertEquals(ResidencyState.PARKED, masked.residency)
        assertEquals("every pin is let go of", 0, masked.pins)
        assertEquals("the copies' TRANSIENT bytes are back", transient, transient())
    }

    /** v1.10 A8 (7/7): a small parked layer with a SWAPPED tile is read off main too, from a snapshot, exactly. */
    @Test
    fun aSmallParkedLayerWithASwappedTileIsReadOffMain() {
        val d = doc(400, 300)
        assertTrue(400L * 300 <= LayerBoundsCache.SYNC_PIXELS)
        val layers = d.layers.take(2)
        val flat = scan(LayerBoundsCache(scope, detectLines = true) {}, layers)
        parkAllButActive(d)
        val movable = layers.flatMap { A8Kit.tilesOf(it) }.filter { it.solid == null }
        assertTrue(movable.isNotEmpty())
        TileOps.nextEpoch()
        movable.forEach { assertTrue("queued", TileWorker.writeOut(it)) }
        A8Kit.drain()
        assertTrue(movable.all { it.state == TileState.SWAPPED })
        val reads = swap.reads
        val materialized = PixelFacade.materializations

        val parked = scan(LayerBoundsCache(scope, detectLines = true) {}, layers)
        assertEquals(flat.bounds, parked.bounds)
        assertEquals(flat.lines, parked.lines)
        val threads = swap.readThreads.drop(reads)
        assertTrue("read from the swap", threads.isNotEmpty())
        assertFalse("no slot read on main: $threads", threads.contains(mainName))
        assertTrue("still swapped", movable.all { it.state == TileState.SWAPPED })
        assertEquals(materialized, PixelFacade.materializations)
        layers.forEach { assertEquals(0, it.pins) }
    }

    private val mainName: String get() = android.os.Looper.getMainLooper().thread.name

    @Test
    fun aSmallParkedLayerIsReadFromACopyOfItsTiles() {
        val d = doc(400, 300)
        assertTrue(400L * 300 <= LayerBoundsCache.SYNC_PIXELS)
        val layers = d.layers.take(2)
        val flat = scan(LayerBoundsCache(scope, detectLines = true) {}, layers)
        parkAllButActive(d)
        val materialized = PixelFacade.materializations
        val transient = transient()
        val cache = LayerBoundsCache(scope, detectLines = true) {}
        cache.request(layers)
        assertEquals("the bounds at once (a tiny layer)", flat.bounds, layers.map { cache.bounds(it) })
        val parked = scan(cache, layers)
        assertEquals(flat.lines, parked.lines)
        assertEquals(materialized, PixelFacade.materializations)
        layers.forEach { assertEquals(ResidencyState.PARKED, it.residency) }
        assertEquals(transient, transient())
    }

    @Test
    fun anEntryFollowsTheContentVersionAndGenerationNotTheBitmap() {
        val d = doc(800, 400)
        val masked = d.layers[1]
        var scans = 0
        val cache = LayerBoundsCache(scope, detectLines = true) {}
        val find = cache.findLines
        cache.findLines = { b, r, m, c -> scans++; find(b, r, m, c) }
        val flat = scan(cache, listOf(masked))
        assertEquals(1, scans)

        // Parked, then FLAT again (a new bitmap), then materialized by the getter: the same content (J5).
        parkAllButActive(d)
        assertTrue("known across the park", cache.isKnown(masked))
        d.activeLayerIndex = 1
        A8Kit.idle()
        A8Kit.drain()
        assertEquals(ResidencyState.FLAT, masked.residency)
        assertTrue("known across the unpark", cache.isKnown(masked))
        cache.request(listOf(masked))
        assertFalse(cache.isBusy)
        assertEquals("not scanned again", 1, scans)
        assertEquals(flat.bounds[0], cache.bounds(masked))

        // The mask turned off: what applies changed.
        masked.maskEnabled = false
        assertFalse(cache.isKnown(masked))
        masked.maskEnabled = true
        assertTrue(cache.isKnown(masked))

        // Another bitmap: a new generation, scanned again.
        masked.bitmap = frame(800, 400)
        assertFalse("a replacement is new content", cache.isKnown(masked))
        scan(cache, listOf(masked))
        assertEquals(2, scans)
    }

    @Test
    fun aBlankLayerHasNothingToSnapToAndIsNotMaterialized() {
        val d = Document("a8", "a8", 800, 400).also { docs += it }
        val blank = Layer(d.newLayerId(), "Blank", BlankPixels(800, 400))
        d.layers += blank
        val materialized = PixelFacade.materializations
        val cache = LayerBoundsCache(scope, detectLines = true) {}
        cache.request(listOf(blank))
        assertFalse(cache.isBusy)
        assertTrue(cache.isKnown(blank))
        assertNull(cache.bounds(blank))
        assertTrue(cache.lines(blank).isEmpty())
        assertEquals(materialized, PixelFacade.materializations)
        assertNotNull(blank.pixels as? BlankPixels)
    }
}
