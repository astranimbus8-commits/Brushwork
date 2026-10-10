package com.brushwork.paint.qa17

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.qa16.QaCurves
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.points.PointGizmo
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.common.PillLabels
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.common.V17Tags
import com.brushwork.paint.ui.editor.HistoryLabels
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.math.abs

/**
 * v1.7 final QA (items 1, 2 and 13 for shapes; design §3.1, §3.2, §3.13) on the full editor at
 * the user's phone size (ZTE Axon 50 Lite, 392 dp), by finger:
 * - a rectangle's corners: "Point roundness" typed on one corner, then another, "Select all
 *   points" reads "Mixed", `*2` doubles each, "Reset point roundness" gives them back to the
 *   shape; the pill's trash cell says "Delete selected points" for some points and "Delete shape"
 *   for all of them (a placed shape: ONE step "Delete shape", one undo brings it back);
 * - a star's outer points with "Select several": dragged together, scaled by a gizmo corner,
 *   pinched by two fingers inside the gizmo, deleted from the trash cell, each ONE in-tool step.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa17.shapespointssandbox"])
class Qa17ShapesPointsUiTest {

    /** The in-tool steps of [t] (undone, then redone again). */
    private fun steps(t: ShapeTool): Int {
        var n = 0
        while (t.undoStep()) n++
        repeat(n) { t.redoStep() }
        settle(2)
        return n
    }

    /** Brings [label] wholly into view as a finger would (sliding its strip), then taps it with a finger. */
    private fun press(s: ChromeScreen, label: String, minDp: Float = 32f) {
        Qa16Ui(s).reach(label, minDp)
        settle()
        Finger.tap(s, label)
    }

    /** The state description of [label]'s node, or of its nearest clickable ancestor. */
    private fun stateOf(label: String): String? {
        var n: SemanticsNode? = SmokeUi.find(label, exact = true)?.node
        n?.config?.getOrNull(SemanticsProperties.StateDescription)?.let { return it }
        while (n != null && n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent
        return n?.config?.getOrNull(SemanticsProperties.StateDescription)
    }

    private fun typeRoundness(s: ChromeScreen, text: String) {
        press(s, "Type ${PointLabels.ROUNDNESS}")
        SmokeUi.typeAndDone(PointLabels.ROUNDNESS, text)
    }

    @Test
    fun shapePointsByFingerAt392dp() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 180_000)
        val h = ChromeHarness()
        h.section("a rectangle's corners: roundness, Mixed, reset, the trash cell") { rectangle(h.editor()) }
        h.section("a star's outer points: drag, gizmo, pinch, delete") { star(h.editor()) }
        dog.interrupt()
        h.finish()
    }

    private fun rectangle(s: ChromeScreen) {
        val c = s.c
        assertEquals("the phone is 392 dp wide", 392f, s.widthDp, 1f)
        QaCurves.tool(s, "Shape")
        val tool = c.currentTool as ShapeTool
        QaCurves.snapOff(c)
        assertEquals(ShapeType.RECTANGLE, tool.settings.type)
        QaCurves.drag(s, Vec2(100f, 80f), Vec2(200f, 140f))
        assertNotNull("a pending rectangle", tool.box)
        press(s, "Points")
        assertTrue(tool.pointsMode)
        assertEquals(4, tool.pointCount)
        fun radii() = tool.docAnchors()!!.map { it.radius }

        // One corner, tapped: its own roundness, ONE in-tool step.
        QaCurves.tap(s, tool.pointAt(0))
        assertEquals("the tapped corner", listOf(0), tool.pointSelection.indices)
        var n = steps(tool)
        typeRoundness(s, "20")
        assertEquals(listOf(20f, null, null, null), radii())
        assertEquals("one in-tool step", n + 1, steps(tool))
        // Another corner (a tap replaces the selection without "Select several").
        QaCurves.tap(s, tool.pointAt(2))
        assertEquals(listOf(2), tool.pointSelection.indices)
        typeRoundness(s, "40")
        assertEquals(listOf(20f, null, 40f, null), radii())

        // All four: the values differ, the field reads "Mixed"; "*2" doubles each.
        press(s, PointLabels.SELECT_ALL, 44f)
        assertEquals(4, tool.pointSelection.count)
        assertTrue(has(PointLabels.DESELECT_ALL, exact = true))
        assertTrue("the roundness reads Mixed: ${SmokeUi.shown().take(80)}", has(PointLabels.MIXED, exact = true))
        val state = stateOf(PointLabels.ROUNDNESS)
        assertTrue("its state says the spread: $state", state != null && state.startsWith(PointLabels.MIXED) && state.contains("40"))
        assertTrue("\"Reset point roundness\" is on", SmokeUi.isEnabled(PointLabels.RESET_ROUNDNESS))
        n = steps(tool)
        typeRoundness(s, "*2")
        assertEquals("each doubled (a sharp corner's 0 stays 0)", listOf(40f, 0f, 80f, 0f), radii())
        assertEquals(n + 1, steps(tool))
        press(s, PointLabels.RESET_ROUNDNESS)
        assertEquals("the shape's own roundness again", listOf<Float?>(null, null, null, null), radii())
        assertEquals(n + 2, steps(tool))
        assertFalse("nothing left to reset", SmokeUi.isEnabled(PointLabels.RESET_ROUNDNESS))
        Qa17Shots.screen(s, "shapes-points-rectangle")

        // The trash cell: all points selected = the whole shape; one = that point.
        assertNotNull("the trash cell shows", s.tagged(V17Tags.PILL_TRASH))
        assertTrue("all selected: ${SmokeUi.shown().take(80)}", has(PillLabels.deleteObject("shape"), exact = true))
        QaCurves.tap(s, tool.pointAt(1))
        assertEquals(listOf(1), tool.pointSelection.indices)
        assertTrue(has(PillLabels.DELETE_POINTS, exact = true))
        n = steps(tool)
        Finger.tap(s, PillLabels.DELETE_POINTS)
        assertEquals("the corner is gone", 3, tool.pointCount)
        assertEquals("one in-tool step", n + 1, steps(tool))
        click("Undo point edit", exact = true)
        assertEquals(4, tool.pointCount)

        // A placed shape: "Delete shape" from the trash cell is ONE step; one undo brings it back.
        click("Apply shape edit")
        assertNull(tool.box)
        val layer = c.doc.layers.last()
        assertTrue("the rectangle is a shape layer", layer.isShapeLayer)
        QaCurves.tap(s, 200f, 80f)
        assertNotNull("a tap on its outline opens it", tool.box)
        press(s, "Points")
        press(s, PointLabels.SELECT_ALL, 44f)
        assertTrue(has(PillLabels.deleteObject("shape"), exact = true))
        val before = c.undoManager.undoCount
        val layers = c.doc.layers.toList()
        Finger.tap(s, PillLabels.deleteObject("shape"))
        assertNull("the shape is closed", tool.box)
        assertFalse("its layer is gone", layer in c.doc.layers)
        assertEquals("ONE step", before + 1, c.undoManager.undoCount)
        assertEquals(HistoryLabels.DELETE_SHAPE, c.undoManager.undoLabel)
        click("Undo", exact = true)
        assertEquals("one undo brings it back", layers, c.doc.layers.toList())
        assertTrue(layer.isShapeLayer)
        Smoke.assertQuiet(c, "rectangle points")
    }

    private fun star(s: ChromeScreen) {
        val c = s.c
        QaCurves.tool(s, "Shape")
        val tool = c.currentTool as ShapeTool
        QaCurves.snapOff(c)
        click("Shape type", exact = true)
        click(ShapeType.STAR.label, exact = true)
        assertEquals(ShapeType.STAR, tool.settings.type)
        QaCurves.drag(s, Vec2(100f, 40f), Vec2(200f, 220f))
        assertNotNull("a pending star", tool.box)
        press(s, "Points")
        val count = tool.pointCount
        assertEquals("a five-point star has ten points", 10, count)
        fun pts() = tool.docAnchors()!!.map { it.pos }

        // "Select several": three outer points by three taps.
        press(s, PointLabels.SELECT_SEVERAL, 44f)
        assertTrue(tool.selectSeveral)
        assertTrue("its hint", has(PointLabels.SEVERAL_HINT, exact = true))
        for (i in listOf(0, 4, 6)) QaCurves.tap(s, tool.pointAt(i))
        assertEquals("three points by three taps", listOf(0, 4, 6), tool.pointSelection.indices)

        // A finger on one of them drags all three: ONE in-tool step; the others stay.
        val p0 = pts()
        var n = steps(tool)
        QaCurves.drag(s, p0[0], Vec2(0f, 12f))
        val p1 = pts()
        for (i in 0 until count) {
            val moved = if (i in listOf(0, 4, 6)) 12f else 0f
            assertEquals("point $i x", p0[i].x, p1[i].x, 0.6f)
            assertEquals("point $i y", p0[i].y + moved, p1[i].y, 0.6f)
        }
        assertEquals("one in-tool step", n + 1, steps(tool))

        // A gizmo corner (where no point is): the three scale about the box's centre.
        val t = c.viewTransform
        val gizmo = PointGizmo()
        fun layout() = requireNotNull(gizmo.layout(listOf(0, 4, 6).map { pts()[it] }, t)) { "the gizmo of three points" }
        val l0 = layout()
        val ne = t.screenToDoc(l0.cornersScreen[1])
        fun span() = listOf(0, 4, 6).map { pts()[it] }.let { q -> q.maxOf { it.x } - q.minOf { it.x } }
        val w0 = span()
        n = steps(tool)
        QaCurves.drag(s, ne, Vec2(20f, -20f))
        assertTrue("the corner scaled the three: $w0 -> ${span()}", span() > w0 * 1.08f)
        assertEquals(n + 1, steps(tool))
        val others = (0 until count).filter { it !in listOf(0, 4, 6) }
        val p2 = pts()
        for (i in others) assertEquals("point $i stays", p1[i], p2[i])

        // Two fingers inside the gizmo, spreading apart: the three grow about its pivot; ONE step.
        val l1 = layout()
        val pivot = l1.pivotDoc
        val w1 = span()
        n = steps(tool)
        s.touch.idle(400)
        s.touch.pinch(
            s.screen(pivot.x - 12f, pivot.y + 6f), s.screen(pivot.x + 12f, pivot.y + 6f),
            s.screen(pivot.x - 20f, pivot.y + 6f), s.screen(pivot.x + 20f, pivot.y + 6f),
        )
        settle()
        val w2 = span()
        assertTrue("the pinch spread the three: $w1 -> $w2", w2 > w1 * 1.3f)
        assertEquals("one in-tool step", n + 1, steps(tool))
        val p3 = pts()
        for (i in others) assertTrue("point $i stays: ${p2[i]} -> ${p3[i]}", abs(p2[i].x - p3[i].x) < 0.01f && abs(p2[i].y - p3[i].y) < 0.01f)
        Qa17Shots.screen(s, "shapes-points-star")

        // The trash cell deletes the three (seven are left), ONE in-tool step; undo gives them back.
        assertTrue("some points: ${SmokeUi.shown().take(80)}", has(PillLabels.DELETE_POINTS, exact = true))
        n = steps(tool)
        Finger.tap(s, PillLabels.DELETE_POINTS)
        assertEquals(count - 3, tool.pointCount)
        assertEquals(n + 1, steps(tool))
        click("Undo point edit", exact = true)
        assertEquals(count, tool.pointCount)
        Smoke.assertQuiet(c, "star points")
    }
}
