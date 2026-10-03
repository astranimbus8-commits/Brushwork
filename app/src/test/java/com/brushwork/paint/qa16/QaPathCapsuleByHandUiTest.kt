package com.brushwork.paint.qa16

import android.view.MotionEvent
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.Smoke.P
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.docLength
import com.brushwork.paint.tools.vector.spline.SplineBezier
import com.brushwork.paint.tools.vector.spline.SplinePresets
import com.brushwork.paint.tools.vector.spline.SplineTestSupport
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.math.abs
import kotlin.math.max

/**
 * v1.6 final QA (Path tool): the user's Blender capsule built point by point on the real editor
 * at the user's phone size — the twelve control points of Blender's capsule placed with the
 * finger (where a neighbour already sits within the touch radius, as Blender's pairs of points
 * 0.13 × the height apart always do at a size that fits the screen, the finger goes down beside
 * the curve and drags the new point onto its place), Cyclic, Fill on, No stroke, ✓ — and a
 * control point placed with the X / Y pill: typed exactly, dragged in Length steps and in Fine
 * mode with "#" on, and dragged freely (v1.5) with "#" off. Then the same tool on a raster layer:
 * ✓ paints one undo step and leaves no vector data; Undo / Redo in the top row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.capsulebyhandsandbox"])
class QaPathCapsuleByHandUiTest {

    private val grey = 0xFF8C8C8C.toInt()
    private val centre = Vec2(200f, 150f)
    private val height = 100f

    /** Drags on screen from window px ([x0], [y0]) through [to] (window px), one event per 16 ms; [held] runs before the lift. */
    private fun fingerOnScreen(s: ChromeScreen, x0: Float, y0: Float, to: List<Pair<Float, Float>>, held: () -> Unit = {}) {
        s.touch.idle(400)
        s.touch.send(MotionEvent.ACTION_DOWN, P(0, x0, y0))
        var last = x0 to y0
        for (p in to) {
            for (i in 1..8) {
                s.touch.idle(16)
                s.touch.send(MotionEvent.ACTION_MOVE, P(0, last.first + (p.first - last.first) * i / 8, last.second + (p.second - last.second) * i / 8))
            }
            last = p
        }
        s.touch.idle(16)
        settle(2)
        held()
        s.touch.send(MotionEvent.ACTION_UP, P(0, last.first, last.second))
        s.touch.idle(50)
        settle()
    }

    @Test
    fun theBlenderCapsuleByHandThePillAndARasterLayer() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 60_000)
        val h = ChromeHarness()
        h.section("twelve points by hand, the X / Y pill, Cyclic, Fill, No stroke, ✓ on a vector layer") {
            val s = h.editor()
            val c = s.c
            click("Vector", exact = true)
            assertTrue(c.activeLayer.isVectorLayer)
            QaCurves.tool(s, "Path")
            assertEquals(ToolId.PATH, c.activeToolId)
            val tool = c.currentTool as CurveTool
            c.color = grey
            settle()
            QaCurves.snapOff(c)
            assertFalse("increments off (v1.5)", c.increments.enabled)
            val target = SplinePresets.capsule(centre, height).map { Vec2(it.x, it.y) }
            val radius = c.docLength(24f * tool.handleSize)
            for ((i, p) in target.withIndex()) {
                val crowded = tool.spline?.points?.any { Vec2(it.x, it.y).distanceTo(p) <= radius } == true
                if (!crowded) {
                    QaCurves.tap(s, p)
                } else {
                    // A tap here would grab the neighbour: down beside the curve (outwards), drag onto the place.
                    val out = if (p.y < centre.y) -60f else 60f
                    QaCurves.drag(s, Vec2(p.x, p.y + out), Vec2(0f, -out))
                }
                assertEquals("point ${i + 1} added", i + 1, tool.spline!!.points.size)
                val q = tool.spline!!.points[i]
                assertEquals("point ${i + 1} x", p.x, q.x, 0.05f)
                assertEquals("point ${i + 1} y", p.y, q.y, 0.05f)
            }
            // The pairs 0.13 × the height apart are inside one touch radius here: the case above ran.
            assertTrue("Blender's close pairs need the drag: ${target[0].distanceTo(target[1])} vs $radius", target[0].distanceTo(target[1]) < radius)

            // ---------------------------------------------------------------- the X / Y pill on point 1
            QaCurves.tap(s, target[0])
            assertEquals("the tap selected point 1", 0, tool.selectedPoint)
            assertEquals(12, tool.spline!!.points.size)
            click("Type X")
            assertTrue("\"X position\": ${SmokeUi.shown()}", has("X position", exact = true))
            SmokeUi.typeAndDone("X", "123.4")
            assertEquals("typed: exact", 123.4f, tool.spline!!.points[0].x, 1e-4f)
            click("Undo last point")
            assertEquals("one in-tool step", target[0].x, tool.spline!!.points[0].x, 1e-4f)
            // "#" on (the default steps: Length 10 px); a drag of the X cell lands on multiples of 10.
            click("Increments", exact = true)
            assertTrue(c.increments.enabled)
            val zoom = c.viewTransform.zoom
            val cell = QaCurves.slider("X slider").bounds
            val x0 = cell.center.x
            val y0 = cell.center.y
            fingerOnScreen(s, x0, y0, listOf(x0 + 67f to y0))
            val stepped = tool.spline!!.points[0].x
            val raw = target[0].x + 67f / zoom
            assertTrue("on a 10 px step: $stepped", QaCurves.onStep(stepped, 10f))
            assertTrue("the nearest step to $raw: $stepped", abs(stepped - raw) <= 5f + 1e-3f)
            // Fine: far below the cell a tenth of the speed — and still on the steps.
            val far = 60f * s.density
            var sawFine = false
            val fineStart = QaCurves.slider("X slider").bounds.center
            fingerOnScreen(s, fineStart.x, fineStart.y, listOf(fineStart.x to fineStart.y + far, fineStart.x + 300f to fineStart.y + far)) {
                sawFine = has("Fine", exact = true)
            }
            assertTrue("\"Fine\" while the finger was far below", sawFine)
            assertFalse("\"Fine\" goes with the finger", has("Fine", exact = true))
            val fine = tool.spline!!.points[0].x
            assertTrue("fine: still a 10 px step: $fine", QaCurves.onStep(fine, 10f))
            assertTrue("fine: a tenth of 300 px (${300f / zoom / 10f} doc px) from $stepped: $fine", abs(fine - (stepped + 300f / zoom / 10f)) <= 5f + 1e-3f)
            // "#" off: the drag is the finger's (v1.5).
            click("Increments", exact = true)
            assertFalse(c.increments.enabled)
            val before = tool.spline!!.points[0].x
            val b = QaCurves.slider("X slider").bounds.center
            fingerOnScreen(s, b.x, b.y, listOf(b.x - 67f to b.y))
            assertEquals("free: −67 screen px", before - 67f / zoom, tool.spline!!.points[0].x, 0.02f)
            // Back to its place, typed (exact).
            click("Type X")
            SmokeUi.typeAndDone("X", target[0].x.toString())
            assertEquals(target[0].x, tool.spline!!.points[0].x, 1e-4f)

            // ---------------------------------------------------------------- Cyclic, Fill, No stroke
            click("Cyclic", exact = true)
            assertTrue(tool.spline!!.cyclic)
            assertEquals("the default order is Blender's capsule's", 4, tool.spline!!.order)
            if (!tool.settings.fill) click("Fill", exact = true)
            assertTrue(tool.settings.fill)
            click("Stroke", exact = true)
            click(CurveStroke.NONE.label, exact = true)
            assertEquals(CurveStroke.NONE, tool.settings.stroke)
            val sp = tool.spline!!
            var worst = 0f
            for (q in SplineTestSupport.splineSamples(sp, 4000)) worst = max(worst, QaCurves.stadiumDistance(q, centre, height))
            assertTrue("within 1 % of a stadium: ${worst / height * 100f} %", worst <= 0.01f * height)
            val poly = SplineTestSupport.polyline(SplineBezier.toSubpath(sp))
            assertEquals("3 : 1", 3f, (poly.maxOf { it.x } - poly.minOf { it.x }) / (poly.maxOf { it.y } - poly.minOf { it.y }), 0.03f)

            // ---------------------------------------------------------------- ✓
            val steps = c.undoManager.undoCount
            click("Apply path edit")
            assertEquals("one step", steps + 1, c.undoManager.undoCount)
            assertEquals(CurveTool.PATH_LABEL, c.undoManager.undoLabel)
            val p = c.activeLayer.vector!!.objects.single() as VPath
            assertEquals(sp, p.spline)
            assertTrue("I9", SplineBezier.matches(p))
            assertEquals(VPaint.Solid(grey), p.fill)
            assertNull(p.stroke)
            assertEquals("painted where the capsule is", grey, c.activeLayer.bitmap.getPixel(centre.x.toInt(), centre.y.toInt()))
            assertEquals("not outside it", 0, c.activeLayer.bitmap.getPixel(centre.x.toInt(), (centre.y - height * 0.7f).toInt()))
            QaCurves.shot(s, "path-capsule-byhand")
            Smoke.assertQuiet(c, "capsule by hand")
        }
        h.section("a raster layer: ✓ paints one step, no vector data; top-row Undo / Redo") {
            val s = h.editor()
            val c = s.c
            assertFalse("layer 2 is a raster layer", c.activeLayer.isVectorLayer)
            QaCurves.tool(s, "Path")
            val tool = c.currentTool as CurveTool
            c.color = grey
            settle()
            click("Path shapes", exact = true)
            click("Capsule", exact = true)
            assertEquals(12, tool.spline!!.points.size)
            if (!tool.settings.fill) click("Fill", exact = true)
            val steps = c.undoManager.undoCount
            click("Apply path edit")
            assertEquals(steps + 1, c.undoManager.undoCount)
            // (A fill alone keeps its own name, as with the Curve tool since v1.5.)
            assertEquals("Fill path", c.undoManager.undoLabel)
            assertNull("no vector data on a raster layer", c.activeLayer.vector)
            fun ink() = (0 until 300 step 2).sumOf { y -> (0 until 400 step 2).count { x -> c.activeLayer.bitmap.getPixel(x, y) == grey } }
            val painted = ink()
            assertTrue("painted: $painted", painted > 1000)
            QaCurves.shot(s, "path-capsule-raster")
            click("Undo", exact = true)
            settle()
            assertEquals("undo clears it", 0, ink())
            click("Redo", exact = true)
            settle()
            assertEquals("redo paints it again", painted, ink())
            // A plain line (no fill): the step is named after the tool.
            click("Path shapes", exact = true)
            click("Circle", exact = true)
            assertEquals(SplinePresets.CIRCLE_POINTS, tool.spline!!.points.size)
            click("Stroke", exact = true)
            click(CurveStroke.PLAIN.label, exact = true)
            if (tool.settings.fill) click("Fill", exact = true)
            val n = c.undoManager.undoCount
            click("Apply path edit")
            assertEquals(n + 1, c.undoManager.undoCount)
            assertEquals(CurveTool.PATH_LABEL, c.undoManager.undoLabel)
            assertNull(c.activeLayer.vector)
            Smoke.assertQuiet(c, "raster")
        }
        dog.interrupt()
        h.finish()
    }
}
