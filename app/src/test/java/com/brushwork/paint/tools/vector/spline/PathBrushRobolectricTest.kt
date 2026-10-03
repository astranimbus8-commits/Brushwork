package com.brushwork.paint.tools.vector.spline

import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.sanitized
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.W
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.controller
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.drag
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.onLine
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.pixels
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.render
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tap
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tool
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStrokeKind
import com.brushwork.paint.vector.VectorContent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs
import kotlin.math.max

/**
 * v1.6 §3.2: the Path tool with "Current brush" (its default stroke). The painting tool's own
 * stroke follows the spline's Bézier form live; on a vector layer ✓ keeps those pixels and adds
 * ONE `VPath` (brush, seed, the spline, I9) whose replay they are (I1), also with weights (an
 * approximated conversion), per-point thickness, a fill and cyclic knots. A reopened brush path
 * keeps its brush and texture; To Bézier hands the same texture to the Curve tool; a second
 * finger takes back the point the first one placed.
 */
@RunWith(RobolectricTestRunner::class)
class PathBrushRobolectricTest {

    private val five = listOf(60f to 220f, 120f to 60f, 220f to 240f, 300f to 70f, 350f to 200f)

    private fun channelDiff(a: Int, b: Int): Int {
        var m = 0
        for (s in intArrayOf(24, 16, 8, 0)) m = max(m, abs(((a ushr s) and 0xFF) - ((b ushr s) and 0xFF)))
        return m
    }

    /** The live-vs-replay bar of the Curve tool (v1.5 §4.9e): ≥ 99.5 % of the painted pixels within ±2, max ≤ 12. */
    private fun assertParity(what: String, a: IntArray, b: IntArray) {
        var ok = 0
        var painted = 0
        var worst = 0
        for (i in a.indices) {
            if (a[i] == 0 && b[i] == 0) continue
            painted++
            val d = channelDiff(a[i], b[i])
            if (d <= 2) ok++
            worst = max(worst, d)
        }
        assertTrue("$what: something is painted", painted > 200)
        assertTrue("$what: ${ok * 100.0 / painted} % within ±2 (max $worst)", ok >= painted * 0.995 && worst <= 12)
    }

    @Test
    fun aBrushPathOnAVectorLayerKeepsItsLivePixelsAndIsTheObjectsReplay() {
        for (cyclic in listOf(false, true)) {
            val c = controller(vector = true)
            val layer = c.activeLayer
            c.brush = BrushLibrary.byId("softround")!!.copy(size = 14f)
            val t = c.tool(ToolId.PATH)
            t.update { it.copy(stroke = CurveStroke.BRUSH, taper = true, taperPercent = 15f, fill = cyclic, fillColor = 0xFFE0A020.toInt()) }
            for ((x, y) in five) c.tap(x, y)
            t.setCyclic(cyclic)
            // A heavier point (an approximated conversion) and a thicker one (pressure along the stroke).
            t.select(2)
            t.setWeight(2, 3f)
            t.endNumericEdit()
            t.setWidth(3, 2f)
            t.endNumericEdit()
            t.flushPreview()
            assertTrue("cyclic $cyclic: the painting tool's own stroke shows the path", t.brushLive)
            val seed = t.brushSeed
            t.commit()
            assertFalse(t.hasPendingWork)
            assertEquals("cyclic $cyclic: one step", 1, c.undoManager.undoCount)
            assertEquals(CurveTool.PATH_LABEL, c.undoManager.undoLabel)
            val content = layer.vector!!
            val p = content.objects.single() as VPath
            val st = p.stroke!!
            assertEquals(VStrokeKind.BRUSH, st.kind)
            assertEquals(c.brush.sanitized(), st.brush)
            assertEquals(seed, st.seed)
            assertEquals(15f, st.taperPercent, 0f)
            assertEquals(cyclic, p.fill != null)
            val sp = p.spline!!
            assertEquals(cyclic, sp.cyclic)
            assertEquals(3f, sp.points[2].weight, 0f)
            assertEquals(2f, sp.points[3].width, 0f)
            assertEquals("I9", listOf(SplineBezier.toSubpath(sp)), p.subpaths)
            assertParity("cyclic $cyclic", render(content), pixels(layer.bitmap))
            c.undo()
            assertEquals(VectorContent.EMPTY, layer.vector)
            assertTrue(pixels(layer.bitmap).all { it == 0 })
            c.redo()
            assertSame(content, layer.vector)
        }
    }

    @Test
    fun aReopenedBrushPathKeepsItsBrushTextureAndControlPoints() {
        val c = controller(vector = true)
        val layer = c.activeLayer
        val chalk = BrushLibrary.byId("chalk")!!.copy(size = 16f)
        c.brush = chalk
        val t = c.tool(ToolId.PATH)
        t.update { it.copy(stroke = CurveStroke.BRUSH, taper = false) }
        for ((x, y) in five.take(4)) c.tap(x, y)
        t.flushPreview()
        t.commit()
        val first = layer.vector!!
        val made = first.objects.single() as VPath
        // Another brush picked meanwhile: the path keeps its own.
        c.brush = BrushLibrary.defaultBrush.copy(size = 3f)
        val q = onLine(made)
        c.tap(q.x, q.y)
        assertTrue(t.isReopened)
        assertEquals(made.spline, t.spline)
        t.commit()
        assertSame("untouched: unchanged", first, layer.vector)
        assertEquals(1, c.undoManager.undoCount)
        // Drag the last control point: ONE step "Edit path" with the same brush and texture.
        c.tap(q.x, q.y)
        c.drag(300f to 70f, 310f to 100f, 320f to 130f)
        t.flushPreview()
        assertTrue(t.brushLive)
        t.commit()
        val edited = layer.vector!!.objects.single() as VPath
        assertEquals(made.id, edited.id)
        val madeStroke = made.stroke!!
        val editedStroke = edited.stroke!!
        assertEquals(madeStroke.brush, editedStroke.brush)
        assertEquals(madeStroke.seed, editedStroke.seed)
        assertEquals(Vec2(320f, 130f), SplineEditing.pos(edited.spline!!.points[3]))
        assertTrue(SplineBezier.matches(edited))
        assertArrayEquals(render(layer.vector!!), pixels(layer.bitmap))
        assertEquals("the user's brush is left as it was", 3f, c.brush.size, 0f)
        assertEquals(2, c.undoManager.undoCount)
        assertEquals(CurveTool.EDIT_PATH_LABEL, c.undoManager.undoLabel)
    }

    @Test
    fun toBezierKeepsTheBrushTexture() {
        val c = controller(vector = true)
        val layer = c.activeLayer
        c.brush = BrushLibrary.byId("chalk")!!.copy(size = 12f)
        val path = c.tool(ToolId.PATH)
        path.update { it.copy(stroke = CurveStroke.BRUSH, taper = false) }
        for ((x, y) in five) c.tap(x, y)
        path.flushPreview()
        val seed = path.brushSeed
        assertTrue(path.toBezier())
        assertEquals(ToolId.CURVE, c.activeToolId)
        val curve = c.tools.getValue(ToolId.CURVE) as CurveTool
        curve.flushPreview()
        assertTrue(curve.brushLive)
        assertEquals("the same texture", seed, curve.brushSeed)
        curve.commit()
        val p = layer.vector!!.objects.single() as VPath
        assertEquals(seed, p.stroke!!.seed)
        assertNull("a Curve-tool path: no spline", p.spline)
        assertEquals("Curve", c.undoManager.undoLabel)
        assertEquals(1, c.undoManager.undoCount)
        assertParity("to Bézier", render(layer.vector!!), pixels(layer.bitmap))
    }

    @Test
    fun aSecondFingerTakesBackWhatTheFirstOneDid() {
        val c = controller(vector = true)
        val t = c.tool(ToolId.PATH)
        for ((x, y) in five.take(3)) c.tap(x, y)
        val before = t.spline
        // The first finger of a pinch lands on empty canvas and adds a point; the second cancels it.
        c.pointerDown(ToolPoint(330f, 260f))
        assertEquals(4, t.spline!!.points.size)
        c.pointerCancel()
        assertSame(before, t.spline)
        // It dragged a point: back where it was, and no in-tool step is left behind.
        c.pointerDown(ToolPoint(120f, 60f))
        c.pointerMove(ToolPoint(140f, 90f))
        c.pointerMove(ToolPoint(160f, 120f))
        assertEquals(Vec2(160f, 120f), SplineEditing.pos(t.spline!!.points[1]))
        c.pointerCancel()
        assertSame(before, t.spline)
        assertTrue(t.undoStep())
        assertEquals("the last tap is the step taken back", 2, t.spline!!.points.size)
        // A tap with nothing pending on an empty layer only starts a path.
        t.discard()
        assertFalse(t.hasPendingWork)
        c.tap(200f, 150f)
        assertEquals(1, t.spline!!.points.size)
        assertEquals(1, t.anchors.size)
        assertTrue(t.hasPendingWork)
        // One point draws nothing: ✓ drops it without a step.
        t.commit()
        assertFalse(t.hasPendingWork)
        assertEquals(0, c.undoManager.undoCount)
        assertTrue(pixels(c.activeLayer.bitmap).all { it == 0 })
        assertEquals(W, c.activeLayer.bitmap.width)
    }
}
