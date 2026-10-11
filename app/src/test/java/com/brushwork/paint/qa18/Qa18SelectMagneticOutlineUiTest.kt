package com.brushwork.paint.qa18

import com.brushwork.paint.model.Selection
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.qa18.Qa18SelectPictures.FRUIT_R
import com.brushwork.paint.qa18.Qa18SelectPictures.FRUIT_X
import com.brushwork.paint.qa18.Qa18SelectPictures.FRUIT_Y
import com.brushwork.paint.qa18.Qa18SelectPictures.LEMON_R
import com.brushwork.paint.qa18.Qa18SelectPictures.LEMON_X
import com.brushwork.paint.qa18.Qa18SelectPictures.LEMON_Y
import com.brushwork.paint.qa18.Qa18SelectPictures.SPOT_R
import com.brushwork.paint.qa18.Qa18SelectPictures.SPOT_X
import com.brushwork.paint.qa18.Qa18SelectPictures.SPOT_Y
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.select.magnetic.MagneticLassoTool
import com.brushwork.paint.ui.common.MagneticLabels18
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * v1.8 final QA, item 3 (design §3.4): the magnetic lasso on a photo-like picture (an orange on a
 * woven cloth, a lemon, a bruise), at the T606's 392 dp, all by finger and by the labels the user
 * sees. Ten taps a little outside the orange: each anchor snaps to the rim and every segment
 * between two taps follows it; a point dragged along the rim, the drag undone in the top row; a
 * point undone; "Delete point" and its undo; a tap on the first point closes: ONE step, the
 * orange selected. Then "Add to selection" (the lemon, closed on its first point) and "Subtract
 * from selection" (the bruise, closed by "Close outline"); one top-row Undo per selection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.selectmagneticsandbox"])
class Qa18SelectMagneticOutlineUiTest {

    private companion object {
        const val WAIT_MS = 20_000L

        /** How far (doc px) a traced segment's points may stray from the rim it follows. */
        const val HUG_PX = 2.5f
    }

    private val h = ChromeHarness()
    private lateinit var s: ChromeScreen
    private lateinit var ui: Qa16Ui
    private val c get() = s.c

    @Test
    fun tapsAroundTheFruitFollowItsEdgeInEveryMode() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 240_000)
        h.section("magnetic lasso around a photo-like fruit at 392 dp") { outline() }
        dog.interrupt()
        h.finish()
    }

    private fun steps() = c.undoManager.undoCount

    private fun stateOf(label: String): String? = RobolectricUi.elements().lastOrNull { e ->
        e.node.layoutInfo.isPlaced && e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it == label } == true
    }?.node?.config?.getOrNull(SemanticsProperties.StateDescription)

    /** The doc point at [deg] degrees (0 = right, 90 = down) [r] px from ([cx], [cy]). */
    private fun around(cx: Float, cy: Float, r: Float, deg: Double): Pair<Float, Float> =
        (cx + r * cos(deg * PI / 180).toFloat()) to (cy + r * sin(deg * PI / 180).toFloat())

    private fun outline() {
        val doc = Smoke.document(Qa18SelectPictures.SIZE, Qa18SelectPictures.SIZE, layers = 1)
        Qa18SelectPictures.fruit(doc.layers[0].bitmap)
        s = h.editor(doc) { it.snapping.enabled = false }
        ui = Qa16Ui(s)
        assertEquals("the phone is 392 dp wide", 392f, s.widthDp, 1f)

        ui.tool("Magnetic lasso")
        settle()
        val tool = c.currentTool as MagneticLassoTool
        assertTrue("the edges are found", Smoke.pumpUntil(WAIT_MS) { settle(1); tool.edgeMapReady && tool.idle })
        fun idle() = assertTrue("the tracing settles", Smoke.pumpUntil(WAIT_MS) { settle(1); tool.idle })
        val zoom = c.viewTransform.zoom
        println("Qa18 magnetic: fit zoom $zoom (screen px per doc px), 24 dp = ${24f * s.density / zoom} doc px")
        assertEquals("a new outline replaces the selection", "New selection", stateOf(MagneticLabels18.SELECTION_MODE))
        StripTestKit.closeMoreRow()

        /** Every traced segment of the open outline hugs the rim [rim] measures; the worst distance. */
        fun hug(what: String, rim: (Float, Float) -> Float): Float {
            var worst = 0f
            for (i in 0 until tool.anchorCount) {
                worst = max(worst, rim(tool.points.x(i), tool.points.y(i)))
                assertTrue("$what: anchor $i on the rim (${tool.points.x(i)}, ${tool.points.y(i)})", rim(tool.points.x(i), tool.points.y(i)) <= HUG_PX)
            }
            for (i in 0 until tool.anchorCount - 1) {
                val seg = tool.points.segment(i)
                assertNotNull("$what: segment $i traced", seg)
                seg!!
                assertTrue("$what: segment $i is a traced path, not a straight line (${seg.size / 2} points)", seg.size / 2 > 4)
                for (k in 0 until seg.size / 2) {
                    val d = rim(seg[2 * k], seg[2 * k + 1])
                    worst = max(worst, d)
                    assertTrue("$what: segment $i point $k (${seg[2 * k]}, ${seg[2 * k + 1]}) is $d px off the rim", d <= HUG_PX)
                }
            }
            return worst
        }

        // ---- Ten taps 8 px outside the orange, clockwise from the top: each snaps onto the rim.
        val before = steps()
        val taps = (0 until 10).map { k -> around(FRUIT_X, FRUIT_Y, FRUIT_R + 8f, -90.0 + 36.0 * k) }
        for ((x, y) in taps) {
            ui.tap(x, y)
            idle()
        }
        assertEquals("ten points by ten taps", 10, tool.anchorCount)
        assertTrue("the close shows the count: ${SmokeUi.shown().take(60)}", SmokeUi.has("10 pt", exact = true))
        assertEquals("placing points makes no document step", before, steps())
        val worst = hug("ten taps", Qa18SelectPictures::fromFruitRim)
        println("Qa18 magnetic: ten anchors and nine traced segments, at most $worst px off the orange's rim")
        // The snap moves a tap onto the rim across it, never along it: each anchor stays at its tap's angle.
        val slips = (0 until 10).map { k ->
            val deg = Math.toDegrees(atan2((tool.points.y(k) - FRUIT_Y).toDouble(), (tool.points.x(k) - FRUIT_X).toDouble()))
            val d = ((deg - (-90.0 + 36.0 * k)) % 360.0 + 540.0) % 360.0 - 180.0
            (d * 10).roundToInt() / 10.0
        }
        println("Qa18 magnetic: each anchor's angle from its tap's (degrees): $slips")
        assertTrue("each tap snaps straight onto the rim, not along it: $slips degrees", slips.all { kotlin.math.abs(it) <= 3.0 })
        val l = s.screen(FRUIT_X - FRUIT_R - 60f, FRUIT_Y - FRUIT_R - 60f)
        val r = s.screen(FRUIT_X + FRUIT_R + 60f, FRUIT_Y + FRUIT_R + 60f)
        Qa18SelectShots.crop(s, "magnetic-1-ten-taps", l.first.toInt(), l.second.toInt(), r.first.toInt(), r.second.toInt())

        // ---- The top row's Undo takes the last point back; the same tap puts it again.
        Finger.tap(s, "Undo")
        idle()
        assertEquals("Undo takes the last point back", 9, tool.anchorCount)
        ui.tap(taps[9].first, taps[9].second)
        idle()
        assertEquals(10, tool.anchorCount)

        // ---- A point dragged along the rim (from 18° to 34°): its two segments are traced again and still hug.
        val i3 = 3
        val p3 = tool.points.x(i3) to tool.points.y(i3)
        val to = around(FRUIT_X, FRUIT_Y, FRUIT_R, 34.0)
        val mid = around(FRUIT_X, FRUIT_Y, FRUIT_R + 4f, 26.0)
        ui.stroke(p3, mid, to)
        idle()
        assertEquals("a drag moves a point and adds none", 10, tool.anchorCount)
        val moved = tool.points.x(i3) to tool.points.y(i3)
        println("Qa18 magnetic: the dragged point landed ${hypot(moved.first - to.first, moved.second - to.second)} px from where the finger lifted")
        assertTrue("the point followed the finger: $moved, the finger at $to", hypot(moved.first - to.first, moved.second - to.second) <= 3f)
        hug("after the drag", Qa18SelectPictures::fromFruitRim)
        Qa18SelectShots.crop(s, "magnetic-2-dragged", l.first.toInt(), l.second.toInt(), r.first.toInt(), r.second.toInt())
        Finger.tap(s, "Undo")
        idle()
        assertEquals("Undo puts the dragged point back (x)", p3.first, tool.points.x(i3), 0.5f)
        assertEquals("Undo puts the dragged point back (y)", p3.second, tool.points.y(i3), 0.5f)
        hug("after the drag's undo", Qa18SelectPictures::fromFruitRim)

        // ---- A tap on point 6 makes it active; "Delete point" takes it out, Undo brings it back.
        val p6 = tool.points.x(6) to tool.points.y(6)
        ui.tap(p6.first, p6.second)
        idle()
        assertEquals("a tap on a point picks it", 6, tool.activeAnchor)
        assertEquals("and adds none", 10, tool.anchorCount)
        StripTestKit.closeMoreRow()
        SmokeUi.tap("Delete point", exact = true)
        idle()
        assertEquals("Delete point", 9, tool.anchorCount)
        hug("its neighbours joined", Qa18SelectPictures::fromFruitRim)
        Finger.tap(s, "Undo")
        idle()
        assertEquals("Undo brings it back", 10, tool.anchorCount)
        assertEquals(p6.first, tool.points.x(6), 0.5f)
        assertEquals(p6.second, tool.points.y(6), 0.5f)
        assertEquals("still no document step", before, steps())

        // ---- A tap on the first point closes: ONE step "Magnetic lasso", the orange selected.
        val p0 = tool.points.x(0) to tool.points.y(0)
        ui.tap(p0.first, p0.second)
        assertTrue("a tap on the first point closes and selects", Smoke.pumpUntil(WAIT_MS) { settle(1); tool.idle && c.selection != null && c.busyMessage == null })
        settle()
        assertEquals("the outline is done", 0, tool.anchorCount)
        assertEquals("ONE step", before + 1, steps())
        assertEquals(ToolId.MAGNETIC_LASSO.label, c.undoManager.undoLabel)
        val iou = iou(c.selection!!) { x, y -> hypot(x - FRUIT_X, y - FRUIT_Y) <= FRUIT_R }
        println("Qa18 magnetic: the orange's selection overlaps the orange by IoU $iou")
        assertTrue("the selection is the orange: IoU $iou", iou >= 0.97)
        Qa18SelectShots.crop(s, "magnetic-3-orange-selected", l.first.toInt(), l.second.toInt(), r.first.toInt(), r.second.toInt())

        // ---- "Add to selection" in the mode dropdown: the lemon, closed on its first point.
        SmokeUi.tap(MagneticLabels18.SELECTION_MODE, exact = true)
        SmokeUi.tap("Add to selection", exact = true)
        assertEquals("Add to selection", stateOf(MagneticLabels18.SELECTION_MODE))
        StripTestKit.closeMoreRow()
        for (k in 0 until 7) {
            val (x, y) = around(LEMON_X, LEMON_Y, LEMON_R + 6f, -90.0 + 360.0 / 7 * k)
            ui.tap(x, y)
            idle()
        }
        assertEquals(7, tool.anchorCount)
        val lemonWorst = hug("the lemon", Qa18SelectPictures::fromLemonRim)
        println("Qa18 magnetic: around the lemon at the canvas's top, at most $lemonWorst px off its rim")
        val la = s.screen(0f, 0f)
        val lb = s.screen(200f, 190f)
        Qa18SelectShots.crop(s, "magnetic-4-lemon-outline", la.first.toInt(), la.second.toInt(), lb.first.toInt(), lb.second.toInt())
        ui.tap(tool.points.x(0), tool.points.y(0))
        assertTrue("closed", Smoke.pumpUntil(WAIT_MS) { settle(1); tool.idle && tool.anchorCount == 0 && c.busyMessage == null })
        settle()
        assertEquals("ONE more step", before + 2, steps())
        val union = c.selection!!
        val unionIou = iou(union) { x, y -> hypot(x - FRUIT_X, y - FRUIT_Y) <= FRUIT_R || hypot(x - LEMON_X, y - LEMON_Y) <= LEMON_R }
        println("Qa18 magnetic: orange + lemon IoU $unionIou")
        assertTrue("the orange and the lemon: IoU $unionIou", unionIou >= 0.97)

        // ---- "Subtract from selection": the bruise, closed by "Close outline".
        SmokeUi.tap(MagneticLabels18.SELECTION_MODE, exact = true)
        SmokeUi.tap("Subtract from selection", exact = true)
        assertEquals("Subtract from selection", stateOf(MagneticLabels18.SELECTION_MODE))
        StripTestKit.closeMoreRow()
        for (k in 0 until 6) {
            val (x, y) = around(SPOT_X, SPOT_Y, SPOT_R + 6f, -60.0 + 60.0 * k)
            ui.tap(x, y)
            idle()
        }
        assertEquals(6, tool.anchorCount)
        hug("the bruise", Qa18SelectPictures::fromSpotRim)
        SmokeUi.tap(MagneticLabels18.CLOSE, exact = true)
        assertTrue("closed", Smoke.pumpUntil(WAIT_MS) { settle(1); tool.idle && tool.anchorCount == 0 && c.busyMessage == null })
        settle()
        assertEquals("ONE more step", before + 3, steps())
        val holed = iou(c.selection!!) { x, y ->
            (hypot(x - FRUIT_X, y - FRUIT_Y) <= FRUIT_R && hypot(x - SPOT_X, y - SPOT_Y) > SPOT_R) || hypot(x - LEMON_X, y - LEMON_Y) <= LEMON_R
        }
        println("Qa18 magnetic: orange - bruise + lemon IoU $holed")
        assertTrue("the bruise is out of the selection: IoU $holed", holed >= 0.96)
        assertEquals("the bruise's middle is not selected", 0, c.selection!!.alphaAt(SPOT_X.toInt(), SPOT_Y.toInt()))
        assertEquals("the orange around it is", 255, c.selection!!.alphaAt(FRUIT_X.toInt(), (FRUIT_Y - 90f).toInt()))
        Qa18SelectShots.crop(s, "magnetic-5-add-and-subtract", la.first.toInt(), la.second.toInt(), r.first.toInt(), r.second.toInt())

        // ---- One top-row Undo per selection: the bruise back in, then the lemon out.
        Finger.tap(s, "Undo")
        settle()
        assertEquals(before + 2, steps())
        assertEquals("Undo: the bruise is selected again", 255, c.selection!!.alphaAt(SPOT_X.toInt(), SPOT_Y.toInt()))
        Finger.tap(s, "Undo")
        settle()
        assertEquals(before + 1, steps())
        assertEquals("Undo: the lemon is out again", 0, c.selection!!.alphaAt(LEMON_X.toInt(), LEMON_Y.toInt()))
        assertEquals("the orange stays", 255, c.selection!!.alphaAt(FRUIT_X.toInt(), FRUIT_Y.toInt()))
        Smoke.assertQuiet(c, "magnetic lasso")
    }

    /** Soft intersection over union of [sel]'s alpha and the shape [inside] (pixel centres). */
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
}
