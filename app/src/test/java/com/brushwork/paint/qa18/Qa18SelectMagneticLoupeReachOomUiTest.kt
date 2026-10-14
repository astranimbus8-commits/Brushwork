package com.brushwork.paint.qa18

import android.view.MotionEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.model.Selection
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.qa18.Qa18SelectPictures.FRUIT_R
import com.brushwork.paint.qa18.Qa18SelectPictures.FRUIT_X
import com.brushwork.paint.qa18.Qa18SelectPictures.FRUIT_Y
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.select.SampleSource
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.select.magnetic.Loupe
import com.brushwork.paint.tools.select.magnetic.MagneticLassoTool
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.MagneticLabels18
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * v1.8 final QA, item 3 (design §3.4): the magnetic lasso's edges of the request at the T606's
 * 392 dp, by finger and labels. Without the memory for its edge map ("Not enough memory to find
 * edges: the outline stays straight", once) the outline is straight and still selects; picked
 * again from the tool menu with memory, the edges are back. "Edge reach" (More row) at 8 dp and
 * 64 dp: a tap finds the rim only within its reach, and at 64 dp lands on the rim's nearest point.
 * The loupe ("Your finger never hides the edge being traced") flips below a finger near the top
 * of the canvas and is never drawn under the top bars.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.selectmagneticloupesandbox"])
class Qa18SelectMagneticLoupeReachOomUiTest {

    private companion object {
        const val WAIT_MS = 20_000L
    }

    private val h = ChromeHarness()
    private lateinit var s: ChromeScreen
    private lateinit var ui: Qa16Ui
    private val c get() = s.c

    @Test
    fun noMemoryReachAndTheLoupeAtTheTop() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 240_000)
        h.section("magnetic lasso: no memory, Edge reach, the loupe at 392 dp") { run() }
        dog.interrupt()
        h.finish()
    }

    private fun steps() = c.undoManager.undoCount

    private fun stateOf(label: String): String? = RobolectricUi.elements().lastOrNull { e ->
        e.node.layoutInfo.isPlaced && e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it == label } == true
    }?.node?.config?.getOrNull(SemanticsProperties.StateDescription)

    private fun around(r: Float, deg: Double): Pair<Float, Float> =
        (FRUIT_X + r * cos(deg * PI / 180).toFloat()) to (FRUIT_Y + r * sin(deg * PI / 180).toFloat())

    private fun angleOf(x: Float, y: Float) = Math.toDegrees(atan2((y - FRUIT_Y).toDouble(), (x - FRUIT_X).toDouble()))

    private fun slip(x: Float, y: Float, deg: Double) = abs(((angleOf(x, y) - deg) % 360.0 + 540.0) % 360.0 - 180.0)

    /**
     * "Edge reach" on the More row: its slider dragged by a finger to its [left] or right end; what
     * the chip then says (its state).
     */
    private fun edgeReachTo(left: Boolean): String? {
        assertTrue("the More row opens", StripTestKit.openMoreRow())
        SmokeUi.tap(MagneticLabels18.EDGE_REACH, exact = true)
        val slider = RobolectricUi.elements().last { e ->
            e.node.layoutInfo.isPlaced && e.node.config.contains(SemanticsActions.SetProgress)
        }
        val b = slider.bounds
        val y = b.center.y
        val to = if (left) b.left - 20f else b.right + 20f
        RobolectricUi.drag(slider.window, b.center.x to y, (b.center.x + to) / 2f to y, to to y)
        settle()
        Finger.back()
        val said = stateOf(MagneticLabels18.EDGE_REACH)
        StripTestKit.closeMoreRow()
        return said
    }

    private fun run() {
        val doc = Smoke.document(Qa18SelectPictures.SIZE, Qa18SelectPictures.SIZE, layers = 1)
        Qa18SelectPictures.fruit(doc.layers[0].bitmap)
        s = h.editor(doc) { it.snapping.enabled = false }
        ui = Qa16Ui(s)
        assertEquals("the phone is 392 dp wide", 392f, s.widthDp, 1f)
        val tool = c.tools.getValue(ToolId.MAGNETIC_LASSO) as MagneticLassoTool
        fun idle() = assertTrue("the tool settles", Smoke.pumpUntil(WAIT_MS) { settle(1); tool.idle })

        // ---- No memory for the edge map: said once, straight segments, the outline still selects.
        val real = tool.renderSource
        var tries = 0
        tool.renderSource = { _ -> tries++; throw OutOfMemoryError("test") }
        ui.tool(ToolId.MAGNETIC_LASSO.label)
        idle()
        assertEquals("the edges were tried once", 1, tries)
        assertFalse(tool.edgeMapReady)
        assertTrue("the message shows: ${SmokeUi.shown().take(60)}", SmokeUi.has(MagneticLabels18.OUT_OF_MEMORY, exact = true))
        Qa18SelectShots.screen(s, "magnetic-6-out-of-memory")
        StripTestKit.closeMoreRow()
        val square = listOf(0.0, 90.0, 180.0, 270.0).map { around(FRUIT_R, it) }
        for ((x, y) in square) { ui.tap(x, y); idle() }
        assertEquals(4, tool.anchorCount)
        assertEquals("no new try on a touch (nothing said again)", 1, tries)
        for (i in 0 until 4) {
            assertEquals("anchor $i where the finger was (x)", square[i].first, tool.points.x(i), 0.6f)
            assertEquals("anchor $i where the finger was (y)", square[i].second, tool.points.y(i), 0.6f)
        }
        for (i in 0 until 3) assertFalse("segment $i stays straight", tool.isTraced(i))
        val before = steps()
        SmokeUi.tap(MagneticLabels18.CLOSE, exact = true)
        assertTrue("closed", Smoke.pumpUntil(WAIT_MS) { settle(1); tool.idle && tool.anchorCount == 0 && c.busyMessage == null && c.selection != null })
        settle()
        assertEquals("ONE step", before + 1, steps())
        assertEquals(1, tries)
        val diamond = iou(c.selection!!) { x, y -> abs(x - FRUIT_X) + abs(y - FRUIT_Y) <= FRUIT_R }
        println("Qa18 magnetic: with no memory the four taps select the straight diamond, IoU $diamond")
        assertTrue("the straight outline is selected: IoU $diamond", diamond >= 0.97)

        // ---- Picked again from the tool menu, with memory: the edges are found, nothing said.
        tool.renderSource = real
        ui.tool(ToolId.LASSO.label)
        ui.tool(ToolId.MAGNETIC_LASSO.label)
        assertTrue("the edges are found", Smoke.pumpUntil(WAIT_MS) { settle(1); tool.edgeMapReady && tool.idle })
        assertEquals(SampleSource.LAYER, tool.settings.source)
        StripTestKit.closeMoreRow()

        // ---- "Edge reach" at 8 dp: a tap 8 px off the rim finds it, one 22 px off does not reach it.
        val zoom = c.viewTransform.zoom
        assertEquals("the chip says 8 dp", "8 dp", edgeReachTo(left = true))
        assertEquals(8, tool.settings.edgeReachDp)
        println("Qa18 magnetic: Edge reach 8 dp = ${8f * s.density / zoom} doc px, 64 dp = ${64f * s.density / zoom} doc px")
        around(FRUIT_R + 8f, 0.0).let { (x, y) -> ui.tap(x, y) }
        idle()
        assertTrue("8 px off: on the rim", Qa18SelectPictures.fromFruitRim(tool.points.x(0), tool.points.y(0)) <= 1.5f)
        around(FRUIT_R + 22f, 90.0).let { (x, y) -> ui.tap(x, y) }
        idle()
        val far = Qa18SelectPictures.fromFruitRim(tool.points.x(1), tool.points.y(1))
        println("Qa18 magnetic: at 8 dp a tap 22 px off the rim stays $far px off it")
        assertTrue("22 px off at 8 dp: the rim is out of reach ($far px)", far >= 8f)
        SmokeUi.tap(MagneticLabels18.DISCARD, exact = true)
        assertEquals(0, tool.anchorCount)

        // ---- At 64 dp (about 93 doc px): taps 22 and 50 px off land on the rim's nearest point.
        assertEquals("the chip says 64 dp", "64 dp", edgeReachTo(left = false))
        assertEquals(64, tool.settings.edgeReachDp)
        for ((k, pair) in listOf(22f to 0.0, 50f to 45.0, 22f to 100.0).withIndex()) {
            val (off, deg) = pair
            around(FRUIT_R + off, deg).let { (x, y) -> ui.tap(x, y) }
            idle()
            val d = Qa18SelectPictures.fromFruitRim(tool.points.x(k), tool.points.y(k))
            val sl = slip(tool.points.x(k), tool.points.y(k), deg)
            println("Qa18 magnetic: at 64 dp a tap $off px off at $deg° lands $d px off the rim, $sl° along it")
            assertTrue("$off px off at $deg°: on the rim ($d px)", d <= 1.5f)
            assertTrue("$off px off at $deg°: across the rim, not along it ($sl°)", sl <= 2.0)
        }
        SmokeUi.tap(MagneticLabels18.DISCARD, exact = true)
        assertEquals(0, tool.anchorCount)

        // ---- The loupe at the top of the canvas: it flips below the finger and is never under the bars.
        val t = c.viewTransform
        val top = Loupe.chromeTop(c)
        val radius = t.dp(Loupe.DIAMETER_DP) / 2f
        val canvasH = s.canvas.height.toFloat()
        val docTop = t.docToScreen(0f, 0f).y
        println("Qa18 magnetic: the bars cover ${top / s.density} dp of the canvas; the picture's top is ${(docTop - top) / s.density} dp below them")
        assertTrue("the editor measured its bars", top > 0f)
        fun loupeAt(viewY: Float, what: String, shot: String? = null) {
            val docP = t.screenToDoc(t.docToScreen(LEMON_X_DOC, 0f).x, viewY)
            val (wx, wy) = s.screen(docP.x, docP.y)
            s.touch.idle(400)
            s.touch.send(MotionEvent.ACTION_DOWN, Smoke.P(0, wx, wy))
            s.touch.idle(60)
            settle(2)
            val l = tool.loupe
            assertNotNull("$what: the loupe shows while the finger is down", l)
            l!!
            println("Qa18 magnetic: $what: finger ${(viewY - top) / s.density} dp under the bars, loupe ${if (l.flipped) "below" else "above"}, its top ${(l.center.y - radius - top) / s.density} dp under them")
            assertTrue("$what: the loupe is never under the bars (top ${(l.center.y - radius - top) / s.density} dp from them)", l.center.y - radius >= top - 0.5f)
            assertTrue("$what: nor past the canvas's bottom", l.center.y + radius <= canvasH + 0.5f)
            val near = viewY - top < t.dp(Loupe.FLIP_DP) || viewY - t.dp(Loupe.OFFSET_DP) - radius < top
            assertEquals("$what: below the finger in the canvas's top 140 dp, and wherever above would reach under the bars", near, l.flipped)
            if (shot != null) Qa18SelectShots.screen(s, shot)
            s.touch.send(MotionEvent.ACTION_CANCEL, Smoke.P(0, wx, wy))
            s.touch.idle(60)
            settle(2)
            assertEquals("$what: no anchor from a cancelled touch", 0, tool.anchorCount)
        }
        loupeAt(top + t.dp(40f), "a finger 40 dp under the bars", "magnetic-7-loupe-at-the-top")
        loupeAt(top + t.dp(Loupe.FLIP_DP) + t.dp(4f), "a finger 144 dp under the bars", "magnetic-8-loupe-144dp")
        loupeAt(top + t.dp(260f), "a finger 260 dp under the bars")
        Smoke.assertQuiet(c, "magnetic loupe, reach, memory")
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

/** The x (doc px) the loupe probes run down: the lemon's middle column. */
private const val LEMON_X_DOC = Qa18SelectPictures.LEMON_X
