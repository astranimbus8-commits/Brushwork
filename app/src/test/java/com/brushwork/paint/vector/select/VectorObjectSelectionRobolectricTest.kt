package com.brushwork.paint.vector.select

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.brushwork.paint.model.SelectionMode
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.select.LassoTool
import com.brushwork.paint.tools.select.MarqueeTool
import com.brushwork.paint.tools.select.SelectionJobs
import com.brushwork.paint.vector.VectorOps
import com.brushwork.paint.vector.lift.VectorLift
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.5 A2 (§4.9 Lasso / Select shape rows): on a vector layer an area selects the objects it
 * touches (New / Add / Subtract / Intersect) — no pixel selection, no undo step — shown with
 * dashed boxes; the selection follows the layer's life.
 */
@RunWith(RobolectricTestRunner::class)
class VectorObjectSelectionRobolectricTest {
    private val kit = ObjectTestKit()

    @After
    fun tearDown() = kit.close()

    private fun await(job: Job) = kit.idleUntil { !job.isActive }

    private fun threeObjects(c: com.brushwork.paint.EditorController) =
        c.vectors.addObjects(c.vec, listOf(kit.stroke(30f, 60f, 200f, 80f), kit.box(260f, 40f, 360f, 140f), kit.ellipse(150f, 250f)), "Add")

    @Test
    fun aLassoOnAVectorLayerSelectsTheTouchedObjectsAndNoPixels() {
        val c = kit.controller()
        threeObjects(c)
        val steps = c.undoManager.undoCount
        c.selectTool(ToolId.LASSO)
        val lasso = c.tools.getValue(ToolId.LASSO) as LassoTool
        // Freehand loop around the box and the end of the stroke.
        val loop = listOf(180f to 20f, 380f to 20f, 380f to 160f, 180f to 160f, 180f to 22f)
        lasso.onDown(ToolPoint(loop[0].first, loop[0].second))
        for ((x, y) in loop.drop(1)) {
            for (k in 1..8) lasso.onMove(ToolPoint(x, y))
        }
        lasso.onUp(ToolPoint(loop.last().first, loop.last().second))
        assertTrue(kit.idleUntil { c.vectors.selectedIds.isNotEmpty() })
        assertEquals(setOf(1L, 2L), c.vectors.selectedIds)
        assertEquals(c.vec, c.vectors.selectedLayer)
        assertNull("no pixel selection", c.selection)
        assertEquals("selecting is not history", steps, c.undoManager.undoCount)
    }

    @Test
    fun selectShapeModesCombineWithTheObjectSelection() {
        val c = kit.controller()
        threeObjects(c)
        c.selectTool(ToolId.MARQUEE)
        val marquee = c.tools.getValue(ToolId.MARQUEE) as MarqueeTool
        fun drag(x0: Float, y0: Float, x1: Float, y1: Float, mode: SelectionMode) {
            marquee.mode = mode
            marquee.onDown(ToolPoint(x0, y0))
            marquee.onMove(ToolPoint((x0 + x1) / 2, (y0 + y1) / 2))
            marquee.onMove(ToolPoint(x1, y1))
            marquee.onUp(ToolPoint(x1, y1))
            assertTrue(kit.idleUntil { !marquee.busy })
        }
        // New: the stroke and the box.
        drag(100f, 30f, 300f, 100f, SelectionMode.REPLACE)
        assertEquals(setOf(1L, 2L), c.vectors.selectedIds)
        // Intersect with an area over the box and the ellipse: the box stays.
        drag(140f, 120f, 340f, 300f, SelectionMode.INTERSECT)
        assertEquals(setOf(2L), c.vectors.selectedIds)
        // Add the ellipse.
        drag(120f, 230f, 180f, 270f, SelectionMode.ADD)
        assertEquals(setOf(2L, 3L), c.vectors.selectedIds)
        // Subtract the box.
        drag(250f, 30f, 370f, 150f, SelectionMode.SUBTRACT)
        assertEquals(setOf(3L), c.vectors.selectedIds)
        assertNull(c.selection)
    }

    @Test
    fun anAreaWithoutObjectsSaysSoAndDeselects() {
        val c = kit.controller()
        threeObjects(c)
        c.vectors.setSelection(c.vec, setOf(1L))
        await(SelectionJobs.applyAsync(c, "Lasso", SelectionMode.REPLACE, "Selecting…", toObjects = true) { kit.rectSelection(420, 300, 500, 380) })
        assertTrue(c.vectors.selectedIds.isEmpty())
        assertEquals(VectorObjectSelection.NOTHING_THERE, c.message)
        assertNull(c.selection)
        // A raster layer: the funnel doesn't take it.
        assertFalse(VectorObjectSelection.select(c.also { it.selectLayer(it.doc.layers[0]) }, kit.rectSelection(0, 0, 10, 10), SelectionMode.REPLACE))
    }

    @Test
    fun manyObjectsAreSearchedInTheBackground() {
        val c = kit.controller()
        val boxes = (0 until 80).map { i -> kit.box(10f + (i % 10) * 40f, 10f + (i / 10) * 40f, 40f + (i % 10) * 40f, 40f + (i / 10) * 40f) }
        c.vectors.addObjects(c.vec, boxes, "Add")
        assertTrue(c.vec.vector!!.objects.size > ObjectTouch.SYNC_OBJECTS)
        val sel = kit.rectSelection(0, 0, 130, 85)
        assertTrue(VectorObjectSelection.select(c, sel, SelectionMode.REPLACE))
        assertTrue(kit.idleUntil { c.vectors.selectedIds.isNotEmpty() })
        val expected = c.vec.vector!!.objects.filter { o -> val r = VectorOps.bounds(o); r.left < 130f && r.top < 85f }.map { it.id }.toSet()
        assertEquals(expected, c.vectors.selectedIds)
        // A newer selection replaces one still being searched.
        VectorObjectSelection.select(c, kit.rectSelection(0, 0, 400, 380), SelectionMode.REPLACE)
        VectorObjectSelection.select(c, kit.rectSelection(0, 0, 45, 45), SelectionMode.REPLACE)
        assertTrue(kit.idleUntil { c.vectors.selectedIds.size == 1 })
        // (The replaced search, cancelled, never lands.)
        repeat(40) { kit.idle(); Thread.sleep(5) }
        assertEquals(setOf(1L), c.vectors.selectedIds)
    }

    @Test
    fun theSelectionClearsWhenTheLayerGoesOrItsObjectsVanish() {
        val c = kit.controller()
        threeObjects(c)
        val layer = c.vec
        c.vectors.setSelection(layer, setOf(1L, 2L))
        // Objects deleted elsewhere drop out of the selection.
        c.vectors.update(layer, layer.vector!!.without(setOf(1L)), "Delete")
        assertEquals(setOf(2L), c.vectors.selectedIds)
        c.deleteLayer(layer)
        assertTrue(c.vectors.selectedIds.isEmpty())
        assertNull(c.vectors.selectedLayer)
    }

    @Test
    fun theOverlayDrawsDashedBoxesThatFollowATransform() {
        val c = kit.controller()
        threeObjects(c)
        val layer = c.vec
        c.vectors.setSelection(layer, setOf(2L))
        val b = VectorOps.bounds(layer.vector!!.byId(2)!!)
        val overlay = Bitmap.createBitmap(kit.w, kit.h, Bitmap.Config.ARGB_8888)
        fun draw(): IntArray {
            overlay.eraseColor(0)
            VectorObjectSelection.drawOverlay(c, Canvas(overlay), c.viewTransform)
            return kit.pixels(overlay)
        }
        fun painted(px: IntArray, x: Float, y: Float, r: Int = 2): Boolean {
            for (dy in -r..r) for (dx in -r..r) {
                val xx = x.toInt() + dx; val yy = y.toInt() + dy
                if (xx in 0 until kit.w && yy in 0 until kit.h && Color.alpha(px[yy * kit.w + xx]) > 0) return true
            }
            return false
        }
        var px = draw()
        // Along the box's left edge (dashes: some of the samples), nothing far away.
        val hits = (0 until 10).count { painted(px, b.left, b.top + (b.bottom - b.top) * it / 10f, 0) }
        assertTrue("dashed edge drawn ($hits)", hits in 3..10)
        assertFalse(painted(px, 450f, 300f, 4))
        // Not shown for another active layer.
        c.selectLayer(c.doc.layers[0])
        assertTrue(draw().all { it == 0 })
        c.selectLayer(layer)
        // Lifted by the Transform tool and moved: the box goes along.
        val tool = kit.transform(c)
        tool.moveBy(-200f, 150f)
        px = draw()
        assertTrue(painted(px, b.left - 200f, (b.top + b.bottom) / 2 + 150f, 3))
        assertFalse(painted(px, b.left, (b.top + b.bottom) / 2, 3))
        assertEquals(setOf(2L), VectorLift.activeLift(c)!!.ids)
        tool.discard()
    }
}
