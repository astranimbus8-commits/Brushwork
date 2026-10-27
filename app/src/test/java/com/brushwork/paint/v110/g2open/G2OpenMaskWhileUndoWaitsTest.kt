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
 * back.
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
}
