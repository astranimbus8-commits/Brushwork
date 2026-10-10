package com.brushwork.paint.qa17

import android.graphics.Canvas
import android.graphics.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Layer
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.qa16.QaCurves
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.pathfinder.PathfinderTool
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.common.PathfinderLabels
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.editor.HistoryLabels
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.pathfinder.PathfinderOp
import com.brushwork.paint.vector.render.VectorLayerRenderer
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/**
 * v1.7 final QA (items 1, 2, 6 and 20; design §3.1, §3.2, §3.6, §3.20) by finger on the user's
 * phone (392 dp), a flow the shapes tester left out: a filled rectangle drawn as an OBJECT of a
 * vector layer, opened again by a tap on its outline, its two top corners picked with "Select
 * several" and rounded by a finger scrubbing "Point roundness" (ONE in-tool step, the same
 * radius on both), then "Turn into path": the Points edit, then ONE step "Turn into path" (the
 * object keeps its id, in place), the Path tool selecting both converted corners; their "Point
 * weight" and "Point thickness" (`*2`) as the group fields, each ONE in-tool step; two undos give
 * the shape object back with its rounded corners, its layer showing its data (I1). Redone, a red
 * ellipse drawn over it on the same layer, Pathfinder "Minus front" bites it out IN that layer
 * (no new layer), ONE step, one undo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa17.shapesvectorcornerssandbox"])
class Qa17ShapesVectorCornersUiTest {

    private fun shapeSteps(t: ShapeTool): Int {
        var n = 0
        while (t.undoStep()) n++
        repeat(n) { t.redoStep() }
        settle(2)
        return n
    }

    private fun pathSteps(t: CurveTool): Int {
        var n = 0
        while (t.undoStep()) n++
        repeat(n) { t.redoStep() }
        settle(2)
        return n
    }

    private fun press(s: ChromeScreen, label: String, minDp: Float = 32f) {
        Qa16Ui(s).reach(label, minDp)
        settle()
        Finger.tap(s, label)
    }

    private fun rendered(s: ChromeScreen) {
        assertTrue("rendered", Smoke.pumpUntil(20_000) { settle(1); !s.c.vectors.isRendering })
        settle()
    }

    /** I1: the vector layer's pixels are its data drawn. */
    private fun assertShowsItsData(l: Layer, what: String) {
        val w = l.bitmap.width
        val h = l.bitmap.height
        val b = BitmapUtils.createLayerBitmap(w, h)
        VectorLayerRenderer.render(Canvas(b), l.vector!!, Rect(0, 0, w, h), tips = TipCache(), document = Rect(0, 0, w, h))
        assertTrue("$what: the layer shows its data (I1)", l.bitmap.sameAs(b))
    }

    /**
     * The value of the points' number field [label] (what its state says, "Mixed" or "200 %") is
     * on screen whole: its text is laid out without overflow, left of the field's scrub arrows.
     */
    private fun assertValueShows(s: ChromeScreen, label: String) {
        Qa16Ui(s).reach("Type $label", 40f)
        settle()
        val row = s.placed().lastOrNull { it.node.config.getOrNull(SemanticsActions.OnClick)?.label == "Type $label" }
            ?: throw AssertionError("no \"$label\" field")
        val state = row.node.config.getOrNull(SemanticsProperties.StateDescription)
        val value = row.node.children.lastOrNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == state } == true }
            ?: throw AssertionError("\"$label\" shows no \"$state\"")
        val field = s.dp(row.bounds)
        val r = s.dp(value.boundsInWindow)
        val layouts = ArrayList<TextLayoutResult>()
        value.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        val overflow = layouts.firstOrNull()?.hasVisualOverflow
        assertTrue(
            "\"$label\" shows \"$state\" whole: its text at $r (overflow $overflow) in the field at $field",
            r.width > 4f && overflow == false && r.right <= field.right - 28f,
        )
    }

    @Test
    fun vectorObjectCornersScrubbedTurnedIntoPathAndBittenAt392dp() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 180_000)
        val h = ChromeHarness()
        h.section("a rectangle object of a vector layer: corners scrubbed, to a path, Minus front") {
            flow(h.editor(Smoke.document(400, 300, layers = 1, whiteBottom = true)))
        }
        dog.interrupt()
        h.finish()
    }

    private fun flow(s: ChromeScreen) {
        val c = s.c
        val ui = Qa16Ui(s)
        val vec = requireNotNull(c.addVectorLayer())
        settle()
        assertTrue(vec.isVectorLayer)
        QaCurves.tool(s, "Shape")
        val tool = c.currentTool as ShapeTool
        QaCurves.snapOff(c)
        click("Shape style", exact = true)
        click(ShapeStyle.FILL.label, exact = true)
        assertEquals(ShapeType.RECTANGLE, tool.settings.type)
        c.color = BLUE
        settle()
        QaCurves.drag(s, Vec2(60f, 60f), Vec2(160f, 120f))
        assertNotNull("a pending rectangle", tool.box)
        click("Apply shape edit")
        rendered(s)
        val shape = vec.vector!!.objects.single() as VShape
        assertEquals("filled blue", BLUE, shape.shape.fillColor)
        val layers = c.doc.layers.toList()

        // A tap on its top side opens the object again; "Points"; "Select several"; the two top corners.
        QaCurves.tap(s, 140f, 60f)
        assertTrue("opened", Smoke.pumpUntil(10_000) { settle(1); tool.box != null })
        press(s, "Points")
        assertEquals(4, tool.pointCount)
        val top = (0 until 4).filter { abs(tool.pointAt(it).y - 60f) < 1f }
        assertEquals("two top corners", 2, top.size)
        press(s, PointLabels.SELECT_SEVERAL, 44f)
        assertTrue(tool.selectSeveral)
        for (i in top) QaCurves.tap(s, tool.pointAt(i))
        assertEquals(top, tool.pointSelection.indices)
        val corners = top.map { tool.pointAt(it) }

        // A finger scrubs "Point roundness" to the right: both corners round by the same radius, ONE in-tool step.
        ui.reach("Type ${PointLabels.ROUNDNESS}", 40f)
        settle()
        val field = requireNotNull(Finger.control(s, "Type ${PointLabels.ROUNDNESS}")) { "the roundness field" }
        val n = shapeSteps(tool)
        Finger.slowDrag(s, (field.left + 24f) to field.center.y, (field.left + 104f) to field.center.y)
        val radii = tool.docAnchors()!!.map { it.radius }
        val r = requireNotNull(radii[top[0]]) { "the first corner has its own roundness: $radii" }
        assertTrue("rounded by the scrub: $radii", r > 4f)
        assertEquals("the same radius on both: $radii", r, radii[top[1]]!!, 1e-4f)
        for (i in 0 until 4) if (i !in top) assertEquals("corner $i keeps the shape's: $radii", null, radii[i])
        assertEquals("ONE in-tool step", n + 1, shapeSteps(tool))
        assertValueShows(s, PointLabels.ROUNDNESS)
        Qa17ShapesShots.screen(s, "shapes-vectorcorners-scrubbed")

        // "Turn into path": the Points edit, then ONE step; the object keeps its id and place.
        val before = c.undoManager.undoCount
        press(s, PointLabels.TO_PATH)
        rendered(s)
        assertEquals("the Path tool", ToolId.PATH, c.activeToolId)
        assertEquals(HistoryLabels.TURN_INTO_PATH, c.undoManager.undoLabel)
        assertEquals("the Points edit, then ONE step", before + 2, c.undoManager.undoCount)
        assertEquals("no layer added or removed", layers, c.doc.layers.toList())
        val vp = vec.vector!!.objects.single() as VPath
        assertEquals("the same object", shape.id, vp.id)
        assertEquals(shape.opacity, vp.opacity, 0f)
        assertEquals("still filled blue", BLUE, (vp.fill as? VPaint.Solid)?.color)
        val sp = requireNotNull(vp.spline) { "a NURBS path" }
        assertTrue(sp.cyclic)
        assertShowsItsData(vec, "converted")
        val path = c.currentTool as CurveTool
        assertTrue(path.isPath)
        val sel = path.pointSelection.indices
        assertEquals("both converted corners are selected: $sel", 2, sel.size)
        val np = sp.points.size
        for (ci in sel) {
            val cp = sp.points[ci]
            assertTrue("$ci is at a picked corner", corners.any { hypot(it.x - cp.x, it.y - cp.y) < 0.05f })
            assertFalse("a smooth point", cp.sharp)
            assertEquals("a quarter circle's weight", cos(PI / 4).toFloat(), cp.weight, 1e-3f)
            for (nb in listOf(sp.points[(ci - 1 + np) % np], sp.points[(ci + 1) % np])) {
                assertTrue("the arc's ends are sharp", nb.sharp)
                assertEquals("the arc's ends are the scrubbed radius along the sides", r, hypot(nb.x - cp.x, nb.y - cp.y), 0.05f)
            }
        }

        // The group fields of the two points: weight 2, thickness *2, each ONE in-tool step.
        var k = pathSteps(path)
        press(s, "Type Point weight", 40f)
        SmokeUi.typeAndDone("Point weight", "2")
        for (ci in sel) assertEquals(2f, path.spline!!.points[ci].weight, 1e-4f)
        assertEquals(k + 1, pathSteps(path))
        assertValueShows(s, "Point weight")
        k = pathSteps(path)
        press(s, "Type Point thickness", 40f)
        SmokeUi.typeAndDone("Point thickness", "*2")
        for (ci in sel) assertEquals(2f, path.spline!!.points[ci].width, 1e-4f)
        assertEquals(k + 1, pathSteps(path))
        assertValueShows(s, "Point thickness")
        Qa17ShapesShots.screen(s, "shapes-vectorcorners-path")
        val applied = c.undoManager.undoCount
        click("Apply path edit")
        rendered(s)
        assertEquals(applied + 1, c.undoManager.undoCount)
        assertShowsItsData(vec, "edited path")

        // Two undos: the path as converted, then the rectangle object with its rounded corners.
        click("Undo", exact = true)
        rendered(s)
        assertEquals(HistoryLabels.TURN_INTO_PATH, c.undoManager.undoLabel)
        click("Undo", exact = true)
        rendered(s)
        assertEquals(before + 1, c.undoManager.undoCount)
        val back = vec.vector!!.objects.single() as VShape
        assertEquals("the same object again", shape.id, back.id)
        val backRadii = back.shape.points!!.map { it.radius }
        assertEquals("its two rounded corners: $backRadii", 2, backRadii.count { it != null && abs(it - r) < 1e-3f })
        assertShowsItsData(vec, "undone to the shape")
        assertEquals(layers, c.doc.layers.toList())

        // Redone (the path with its weights), then a red ellipse over its lower right on the same layer.
        click("Redo", exact = true)
        click("Redo", exact = true)
        rendered(s)
        assertEquals(applied + 1, c.undoManager.undoCount)
        val edited = vec.vector!!.objects.single() as VPath
        assertEquals(shape.id, edited.id)
        QaCurves.tool(s, "Shape")
        val tool2 = c.currentTool as ShapeTool
        click("Shape type", exact = true)
        click(ShapeType.ELLIPSE.label, exact = true)
        assertEquals(ShapeStyle.FILL, tool2.settings.style)
        c.color = RED
        settle()
        QaCurves.drag(s, Vec2(150f, 120f), Vec2(120f, 120f))
        assertNotNull("a pending ellipse", tool2.box)
        click("Apply shape edit")
        rendered(s)
        val objects = vec.vector!!.objects
        assertEquals("both objects of the one layer", listOf(edited.id), objects.dropLast(1).map { it.id })
        val ellipse = objects.last() as VShape
        assertEquals(RED, ellipse.shape.fillColor)
        assertEquals("no new layer", layers, c.doc.layers.toList())

        // Pathfinder: "Select all objects", "Minus front": the bite, IN the vector layer, ONE step.
        QaCurves.tool(s, "Pathfinder")
        val pf = c.currentTool as PathfinderTool
        pf.computeDispatcher = Dispatchers.Unconfined
        press(s, PathfinderLabels.SELECT_ALL)
        assertEquals("the path and the ellipse", 2, pf.count)
        val pfBefore = c.undoManager.undoCount
        press(s, PathfinderOp.MINUS_FRONT.description, 40f)
        assertTrue("done", Smoke.pumpUntil(20_000) { settle(1); !pf.busy && !c.vectors.isRendering })
        settle()
        assertEquals("ONE step", pfBefore + 1, c.undoManager.undoCount)
        assertEquals(PathfinderOp.MINUS_FRONT.historyLabel, c.undoManager.undoLabel)
        assertEquals("the result stays in the layer: no layer added or removed", layers, c.doc.layers.toList())
        val bitten = vec.vector!!.objects.single() as VPath
        assertEquals("the back object's colour", BLUE, (bitten.fill as? VPaint.Solid)?.color)
        assertEquals("inside the rectangle, away from the ellipse", BLUE, vec.bitmap.getPixel(100, 110))
        assertEquals("the bite", 0, vec.bitmap.getPixel(200, 160) ushr 24)
        assertEquals("the ellipse is gone", 0, vec.bitmap.getPixel(240, 220) ushr 24)
        assertShowsItsData(vec, "bitten")
        Qa17ShapesShots.doc(s, "shapes-vectorcorners-minusfront")

        click("Undo", exact = true)
        rendered(s)
        assertEquals(pfBefore, c.undoManager.undoCount)
        assertEquals("one undo: both objects back", listOf(edited.id, ellipse.id), vec.vector!!.objects.map { it.id })
        assertShowsItsData(vec, "pathfinder undone")
        Smoke.assertQuiet(c, "vector corners")
    }

    private companion object {
        val RED = 0xFFE53935.toInt()
        val BLUE = 0xFF1E88E5.toInt()
    }
}
