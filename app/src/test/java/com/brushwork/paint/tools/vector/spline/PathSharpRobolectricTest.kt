package com.brushwork.paint.tools.vector.spline

import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.controller
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.onLine
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.plainLine
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tap
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tool
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VectorCodec
import com.brushwork.paint.vector.VectorContent
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.7 (item 4, design §3.4d): sharp points in the Path tool. Toggling one is ONE in-tool step;
 * the ends of an open path refuse it (and lose it when a middle point becomes an end); the
 * corner is stored (never written when false), survives a `.vec` round trip and the path reopens
 * as a Path; with the field stripped (what a v1.6 reader sees) the stored Bézier form no longer
 * matches the spline, so the path reopens as a Bézier path in the Curve tool.
 */
@RunWith(RobolectricTestRunner::class)
class PathSharpRobolectricTest {

    private val pts = listOf(40f to 240f, 110f to 50f, 190f to 250f, 270f to 40f, 350f to 230f)

    private fun steps(t: CurveTool): Int {
        var n = 0
        while (t.undoStep()) n++
        repeat(n) { t.redoStep() }
        return n
    }

    @Test
    fun aToggleIsOneInToolStepAndTheEndsRefuseIt() {
        val c = controller()
        val t = c.tool(ToolId.PATH)
        t.plainLine()
        for ((x, y) in pts) c.tap(x, y)
        val n = steps(t)
        assertTrue(t.canBeSharp(2))
        assertFalse(t.canBeSharp(0))
        assertFalse(t.canBeSharp(4))
        t.setSharp(2, true)
        assertTrue(t.spline!!.points[2].sharp)
        assertEquals("one step", n + 1, steps(t))
        t.setSharp(0, true)
        t.setSharpPoints(listOf(0, 4), true)
        assertFalse(t.spline!!.points[0].sharp)
        assertFalse(t.spline!!.points[4].sharp)
        assertEquals("the ends refused: no step", n + 1, steps(t))
        assertTrue(t.undoStep())
        assertFalse(t.spline!!.points[2].sharp)
        assertTrue(t.redoStep())
        // Several at once: one step.
        t.setSharpPoints(listOf(1, 2, 3), true)
        assertEquals(listOf(false, true, true, true, false), t.spline!!.points.map { it.sharp })
        assertEquals(n + 2, steps(t))
        // A middle corner that becomes an end (its outer neighbour deleted) is smooth again.
        t.selectPoints(PointSelection.of(5, 0))
        assertTrue(t.deleteSelectedPoints())
        assertFalse(t.spline!!.points.first().sharp)
        // Cyclic: every point can be a corner; turned open again, the ends are smooth.
        t.setCyclic(true)
        assertTrue(t.canBeSharp(0))
        t.setSharp(0, true)
        assertTrue(t.spline!!.points[0].sharp)
        t.setCyclic(false)
        assertFalse(t.spline!!.points.first().sharp)
        assertFalse(t.spline!!.points.last().sharp)
        t.discard()
    }

    @Test
    fun saveAndReloadKeepAPathAndWithTheFieldStrippedItReopensAsBezier() {
        val c = controller()
        val layer = c.activeLayer
        val t = c.tool(ToolId.PATH)
        t.plainLine()
        for ((x, y) in pts) c.tap(x, y)
        t.setSharp(2, true)
        t.commit()
        val p = layer.vector!!.objects.single() as VPath
        assertTrue("I9", SplineBezier.matches(p))
        assertTrue(p.spline!!.points[2].sharp)
        // The corner is an anchor of the stored form, exactly at the point.
        val (x2, y2) = pts[2]
        assertTrue(p.subpaths[0].anchors.toString(), p.subpaths[0].anchors.any { it.sharp && it.x == x2 && it.y == y2 })
        // Written only where true.
        val json = Json { encodeDefaults = true }.encodeToString(VObject.serializer(), p)
        assertEquals(1, Regex("\"sharp\":true").findAll(json.substringAfter("\"spline\"")).count())
        assertFalse(json.substringAfter("\"spline\"").contains("\"sharp\":false"))

        // A .vec round trip: the same content, it reopens in Path with its corner.
        val back = VectorCodec.decode(VectorCodec.encode(layer.vector!!))
        assertEquals(layer.vector, back)
        layer.vector = back
        c.tap(onLine(p))
        assertEquals(ToolId.PATH, c.activeToolId)
        assertTrue(t.isReopened)
        assertTrue(t.spline!!.points[2].sharp)
        t.discard()

        // What a v1.6 reader keeps: the spline without the field. Its Bézier form no longer
        // matches the stored corner, so the path reopens as a Bézier path (Curve tool).
        val s = p.spline!!
        val stripped = p.copy(spline = s.copy(points = s.points.map { it.copy(sharp = false) }))
        assertFalse(SplineBezier.matches(stripped))
        layer.vector = VectorContent.EMPTY.plus(listOf(stripped)).first
        c.selectTool(ToolId.PATH)
        c.tap(onLine(stripped))
        assertEquals(ToolId.CURVE, c.activeToolId)
        val curve = c.tools.getValue(ToolId.CURVE) as CurveTool
        assertTrue(curve.isReopened)
        curve.discard()
    }
}
