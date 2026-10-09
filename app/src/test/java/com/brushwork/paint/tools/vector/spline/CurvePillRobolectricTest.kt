package com.brushwork.paint.tools.vector.spline

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.controller
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.onLine
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.plainLine
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tap
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tool
import com.brushwork.paint.ui.common.PillLabels
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VectorCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.7 (items 12 and 13, design §3.12 and §3.13; the pill of §3.1): the Curve, Polyline and Path
 * tools as the pill's ONE source ([CurveTool.pillPosition]: "Point 3", "Selected points",
 * "Center"), its Scale row ([CurveTool.objectScale]: about the box centre, relative to the
 * points as selected, thickness kept) and its trash cell ([CurveTool.objectDeletion]).
 */
@RunWith(RobolectricTestRunner::class)
class CurvePillRobolectricTest {

    private val five = listOf(40f to 240f, 110f to 50f, 190f to 250f, 270f to 40f, 350f to 230f)

    private fun steps(t: CurveTool): Int {
        var n = 0
        while (t.undoStep()) n++
        repeat(n) { t.redoStep() }
        return n
    }

    private fun near(want: Vec2, got: Vec2, tol: Float = 1e-3f, what: String = "") {
        assertEquals("$what x", want.x, got.x, tol)
        assertEquals("$what y", want.y, got.y, tol)
    }

    @Test
    fun aFourAnchorBezierScaled200DoublesAnchorsAndHandlesAboutItsCentre() {
        val c = controller()
        val t = c.tool(ToolId.CURVE)
        t.plainLine()
        assertNull("nothing open: the pill hides", t.pillPosition.position)
        assertNull(t.objectScale)
        assertNull(t.objectDeletion)
        for (p in listOf(Vec2(100f, 100f), Vec2(180f, 60f), Vec2(260f, 120f), Vec2(200f, 200f))) assertTrue(t.addAnchor(p))
        t.setWidths(listOf(1), floatArrayOf(0.5f))
        t.deselect()
        // No point selected: the object's centre, and the Scale row is there.
        assertEquals(CurveTool.CENTER_LABEL, t.pillPosition.label)
        val centre = Vec2(180f, 130f)
        near(centre, t.pillPosition.position!!, what = "centre")
        val scale = t.objectScale!!
        assertEquals(Vec2(100f, 100f), scale.scalePercent)
        val before = t.anchors
        val handles = before.indices.map { t.handlesOf(it) }
        val n = steps(t)
        scale.beginScaleEdit()
        scale.setScale(150f, 150f)
        scale.setScale(200f, 200f)
        scale.endScaleEdit()
        assertEquals("one in-tool step for the scrub", n + 1, steps(t))
        assertEquals(Vec2(200f, 200f), scale.scalePercent)
        for (i in before.indices) {
            near(centre + (before[i].pos - centre) * 2f, t.anchors[i].pos, what = "anchor $i")
            val (hi, ho) = t.handlesOf(i)
            near(handles[i].first * 2f, hi, 1e-2f, "handle in $i")
            near(handles[i].second * 2f, ho, 1e-2f, "handle out $i")
        }
        assertEquals("the thickness is not scaled", before.map { it.width }, t.anchors.map { it.width })
        near(centre, t.pillPosition.position!!, what = "about its centre")
        // Typed: relative to the points as selected (200 % then 50 % is half the original).
        scale.setScale(null, 50f)
        assertEquals(Vec2(200f, 50f), scale.scalePercent)
        near(Vec2(centre.x + (before[0].x - centre.x) * 2f, centre.y + (before[0].y - centre.y) * 0.5f), t.anchors[0].pos)
        // Undo: back to the original points at 100 %.
        assertTrue(t.undoStep())
        assertTrue(t.undoStep())
        assertEquals(before, t.anchors)
        assertEquals(Vec2(100f, 100f), scale.scalePercent)
        t.discard()
    }

    @Test
    fun aPathScaled50SavedAndReloadedStillOpensAsAPath() {
        val c = controller()
        val layer = c.activeLayer
        val t = c.tool(ToolId.PATH)
        t.plainLine()
        for ((x, y) in five) c.tap(x, y)
        t.setSharp(2, true)
        t.deselect()
        val pts = t.spline!!.points
        val centre = t.pillPosition.position!!
        t.objectScale!!.setScale(50f, 50f)
        for (i in pts.indices) {
            val q = t.spline!!.points[i]
            near(centre + (Vec2(pts[i].x, pts[i].y) - centre) * 0.5f, Vec2(q.x, q.y), what = "point $i")
            assertEquals(pts[i].width, q.width)
            assertEquals(pts[i].weight, q.weight)
        }
        assertTrue(t.spline!!.points[2].sharp)
        t.commit()
        val p = layer.vector!!.objects.single() as VPath
        assertTrue("I9", SplineBezier.matches(p))
        layer.vector = VectorCodec.decode(VectorCodec.encode(layer.vector!!))
        c.tap(onLine(p))
        assertEquals(ToolId.PATH, c.activeToolId)
        assertTrue(t.isReopened)
        assertEquals(p.spline, t.spline)
        t.discard()
    }

    @Test
    fun aPolylineMovedThroughThePillAndAGroupOfAPath() {
        val c = controller()
        val t = c.tool(ToolId.POLYLINE)
        for ((x, y) in five.take(3)) assertTrue(t.addAnchor(Vec2(x, y)))
        t.deselect()
        val before = t.anchors.map { it.pos }
        val at = t.pillPosition.position!!
        val n = steps(t)
        t.pillPosition.setPosition(at.x - 20f, at.y + 5f)
        assertEquals(before.map { it + Vec2(-20f, 5f) }, t.anchors.map { it.pos })
        assertEquals("one step", n + 1, steps(t))
        // One point: "Point 2", as v1.6.
        t.select(1)
        assertEquals("Point 2", t.pillPosition.label)
        t.pillPosition.setPosition(10f, null)
        assertEquals(10f, t.anchors[1].x)
        t.discard()

        // A Path with 3 of 5 selected: X moves the group (and only it).
        val path = c.tool(ToolId.PATH)
        path.plainLine()
        for ((x, y) in five) c.tap(x, y)
        path.selectPoints(PointSelection.of(5, 1, 2, 3))
        assertEquals(CurveTool.SELECTED_POINTS_LABEL, path.pillPosition.label)
        val box = path.pillPosition.position!!
        assertEquals((110f + 270f) / 2f, box.x, 1e-4f)
        val was = path.spline!!.points
        path.pillPosition.setPosition(box.x + 30f, null)
        val now = path.spline!!.points
        for (i in was.indices) assertEquals("point $i", if (i in 1..3) was[i].x + 30f else was[i].x, now[i].x, 1e-4f)
        assertEquals(was.map { it.y }, now.map { it.y })
        // The Scale row scales the group about its box centre.
        path.objectScale!!.setScale(200f, 100f)
        assertEquals(was[0], path.spline!!.points[0])
        assertEquals(was[4], path.spline!!.points[4])
        assertEquals(box.x + 30f + (was[1].x + 30f - box.x - 30f) * 2f, path.spline!!.points[1].x, 1e-3f)
        path.discard()
    }

    @Test
    fun theTrashCellLabelsAndDeletes() {
        val c = controller()
        for ((id, noun) in listOf(ToolId.CURVE to "curve", ToolId.POLYLINE to "polyline", ToolId.PATH to "path")) {
            val t = c.tool(id)
            t.plainLine()
            assertNull(t.objectDeletion)
            for ((x, y) in five) assertTrue(t.addAnchor(Vec2(x, y)))
            t.deselect()
            val del = t.objectDeletion!!
            assertEquals("$noun, none selected", PillLabels.deleteObject(noun), del.deleteLabel)
            t.selectPoints(PointSelection.of(5, 1))
            assertEquals("$noun, some", PillLabels.DELETE_POINTS, del.deleteLabel)
            t.selectPoints(PointSelection.of(5, 0, 1, 2))
            assertEquals("$noun, some", PillLabels.DELETE_POINTS, del.deleteLabel)
            t.selectPoints(PointSelection.all(5))
            assertEquals("$noun, all", PillLabels.deleteObject(noun), del.deleteLabel)
            t.discard()
        }
        assertEquals("Delete path", PillLabels.deleteObject("path"))

        // 3 of 5 path points: 2 stay, one in-tool step.
        val t = c.tool(ToolId.PATH)
        for ((x, y) in five) c.tap(x, y)
        t.selectPoints(PointSelection.of(5, 1, 2, 3))
        val n = steps(t)
        t.objectDeletion!!.delete()
        assertEquals(2, t.spline!!.points.size)
        assertEquals(n + 1, steps(t))

        // Every point selected: the path goes, no minimum-count refusal; the undo brings it back.
        t.selectPoints(PointSelection.all(2))
        c.message = null
        t.objectDeletion!!.delete()
        assertEquals(0, t.pointCount)
        assertNull(c.message)
        assertNull("nothing open", t.objectDeletion)
        assertTrue(t.hasPendingWork)
        c.undo()
        assertEquals(2, t.pointCount)
        t.commit()
    }

    @Test
    fun anOpenPathWithNoPointSelectedOffersDeletePathAndOneUndoRestoresIt() {
        val c = controller()
        val layer = c.activeLayer
        val t = c.tool(ToolId.PATH)
        t.plainLine()
        for ((x, y) in five) c.tap(x, y)
        t.commit()
        val p = layer.vector!!.objects.single() as VPath
        c.tap(onLine(p))
        assertTrue(t.isReopened)
        t.deselect()
        val del = t.objectDeletion!!
        assertEquals("Delete path", del.deleteLabel)
        del.delete()
        assertFalse(t.isReopened)
        assertTrue("gone from the layer", layer.vector!!.objects.isEmpty())
        assertEquals("Delete path", c.undoManager.undoLabel)
        c.undo()
        assertEquals(1, layer.vector!!.objects.size)
        assertEquals(p.spline, (layer.vector!!.objects.single() as VPath).spline)
        // Reopened again with every point selected: the same, no refusal.
        c.tap(onLine(p))
        assertTrue(t.isReopened)
        t.selectPoints(PointSelection.all(t.pointCount))
        c.message = null
        assertEquals("Delete path", t.objectDeletion!!.deleteLabel)
        t.objectDeletion!!.delete()
        assertTrue(layer.vector!!.objects.isEmpty())
        assertNull(c.message)
        assertSame(t, c.tools.getValue(ToolId.PATH))
    }
}
