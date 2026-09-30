package com.brushwork.paint.tools.transform

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.GridSettings
import com.brushwork.paint.model.GridType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Smart guide math (pure): snapping a moving box to the canvas, other objects and the grid. */
class SnapGuidesTest {
    private val eps = 1e-4f

    /** 1000 x 800 canvas (lines far away) with one other object at (200, 100)-(300, 160). */
    private val other = SnapBox(DocBox(200f, 100f, 300f, 160f), "Layer 2")
    private val targets = SnapTargets.build(1000f, 800f, listOf(other))

    private fun box(l: Float, t: Float, w: Float, h: Float) = DocBox(l, t, l + w, t + h)

    @Test
    fun snapsToTheTopCenterAndBottomOfAnotherObject() {
        // Top edge 5 px below the other object's top.
        var r = SnapGuides.snapMove(box(20f, 105f, 40f, 20f), targets, 8f)
        assertEquals(0f, r.dx, eps)
        assertEquals(-5f, r.dy, eps)
        assertTrue(r.guides.any { it.axis == SnapAxis.Y && it.pos == 100f && it.label == "Layer 2 top" })
        // Vertical centers (object center y = 130): box center 127 -> 130.
        r = SnapGuides.snapMove(box(20f, 117f, 40f, 20f), targets, 8f)
        assertEquals(3f, r.dy, eps)
        assertTrue(r.guides.any { it.axis == SnapAxis.Y && it.pos == 130f && it.label == "Layer 2 center" })
        // Bottom edges (160): box bottom 163 -> 160.
        r = SnapGuides.snapMove(box(20f, 123f, 40f, 40f), targets, 8f)
        assertEquals(-3f, r.dy, eps)
        assertEquals(listOf("Layer 2 bottom"), r.guides.map { it.label })
    }

    @Test
    fun snapsToTheLeftCenterAndRightOfAnotherObject() {
        var r = SnapGuides.snapMove(box(196f, 250f, 30f, 20f), targets, 8f)
        assertEquals(4f, r.dx, eps)
        assertEquals(0f, r.dy, eps)
        assertTrue(r.guides.any { it.axis == SnapAxis.X && it.pos == 200f && it.label == "Layer 2 left" })
        // Centers: object center x = 250, box center 243 -> 250.
        r = SnapGuides.snapMove(box(228f, 250f, 30f, 20f), targets, 8f)
        assertEquals(7f, r.dx, eps)
        assertTrue(r.guides.any { it.axis == SnapAxis.X && it.pos == 250f && it.label == "Layer 2 center" })
        // Right edges: box right 305 -> 300.
        r = SnapGuides.snapMove(box(275f, 250f, 30f, 20f), targets, 8f)
        assertEquals(-5f, r.dx, eps)
        assertTrue(r.guides.any { it.axis == SnapAxis.X && it.pos == 300f && it.label == "Layer 2 right" })
        // Side by side: box left 303 -> the object's right edge 300.
        r = SnapGuides.snapMove(box(303f, 250f, 30f, 20f), targets, 8f)
        assertEquals(-3f, r.dx, eps)
    }

    @Test
    fun snapsToTheCanvasCenterAndEdgesWithGuidesAcrossTheCanvas() {
        val canvas = SnapTargets.build(400f, 300f, emptyList())
        // 50 x 40 box centered 4 px off the canvas center (200, 150) on both axes.
        val r = SnapGuides.snapMove(box(179f, 126f, 50f, 40f), canvas, 8f)
        assertEquals(-4f, r.dx, eps)
        assertEquals(4f, r.dy, eps)
        val vx = r.guides.single { it.axis == SnapAxis.X }
        assertEquals(200f, vx.pos, eps)
        assertEquals("Canvas center", vx.label)
        assertEquals(SnapSource.CANVAS, vx.source)
        // A canvas guide spans the whole canvas.
        assertEquals(0f, vx.start, eps)
        assertEquals(300f, vx.end, eps)
        assertEquals(150f, r.guides.single { it.axis == SnapAxis.Y }.pos, eps)
        // Edges.
        val e = SnapGuides.snapMove(box(-3f, 293f, 20f, 4f), canvas, 8f)
        assertEquals(3f, e.dx, eps)
        assertEquals(3f, e.dy, eps)
        assertEquals(2, e.guides.size)
        assertTrue(e.guides.all { it.label == "Canvas edge" })
    }

    @Test
    fun objectGuidesSpanBothObjects() {
        // 10 px from the object's left edge: no snap.
        assertFalse(SnapGuides.snapMove(box(210f, 20f, 30f, 20f), targets, 8f).snappedX)
        val s = SnapGuides.snapMove(box(204f, 20f, 30f, 20f), targets, 8f)
        val g = s.guides.single { it.axis == SnapAxis.X }
        assertEquals(200f, g.pos, eps)
        assertEquals(20f, g.start, eps)   // from the moving box's top
        assertEquals(160f, g.end, eps)    // to the object's bottom
    }

    @Test
    fun movingBeyondTheThresholdEscapesTheSnap() {
        // 8.5 px from the object's left edge (and nothing else near): free.
        val r = SnapGuides.snapMove(box(208.5f, 250f, 3f, 3f), targets, 8f)
        assertEquals(0f, r.dx, 0f)
        assertEquals(0f, r.dy, 0f)
        assertTrue(r.guides.isEmpty())
        // At exactly the threshold it still snaps.
        assertEquals(-8f, SnapGuides.snapMove(box(208f, 250f, 3f, 3f), targets, 8f).dx, eps)
    }

    @Test
    fun theClosestLineWinsAndTiesGoToTheCanvas() {
        // Left 194 (6 from 200), center 199 (1 from 200), right 204 (4 from 200): the center wins.
        assertEquals(1f, SnapGuides.snapMove(box(194f, 250f, 10f, 10f), targets, 8f).dx, eps)
        // Canvas center and an object edge on the same line: the canvas comes first.
        val t2 = SnapTargets.build(400f, 300f, listOf(SnapBox(DocBox(200f, 0f, 250f, 10f), "A")))
        val hit = SnapGuides.snapValue(197f, SnapAxis.X, t2, 8f)
        assertNotNull(hit)
        assertEquals(SnapSource.CANVAS, hit!!.line.source)
        // ...and both show as ONE guide.
        val g = SnapGuides.snapMove(box(177f, 250f, 40f, 10f), t2, 8f)
        assertEquals(3f, g.dx, eps)
        assertEquals(1, g.guides.count { it.axis == SnapAxis.X && it.pos == 200f })
    }

    @Test
    fun nudgesStopOnTheLinesTheyWouldJumpOver() {
        // Box right edge at 290; a 20 px nudge right would jump over the object's right edge
        // (300): it stops there.
        val r = SnapGuides.snapNudge(box(260f, 250f, 30f, 10f), 20f, 0f, targets)
        assertEquals(10f, r.dx, eps)
        assertTrue(r.guides.any { it.axis == SnapAxis.X && it.pos == 300f })
        // The line it is on doesn't hold it: the next nudge goes on (to where its center meets 300).
        assertEquals(15f, SnapGuides.snapNudge(box(270f, 250f, 30f, 10f), 20f, 0f, targets).dx, eps)
        // One-pixel nudges always move exactly one pixel.
        for (x in 190..210) assertEquals(-1f, SnapGuides.snapNudge(box(x.toFloat(), 250f, 30f, 10f), -1f, 0f, targets).dx, eps)
        // Upward, crossing the object's bottom (160) from 175.
        val up = SnapGuides.snapNudge(box(20f, 175f, 10f, 10f), 0f, -40f, targets)
        assertEquals(0f, up.dx, 0f)
        assertEquals(-15f, up.dy, eps)
        // Nothing in the way: the full step.
        assertEquals(-40f, SnapGuides.snapNudge(box(600f, 700f, 10f, 10f), -40f, 0f, targets).dx, eps)
    }

    @Test
    fun freePointsSnapOnEachAxis() {
        val (p, guides) = SnapGuides.snapPoint(Vec2(203f, 97f), targets, 8f)
        assertEquals(Vec2(200f, 100f), p)
        assertEquals(2, guides.size)
        val (q, none) = SnapGuides.snapPoint(Vec2(120f, 60f), targets, 8f)
        assertEquals(Vec2(120f, 60f), q)
        assertTrue(none.isEmpty())
    }

    @Test
    fun gridLinesWhenGridSnappingIsOn() {
        val square = SnapGuides.gridLines(GridSettings(enabled = true, spacingPx = 50f, offsetXPx = 10f, snap = true), 400, 300)!!
        val t = SnapTargets.build(400f, 300f, emptyList(), square, includeCanvas = false)
        val hit = SnapGuides.snapValue(113f, SnapAxis.X, t, 8f)!!
        assertEquals(110f, hit.pos, eps)
        assertEquals(SnapSource.GRID, hit.line.source)
        assertEquals(100f, SnapGuides.snapValue(96f, SnapAxis.Y, t, 8f)!!.pos, eps)
        assertNull(SnapGuides.snapValue(135f, SnapAxis.X, t, 8f))
        // A grid guide spans the canvas.
        val g = SnapGuides.guidesFor(DocBox(110f, 20f, 120f, 30f), t, xEdges = listOf(SnapEdge.START), yEdges = emptyList()).single()
        assertEquals(0f, g.start, eps)
        assertEquals(300f, g.end, eps)
        // Nudging over grid lines stops on each.
        assertEquals(7f, SnapGuides.snapNudge(DocBox(103f, 0f, 103f, 1f), 30f, 0f, t).dx, eps)
        assertEquals(-3f, SnapGuides.snapNudge(DocBox(63f, 0f, 63f, 1f), -30f, 0f, t).dx, eps)

        val thirds = SnapGuides.gridLines(GridSettings(enabled = true, type = GridType.RULE_OF_THIRDS), 300, 300)!!
        assertEquals(listOf(100f, 200f), thirds.fixedX)
        val tt = SnapTargets.build(300f, 300f, emptyList(), thirds)
        val h3 = SnapGuides.snapValue(104f, SnapAxis.X, tt, 8f)!!
        assertEquals(100f, h3.pos, eps)
        assertEquals("Grid", h3.line.label)

        assertNull(SnapGuides.gridLines(GridSettings(enabled = false), 300, 300))
        assertNull(SnapGuides.gridLines(GridSettings(enabled = true, type = GridType.DIAGONAL), 300, 300))
        assertNotNull(SnapGuides.gridLines(GridSettings(enabled = true, type = GridType.ISOMETRIC), 300, 300)!!.x)
    }

    @Test
    fun nonFiniteInputIsIgnored() {
        assertNull(SnapGuides.snapValue(Float.NaN, SnapAxis.X, targets, 8f))
        assertNull(SnapGuides.snapValue(10f, SnapAxis.X, targets, Float.NaN))
        val t = SnapTargets.build(400f, 300f, listOf(SnapBox(DocBox(Float.NaN, 0f, 1f, 1f), "bad")))
        assertEquals(6, t.xs.size + t.ys.size) // only the canvas lines
        val r = SnapGuides.snapNudge(DocBox(0f, 0f, 1f, 1f), Float.NaN, 0f, t)
        assertTrue(r.dx.isNaN()) // passed through: the caller ignores non-finite moves
    }
}
