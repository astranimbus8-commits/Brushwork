package com.brushwork.paint.v110.g2open

import android.graphics.Bitmap
import android.graphics.Rect
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.tiles.ClassicResidency
import com.brushwork.paint.engine.tiles.ResidencyPolicy
import com.brushwork.paint.engine.tiles.TileOps
import com.brushwork.paint.engine.tiles.TileState
import com.brushwork.paint.engine.tiles.TileWorker
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeSwapStore
import com.brushwork.paint.ui.layers.LayerOps
import com.brushwork.paint.v110.a8.A8Kit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.10 Gate 2 (lane open, G2-O-a; FULL with a [FakeSwapStore] that takes 2 ms a read): an undo
 * of the active layer whose step holds SWAPPED tiles waits for its tiles; "Add mask" asked
 * meanwhile, through `EditorController.addMask` and through the Layers panel's
 * `LayerOps.addMask` (a disabled mask: mask and props in one step), settles the undo first. When
 * the call returns, the undo has applied, the mask is in the layer, and the history holds the
 * undo first, then the mask: undo takes the mask away with the undone pixels kept, redo puts it
 * back. Gate 2 review: with the undo of an Add mask queued behind such a waiting undo (the mask
 * still in the layer), Delete, Apply and Invert mask (the Layers panel's, and the controller's
 * Invert) look at the layer only once both undos applied: no mask is left, nothing changes, no
 * step is added and redo still brings the mask back.
 */
@RunWith(RobolectricTestRunner::class)
class G2OpenMaskWhileUndoWaitsTest {
    private val swap = FakeSwapStore(latencyMs = 2L)

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

    private fun editor(): EditorController {
        val doc = A8Kit.doc(3, made).also { docs += it }
        doc.activeLayerIndex = 2
        val sc = Smoke.newScope().also { scope = it }
        val c = Smoke.controller(RuntimeEnvironment.getApplication(), doc, sc).also { controller = it }
        A8Kit.idle()
        return c
    }

    /** Paints [l], sends the stroke's tiles to the swap and undoes it: the undo waits. Returns the pixels before the stroke. */
    private fun undoWaiting(c: EditorController, l: Layer): IntArray {
        val before = A8Kit.pixelsOf(l).first
        A8Kit.paint(c, l, Rect(100, 50, 400, 250), 0xFF3366AA.toInt())
        val tiles = A8Kit.tilesOfStep(A8Kit.newestStep(c))
        TileOps.nextEpoch()
        for (t in tiles) assertTrue("queued", TileWorker.writeOut(t))
        A8Kit.drain()
        assertTrue("swapped", tiles.all { it.state == TileState.SWAPPED })
        c.undo()
        assertSame("the undo waits for its tiles", l, c.activating)
        return before
    }

    private fun assertMaskAfterTheUndo(c: EditorController, l: Layer, before: IntArray, stepsBeforeThePaint: Int) {
        assertNull("the undo applied first", c.activating)
        assertArrayEquals("the undo's pixels", before, A8Kit.pixelsOf(l).first)
        assertTrue("the mask is in the layer", l.hasMask)
        assertTrue("the mask shows the whole layer", A8Kit.pixelsOf(l).second!!.all { it == -1 })
        assertTrue(l.maskEnabled)
        assertEquals("the paint undone, then the mask", stepsBeforeThePaint + 1, c.undoManager.undoCount)
        assertEquals("Add mask", A8Kit.newestStep(c).label)
        assertFalse("the mask's step cleared the redo side", c.undoManager.canRedo)

        c.undo()
        A8Kit.drain()
        assertFalse("undo takes the mask away", l.hasMask)
        assertArrayEquals("and keeps the undone pixels", before, A8Kit.pixelsOf(l).first)
        c.redo()
        A8Kit.drain()
        assertTrue("redo puts it back", l.hasMask)
        assertTrue(l.maskEnabled)
        assertArrayEquals(before, A8Kit.pixelsOf(l).first)
    }

    @Test
    fun addMaskWhileAnUndoWaitsLandsAfterIt() {
        val c = editor()
        val l = c.doc.layers[2]
        val steps = c.undoManager.undoCount
        val before = undoWaiting(c, l)
        c.addMask(l, fromSelection = false)
        assertMaskAfterTheUndo(c, l, before, steps)
    }

    @Test
    fun theLayersPanelsAddMaskWhileAnUndoWaitsLandsAfterIt() {
        val c = editor()
        val l = c.doc.layers[2]
        l.maskEnabled = false
        val steps = c.undoManager.undoCount
        val before = undoWaiting(c, l)
        LayerOps.addMask(c, l, fromSelection = false)
        assertMaskAfterTheUndo(c, l, before, steps)
    }

    /**
     * Gate 2 review: [l] gets a mask ("Add mask"), then its pixels are painted, the stroke's tiles
     * go to the swap and undo is asked twice: the paint's undo waits for its tiles and the Add
     * mask's undo is queued behind it, so the layer still holds the mask (the panel still offers
     * Delete, Apply and Invert mask). Returns the pixels before the paint.
     */
    private fun maskUndoQueued(c: EditorController, l: Layer): IntArray {
        c.addMask(l, fromSelection = false)
        c.setEditingMask(l, false)
        A8Kit.drain()
        assertEquals("Add mask", A8Kit.newestStep(c).label)
        val before = undoWaiting(c, l)
        c.undo()
        assertTrue("the Add mask's undo is queued: the mask is still in the layer", l.hasMask)
        return before
    }

    /**
     * Both undos applied before the mask operation looked at the layer: no mask is left, so it
     * changed nothing and added no step, and the redo side still holds the Add mask and the paint.
     */
    private fun assertBothUndosAndNothingElse(c: EditorController, l: Layer, before: IntArray, stepsBeforeTheMask: Int) {
        assertNull("the paint's undo applied", c.activating)
        assertArrayEquals("the undone pixels", before, A8Kit.pixelsOf(l).first)
        assertFalse("the Add mask's undo took the mask away", l.hasMask)
        assertEquals("no step was added", stepsBeforeTheMask, c.undoManager.undoCount)
        assertEquals("the redo side still holds the Add mask", "Add mask", c.undoManager.redoLabel)
        c.redo()
        A8Kit.drain()
        assertTrue("redo puts the mask back", l.hasMask)
        assertTrue("the mask as it was added", A8Kit.pixelsOf(l).second!!.all { it == -1 })
        assertArrayEquals(before, A8Kit.pixelsOf(l).first)
        assertEquals("the paint still to redo", "Paint", c.undoManager.redoLabel)
    }

    @Test
    fun deleteMaskWhileTheUndoOfAddMaskWaitsAddsNoStep() {
        val c = editor()
        val l = c.doc.layers[2]
        val steps = c.undoManager.undoCount
        val before = maskUndoQueued(c, l)
        LayerOps.deleteMask(c, l)
        assertBothUndosAndNothingElse(c, l, before, steps)
    }

    @Test
    fun applyMaskWhileTheUndoOfAddMaskWaitsBakesNothingIn() {
        val c = editor()
        val l = c.doc.layers[2]
        val steps = c.undoManager.undoCount
        val before = maskUndoQueued(c, l)
        LayerOps.applyMask(c, l)
        assertBothUndosAndNothingElse(c, l, before, steps)
    }

    @Test
    fun invertMaskWhileTheUndoOfAddMaskWaitsInvertsNothing() {
        val c = editor()
        val l = c.doc.layers[2]
        val steps = c.undoManager.undoCount
        val before = maskUndoQueued(c, l)
        LayerOps.invertMask(c, l)
        assertBothUndosAndNothingElse(c, l, before, steps)
    }

    @Test
    fun theControllersInvertMaskWhileTheUndoOfAddMaskWaitsInvertsNothing() {
        val c = editor()
        val l = c.doc.layers[2]
        val steps = c.undoManager.undoCount
        val before = maskUndoQueued(c, l)
        c.invertMask(l)
        assertBothUndosAndNothingElse(c, l, before, steps)
    }
}
