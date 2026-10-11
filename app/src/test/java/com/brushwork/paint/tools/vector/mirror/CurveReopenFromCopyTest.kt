package com.brushwork.paint.tools.vector.mirror

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurvePaint
import com.brushwork.paint.tools.vector.mirror.CurveMirrorSupport.drawPath
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.controller
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.drag
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.onLine
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.plainLine
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tap
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tool
import com.brushwork.paint.vector.CopyMaps
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.8 (item 1a, design §3.1): a tap on any copy of a stored path reopens the path itself (its
 * source, with its points where the source is); the reopened path keeps its stored copies
 * whatever the ruler is now, and MirrorLabels18.REMOVE stores it without them.
 */
@RunWith(RobolectricTestRunner::class)
class CurveReopenFromCopyTest {
    private val mirror = SymmetrySettings(type = SymmetryType.MIRROR)

    /** x ↦ 400 − x: the mirror copy of [p] on the 400 px wide test document. */
    private fun mirrored(p: Vec2) = Vec2(400f - p.x, p.y)

    @Test
    fun aTapOnACopyReopensTheSource() {
        for (id in listOf(ToolId.CURVE, ToolId.POLYLINE, ToolId.PATH)) {
            val c = controller(vector = true)
            c.updateSymmetry(mirror)
            val t = c.tool(id)
            t.plainLine()
            c.drawPath()
            t.commit()
            val stored = c.activeLayer.vector!!.objects.single() as VPath
            val points = List(3) { i -> CurveMirrorSupport.points[i] }
            // The ruler is off now: the stored copies stay the path's.
            c.updateSymmetry(SymmetrySettings())
            c.tap(mirrored(onLine(stored)))
            assertTrue("$id: reopened from its copy", t.isReopened)
            for (i in points.indices) assertEquals("$id: point $i is the source's", 0f, t.pointAt(i).distanceTo(points[i]), 0.01f)
            assertTrue(t.hasCopies)
            assertEquals(stored.copies, CopyMaps.of(t.copyMapsNow))
            // Unchanged: the object as it was, no step.
            val steps = c.undoManager.undoCount
            t.commit()
            assertSame(stored, c.activeLayer.vector!!.objects.single())
            assertEquals(steps, c.undoManager.undoCount)
            // Edited: still the same copies.
            c.tap(mirrored(onLine(stored)))
            assertTrue(t.isReopened)
            val p1 = t.pointAt(1)
            c.drag(p1.x to p1.y, p1.x + 6f to p1.y + 10f, p1.x + 12f to p1.y + 20f)
            t.commit()
            val edited = c.activeLayer.vector!!.objects.single() as VPath
            assertEquals("$id: same object", stored.id, edited.id)
            assertEquals("$id: copies kept", stored.copies, edited.copies)
            assertEquals(steps + 1, c.undoManager.undoCount)
        }
    }

    @Test
    fun aFilledPathReopensFromTheLineOfACopyNotItsFill() {
        val c = controller(vector = true)
        c.updateSymmetry(mirror)
        val t = c.tool(ToolId.POLYLINE)
        t.plainLine()
        t.setPaintMode(CurvePaint.BOTH)
        c.drawPath(listOf(Vec2(40f, 60f), Vec2(150f, 60f), Vec2(150f, 200f), Vec2(40f, 200f)))
        t.commit()
        val stored = c.activeLayer.vector!!.objects.single() as VPath
        assertFalse(stored.copies.isEmpty)
        // Inside the copy's fill, far from its line: a new path starts there.
        c.tap(mirrored(Vec2(95f, 130f)))
        assertFalse(t.isReopened)
        assertEquals(1, t.pointCount)
        t.discard()
        // On the copy's line.
        c.tap(mirrored(Vec2(95f, 60f)))
        assertTrue(t.isReopened)
        assertEquals(0f, t.pointAt(0).distanceTo(Vec2(40f, 60f)), 0.01f)
        t.discard()
    }

    @Test
    fun removeMirrorCopiesStoresThePathWithoutThem() {
        val c = controller(vector = true)
        c.updateSymmetry(mirror)
        val t = c.tool(ToolId.CURVE)
        t.plainLine()
        c.drawPath()
        t.commit()
        val stored = c.activeLayer.vector!!.objects.single() as VPath
        c.tap(onLine(stored))
        assertTrue(t.isReopened)
        assertTrue(t.removeCopies())
        assertFalse(t.hasCopies)
        assertFalse("nothing more to remove", t.removeCopies())
        t.commit()
        val after = c.activeLayer.vector!!.objects.single() as VPath
        assertEquals(stored.id, after.id)
        assertTrue(after.copies.isEmpty)
        assertEquals(ObjectCopies.source(stored).let { (it as VPath).subpaths }, after.subpaths)
        c.undo()
        assertEquals(stored.copies, (c.activeLayer.vector!!.objects.single() as VPath).copies)
    }
}
