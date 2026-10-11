package com.brushwork.paint.qa18

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.model.Selection
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.qa18.Qa18SelectPictures.FRUIT_R
import com.brushwork.paint.qa18.Qa18SelectPictures.FRUIT_X
import com.brushwork.paint.qa18.Qa18SelectPictures.FRUIT_Y
import com.brushwork.paint.qa18.Qa18SelectPictures.SPOT_R
import com.brushwork.paint.qa18.Qa18SelectPictures.SPOT_X
import com.brushwork.paint.qa18.Qa18SelectPictures.SPOT_Y
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.select.magnetic.MagneticLassoTool
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.MagneticLabels18
import com.brushwork.paint.ui.common.PillLabels
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * v1.8 final QA verification, item 3 (design §3.4): the magnetic lasso by the gestures the
 * select tester did not drive, at the T606's 392 dp. Around the orange: a two-finger tap takes
 * the last point back and a three-finger tap brings it again (in-tool steps, no document step);
 * a double tap closes (ONE step). Then "Intersect with selection" around the bruise: "Discard
 * outline" and the top row's Undo / Redo / Undo; a pinch zooms in mid-outline (no point, no
 * step); a press-and-drag places a snapped point where the finger lifts; with "Snap to edge" off
 * a tap stays where the finger is and the pill's "Delete selected points" takes it out; "Close
 * outline" leaves the bruise selected (ONE step) and Undo gives the orange back byte for byte;
 * leaving the tool with three points closes them (ONE step). A pinch whose first finger lands on
 * a point is no tap: the point picked before it stays picked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.selectmagneticfingerssandbox"])
class Qa18SelectMagneticFingersUiTest {

    private companion object {
        const val WAIT_MS = 20_000L

        /** How far (doc px) a snapped point may sit from the rim it snapped to. */
        const val RIM_PX = 2.5f
    }

    private val h = ChromeHarness()
    private lateinit var s: ChromeScreen
    private lateinit var ui: Qa16Ui
    private val c get() = s.c

    @Test
    fun fingersUndoRedoCloseZoomAndIntersect() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 240_000)
        h.section("magnetic lasso by fingers at 392 dp") { fingers() }
        dog.interrupt()
        h.finish()
    }

    private fun steps() = c.undoManager.undoCount

    private fun stateOf(label: String): String? = RobolectricUi.elements().lastOrNull { e ->
        e.node.layoutInfo.isPlaced && e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it == label } == true
    }?.node?.config?.getOrNull(SemanticsProperties.StateDescription)

    private fun around(cx: Float, cy: Float, r: Float, deg: Double): Pair<Float, Float> =
        (cx + r * cos(deg * PI / 180).toFloat()) to (cy + r * sin(deg * PI / 180).toFloat())

    private fun angleOf(cx: Float, cy: Float, x: Float, y: Float, want: Double): Double {
        val deg = Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble()))
        return ((deg - want) % 360.0 + 540.0) % 360.0 - 180.0
    }

    private fun alphas(sel: Selection): ByteArray {
        val out = ByteArray(sel.width * sel.height)
        for (y in 0 until sel.height) for (x in 0 until sel.width) out[y * sel.width + x] = sel.alphaAt(x, y).toByte()
        return out
    }

    private fun iou(sel: Selection, inside: (Float, Float) -> Boolean): Double {
        var inter = 0.0
        var uni = 0.0
        for (y in 0 until sel.height) for (x in 0 until sel.width) {
            val a = sel.alphaAt(x, y) / 255.0
            val b = if (inside(x + 0.5f, y + 0.5f)) 1.0 else 0.0
            inter += min(a, b)
            uni += max(a, b)
        }
        return if (uni == 0.0) 0.0 else inter / uni
    }

    private fun fingers() {
        val doc = Smoke.document(Qa18SelectPictures.SIZE, Qa18SelectPictures.SIZE, layers = 1)
        Qa18SelectPictures.fruit(doc.layers[0].bitmap)
        s = h.editor(doc) { it.snapping.enabled = false }
        ui = Qa16Ui(s)
        assertEquals("the phone is 392 dp wide", 392f, s.widthDp, 1f)
        assertTrue("two-finger undo is on", c.settings.twoFingerUndo)
        assertTrue("three-finger redo is on", c.settings.threeFingerRedo)

        ui.tool("Magnetic lasso")
        settle()
        val tool = c.currentTool as MagneticLassoTool
        assertTrue("the edges are found", Smoke.pumpUntil(WAIT_MS) { settle(1); tool.edgeMapReady && tool.idle })
        fun idle() = assertTrue("the tracing settles", Smoke.pumpUntil(WAIT_MS) { settle(1); tool.idle })
        fun anchors() = tool.points.anchors().copyOf()
        StripTestKit.closeMoreRow()
        val before = steps()

        // ---- Eight taps outside the orange.
        for (k in 0 until 8) {
            val (x, y) = around(FRUIT_X, FRUIT_Y, FRUIT_R + 8f, -90.0 + 45.0 * k)
            ui.tap(x, y)
            idle()
        }
        assertEquals(8, tool.anchorCount)
        val eight = anchors()

        // ---- A two-finger tap on the canvas takes the last point back; a three-finger tap brings it again.
        Fingers18.twoFingerTap(s, FRUIT_X, FRUIT_Y)
        idle()
        assertEquals("a two-finger tap takes the last point back", 7, tool.anchorCount)
        assertEquals("and makes no document step", before, steps())
        assertTrue("the outline is still pending", tool.hasPendingWork)
        Fingers18.threeFingerTap(s, FRUIT_X, FRUIT_Y)
        idle()
        assertEquals("a three-finger tap brings it again", 8, tool.anchorCount)
        assertArrayEquals("exactly where it was", eight, anchors(), 0f)
        assertEquals("no document step", before, steps())

        // ---- A double tap (two quick taps at one spot) adds its point and closes: ONE step.
        val (dx, dy) = around(FRUIT_X, FRUIT_Y, FRUIT_R + 8f, -112.5)
        val (wx, wy) = s.screen(dx, dy)
        s.touch.idle(400)
        s.touch.tap(wx, wy)
        s.touch.tap(wx, wy)
        assertTrue("a double tap closes and selects", Smoke.pumpUntil(WAIT_MS) { settle(1); tool.idle && c.selection != null && c.busyMessage == null })
        settle()
        assertEquals("the outline is done", 0, tool.anchorCount)
        assertEquals("ONE step", before + 1, steps())
        assertEquals(ToolId.MAGNETIC_LASSO.label, c.undoManager.undoLabel)
        val orangeIou = iou(c.selection!!) { x, y -> hypot(x - FRUIT_X, y - FRUIT_Y) <= FRUIT_R }
        println("Qa18 magnetic fingers: double-tap close, the orange by IoU $orangeIou")
        assertTrue("the selection is the orange: IoU $orangeIou", orangeIou >= 0.97)
        val orange = alphas(c.selection!!)

        // ---- "Intersect with selection": three taps around the bruise.
        SmokeUi.tap(MagneticLabels18.SELECTION_MODE, exact = true)
        SmokeUi.tap("Intersect with selection", exact = true)
        assertEquals("Intersect with selection", stateOf(MagneticLabels18.SELECTION_MODE))
        StripTestKit.closeMoreRow()
        for (deg in listOf(-90.0, -30.0, 30.0)) {
            val (x, y) = around(SPOT_X, SPOT_Y, SPOT_R + 6f, deg)
            ui.tap(x, y)
            idle()
        }
        assertEquals(3, tool.anchorCount)
        val three = anchors()

        // ---- "Discard outline", then the top row's Undo brings the points back, Redo drops them, Undo again.
        SmokeUi.tap(MagneticLabels18.DISCARD, exact = true)
        idle()
        assertEquals("Discard outline drops the points", 0, tool.anchorCount)
        assertEquals("no document step", before + 1, steps())
        Finger.tap(s, "Undo")
        idle()
        assertArrayEquals("Undo brings the discarded points back", three, anchors(), 0f)
        Finger.tap(s, "Redo")
        idle()
        assertEquals("Redo discards them again", 0, tool.anchorCount)
        Finger.tap(s, "Undo")
        idle()
        assertArrayEquals("Undo again", three, anchors(), 0f)
        assertEquals("the orange stays selected, untouched", before + 1, steps())
        assertArrayEquals(orange, alphas(c.selection!!))

        // ---- A tap on point 1 picks it. A pinch whose first finger lands on point 0 zooms in on
        // the bruise mid-outline: no point, no step, and point 1 stays the picked one.
        ui.tap(tool.points.x(1), tool.points.y(1))
        idle()
        assertEquals("a tap on point 1 picks it", 1, tool.activeAnchor)
        assertArrayEquals(three, anchors(), 0f)
        val zoom0 = c.viewTransform.zoom
        val inTool = tool.points.undoCount
        // Fingers18.pinch puts its first finger 30 dp left of the middle: on point 0.
        Fingers18.pinch(s, tool.points.x(0) + 30f * s.density / zoom0, tool.points.y(0))
        idle()
        val zoom1 = c.viewTransform.zoom
        println("Qa18 magnetic fingers: pinch zoom $zoom0 -> $zoom1")
        assertTrue("the pinch zooms in: $zoom0 -> $zoom1", zoom1 > zoom0 * 2f)
        assertArrayEquals("the pinch moves no point", three, anchors(), 0f)
        assertEquals("the pinch adds no in-tool step", inTool, tool.points.undoCount)
        assertEquals("nor a document step", before + 1, steps())
        assertEquals("a pinch is no tap: the picked point stays picked", 1, tool.activeAnchor)

        // ---- A press-and-drag places its point where the finger lifts, snapped onto the rim.
        val from = around(SPOT_X, SPOT_Y, SPOT_R + 18f, 90.0)
        val lift = around(SPOT_X, SPOT_Y, SPOT_R + 4f, 90.0)
        ui.stroke(from, around(SPOT_X, SPOT_Y, SPOT_R + 11f, 90.0), lift)
        idle()
        assertEquals("a drag off the points adds one", 4, tool.anchorCount)
        val dragged = tool.points.x(3) to tool.points.y(3)
        val dragSlip = angleOf(SPOT_X, SPOT_Y, dragged.first, dragged.second, 90.0)
        println("Qa18 magnetic fingers: the dragged point at $dragged, ${Qa18SelectPictures.fromSpotRim(dragged.first, dragged.second)} px off the rim, $dragSlip degrees")
        assertTrue("snapped onto the rim", Qa18SelectPictures.fromSpotRim(dragged.first, dragged.second) <= RIM_PX)
        assertTrue("where the finger lifted: $dragSlip degrees", abs(dragSlip) <= 3.0)

        for (deg in listOf(150.0, 210.0)) {
            val (x, y) = around(SPOT_X, SPOT_Y, SPOT_R + 6f, deg)
            ui.tap(x, y)
            idle()
        }
        assertEquals(6, tool.anchorCount)
        for (i in 0 until 6) assertTrue("point $i on the rim", Qa18SelectPictures.fromSpotRim(tool.points.x(i), tool.points.y(i)) <= RIM_PX)

        // ---- "Snap to edge" off: a tap stays where the finger is; the pill's trash takes it out.
        assertTrue("the More row opens", StripTestKit.openMoreRow())
        SmokeUi.tap(MagneticLabels18.SNAP_TO_EDGE, exact = true)
        settle()
        assertFalse("snapping is off", tool.settings.snapToEdge)
        StripTestKit.closeMoreRow()
        val loose = around(SPOT_X, SPOT_Y, SPOT_R + 3f, 250.0)
        ui.tap(loose.first, loose.second)
        idle()
        assertEquals(7, tool.anchorCount)
        assertEquals("the point stays where the finger is (x)", loose.first, tool.points.x(6), 0.05f)
        assertEquals("the point stays where the finger is (y)", loose.second, tool.points.y(6), 0.05f)
        assertEquals("it is the active point", 6, tool.activeAnchor)
        assertTrue("the pill's trash: ${SmokeUi.shown().take(60)}", SmokeUi.has(PillLabels.DELETE_POINTS, exact = true))
        SmokeUi.tap(PillLabels.DELETE_POINTS, exact = true)
        idle()
        assertEquals("the pill's trash deletes the active point", 6, tool.anchorCount)
        assertTrue("the More row opens", StripTestKit.openMoreRow())
        SmokeUi.tap(MagneticLabels18.SNAP_TO_EDGE, exact = true)
        settle()
        assertTrue("snapping is on again", tool.settings.snapToEdge)
        StripTestKit.closeMoreRow()

        // ---- "Close outline": ONE step, the orange intersected with the bruise is the bruise.
        SmokeUi.tap(MagneticLabels18.CLOSE, exact = true)
        assertTrue("closed", Smoke.pumpUntil(WAIT_MS) { settle(1); tool.idle && tool.anchorCount == 0 && c.busyMessage == null })
        settle()
        assertEquals("ONE more step", before + 2, steps())
        val spotIou = iou(c.selection!!) { x, y -> hypot(x - SPOT_X, y - SPOT_Y) <= SPOT_R }
        println("Qa18 magnetic fingers: orange ∩ bruise IoU $spotIou")
        assertTrue("only the bruise is left selected: IoU $spotIou", spotIou >= 0.96)
        assertEquals("the orange around it is not", 0, c.selection!!.alphaAt(FRUIT_X.toInt(), (FRUIT_Y - 100f).toInt()))
        val (l, t) = s.screen(SPOT_X - SPOT_R - 40f, SPOT_Y - SPOT_R - 40f)
        val (r, b) = s.screen(SPOT_X + SPOT_R + 40f, SPOT_Y + SPOT_R + 40f)
        Qa18SelectShots.crop(s, "magnetic-fingers-intersect", l.toInt(), t.toInt(), r.toInt(), b.toInt())

        // ---- The top row's Undo: the orange again, byte for byte.
        Finger.tap(s, "Undo")
        settle()
        assertEquals(before + 1, steps())
        assertArrayEquals("Undo gives the orange back exactly", orange, alphas(c.selection!!))

        // ---- Three points around the bruise, then another tool: leaving closes the outline, ONE step.
        for (deg in listOf(-90.0, 30.0, 150.0)) {
            val (x, y) = around(SPOT_X, SPOT_Y, SPOT_R + 6f, deg)
            ui.tap(x, y)
            idle()
        }
        assertEquals(3, tool.anchorCount)
        ui.tool(ToolId.BRUSH.label)
        assertTrue("leaving closes the outline", Smoke.pumpUntil(WAIT_MS) { settle(1); c.busyMessage == null && steps() == before + 2 })
        settle()
        assertEquals(ToolId.MAGNETIC_LASSO.label, c.undoManager.undoLabel)
        val leftIou = iou(c.selection!!) { x, y -> hypot(x - SPOT_X, y - SPOT_Y) <= SPOT_R }
        println("Qa18 magnetic fingers: closed by leaving the tool, the bruise by IoU $leftIou")
        assertTrue("the orange intersected with the three points' outline: IoU $leftIou", leftIou >= 0.95)
        Finger.tap(s, "Undo")
        settle()
        assertArrayEquals("Undo: the orange again", orange, alphas(c.selection!!))
        Smoke.assertQuiet(c, "magnetic lasso by fingers")
    }
}
