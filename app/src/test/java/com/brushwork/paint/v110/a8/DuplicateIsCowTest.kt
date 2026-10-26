package com.brushwork.paint.v110.a8

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.tiles.ClassicResidency
import com.brushwork.paint.engine.tiles.FlatPixels
import com.brushwork.paint.engine.tiles.ResidencyPolicy
import com.brushwork.paint.engine.tiles.ResidencyState
import com.brushwork.paint.engine.tiles.Tile
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.PixelFacade
import com.brushwork.paint.model.Selection
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeSwapStore
import com.brushwork.paint.v110.lead.F1Pixels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.IdentityHashMap

/**
 * v1.10 A8 step 5 (design 2.6, 2.16 "DuplicateIsCowTest"; FULL, the editor's own policy): a
 * duplicate of a PARKED layer, and of a folder of parked layers, copies every store as a
 * copy-on-write `snapshot()`: each copy has a store of its own holding the very tiles of its
 * source (content and mask, one reference more each), the TILES ledger is unchanged, nothing is
 * materialized and the source stays parked in the same store with the same generation. It is
 * ONE undo step: undo brings back the layer list and the active row exactly, redo the same copies
 * with the same pixels. The copy becomes FLAT at its activation from the shared tiles (still no
 * byte under TILES), and an undone duplicate dropped from the redo stack lets go of its tiles
 * (every reference back as it was). "Duplicate selection" of a parked layer copies the selected
 * pixels and leaves the source parked, materializing nothing.
 */
@RunWith(RobolectricTestRunner::class)
class DuplicateIsCowTest {
    @get:Rule
    val engine = EngineRule(EngineLevel.FULL, residency = ClassicResidency, swap = FakeSwapStore())

    private val made = ArrayList<Bitmap>()
    private val docs = ArrayList<Document>()
    private lateinit var baseline: A8Kit.Baseline
    private var controller: EditorController? = null
    private var scope: CoroutineScope? = null

    @Before
    fun setUp() {
        baseline = A8Kit.Baseline()
    }

    @After
    fun tearDown() {
        (EngineConfig.residency as? ResidencyPolicy)?.stop()
        A8Kit.drain()
        controller?.dispose()
        scope?.cancel()
        baseline.assertBack(docs)
        made.forEach { if (!it.isRecycled) it.recycle() }
    }

    /** An editor over [doc] (row [active] active) with its policy; every other pixel layer then parks (its own tick). */
    private fun editor(doc: Document, active: Int): EditorController {
        docs += doc
        doc.activeLayerIndex = active
        val sc = Smoke.newScope().also { scope = it }
        val c = Smoke.controller(RuntimeEnvironment.getApplication(), doc, sc).also { controller = it }
        assertNotNull("the editor installed its policy at FULL", EngineConfig.residency as? ResidencyPolicy)
        // Tiles never sent out, so the TILES ledger moves only by what a duplicate copies.
        (EngineConfig.residency as ResidencyPolicy).tileRam = { Long.MAX_VALUE }
        A8Kit.idle()
        A8Kit.advance(2600)
        A8Kit.drain()
        for (l in doc.layers) if (!l.isFolder && l !== c.activeLayer) assertEquals("${l.name} parked", ResidencyState.PARKED, l.residency)
        return c
    }

    /** [n] noisy 512 x 300 layers under a folder (every odd child masked), above one plain layer: [bg, children..., folder]. */
    private fun folderDoc(n: Int): Document {
        val d = Document("a8", "a8", 512, 300)
        d.layers += Layer(d.newLayerId(), "Background", F1Pixels.mixed(512, 300, 90L).also { made += it })
        val folder = Layer.newFolder(d.newLayerId(), "Folder")
        repeat(n) { i ->
            d.layers += Layer(d.newLayerId(), "Child $i", F1Pixels.mixed(512, 300, 91L + i).also { made += it }).apply {
                parentId = folder.id
                if (i % 2 == 1) mask = F1Pixels.mixedMask(512, 300, 191L + i).also { made += it }
            }
        }
        d.layers += folder
        return d
    }

    /** How many times each tile is held by [layers]' stores (identity), with its references now. */
    private fun holds(layers: List<Layer>): IdentityHashMap<Tile, IntArray> {
        val out = IdentityHashMap<Tile, IntArray>()
        for (l in layers) for (t in A8Kit.tilesOf(l)) out.getOrPut(t) { intArrayOf(0, t.refs) }[0]++
        return out
    }

    /** Asserts [copy]'s stores are not [source]'s but hold the same tile at every index. */
    private fun assertShares(source: Layer, copy: Layer) {
        for ((s, k) in listOf(source.pixels to copy.pixels, source.maskPixels to copy.maskPixels)) {
            if (s == null) {
                assertEquals("no mask, no mask", null, k)
                continue
            }
            val a = s as TiledPixels
            val b = k as TiledPixels
            assertNotSame("${copy.name}: a store of its own", a, b)
            assertEquals(a.tiles.size, b.tiles.size)
            for (i in a.tiles.indices) assertSame("${copy.name}: tile $i shared", a.tiles[i], b.tiles[i])
        }
    }

    private fun assertSamePixels(what: String, expected: Pair<IntArray, IntArray?>, layer: Layer) {
        val now = A8Kit.pixelsOf(layer)
        assertArrayEquals("$what: content", expected.first, now.first)
        if (expected.second == null) assertEquals("$what: no mask", null, now.second) else assertArrayEquals("$what: mask", expected.second, now.second)
    }

    @Test
    fun aDuplicateOfAParkedLayerSharesItsTilesInOneStep() {
        val c = editor(A8Kit.doc(3, made), active = 2)
        val source = c.doc.layers[1]
        val store = source.pixels
        val generation = source.pixelGeneration
        val pixels = A8Kit.pixelsOf(source)
        val held = holds(listOf(source))
        val tiles = A8Kit.tiles()
        val materialized = PixelFacade.materializations
        val steps = c.undoManager.undoCount
        val before = c.doc.layers.toList()

        val copy = checkNotNull(c.duplicateLayer(source)) { "duplicated" }

        assertShares(source, copy)
        for ((t, h) in held) assertEquals("one reference more per hold", h[1] + h[0], t.refs)
        assertEquals("no tile copied", tiles, A8Kit.tiles())
        assertEquals("nothing materialized", materialized, PixelFacade.materializations)
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertSame("the source keeps its store", store, source.pixels)
        assertEquals(ResidencyState.PARKED, source.residency)
        assertEquals("J5", generation, source.pixelGeneration)
        assertSame("the copy right above, active", copy, c.activeLayer)
        assertEquals(2, c.doc.indexOf(copy))
        assertSamePixels("the copy", pixels, copy)

        c.undo()
        assertEquals("undo: the layer list as before", before, c.doc.layers.toList())
        assertEquals("undo: the active row as before", 2, c.doc.activeLayerIndex)
        assertSame(before[2], c.activeLayer)
        assertSamePixels("the source after undo", pixels, source)

        c.redo()
        assertSame("redo: the same copy", copy, c.doc.layers[2])
        assertSame(copy, c.activeLayer)
        assertSamePixels("the copy after redo", pixels, copy)
        assertEquals("one step", steps + 1, c.undoManager.undoCount)

        // At its activation the copy becomes FLAT from the shared tiles: its snapshot is let go of.
        A8Kit.idle()
        A8Kit.drain()
        assertEquals(ResidencyState.FLAT, copy.residency)
        assertSamePixels("the flat copy", pixels, copy)
        assertEquals("the copy's tiles were the source's", tiles, A8Kit.tiles())
        for ((t, h) in held) assertEquals("the source's references alone", h[1], t.refs)
        assertEquals(ResidencyState.PARKED, source.residency)
    }

    @Test
    fun anUndoneDuplicateDroppedFromTheRedoStackLetsGoOfItsTiles() {
        val c = editor(A8Kit.doc(3, made), active = 2)
        val source = c.doc.layers[1]
        val held = holds(listOf(source))
        val tiles = A8Kit.tiles()

        val copy = checkNotNull(c.duplicateLayer(source)) { "duplicated" }
        c.undo()
        A8Kit.idle()
        assertTrue("the undone copy still holds the shared tiles", (copy.pixels as TiledPixels).occupied() > 0)
        assertEquals("the redo step holds them", tiles, A8Kit.tiles())

        // A new step drops the redo stack: the add step lets go of the copy's tiles.
        A8Kit.paint(c, c.activeLayer, Rect(10, 10, 60, 40), 0xFF224466.toInt())
        A8Kit.drain()
        assertEquals("the copy's store is empty", 0, (copy.pixels as TiledPixels).occupied())
        assertEquals("the copy's mask store is empty", 0, (copy.maskPixels as TiledPixels).occupied())
        for ((t, h) in held) assertEquals("every reference back", h[1], t.refs)
        assertEquals(ResidencyState.PARKED, source.residency)
    }

    @Test
    fun aDuplicateOfAFolderOfParkedLayersSharesEveryTileInOneStep() {
        val c = editor(folderDoc(3), active = 0)
        val folder = c.doc.layers[4]
        val children = c.doc.layers.subList(1, 4).toList()
        val pixels = children.map { A8Kit.pixelsOf(it) }
        val held = holds(children)
        val tiles = A8Kit.tiles()
        val materialized = PixelFacade.materializations
        val steps = c.undoManager.undoCount
        val before = c.doc.layers.toList()
        val parentsBefore = before.map { it.parentId }

        val top = checkNotNull(c.duplicateLayer(folder)) { "duplicated" }

        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertEquals("the block twice", 9, c.doc.layers.size)
        val after = c.doc.layers.toList()
        val copies = after.subList(5, 8)
        assertSame("the folder's copy on top", top, after[8])
        assertTrue(top.isFolder)
        for ((i, k) in copies.withIndex()) {
            assertEquals("${k.name} is in the copy", top.id, k.parentId)
            assertShares(children[i], k)
            assertSamePixels(k.name, pixels[i], k)
        }
        for ((t, h) in held) assertEquals("one reference more per hold", h[1] + h[0], t.refs)
        assertEquals("no tile copied", tiles, A8Kit.tiles())
        assertEquals("nothing materialized", materialized, PixelFacade.materializations)
        for (l in children) assertEquals(ResidencyState.PARKED, l.residency)

        c.undo()
        assertEquals("undo: the layer list as before", before, c.doc.layers.toList())
        assertEquals("undo: the parents as before", parentsBefore, c.doc.layers.map { it.parentId })
        assertSame(before[0], c.activeLayer)
        c.redo()
        assertEquals("redo: the same copies", after, c.doc.layers.toList())
        for ((i, k) in copies.withIndex()) assertSamePixels("${k.name} after redo", pixels[i], k)

        // Undone and dropped from the redo stack: the tree step lets go of the copies' tiles.
        c.undo()
        c.selectLayer(c.doc.layers[0])
        A8Kit.paint(c, c.doc.layers[0], Rect(10, 10, 60, 40), 0xFF224466.toInt())
        A8Kit.drain()
        for ((t, h) in held) assertEquals("every reference back", h[1], t.refs)
        val painted = A8Kit.tilesOfStep(A8Kit.newestStep(c)).sumOf { it.bytes.toLong() }
        assertEquals("the ledger as before, with the paint's undo tile", tiles + painted, A8Kit.tiles())
    }

    @Test
    fun duplicateSelectionOfAParkedLayerCopiesTheSelectedPixelsAndLeavesItParked() {
        val c = editor(A8Kit.doc(3, made), active = 2)
        val source = c.doc.layers[0]
        val store = source.pixels
        val pixels = A8Kit.pixelsOf(source).first
        val w = c.doc.width
        val h = c.doc.height
        val inside = Rect(40, 30, 300, 200)
        val sel = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8).also { m ->
            Canvas(m).drawRect(inside, Paint().apply { color = 0xFF000000.toInt() })
            made += m
        }
        c.setSelection(Selection.wrap(sel), recordUndo = false)
        val materialized = PixelFacade.materializations
        val tiles = A8Kit.tiles()

        val copy = checkNotNull(c.duplicateLayer(source)) { "duplicated" }

        assertTrue("a flat copy of the selected pixels", copy.pixels is FlatPixels)
        val expected = IntArray(pixels.size) { i -> if (inside.contains(i % w, i / w)) pixels[i] else 0 }
        assertArrayEquals(expected, A8Kit.pixelsOf(copy).first)
        assertSame("the source keeps its store", store, source.pixels)
        assertEquals(ResidencyState.PARKED, source.residency)
        assertEquals("nothing materialized", materialized, PixelFacade.materializations)
        assertEquals("no tile copied", tiles, A8Kit.tiles())
    }
}
