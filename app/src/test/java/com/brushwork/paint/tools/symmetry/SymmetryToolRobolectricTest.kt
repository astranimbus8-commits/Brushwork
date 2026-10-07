package com.brushwork.paint.tools.symmetry

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import com.brushwork.paint.EditorController
import com.brushwork.paint.assist.SymmetryGuides
import com.brushwork.paint.assist.SymmetryHandle
import com.brushwork.paint.assist.SymmetryHandles
import com.brushwork.paint.assist.SymmetryMaps
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.StubToolFixtures
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.7 (item 18, §3.18; replaces F5's stub test): the Symmetry tool. Picked on any layer kind it
 * turns the mirror on and, like the stub, leaves no step, pixel, structure change or pending work
 * (its drags change only the document's symmetry, never undoably); its handles move the centre,
 * turn the axis (snapping to 15°), set the array spacings and reshape the perspective cell (kept
 * convex); a tap moves nothing and a cancelled drag restores the ruler. The guides draw nothing
 * while symmetry is off and the ruler's lines and handles while it is on.
 */
@RunWith(RobolectricTestRunner::class)
class SymmetryToolRobolectricTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private fun controller(): EditorController = Smoke.controller(app).also { it.viewTransform.set(Matrix()) }

    private fun EditorController.drag(vararg pts: Pair<Float, Float>) {
        pointerDown(ToolPoint(pts[0].first, pts[0].second))
        for (i in 1 until pts.size) pointerMove(ToolPoint(pts[i].first, pts[i].second))
        pointerUp(ToolPoint(pts.last().first, pts.last().second))
    }

    @Test
    fun theToolTurnsTheMirrorOnAndLeavesNoTraceInTheLayers() {
        val c = controller()
        assertEquals(SymmetryType.OFF, c.symmetry.type)
        StubToolFixtures.assertLeavesNoTrace(c, ToolId.SYMMETRY, StubToolFixtures.everyKind(c))
        assertEquals(SymmetryType.MIRROR, c.symmetry.type)
        assertEquals(c.symmetry, c.doc.symmetry)
        // Chosen "Off" in the strip, it stays off while the tool is active (a layer change too).
        c.selectTool(ToolId.SYMMETRY)
        c.updateSymmetry(c.symmetry.copy(type = SymmetryType.OFF))
        c.selectLayer(c.doc.layers.first())
        assertEquals(SymmetryType.OFF, c.symmetry.type)
        // Off, a drag does nothing at all.
        val off = c.symmetry
        c.drag(10f to 10f, 80f to 80f)
        assertEquals(off, c.symmetry)
    }

    @Test
    fun theHandlesMoveTheCentreTurnTheAxisAndCancelRestores() {
        val c = controller()
        val d = c.doc
        c.selectTool(ToolId.SYMMETRY)
        val perDp = c.viewTransform.dp(1f) / c.viewTransform.zoom
        // The centre (unplaced: the canvas centre) follows the finger by how far it moved.
        c.drag(201f to 152f, 205f to 160f, 231f to 172f)
        assertEquals(230f, c.symmetry.centerX, 1e-3f)
        assertEquals(170f, c.symmetry.centerY, 1e-3f)
        // A tap moves nothing.
        val placed = c.symmetry
        c.drag(232f to 171f, 232f to 171f)
        assertEquals(placed, c.symmetry)
        // The angle knob turns the axis, snapping to 15°.
        val knob = SymmetryHandles.position(c.symmetry, d.width, d.height, SymmetryHandle.ANGLE, perDp)
        assertEquals(SymmetryHandle.ANGLE, SymmetryHandles.hit(c.symmetry, d.width, d.height, knob.x, knob.y, perDp))
        c.drag(knob.x to knob.y, knob.x + 20f to knob.y, 230f + 100f to 170f + 2f)
        assertEquals("snapped to 0°", 0f, c.symmetry.angleDeg, 1e-3f)
        assertEquals(placed.centerX, c.symmetry.centerX, 1e-3f)
        val k2 = SymmetryHandles.position(c.symmetry, d.width, d.height, SymmetryHandle.ANGLE, perDp)
        c.drag(k2.x to k2.y, k2.x - 10f to k2.y + 10f, 230f + 60f to 170f + 80f)
        assertEquals("not snapped far from a multiple of 15°", Math.toDegrees(kotlin.math.atan2(80.0, 60.0)).toFloat(), c.symmetry.angleDeg, 0.05f)
        // A second finger cancels: the ruler is as it was.
        val before = c.symmetry
        c.pointerDown(ToolPoint(50f, 50f))
        c.pointerMove(ToolPoint(90f, 60f))
        c.pointerMove(ToolPoint(120f, 80f))
        assertTrue(c.symmetry != before)
        c.pointerCancel()
        assertEquals(before, c.symmetry)
        assertEquals(0, c.undoManager.undoCount)
    }

    @Test
    fun theArraySpacingsAndThePerspectiveCornersAreDraggedOnTheCanvas() {
        val c = controller()
        val d = c.doc
        val perDp = c.viewTransform.dp(1f) / c.viewTransform.zoom
        c.updateSymmetry(SymmetrySettings(SymmetryType.ARRAY, centerX = 50f, centerY = 40f, spacingX = 100f, spacingY = 80f))
        c.selectTool(ToolId.SYMMETRY)
        assertEquals(SymmetryHandle.SPACING_X, SymmetryHandles.hit(c.symmetry, d.width, d.height, 150f, 40f, perDp))
        // "Spacing X" from the far corner of the first edge: longer, and the grid turns with it.
        c.drag(150f to 40f, 160f to 45f, 170f to 40f)
        assertEquals(120f, c.symmetry.spacingX, 1e-3f)
        assertEquals(90f, c.symmetry.angleDeg, 1e-3f)
        // "Spacing Y" along the second edge only.
        c.drag(50f to 120f, 52f to 130f, 58f to 140f)
        assertEquals(100f, c.symmetry.spacingY, 1e-3f)
        assertEquals(120f, c.symmetry.spacingX, 1e-3f)
        // The perspective cell: a corner moves while the cell stays convex.
        c.updateSymmetry(SymmetrySettings(SymmetryType.PERSPECTIVE_ARRAY))
        val q = SymmetryMaps.quad(c.symmetry, d.width, d.height)
        c.drag(q[2] to q[3], q[2] + 8f to q[3], q[2] + 10f to q[3] - 4f)
        val moved = c.symmetry.quad
        assertEquals(q[2] + 10f, moved[2], 1e-3f)
        assertEquals(q[3] - 4f, moved[3], 1e-3f)
        assertTrue(SymmetrySettings.isConvexQuad(moved))
        // Dragged across the opposite edge the corner stops where the cell was still convex.
        c.drag(moved[2] to moved[3], moved[2] - 30f to moved[3], q[0] - 200f to moved[3])
        assertEquals("the last convex place", moved[2] - 30f, c.symmetry.quad[2], 1e-3f)
        assertTrue(SymmetrySettings.isConvexQuad(c.symmetry.quad))
        // Elsewhere, the whole cell moves.
        val cell = c.symmetry.quad
        c.drag(5f to 5f, 15f to 5f, 25f to 15f)
        assertEquals(cell.mapIndexed { i, v -> v + if (i % 2 == 0) 20f else 10f }, c.symmetry.quad)
        assertEquals(0, c.undoManager.undoCount)
    }

    @Test
    fun theGuidesDrawNothingWhenOffAndTheRulerWhenOn() {
        val c = controller()
        val size = c.doc.width to c.doc.height
        fun drawn(editing: Boolean): Int {
            val b = Bitmap.createBitmap(size.first, size.second, Bitmap.Config.ARGB_8888)
            SymmetryGuides.draw(Canvas(b), c.viewTransform, c.doc, editing)
            val px = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
            return px.count { it != 0 }
        }
        assertEquals("off: nothing, editing or not", 0, drawn(true) + drawn(false))
        for (type in SymmetryType.entries.filter { it != SymmetryType.OFF }) {
            c.updateSymmetry(SymmetrySettings(type, spacingX = 90f, spacingY = 70f))
            val plain = drawn(false)
            val editing = drawn(true)
            assertTrue("$type: guides ($plain)", plain > 100)
            assertTrue("$type: handles while editing ($editing vs $plain)", editing > plain)
        }
        // A mirror whose axis is off the canvas draws no line over it.
        c.updateSymmetry(SymmetrySettings(SymmetryType.MIRROR, centerX = -500f, centerY = 100f))
        assertEquals(0, drawn(false))
        assertNull(SymmetryHandles.hit(SymmetrySettings(), 100, 100, 50f, 50f, 1f))
        assertFalse(SymmetryHandles.insideQuad(SymmetrySettings(SymmetryType.PERSPECTIVE_ARRAY), 400, 300, 5f, 5f))
    }
}
