package com.brushwork.paint.tools.vector.gen

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.controller
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.drag
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.plainLine
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tool
import com.brushwork.paint.vector.CurveGenKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.atan2

/**
 * v1.8 (design §3.7a): the generated curve's in-tool steps. Picking a curve is one step; a slider
 * drag of the sheet is ONE step however many values it passes (redo brings it back), a toggle
 * is a step of its own; a drag of the centre handle moves the curve and a drag of the size
 * handle scales and turns it, each one step; the last-used settings are remembered across picks
 * and shared by Curve and Path.
 */
@RunWith(RobolectricTestRunner::class)
class GeneratorUndoTest {
    private fun angleOf(v: Vec2) = Math.toDegrees(atan2(v.y.toDouble(), v.x.toDouble())).toFloat()

    /** [a] degrees in -180..180. */
    private fun wrap(a: Float) = ((a % 360f) + 540f) % 360f - 180f

    @Test
    fun pickingACurveIsOneStep() {
        for (id in listOf(ToolId.CURVE, ToolId.PATH)) {
            val c = controller()
            val t = c.tool(id)
            t.plainLine()
            assertTrue(t.startGenerator(CurveGenKind.GOLDEN, null))
            val g = t.generator!!
            val n = t.pointCount
            assertTrue(t.undoStep())
            assertEquals("$id: nothing is open", 0, t.pointCount)
            assertNull(t.generator)
            assertNull(t.generatorHandles)
            assertTrue(t.redoStep())
            assertEquals(g, t.generator)
            assertEquals(n, t.pointCount)
        }
    }

    @Test
    fun aSliderDragIsOneStepAndRedoBringsItBack() {
        for (id in listOf(ToolId.CURVE, ToolId.PATH)) {
            val c = controller()
            val t = c.tool(id)
            t.plainLine()
            assertTrue(t.startGenerator(CurveGenKind.ARCHIMEDEAN, null))
            val g0 = t.generator!!
            val before = t.pointCount
            t.beginNumericEdit()
            for (i in 1..40) t.changeGenerator(t.generator!!.copy(turns = g0.turns + i * 0.1f))
            t.endNumericEdit()
            val g1 = t.generator!!
            assertEquals(g0.turns + 4f, g1.turns, 1e-4f)
            assertNotEquals("more turns, more points", before, t.pointCount)
            assertTrue(CurveGenerators.matches(t.buildVPath()!!))
            assertTrue(t.undoStep())
            assertEquals("$id: ONE step back to before the drag", g0, t.generator)
            assertEquals(before, t.pointCount)
            assertTrue(t.redoStep())
            assertEquals(g1, t.generator)
            assertTrue(CurveGenerators.matches(t.buildVPath()!!))
            // Two toggles that are not held are two steps.
            t.changeGenerator(g1.copy(clockwise = !g1.clockwise))
            t.changeGenerator(t.generator!!.copy(inner = 0.4f))
            assertTrue(t.undoStep())
            assertEquals(!g1.clockwise, t.generator!!.clockwise)
            assertEquals(g1.inner, t.generator!!.inner, 0f)
            assertTrue(t.undoStep())
            assertEquals(g1, t.generator)
            // The same values again change nothing and make no step.
            t.changeGenerator(g1)
            assertTrue(t.undoStep())
            assertEquals(g0, t.generator)
        }
    }

    @Test
    fun aDragOfTheCentreHandleMovesTheCurveInOneStep() {
        for (id in listOf(ToolId.CURVE, ToolId.PATH)) {
            val c = controller()
            val t = c.tool(id)
            t.plainLine()
            assertTrue(t.startGenerator(CurveGenKind.ROSE, null))
            val g = t.generator!!
            val anchorsBefore = t.anchors
            val splineBefore = t.spline
            val h = t.generatorHandles!!.centre
            c.drag(h.x to h.y, h.x + 10f to h.y + 5f, h.x + 25f to h.y + 12f, h.x + 40f to h.y - 20f)
            val moved = t.generator!!
            assertEquals("$id: the centre followed the finger", g.cx + 40f, moved.cx, 1e-3f)
            assertEquals(g.cy - 20f, moved.cy, 1e-3f)
            assertEquals(g.size, moved.size, 0f)
            assertTrue(CurveGenerators.matches(t.buildVPath()!!))
            assertTrue(t.undoStep())
            assertEquals("$id: one step", g, t.generator)
            assertEquals(anchorsBefore, t.anchors)
            assertEquals(splineBefore, t.spline)
        }
    }

    @Test
    fun aDragOfTheSizeHandleScalesAndTurnsTheCurveInOneStep() {
        for (id in listOf(ToolId.CURVE, ToolId.PATH)) for (gen in listOf(CurveGenKind.LOGARITHMIC, CurveGenKind.SINE)) {
            val c = controller()
            val t = c.tool(id)
            t.plainLine()
            assertTrue(t.startGenerator(gen, null))
            val g = t.generator!!
            val h0 = t.generatorHandles!!
            val reach0 = h0.size - h0.centre
            val end = h0.size + Vec2(-30f, 45f)
            c.drag(h0.size.x to h0.size.y, h0.size.x - 10f to h0.size.y + 15f, end.x to end.y)
            val h1 = t.generatorHandles!!
            assertEquals("$id / $gen: the size handle ends under the finger", 0f, h1.size.distanceTo(end), 0.01f)
            assertEquals(h0.centre, h1.centre)
            val reach1 = h1.size - h1.centre
            val scale = reach1.length / reach0.length
            assertEquals(g.size * scale, t.generator!!.size, 0.01f)
            val turned = (angleOf(reach1) - angleOf(reach0)) - (t.generator!!.angleDeg - g.angleDeg)
            assertEquals("$id / $gen: turned with the finger", 0f, wrap(turned), 0.01f)
            assertTrue(CurveGenerators.matches(t.buildVPath()!!))
            assertTrue(t.undoStep())
            assertEquals("$id / $gen: one step", g, t.generator)
        }
    }

    @Test
    fun theLastSettingsAreRememberedAndSharedByCurveAndPath() {
        val c = controller()
        val curve = c.tool(ToolId.CURVE)
        curve.plainLine()
        assertTrue(curve.startGenerator(CurveGenKind.ARCHIMEDEAN, null))
        val g = curve.generator!!
        // A held slider becomes the last-used setting when it is let go.
        curve.beginNumericEdit()
        curve.changeGenerator(g.copy(turns = 5.5f))
        curve.changeGenerator(curve.generator!!.copy(turns = 6f))
        curve.endNumericEdit()
        curve.changeGenerator(curve.generator!!.copy(clockwise = true, inner = 0.25f, amplitude = g.size * 0.5f))
        curve.commit()
        assertEquals(0, curve.pointCount)

        val path = c.tool(ToolId.PATH)
        path.plainLine()
        assertTrue(path.startGenerator(CurveGenKind.FERMAT, null))
        val p = path.generator!!
        assertEquals(CurveGenKind.FERMAT, p.kind)
        assertEquals(6f, p.turns, 1e-4f)
        assertTrue(p.clockwise)
        assertEquals(0.25f, p.inner, 1e-4f)
        // The placement is the view's, never the last curve's: centre and size come from the view.
        assertEquals(g.cx, p.cx, 0f)
        assertEquals(g.size, p.size, 0f)
        assertEquals(0f, p.angleDeg, 0f)
        path.discard()

        // A wave keeps its height relative to its length.
        assertTrue(path.startGenerator(CurveGenKind.SINE, null))
        assertEquals(path.generator!!.size * 0.5f, path.generator!!.amplitude, 1e-3f)
    }

    @Test
    fun undoAfterAHandEditBringsTheGeneratorAndTheSheetBack() {
        val c = controller()
        val t: CurveTool = c.tool(ToolId.CURVE)
        t.plainLine()
        assertTrue(t.startGenerator(CurveGenKind.ROSE, null))
        val g = t.generator!!
        t.deleteAnchor(t.pointCount / 2)
        assertNull(t.generator)
        // Without a generator the sheet's changes do nothing.
        t.changeGenerator(g.copy(petalsN = 7))
        assertNull(t.generator)
        assertTrue(t.undoStep())
        assertNotNull(t.generator)
        t.changeGenerator(g.copy(petalsN = 7))
        assertEquals(7, t.generator!!.petalsN)
        assertTrue(CurveGenerators.matches(t.buildVPath()!!))
        assertFalse("the redo of the deletion is gone", t.canRedoStep)
    }
}
