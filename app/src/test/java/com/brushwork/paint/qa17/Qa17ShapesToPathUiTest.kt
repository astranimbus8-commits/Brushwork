package com.brushwork.paint.qa17

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.qa16.QaCurves
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CornerStyle
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeStroke
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeToSpline
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.editor.HistoryLabels
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VSplinePoint
import com.brushwork.paint.vector.VStrokeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min

/**
 * v1.7 final QA (item 6, design §3.6) by finger on the user's phone (392 dp): "The Corner turns
 * into an editable path (the Blender-like NURBS path), so you keep editing it in the path tool
 * with weights, thickness, etc."
 * - a placed rectangle with round corners, opened again, one corner tapped in "Points", "Turn
 *   into path": the Points edit then ONE step, the Path tool opens with THAT corner's arc point selected (at the
 *   corner, weight cos 45°, its arc ends sharp 30 px along the sides); its weight and thickness
 *   typed and the point dragged, each one in-tool step; ✓ keeps them;
 * - a pending triangle outlined with the current brush, its sharp top corner turned into a path:
 *   the shape lands first (its own step), then the path, painted with the same brush, the corner
 *   now an arc cut 25 % of the shorter side back; one undo gives the triangle back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa17.shapestopathsandbox"])
class Qa17ShapesToPathUiTest {

    private fun steps(t: CurveTool): Int {
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

    private fun dist(p: VSplinePoint, q: Vec2): Float = hypot(p.x - q.x, p.y - q.y)

    private fun rendered(s: ChromeScreen) {
        assertTrue("rendered", Smoke.pumpUntil(20_000) { settle(1); !s.c.vectors.isRendering })
        settle()
    }

    @Test
    fun aShapeCornerBecomesAnEditablePathAt392dp() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 180_000)
        val h = ChromeHarness()
        h.section("a placed round-cornered rectangle: one corner to a path, weight, thickness, drag") { rounded(h.editor()) }
        h.section("a pending brush-outlined triangle: its sharp corner to a path") { triangle(h.editor()) }
        dog.interrupt()
        h.finish()
    }

    private fun rounded(s: ChromeScreen) {
        val c = s.c
        QaCurves.tool(s, "Shape")
        val tool = c.currentTool as ShapeTool
        QaCurves.snapOff(c)
        click("Shape settings", exact = true)
        press(s, CornerStyle.ROUND.label)
        assertEquals(CornerStyle.ROUND, tool.settings.corner)
        assertEquals(30f, tool.settings.cornerRadius, 0f)
        click("Close", exact = true)
        QaCurves.drag(s, Vec2(100f, 80f), Vec2(200f, 140f))
        click("Apply shape edit")
        val layer = c.doc.layers.last()
        assertTrue("a shape layer", layer.isShapeLayer)

        // Opened again by a tap on its outline; "Points"; the top-left corner tapped.
        QaCurves.tap(s, 200f, 80f)
        assertNotNull("opened", tool.box)
        press(s, "Points")
        QaCurves.tap(s, tool.pointAt(0))
        assertEquals(listOf(0), tool.pointSelection.indices)
        val corner = tool.pointAt(0)
        assertEquals("the top-left corner", 100f, corner.x, 0.6f)
        assertEquals(80f, corner.y, 0.6f)

        val before = c.undoManager.undoCount
        press(s, PointLabels.TO_PATH)
        rendered(s)
        assertEquals("the Path tool", ToolId.PATH, c.activeToolId)
        assertEquals(HistoryLabels.TURN_INTO_PATH, c.undoManager.undoLabel)
        // "Points" gave the opened rectangle its own points: that pending edit lands first, as its
        // own step (§3.6 (a) 1, as ShapeToPathRobolectricTest), then the conversion, ONE step.
        assertEquals("the Points edit, then ONE step", before + 2, c.undoManager.undoCount)
        assertSame(layer, c.activeLayer)
        assertTrue("now a vector layer", layer.isVectorLayer && !layer.isShapeLayer)
        val vp = layer.vector!!.objects.single() as VPath
        val sp = requireNotNull(vp.spline) { "a NURBS path" }
        assertTrue(sp.cyclic)
        val path = c.currentTool as CurveTool
        assertTrue(path.isPath)

        // The corner's arc point is selected: at the corner, weight cos 45°, its arc ends sharp, 30 px along the sides.
        val sel = path.pointSelection.indices
        assertEquals("the converted corner is selected: $sel", 1, sel.size)
        val ci = sel.single()
        val n = sp.points.size
        val cp = sp.points[ci]
        assertEquals(corner.x, cp.x, 0.01f)
        assertEquals(corner.y, cp.y, 0.01f)
        assertEquals("a quarter circle's weight", cos(PI / 4).toFloat(), cp.weight, 1e-3f)
        assertFalse("a smooth point to drag", cp.sharp)
        val a = sp.points[(ci - 1 + n) % n]
        val b = sp.points[(ci + 1) % n]
        assertTrue("the arc's ends are sharp", a.sharp && b.sharp)
        assertEquals(30f, dist(a, corner), 0.05f)
        assertEquals(30f, dist(b, corner), 0.05f)
        assertTrue("its weight shows", SmokeUi.has("Point weight 0.71", exact = true))

        // Weight, thickness and position, each ONE in-tool step.
        var k = steps(path)
        press(s, "Type the point weight")
        SmokeUi.typeAndDone("Weight", "2")
        assertEquals(2f, path.spline!!.points[ci].weight, 1e-4f)
        assertEquals(k + 1, steps(path))
        press(s, "Type the point thickness")
        SmokeUi.typeAndDone("Thickness", "200")
        assertEquals(2f, path.spline!!.points[ci].width, 1e-4f)
        assertEquals(k + 2, steps(path))
        QaCurves.drag(s, corner, Vec2(-16f, -16f))
        val moved = path.spline!!.points[ci]
        assertEquals(corner.x - 16f, moved.x, 0.6f)
        assertEquals(corner.y - 16f, moved.y, 0.6f)
        assertEquals(k + 3, steps(path))
        Qa17Shots.screen(s, "shapes-topath-rounded")

        // ✓: the layer keeps the edited path (ONE step).
        val applied = c.undoManager.undoCount
        click("Apply path edit")
        rendered(s)
        assertEquals(applied + 1, c.undoManager.undoCount)
        val kept = (layer.vector!!.objects.single() as VPath).spline!!.points[ci]
        assertEquals(2f, kept.weight, 1e-4f)
        assertEquals(2f, kept.width, 1e-4f)
        Qa17Shots.doc(s, "shapes-topath-rounded")

        // Undo: the path as converted; again: the rectangle is a shape layer (as "Points" left it).
        click("Undo", exact = true)
        rendered(s)
        assertEquals(applied, c.undoManager.undoCount)
        assertEquals(HistoryLabels.TURN_INTO_PATH, c.undoManager.undoLabel)
        click("Undo", exact = true)
        rendered(s)
        assertTrue("the shape layer is back", layer.isShapeLayer && !layer.isVectorLayer)
        assertEquals(before + 1, c.undoManager.undoCount)
        Smoke.assertQuiet(c, "rounded corner to path")
    }

    private fun triangle(s: ChromeScreen) {
        val c = s.c
        QaCurves.tool(s, "Shape")
        val tool = c.currentTool as ShapeTool
        QaCurves.snapOff(c)
        click("Shape type", exact = true)
        click(ShapeType.POLYGON.label, exact = true)
        click("Shape settings", exact = true)
        SmokeUi.typeAndDone("Number of sides", "3")
        assertEquals(3, tool.settings.sides)
        click("Close", exact = true)
        click("Stroke with", exact = true)
        click(ShapeStroke.BRUSH.label, exact = true)
        assertEquals(ShapeStroke.BRUSH, tool.settings.strokeWith)
        QaCurves.drag(s, Vec2(110f, 50f), Vec2(180f, 200f))
        assertNotNull("a pending triangle", tool.box)
        press(s, "Points")
        assertEquals(3, tool.pointCount)
        val v = (0 until 3).map { tool.pointAt(it) }
        // The top corner (the one with the smallest y).
        val top = v.indices.minBy { v[it].y }
        QaCurves.tap(s, v[top])
        assertEquals(listOf(top), tool.pointSelection.indices)
        assertTrue("\"Turn into path\" is on", SmokeUi.isEnabled(PointLabels.TO_PATH))

        val layersBefore = c.doc.layers.toList()
        val before = c.undoManager.undoCount
        press(s, PointLabels.TO_PATH)
        rendered(s)
        assertEquals(ToolId.PATH, c.activeToolId)
        assertEquals("the pending triangle lands (its own step), then the path (ONE step)", before + 2, c.undoManager.undoCount)
        assertEquals(HistoryLabels.TURN_INTO_PATH, c.undoManager.undoLabel)
        val layer = c.activeLayer
        assertTrue(layer.isVectorLayer)
        val vp = layer.vector!!.objects.single() as VPath
        val st = requireNotNull(vp.stroke)
        assertEquals("painted with the brush", VStrokeKind.BRUSH, st.kind)
        assertNotNull("the brush itself", st.brush)
        assertEquals(ToolId.BRUSH, st.brushTool)
        assertNull("an outline only", vp.fill)

        // The sharp corner became an arc, cut a quarter of the shorter side back.
        val sp = vp.spline!!
        val path = c.currentTool as CurveTool
        val ci = path.pointSelection.indices.single()
        val cp = sp.points[ci]
        val p = v[top]
        assertEquals(p.x, cp.x, 0.01f)
        assertEquals(p.y, cp.y, 0.01f)
        val prev = v[(top + 2) % 3]
        val next = v[(top + 1) % 3]
        val cut = ShapeToSpline.SELECTED_CUT * min(p.distanceTo(prev), p.distanceTo(next))
        val n = sp.points.size
        assertEquals(cut, dist(sp.points[(ci - 1 + n) % n], p), 0.05f)
        assertEquals(cut, dist(sp.points[(ci + 1) % n], p), 0.05f)
        val ua = (prev - p).normalized()
        val ub = (next - p).normalized()
        val theta = acos(ua.dot(ub).coerceIn(-1f, 1f))
        assertEquals("weight cos(φ/2), φ the turn", cos((PI.toFloat() - theta) / 2f), cp.weight, 1e-3f)
        Qa17Shots.screen(s, "shapes-topath-triangle")
        Qa17Shots.doc(s, "shapes-topath-triangle")

        // One undo: the triangle is a shape layer again.
        click("Undo", exact = true)
        rendered(s)
        assertTrue("the shape layer is back", layer.isShapeLayer && !layer.isVectorLayer)
        click("Undo", exact = true)
        assertEquals("a second undo: as before", layersBefore, c.doc.layers.toList())
        Smoke.assertQuiet(c, "triangle corner to path")
    }
}
