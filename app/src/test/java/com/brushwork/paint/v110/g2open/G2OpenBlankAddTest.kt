package com.brushwork.paint.v110.g2open

import android.graphics.Color
import android.os.Looper
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.LayerStructure
import com.brushwork.paint.engine.tiles.BlankPixels
import com.brushwork.paint.engine.tiles.ResidencyState
import com.brushwork.paint.masks.AdjustmentEffects
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.PixelFacade
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ToolPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * v1.10 Gate 2 (lane open, O5): Add layer, a vector layer and an adjustment layer build their new
 * layer as `BlankPixels` directly, so the insert's blank safety net (`LayerStructure`, region
 * `v110-A7 blank`) never scans a fresh layer-sized bitmap on main for them; a layer made with
 * content still goes through it (the seam counts). Each add is one step, undo and redo put the
 * same blank layer back, nothing is materialized until a tool paints, and the first stroke makes
 * the layer flat in one step that undo takes back. No engine rule: it runs at the suite's level
 * (Classic, and FULL under `-PunitFull`).
 */
@RunWith(RobolectricTestRunner::class)
class G2OpenBlankAddTest {
    private var controller: EditorController? = null
    private var scope: CoroutineScope? = null

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @After
    fun tearDown() {
        controller?.dispose()
        scope?.cancel()
        idle()
    }

    /** A 512² document with one painted layer, active. */
    private fun open(): Pair<EditorController, Document> {
        val doc = Smoke.document(SIZE, SIZE, layers = 1)
        doc.layers[0].bitmap.eraseColor(BOTTOM)
        val s = Smoke.newScope().also { scope = it }
        val c = Smoke.controller(RuntimeEnvironment.getApplication(), doc, s).also { controller = it }
        idle()
        return c to doc
    }

    private fun assertBlank(layer: Layer) {
        assertTrue("${layer.name} is blank: ${layer.pixels}", layer.pixels is BlankPixels)
        assertEquals(ResidencyState.BLANK, layer.residency)
        assertEquals(0L, layer.pixels.residentBytes())
        assertEquals(SIZE, layer.width)
        assertEquals(SIZE, layer.height)
    }

    private fun stroke(c: EditorController) {
        c.pointerDown(ToolPoint(40f, 40f))
        for (i in 1..12) c.pointerMove(ToolPoint(40f + 14f * i, 40f + 10f * i))
        c.pointerUp(ToolPoint(208f, 160f))
        idle()
    }

    @Test
    fun theAddPathsMakeBlankLayersWithoutTheScan() {
        val (c, doc) = open()
        val scans = LayerStructure.blankScans
        val made = PixelFacade.materializations
        val steps = c.undoManager.undoCount

        val layer = c.addLayer()!!
        idle()
        assertBlank(layer)
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        val vector = c.addVectorLayer()!!
        idle()
        assertTrue(vector.isVectorLayer)
        assertBlank(vector)
        val adjustment = c.addAdjustmentLayer(AdjustmentEffects.defaultSpec(drawingColor = c.color), null)!!
        idle()
        assertTrue(adjustment.isAdjustmentLayer)
        assertBlank(adjustment)
        assertEquals("one step each", steps + 3, c.undoManager.undoCount)
        assertEquals("no add path scanned its new layer", scans, LayerStructure.blankScans)
        assertEquals("nothing materialized them", made, PixelFacade.materializations)

        // A layer made with content still meets the safety net (the seam counts a scan).
        val nothing = c.addLayerWithContent("Nothing", "Add nothing") { }!!
        idle()
        assertEquals("the content path still scans", scans + 1, LayerStructure.blankScans)
        assertBlank(nothing)

        val added = listOf(layer, vector, adjustment, nothing)
        repeat(added.size) { c.undo(); idle() }
        assertEquals("every add undone", 1, doc.layers.size)
        repeat(added.size) { c.redo(); idle() }
        assertEquals("every add redone", added, doc.layers.drop(1))
        added.forEach { assertBlank(it) }
        assertEquals("undo and redo scanned nothing", scans + 1, LayerStructure.blankScans)

        // The first stroke on the new blank layer makes it flat, one step; undo takes it back.
        c.selectLayer(layer)
        idle()
        assertSame(layer, c.activeLayer)
        val before = c.undoManager.undoCount
        stroke(c)
        assertEquals("one step", before + 1, c.undoManager.undoCount)
        assertTrue(layer.contentVersion > 0L)
        assertTrue("the stroke painted", Color.alpha(layer.bitmap.getPixel(124, 100)) > 0)
        assertEquals("the layer below unchanged", BOTTOM, doc.layers[0].bitmap.getPixel(124, 100))
        c.undo()
        idle()
        assertTrue("undo leaves it transparent", layer.pixels.isEmpty())
    }

    private companion object {
        const val SIZE = 512
        const val BOTTOM = 0xFF336699.toInt()
    }
}
