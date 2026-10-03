package com.brushwork.paint.qa16

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.exchange.VectorFormat
import com.brushwork.paint.exchange.export.ExportOptions
import com.brushwork.paint.exchange.export.ExportSceneBuilder
import com.brushwork.paint.exchange.export.SvgWriter
import com.brushwork.paint.exchange.export.TextSource
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveKind
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.spline.SplineBezier
import com.brushwork.paint.tools.vector.spline.SplinePresets
import com.brushwork.paint.tools.vector.spline.SplineTestSupport
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VSpline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.max

/**
 * v1.6 final QA (Path tool, "more like a blender path"): the user's Blender capsule rebuilt with
 * the Path tool's quick start on the real editor at the user's phone size, through what a finger
 * reaches — Vector in the top row, Path in the tool menu, Shapes ▾ › Capsule, Order ‹ ›,
 * Endpoint / Cyclic, a control point tapped, its Weight and Thickness typed and stepped, ✓ — then
 * saved and loaded back, reopened with a tap and edited ("Edit path"), handed to the Curve tool
 * with To Bézier (✓ stores a plain Bézier path and says so), undone and redone from the top row,
 * and exported to SVG.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.capsulequickstartsandbox"])
class QaPathCapsuleQuickStartUiTest {

    private val grey = 0xFF8C8C8C.toInt()

    @Test
    fun theBlenderCapsuleByQuickStartSavedReopenedConvertedAndExported() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 60_000)
        val h = ChromeHarness()
        var savedId: String? = null
        var saved: VSpline? = null
        h.section("quick start on a vector layer, the strip, ✓, save") {
            val s = h.editor()
            val c = s.c
            // Vector (top row): the empty layer 2 becomes a vector layer.
            click("Vector", exact = true)
            assertTrue("a vector layer", c.activeLayer.isVectorLayer)
            QaCurves.tool(s, "Path")
            assertEquals(ToolId.PATH, c.activeToolId)
            val tool = c.currentTool as CurveTool
            assertEquals(CurveKind.PATH, tool.kind)
            c.color = grey
            settle()

            // Shapes ▾ › Capsule: 12 points, cyclic, order 4, 60 % of what shows, within 1 % of a stadium.
            assertTrue("Shapes ▾ while empty: ${SmokeUi.shown()}", has("Path shapes", exact = true))
            val area = tool.shapeArea(s.canvas.freeArea())
            click("Path shapes", exact = true)
            click("Capsule", exact = true)
            val sp = tool.spline!!
            assertEquals(12, sp.points.size)
            assertTrue(sp.cyclic)
            assertEquals(4, sp.order)
            val (centre, height) = SplinePresets.capsuleIn(area)
            var worst = 0f
            for (q in SplineTestSupport.splineSamples(sp, 4000)) worst = max(worst, QaCurves.stadiumDistance(q, centre, height))
            assertTrue("the curve is within 1 % of a stadium: ${worst / height * 100f} %", worst <= 0.01f * height)
            var drawn = 0f
            val poly = SplineTestSupport.polyline(SplineBezier.toSubpath(sp))
            for (q in poly) drawn = max(drawn, QaCurves.stadiumDistance(q, centre, height))
            assertTrue("what is drawn is within 1 %: ${drawn / height * 100f} %", drawn <= 0.01f * height)
            val w = poly.maxOf { it.x } - poly.minOf { it.x }
            val hh = poly.maxOf { it.y } - poly.minOf { it.y }
            assertEquals("3 : 1", 3f, w / hh, 0.03f)
            assertFalse("Shapes ▾ goes once a point exists", has("Path shapes", exact = true))
            assertTrue(has("To Bézier", exact = true))
            assertFalse("Endpoint is greyed while Cyclic is on", SmokeUi.isEnabled("Endpoint"))
            QaCurves.shot(s, "path-capsule-quickstart")

            // Order ‹ ›: in-tool steps; the strip reads the order.
            click("Higher order")
            assertEquals(5, tool.spline!!.order)
            assertTrue(has("Order 5", exact = true))
            click("Lower order")
            click("Lower order")
            assertEquals(3, tool.spline!!.order)
            click("Undo last point")
            assertEquals("undo: back to 4", 4, tool.spline!!.order)
            click("Redo point")
            assertEquals("redo: 3", 3, tool.spline!!.order)
            click("Higher order")
            assertEquals(4, tool.spline!!.order)

            // Cyclic off: open, Endpoint comes back and switches the clamped ends; then back on.
            click("Cyclic", exact = true)
            assertFalse(tool.spline!!.cyclic)
            assertTrue("Endpoint usable on an open path", SmokeUi.isEnabled("Endpoint"))
            val ep = tool.spline!!.endpoint
            click("Endpoint", exact = true)
            assertEquals(!ep, tool.spline!!.endpoint)
            val openEnds = SplineBezier.toSubpath(tool.spline!!)
            assertFalse(openEnds.closed)
            click("Endpoint", exact = true)
            click("Cyclic", exact = true)
            assertTrue(tool.spline!!.cyclic)
            assertEquals("the points are the capsule's still", sp.points, tool.spline!!.points)

            // A tap on point 4 selects it: its Weight and Thickness show.
            val p3 = tool.spline!!.points[3]
            QaCurves.tap(s, p3.x, p3.y)
            assertEquals("the tap selected point 4 (no new point)", 3, tool.selectedPoint)
            assertEquals(12, tool.spline!!.points.size)
            assertTrue("Weight: ${SmokeUi.shown()}", has("Point weight 1", exact = true))
            assertTrue(has("Point thickness 100 %", exact = true))
            click("Type the point weight")
            SmokeUi.typeAndDone("Weight", "2.5")
            assertEquals("typed: exact", 2.5f, tool.spline!!.points[3].weight, 0f)
            click("Thicker point")
            assertEquals("› +5 %", 1.05f, tool.spline!!.points[3].width, 1e-5f)
            click("Type the point thickness")
            SmokeUi.typeAndDone("Thickness", "137")
            assertEquals("typed: exact", 1.37f, tool.spline!!.points[3].width, 1e-6f)
            assertTrue(has("Point thickness 137 %", exact = true))

            // ✓: one undo step "Path", one VPath whose Bézier form is its spline's (I9).
            val steps = c.undoManager.undoCount
            val pending = tool.spline!!
            click("Apply path edit")
            assertFalse(tool.hasPendingWork)
            assertEquals("one step", steps + 1, c.undoManager.undoCount)
            assertEquals(CurveTool.PATH_LABEL, c.undoManager.undoLabel)
            val p = c.activeLayer.vector!!.objects.single() as VPath
            assertEquals("the spline is kept", pending, p.spline)
            assertTrue("I9", SplineBezier.matches(p))
            assertEquals("Capsule: filled with the main color", VPaint.Solid(grey), p.fill)
            assertNull("Capsule: no line", p.stroke)
            val ink = (0 until 300 step 2).sumOf { y -> (0 until 400 step 2).count { x -> c.activeLayer.bitmap.getPixel(x, y) == grey } }
            assertTrue("the capsule is painted: $ink", ink > 1000)
            QaCurves.shot(s, "path-capsule-committed")

            // Save (what the gallery and the auto-save do).
            runBlocking { ProjectRepository(s.activity.application).save(c.doc, null) }
            savedId = c.doc.id
            saved = p.spline
            Smoke.assertQuiet(c, "capsule quick start")
        }
        h.section("load, reopen with a tap, edit, To Bézier, undo / redo, SVG") {
            val id = requireNotNull(savedId) { "nothing saved" }
            val app = org.robolectric.RuntimeEnvironment.getApplication()
            val loaded = runBlocking { ProjectRepository(app).load(id) }
            val s = h.editor(loaded)
            val c = s.c
            assertTrue("the vector layer is active again", c.activeLayer.isVectorLayer)
            val p0 = c.activeLayer.vector!!.objects.single() as VPath
            assertEquals("the spline survived save / load", saved, p0.spline)
            assertTrue(SplineBezier.matches(p0))

            QaCurves.tool(s, "Path")
            val path = c.currentTool as CurveTool
            // A tap on the outline reopens it.
            val onLine = SplineBezier.toSubpath(p0.spline!!).anchors[0]
            QaCurves.tap(s, onLine.x, onLine.y)
            assertTrue("reopened", path.isReopened)
            assertEquals(p0.spline, path.spline)
            // Drag point 1 down 12 px (Snap to objects off in the strip): a different path; ✓ = "Edit path".
            QaCurves.snapOff(c)
            val q0 = path.spline!!.points[0]
            QaCurves.drag(s, Vec2(q0.x, q0.y), Vec2(0f, 12f))
            assertEquals(q0.y + 12f, path.spline!!.points[0].y, 0.05f)
            click("Apply path edit")
            assertEquals(CurveTool.EDIT_PATH_LABEL, c.undoManager.undoLabel)
            val p1 = c.activeLayer.vector!!.objects.single() as VPath
            assertNotNull(p1.spline)
            assertTrue(SplineBezier.matches(p1))
            assertNotEquals(p0.spline, p1.spline)

            // Reopen › To Bézier: the Curve tool holds it; ✓ stores a plain Bézier path and says so.
            QaCurves.tap(s, onLine.x, onLine.y)
            assertTrue(path.isReopened)
            click("To Bézier", exact = true)
            assertEquals(ToolId.CURVE, c.activeToolId)
            val curve = c.currentTool as CurveTool
            assertTrue(curve.hasPendingWork)
            assertEquals("every Bézier anchor of the spline", SplineBezier.toSubpath(p1.spline!!).anchors.size, curve.anchors.size)
            click("Apply curve edit")
            assertTrue("the message: ${SmokeUi.shown()}", has(CurveTool.EDITED_AS_BEZIER, exact = true))
            assertEquals(CurveTool.EDIT_PATH_LABEL, c.undoManager.undoLabel)
            val bez = c.activeLayer.vector!!.objects.single() as VPath
            assertNull("plain Bézier now", bez.spline)

            // Top row Undo / Redo.
            click("Undo", exact = true)
            settle()
            assertEquals("undo: the path has its spline again", p1.spline, (c.activeLayer.vector!!.objects.single() as VPath).spline)
            click("Redo", exact = true)
            settle()
            assertNull((c.activeLayer.vector!!.objects.single() as VPath).spline)
            click("Undo", exact = true)
            settle()

            // SVG: one closed path of M / C (L for straight sides) / Z.
            val scene = runBlocking { ExportSceneBuilder(c, ExportOptions(VectorFormat.SVG), TextSource.Default, Dispatchers.Unconfined, Dispatchers.Unconfined).build() }
            val out = ByteArrayOutputStream()
            runBlocking { SvgWriter(scene).write(out) }
            val dom = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(ByteArrayInputStream(out.toByteArray()))
            val paths = dom.getElementsByTagNameNS("http://www.w3.org/2000/svg", "path")
            val ds = (0 until paths.length).map { (paths.item(it) as org.w3c.dom.Element).getAttribute("d") }.filter { it.isNotBlank() }
            assertEquals("one path: $ds", 1, ds.size)
            val commands = ds[0].filter { it.isLetter() && it != 'e' && it != 'E' }
            assertTrue("only M, C, L and Z: $commands", commands.all { it in "MCLZ" })
            assertTrue("closed", commands.endsWith("Z"))
            QaCurves.shot(s, "path-capsule-reloaded")
            File(app.filesDir, "projects/$id").deleteRecursively()
            Smoke.assertQuiet(c, "reload / reopen / To Bézier")
        }
        dog.interrupt()
        h.finish()
    }
}
