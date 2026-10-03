package com.brushwork.paint.ui.vector

import android.graphics.RectF
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.spline.SplineBezier
import com.brushwork.paint.tools.vector.spline.SplineEditing
import com.brushwork.paint.tools.vector.spline.SplinePresets
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.vector.VSplinePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.math.hypot
import kotlin.math.min

/**
 * v1.6 §3.2a: the Path tool's Circle and Capsule quick starts size and centre themselves from the
 * part of the canvas that shows — the canvas view less the chrome over it (the area the fit
 * centres in), cut to the canvas — not from the window's root view. Zoomed in on the real editor
 * at the user's phone size, the shape sits in the middle of the free area and is 60 % of it.
 */
// Own sandbox (the test recomposer policy and paused Choreographer are global); the user's phone size.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.vector.pathquickstartsandbox"])
class PathQuickStartViewportUiRobolectricTest {

    /** What the free area [free] (canvas view px) shows of the canvas, as a rectangle (no turn). */
    private fun shownDoc(s: ChromeScreen, free: RectF): RectF {
        val a = s.c.viewTransform.screenToDoc(free.left, free.top)
        val b = s.c.viewTransform.screenToDoc(free.right, free.bottom)
        val r = RectF(min(a.x, b.x), min(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
        assertTrue(r.intersect(0f, 0f, s.c.doc.width.toFloat(), s.c.doc.height.toFloat()))
        return r
    }

    private fun assertPoints(where: String, expected: List<VSplinePoint>, actual: List<VSplinePoint>) {
        assertEquals("$where: point count", expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("$where: point $i x", expected[i].x, actual[i].x, 1e-3f)
            assertEquals("$where: point $i y", expected[i].y, actual[i].y, 1e-3f)
        }
    }

    /** Every on-curve anchor of the tool's path is inside [free] on screen (canvas view px). */
    private fun assertOnScreen(where: String, s: ChromeScreen, tool: CurveTool, free: RectF) {
        val anchors = SplineBezier.toSubpath(tool.spline!!).anchors
        for (a in anchors) {
            val p = s.c.viewTransform.docToScreen(a.x, a.y)
            assertTrue("$where: ($p) inside the free area $free", free.contains(p.x, p.y))
        }
    }

    private fun quickStart(label: String) {
        click("Path shapes", exact = true)
        click(label, exact = true)
        settle()
    }

    @Test
    fun theQuickStartsGoWhereTheCanvasShows() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        val h = ChromeHarness()
        h.section("fitted: the whole canvas shows, so the shape is on the canvas's middle") {
            val s = h.editor()
            s.c.selectTool(ToolId.PATH)
            settle()
            val tool = s.c.tools.getValue(ToolId.PATH) as CurveTool
            val free = s.canvas.freeArea()
            assertTrue("the chrome leaves a band of the canvas view: $free in ${s.canvas.width} x ${s.canvas.height}", free.top > 0f && free.bottom < s.canvas.height)
            val area = shownDoc(s, free)
            assertEquals("the fit shows the whole canvas", RectF(0f, 0f, 400f, 300f), area)
            quickStart("Circle")
            assertPoints("circle", SplinePresets.circleIn(area).let { (c, r) -> SplinePresets.circle(c, r) }.map { SplineEditing.clean(it) }, tool.spline!!.points)
            assertOnScreen("circle", s, tool, free)
            tool.discard()
            settle()
        }
        h.section("zoomed in: the shape is in the middle of what shows, sized to it") {
            val s = h.editor()
            s.c.selectTool(ToolId.PATH)
            settle()
            val tool = s.c.tools.getValue(ToolId.PATH) as CurveTool
            // Two fingers spread around a point right of and below the canvas's middle (an
            // empty Path tool leaves a pinch to the view).
            val z0 = s.canvas.zoom
            val a0 = s.screen(250f, 190f)
            val b0 = s.screen(290f, 190f)
            s.touch.idle(300)
            s.touch.pinch(a0, b0, a0.first - 180f to a0.second, b0.first + 180f to b0.second)
            settle()
            assertTrue("the view zoomed in: $z0 → ${s.canvas.zoom}", s.canvas.zoom > z0 * 2f)
            val free = s.canvas.freeArea()
            val area = shownDoc(s, free)
            assertTrue("only part of the canvas shows: $area", area.width() < 200f && area.height() < 200f)
            val toolArea = tool.shapeArea(free)
            for ((name, e, a) in listOf(Triple("left", area.left, toolArea.left), Triple("top", area.top, toolArea.top), Triple("right", area.right, toolArea.right), Triple("bottom", area.bottom, toolArea.bottom))) {
                assertEquals("the tool's area is what shows ($name)", e, a, 1e-3f)
            }
            // (v1.6 before the fix: the window's root view, chrome bands and all, put the
            // shape's middle elsewhere — under the bands' difference.)
            val root = s.canvas.rootView
            val old = s.c.viewTransform.visibleDocRect(root.width, root.height).also { it.intersect(0f, 0f, 400f, 300f) }
            assertTrue(
                "the root view's middle ${old.centerX()}, ${old.centerY()} is not the free area's ${area.centerX()}, ${area.centerY()}",
                hypot(old.centerX() - area.centerX(), old.centerY() - area.centerY()) > 1f,
            )

            quickStart("Circle")
            assertPoints("circle", SplinePresets.circleIn(area).let { (c, r) -> SplinePresets.circle(c, r) }.map { SplineEditing.clean(it) }, tool.spline!!.points)
            assertOnScreen("circle", s, tool, free)
            // On screen: centred in the free area, 60 % of its narrower side across.
            val ctr = s.c.viewTransform.docToScreen(area.centerX(), area.centerY())
            assertEquals(free.centerX(), ctr.x, 1f)
            assertEquals(free.centerY(), ctr.y, 1f)
            val pts = SplineBezier.toSubpath(tool.spline!!).anchors.map { s.c.viewTransform.docToScreen(Vec2(it.x, it.y)) }
            val r = pts.maxOf { hypot(it.x - free.centerX(), it.y - free.centerY()) }
            assertEquals("the circle is 60 % of the free area across", 0.3f * min(free.width(), free.height()), r, 0.02f * r)
            assertTrue(tool.undoStep())
            settle()

            quickStart("Capsule")
            assertPoints("capsule", SplinePresets.capsuleIn(area).let { (c, hh) -> SplinePresets.capsule(c, hh) }.map { SplineEditing.clean(it) }, tool.spline!!.points)
            assertOnScreen("capsule", s, tool, free)
            tool.discard()
            settle()
            Smoke.assertQuiet(s.c, "quick starts")
        }
        dog.interrupt()
        h.finish()
    }
}
