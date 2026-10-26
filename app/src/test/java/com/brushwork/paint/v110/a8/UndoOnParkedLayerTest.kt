package com.brushwork.paint.v110.a8

import android.graphics.Bitmap
import android.graphics.Rect
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.DocumentBitmapsAction
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.tiles.ClassicResidency
import com.brushwork.paint.engine.tiles.ResidencyPolicy
import com.brushwork.paint.engine.tiles.ResidencyState
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.PixelFacade
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.10 A8 step 4 (design 2.6; FULL, the editor's own policy): an undo or redo of a pixel edit of
 * a PARKED layer swaps the step's tiles into its store in place (`replaceTiles`): the layer stays
 * PARKED in the same store, nothing is materialized or copied (the TILES ledger is unchanged),
 * `pixelGeneration` stays and `contentVersion` moves, and the pixels are those before (after) the
 * edit, for the content and for a mask. Back on the layer (FLAT again), the same step swaps the
 * store's old tiles into its flat bitmap. A canvas change ([DocumentBitmapsAction]) or a mask
 * change undone after the layer was parked and made flat again brings back the store as it left,
 * never a stale bitmap with an edit already undone in it (the unit-full `VectorFuzzQaTest` case).
 */
@RunWith(RobolectricTestRunner::class)
class UndoOnParkedLayerTest {
    private val swap = FakeSwapStore()

    @get:Rule
    val engine = EngineRule(EngineLevel.FULL, residency = ClassicResidency, swap = swap)

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

    /** An editor over 3 noisy layers (layer 1 masked), the top one active, with its policy. */
    private fun editor(): EditorController {
        val doc = A8Kit.doc(3, made).also { docs += it }
        doc.activeLayerIndex = 2
        val sc = Smoke.newScope().also { scope = it }
        val c = Smoke.controller(RuntimeEnvironment.getApplication(), doc, sc).also { controller = it }
        assertNotNull("the editor installed its policy at FULL", EngineConfig.residency as? ResidencyPolicy)
        A8Kit.idle()
        return c
    }

    /** Selects another layer than [layer] and lets [layer] park (its own tick). */
    private fun parkAway(c: EditorController, layer: Layer) {
        c.selectLayer(c.doc.layers.first { it !== layer })
        A8Kit.advance(2600)
        A8Kit.drain()
        assertEquals("parked", ResidencyState.PARKED, layer.residency)
    }

    /** Selects [layer] again (its tiles resident: FLAT when selectLayer returns). */
    private fun backTo(c: EditorController, layer: Layer) {
        c.selectLayer(layer)
        A8Kit.drain()
        assertEquals("flat again", ResidencyState.FLAT, layer.residency)
    }

    /** Asserts an undo or redo ([step]) on parked [layer] leaves it parked in the same store with [expected] pixels, copying and materializing nothing. */
    private fun assertInPlace(layer: Layer, expected: IntArray, mask: Boolean, step: () -> Unit) {
        val store = if (mask) layer.maskPixels else layer.pixels
        val generation = layer.pixelGeneration
        val version = layer.contentVersion
        val materialized = PixelFacade.materializations
        val tiles = A8Kit.tiles()
        step()
        assertEquals(ResidencyState.PARKED, layer.residency)
        assertSame("the same store", store, if (mask) layer.maskPixels else layer.pixels)
        assertEquals("J5: the same store keeps the generation", generation, layer.pixelGeneration)
        assertTrue("the change is counted", layer.contentVersion > version)
        assertEquals("nothing materialized", materialized, PixelFacade.materializations)
        assertEquals("no tile copied", tiles, A8Kit.tiles())
        val now = A8Kit.pixelsOf(layer)
        assertArrayEquals(expected, if (mask) now.second else now.first)
    }

    @Test
    fun anUndoOnAParkedLayerSwapsTheStepsTilesIntoItsStore() {
        val c = editor()
        val l = c.doc.layers[2]
        val before = A8Kit.pixelsOf(l).first
        A8Kit.paint(c, l, Rect(100, 50, 400, 250), 0xFF3366AA.toInt())
        val after = A8Kit.pixelsOf(l).first
        parkAway(c, l)

        assertInPlace(l, before, mask = false) { c.undo() }
        assertInPlace(l, after, mask = false) { c.redo() }
        assertInPlace(l, before, mask = false) { c.undo() }

        // Back on the layer, the step (now holding the store's old tiles) swaps into the flat bitmap.
        backTo(c, l)
        c.redo()
        assertArrayEquals(after, A8Kit.pixelsOf(l).first)
        c.undo()
        assertArrayEquals(before, A8Kit.pixelsOf(l).first)
        assertEquals(ResidencyState.FLAT, l.residency)
    }

    @Test
    fun anUndoOfAMaskEditOnAParkedLayerSwapsInPlace() {
        val c = editor()
        val l = c.doc.layers[1]
        backTo(c, l)
        val before = A8Kit.pixelsOf(l).second!!
        A8Kit.paint(c, l, Rect(30, 20, 300, 280), 0xFF000000.toInt(), EditTarget.MASK)
        val after = A8Kit.pixelsOf(l).second!!
        parkAway(c, l)
        assertTrue(l.maskPixels !is com.brushwork.paint.engine.tiles.FlatPixels)

        assertInPlace(l, before, mask = true) { c.undo() }
        assertInPlace(l, after, mask = true) { c.redo() }
    }

    @Test
    fun aCanvasChangeUndoneAfterAParkBringsBackTheStoreAsItLeft() {
        val c = editor()
        val l = c.doc.layers[2]
        val replaced = F1Pixels.mixed(512, 300, 990L).also { made += it }
        val doc = c.doc
        val change = DocumentBitmapsAction(
            "Replace", listOf(DocumentBitmapsAction.Entry(l, l.bitmap, null, replaced, null)),
            doc.width to doc.height, doc.width to doc.height, doc.dpi, doc.dpi, doc.colorMode, doc.colorMode,
        )
        change.redo(c)
        c.pushUndo(change)
        val changed = A8Kit.pixelsOf(l).first
        A8Kit.paint(c, l, Rect(60, 40, 330, 260), 0xFF20A040.toInt())
        val painted = A8Kit.pixelsOf(l).first

        // Parked (its flat bitmap cut into tiles), then flat again in a new bitmap.
        parkAway(c, l)
        backTo(c, l)
        c.undo()
        assertArrayEquals("the edit undone", changed, A8Kit.pixelsOf(l).first)
        c.undo()
        c.redo()
        assertArrayEquals("the change redone without the undone edit", changed, A8Kit.pixelsOf(l).first)
        c.redo()
        assertArrayEquals(painted, A8Kit.pixelsOf(l).first)
        c.undo()
        assertArrayEquals(changed, A8Kit.pixelsOf(l).first)
    }

    @Test
    fun aMaskAddedThenParkedComesBackAsItLeft() {
        val c = editor()
        val l = c.doc.layers[2]
        c.addMask(l, fromSelection = false)
        val added = A8Kit.pixelsOf(l).second!!
        A8Kit.paint(c, l, Rect(60, 40, 330, 260), 0xFF000000.toInt(), EditTarget.MASK)
        val painted = A8Kit.pixelsOf(l).second!!

        parkAway(c, l)
        backTo(c, l)
        c.undo()
        assertArrayEquals("the mask edit undone", added, A8Kit.pixelsOf(l).second)
        c.undo()
        assertTrue("the mask is gone", !l.hasMask)
        c.redo()
        assertArrayEquals("the mask back without the undone edit", added, A8Kit.pixelsOf(l).second)
        c.redo()
        assertArrayEquals(painted, A8Kit.pixelsOf(l).second)
    }

    @Test
    fun anUndoOfAChangeThatPutsATiledStoreBackIntoTheActiveLayerMakesItResident() {
        val c = editor()
        val l = c.doc.layers[1]
        val doc = c.doc
        // l parked, then its parked store exchanged out by a change and back in by its undo while l is active.
        parkAway(c, l)
        val parked = l.pixels
        val replaced = F1Pixels.mixed(512, 300, 991L).also { made += it }
        val change = DocumentBitmapsAction(
            "Replace", listOf(DocumentBitmapsAction.Entry(l, parked, l.maskPixels, com.brushwork.paint.engine.tiles.FlatPixels(replaced), l.maskPixels)),
            doc.width to doc.height, doc.width to doc.height, doc.dpi, doc.dpi, doc.colorMode, doc.colorMode,
        )
        val content = A8Kit.pixelsOf(l).first
        change.redo(c)
        c.pushUndo(change)
        c.selectLayer(l)
        A8Kit.drain()
        assertEquals(ResidencyState.FLAT, l.residency)
        c.undo()
        A8Kit.drain()
        assertEquals("the active layer is resident after the undo", ResidencyState.FLAT, l.residency)
        assertArrayEquals(content, A8Kit.pixelsOf(l).first)
    }
}
