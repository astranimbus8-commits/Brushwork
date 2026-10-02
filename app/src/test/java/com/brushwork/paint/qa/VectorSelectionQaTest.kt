package com.brushwork.paint.qa

import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.SelectionMode
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.select.LassoTool
import com.brushwork.paint.tools.select.MagicWandTool
import com.brushwork.paint.tools.select.MarqueeTool
import com.brushwork.paint.tools.select.SelectionEdits
import com.brushwork.paint.vector.VFillRule
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VectorOps
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * QA (§4.9 "Selection bar Clear / Fill", "Lasso, Select shape"): selections on a vector layer as
 * the user makes them on the phone. A pixel selection (Magic wand) then the Selection sheet's
 * "Selected pixels" Fill / Clear must do what the floating selection bar's Clear and the layer
 * menu's Fill / Clear do on a vector layer — remove the touched objects, add a filled object —
 * and never turn the layer into a raster layer. Select shape's New / Add / Subtract / Intersect
 * combine OBJECT selections.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h857dp-xxhdpi")
class VectorSelectionQaTest {
    private lateinit var r: VectorQaRig
    private val c get() = r.c
    private val ink = 0xFF1A2A6C.toInt()

    @After
    fun tearDown() { if (this::r.isInitialized) r.close() }

    /** A new 480 x 320 canvas in vector mode with three finger strokes (y 60, 170, 260). */
    private fun drawing(): Layer {
        r = VectorQaRig(480, 320)
        c.toggleVectorMode()
        r.checkpoint("Vector on")
        r.tool(ToolId.BRUSH)
        c.color = ink
        c.brush = BrushLibrary.defaultBrush.copy(size = 9f)
        r.stroke(40f to 60f, 240f to 70f, 440f to 60f); r.checkpoint("s1")
        r.stroke(40f to 170f, 240f to 180f, 440f to 170f); r.checkpoint("s2")
        r.stroke(60f to 260f, 200f to 290f, 300f to 250f); r.checkpoint("s3")
        return c.activeLayer
    }

    private fun strokeAt(layer: Layer, y: Float) = layer.vector!!.objects.filterIsInstance<VStroke>().firstOrNull { abs(it.points.bounds().centerY() - y) < 15f }

    /** The Magic wand on the middle stroke: a PIXEL selection of it. */
    private fun wandOnMiddleStroke() {
        r.tool(ToolId.MAGIC_WAND)
        (c.tools.getValue(ToolId.MAGIC_WAND) as MagicWandTool).selectAt(240f, 178f)
        assertTrue(Smoke.pumpUntil(10_000) { c.selection != null })
        r.checkpoint("wand", steps = null)
    }

    @Test
    fun selectionSheetClearRemovesTheTouchedObjectsAndKeepsTheVectorLayer() {
        val vec = drawing()
        val content = vec.vector!!
        wandOnMiddleStroke()
        val before = r.snapshot()
        // Selection sheet > Selected pixels > Clear.
        assertTrue(SelectionEdits.clearSelection(c))
        r.checkpoint("sheet Clear")
        assertNotNull("still a vector layer", vec.vector)
        assertTrue(c.isVectorMode)
        assertEquals("the touched stroke went", 2, vec.vector!!.objects.size)
        assertEquals(null, strokeAt(vec, 175f))
        assertNotNull(strokeAt(vec, 65f))
        assertNotNull(strokeAt(vec, 265f))
        r.undoAndCheck("undo sheet Clear")
        r.assertState("back", before)
        assertSame(content, vec.vector)
        r.redoAndCheck("redo sheet Clear")
    }

    @Test
    fun selectionSheetFillAddsAFilledObjectAndKeepsTheVectorLayer() {
        val vec = drawing()
        wandOnMiddleStroke()
        val sel = c.selection!!.bounds
        val n = vec.vector!!.objects.size
        val green = 0xFF2E9D4A.toInt()
        c.color = green
        // Selection sheet > Selected pixels > Fill (the main color).
        assertTrue(SelectionEdits.fillSelection(c, c.color))
        r.checkpoint("sheet Fill")
        assertNotNull("still a vector layer", vec.vector)
        assertEquals("one filled object on top", n + 1, vec.vector!!.objects.size)
        val fill = vec.vector!!.objects.last() as VPath
        assertEquals(VPaint.Solid(green), fill.fill)
        assertEquals(VFillRule.EVENODD, fill.fillRule)
        val fb = VectorOps.bounds(fill)
        assertTrue("its outline is the selection's: $fb vs $sel", abs(fb.left - sel.left) < 3f && abs(fb.right - sel.right) < 3f && abs(fb.top - sel.top) < 3f && abs(fb.bottom - sel.bottom) < 3f)
        r.undoAndCheck("undo sheet Fill")
        assertEquals(n, vec.vector!!.objects.size)
        // "Fill with…" (another color) goes the same way.
        assertTrue(SelectionEdits.fillSelection(c, 0xFFE03030.toInt()))
        r.checkpoint("sheet Fill with")
        assertEquals(VPaint.Solid(0xFFE03030.toInt()), (vec.vector!!.objects.last() as VPath).fill)
    }

    @Test
    fun selectionSheetClearOnAnAlphaLockedVectorLayerStillRemovesObjects() {
        // Objects ignore alpha lock (§4.9 table), as the layer menu's Clear on a vector layer does.
        val vec = drawing()
        c.toggleAlphaLock(vec)
        r.checkpoint("lock alpha")
        wandOnMiddleStroke()
        assertTrue(SelectionEdits.clearSelection(c))
        r.checkpoint("sheet Clear (alpha locked)")
        assertNotNull(vec.vector)
        assertEquals(null, strokeAt(vec, 175f))
    }

    @Test
    fun selectShapeNewAddSubtractIntersectCombineObjectSelections() {
        val vec = drawing()
        val s1 = strokeAt(vec, 65f)!!.id
        val s2 = strokeAt(vec, 175f)!!.id
        val s3 = strokeAt(vec, 265f)!!.id
        r.tool(ToolId.MARQUEE)
        val marquee = c.tools.getValue(ToolId.MARQUEE) as MarqueeTool
        fun drag(x0: Float, y0: Float, x1: Float, y1: Float, mode: SelectionMode, expect: Set<Long>) {
            marquee.mode = mode
            r.stroke(x0 to y0, (x0 + x1) / 2f to (y0 + y1) / 2f, x1 to y1)
            assertTrue("$mode -> $expect (now ${c.vectors.selectedIds})", Smoke.pumpUntil(10_000) { c.vectors.selectedIds == expect })
            r.checkpoint("select shape $mode", steps = 0)
            assertEquals("no pixel selection on a vector layer", null, c.selection)
        }
        // New: the top two strokes.
        drag(20f, 40f, 460f, 190f, SelectionMode.REPLACE, setOf(s1, s2))
        // Add: the bottom one.
        drag(40f, 230f, 320f, 300f, SelectionMode.ADD, setOf(s1, s2, s3))
        // Subtract: the middle one.
        drag(20f, 150f, 460f, 195f, SelectionMode.SUBTRACT, setOf(s1, s3))
        // Intersect with an area over the bottom two: only s3 stays.
        drag(20f, 150f, 460f, 310f, SelectionMode.INTERSECT, setOf(s3))
        // Lasso Add the top one.
        r.tool(ToolId.LASSO)
        (c.tools.getValue(ToolId.LASSO) as LassoTool).mode = SelectionMode.ADD
        r.stroke(20f to 40f, 460f to 40f, 460f to 90f, 20f to 90f, 20f to 42f)
        assertTrue(Smoke.pumpUntil(10_000) { c.vectors.selectedIds == setOf(s1, s3) })
        r.checkpoint("lasso add", steps = 0)
        (c.tools.getValue(ToolId.LASSO) as LassoTool).mode = SelectionMode.REPLACE
    }
}
