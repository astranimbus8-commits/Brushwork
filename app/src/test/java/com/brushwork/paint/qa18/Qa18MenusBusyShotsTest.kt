package com.brushwork.paint.qa18

import android.graphics.Canvas
import android.graphics.Paint
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.common.SavedSelectionLabels
import com.brushwork.paint.ui.common.StripLabels18
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.ui.editor.chrome.Clickables
import com.brushwork.paint.ui.tools.strip.StripLayout
import com.brushwork.paint.vector.select.ObjectTestKit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 final QA (items 8 and 9) on the user's phone (392 × 873 dp): the strips at their busiest,
 * reached by fingers, their More rows opened by the toggle and rendered (`menus-busy-*.png`) for a
 * human to read: Transform with a lifted picture (box and Free deform, and its mode dropdown), an
 * open curve, a shape in Points mode; the selection bar with a selection (every button reachable:
 * the bar scrolls sideways, design §3.9 "keeps its buttons"); the vector object bar's More row;
 * the top-row More menu and a strip dropdown over a dark and over a white canvas.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.menusbusyshotssandbox"])
class Qa18MenusBusyShotsTest {

    private fun shot(s: ChromeScreen, name: String, full: Boolean = false) {
        Qa18MenusShots.save(Qa18MenusShots.capture(), s.density, "busy-$name", if (full) s.heightDp else 420f)
    }

    private fun more(s: ChromeScreen, what: String, name: String): Int {
        val (items, lines) = Qa18MenusShots.openMore(s, what)
        shot(s, name)
        System.err.println("[menus] $what | More ($lines lines): ${Qa18MenusShots.names(items)}")
        Qa18MenusShots.closeMore(s, what)
        return lines
    }

    @Test
    fun theBusiestStripsTheBarsAndTheMenusShowWhole() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 600_000)
        val h = ChromeHarness()
        h.section("Transform with a lifted picture") { transform(h) }
        h.section("an open curve and a shape in Points mode") { curveAndShape(h) }
        h.section("the selection bar") { selectionBar(h) }
        h.section("the object bar") { objectBar(h) }
        for (dark in listOf(true, false)) h.section(if (dark) "menus over a dark canvas" else "menus over a white canvas") { menus(h, dark) }
        dog.interrupt()
        h.finish()
    }

    private fun transform(h: ChromeHarness) {
        val s = h.editor(Qa18MenusShots.doc(vector = false)) { c ->
            check(c.editWholeLayer(c.doc.layers[1], "Seed") { b -> Canvas(b).drawRect(100f, 60f, 300f, 240f, Paint().apply { color = 0xFF2266CC.toInt() }) })
        }
        Qa18MenusShots.pick(s, ToolId.TRANSFORM)
        val t = s.c.currentTool as TransformTool
        assertTrue("the picture is lifted", Smoke.pumpUntil(10_000) { SmokeUi.settle(); t.transformState != null })
        // Its hint (two lines) and its eight items: the most the More row holds, three lines.
        assertEquals("Transform's More row: the hint, then its eight items", StripLayout.MAX_MORE_LINES, more(s, "Transform (box)", "transform"))
        // The mode dropdown (row 1's first cell, announced by the mode): opaque, every mode named.
        Finger.tap(s, TransformTool.Mode.FREE.label)
        Smoke.pump(300); SmokeUi.settle()
        assertEquals("the mode dropdown drops", 2, SmokeUi.windows().size)
        shot(s, "transform-modes")
        SmokeUi.tap(TransformTool.Mode.MESH.label, exact = true)
        Smoke.pump(300); SmokeUi.settle()
        assertEquals(TransformTool.Mode.MESH, t.mode)
        more(s, "Transform (Free deform)", "transform-mesh")
        Smoke.assertQuiet(s.c, "Transform")
    }

    private fun curveAndShape(h: ChromeHarness) {
        val s = h.editor(Qa18MenusShots.doc(vector = false))
        Qa18MenusShots.pick(s, ToolId.CURVE)
        for ((x, y) in listOf(60f to 70f, 110f to 100f, 150f to 80f)) Fingers18.tap(s, x, y)
        more(s, "an open curve", "curve-open")
        Qa18MenusShots.pick(s, ToolId.SHAPE)
        Fingers18.stroke(s, 120f, 90f, 260f, 210f)
        Finger.tap(s, "Points")
        Smoke.pump(300); SmokeUi.settle()
        more(s, "a shape in Points mode", "shape-points")
        Smoke.assertQuiet(s.c, "curve and shape")
    }

    /**
     * A selection made by a finger brings the selection bar. It keeps its buttons (design §3.9):
     * those past the screen's edge are reached by sliding the bar sideways, every one whole.
     */
    private fun selectionBar(h: ChromeHarness) {
        val s = h.editor(Qa18MenusShots.doc(vector = false))
        Qa18MenusShots.pick(s, ToolId.MARQUEE)
        Fingers18.stroke(s, 100f, 80f, 260f, 210f)
        assertTrue("a selection", s.c.selection != null)
        val bar = s.tagged(ChromeTags.SELECTION_BAR) ?: throw AssertionError("no selection bar")
        Qa18MenusShots.onScreen(s, "the selection bar", bar)
        shot(s, "selection-bar")
        val before = Clickables.onScreen(s, inside = bar)
        System.err.println("[menus] the selection bar at first | ${Qa18MenusShots.names(before)} || bar $bar")
        val last = listOf(ArrayLabels.FROM_SELECTION, SavedSelectionLabels.SAVE)
        val hidden = last.filter { l -> (Finger.control(s, l)?.width ?: 0f) < 40f }
        System.err.println("[menus] the selection bar: past the edge at first: $hidden")
        // A finger slides the bar sideways: every button comes into view, whole.
        Finger.slowDrag(s, (bar.right - 24f) to bar.center.y, (bar.left + 24f) to bar.center.y)
        Smoke.pump(300); SmokeUi.settle()
        shot(s, "selection-bar-slid")
        for (l in last) {
            val r = Finger.control(s, l) ?: throw AssertionError("no \"$l\" on the selection bar")
            assertTrue("\"$l\" whole in view after a slide: $r in $bar", r.left >= bar.left - 0.5f && r.right <= bar.right + 0.5f && r.width >= 40f)
        }
        // The strip's More row opens over the bar's place and moves the bar down under it.
        val (items, lines) = Qa18MenusShots.openMore(s, "Select shape with a selection")
        val more = s.tagged(V18Tags.STRIP_MORE_ROW)!!
        val moved = s.tagged(ChromeTags.SELECTION_BAR)!!
        assertTrue("the bar moves under the More row: $moved under $more", moved.top >= more.bottom - 0.5f)
        shot(s, "selection-bar-under-more")
        System.err.println("[menus] Select shape with a selection | More ($lines lines): ${Qa18MenusShots.names(items)}")
        Qa18MenusShots.closeMore(s, "Select shape with a selection")
        Smoke.assertQuiet(s.c, "the selection bar")
    }

    private fun objectBar(h: ChromeHarness) {
        val kit = ObjectTestKit(400, 300)
        try {
            val s = h.editor(Qa18MenusShots.doc(vector = true)) { c ->
                val layer = c.doc.layers[2]
                c.vectors.addObjects(layer, listOf(kit.box(40f, 40f, 120f, 110f), kit.stroke(60f, 200f, 200f, 230f)), "Add")
                c.vectors.setSelection(layer, setOf(1L))
            }
            Smoke.pumpUntil(10_000) { !s.c.vectors.isRendering }
            SmokeUi.settle()
            assertNotNull("the object bar", s.tagged(ChromeTags.SELECTION_BAR))
            assertEquals(StripLabels18.COLLAPSED, Fingers18.state(s, StripLabels18.MORE_OBJECT))
            Finger.tap(s, StripLabels18.MORE_OBJECT)
            Smoke.pump(300); SmokeUi.settle()
            assertEquals(StripLabels18.EXPANDED, Fingers18.state(s, StripLabels18.MORE_OBJECT))
            val more = s.tagged(V18Tags.STRIP_MORE_ROW) ?: throw AssertionError("no object More row")
            Qa18MenusShots.onScreen(s, "the object bar's More row", more)
            val items = Clickables.onScreen(s, inside = more)
            items.forEach { Qa18MenusShots.onScreen(s, "\"${it.own.firstOrNull()}\"", it.bounds) }
            System.err.println("[menus] the object bar | More: ${Qa18MenusShots.names(items)}")
            shot(s, "object-bar")
            Finger.tap(s, StripLabels18.MORE_OBJECT)
            Smoke.assertQuiet(s.c, "the object bar")
        } finally {
            kit.close()
        }
    }

    /** The top row's More menu and the Selection mode dropdown over a [dark] (or white) canvas. */
    private fun menus(h: ChromeHarness, dark: Boolean) {
        val s = h.editor(Qa18MenusShots.doc(vector = false, dark = dark))
        val tone = if (dark) "dark" else "white"
        // Zoomed in by two fingers, as while painting: the picture fills the screen under the menus.
        Fingers18.pinch(s, 200f, 150f)
        Fingers18.pinch(s, 200f, 150f)
        val zoomed = s.screen(0f, 0f)
        assertTrue("the picture covers the top of the screen: $zoomed", zoomed.second < 0f)
        Finger.tap(s, "More options")
        Smoke.pump(300); SmokeUi.settle()
        assertEquals("the More menu drops", 2, SmokeUi.windows().size)
        shot(s, "$tone-more-menu", full = true)
        Finger.back()
        Qa18MenusShots.pick(s, ToolId.MAGIC_WAND)
        Finger.tap(s, "Selection mode")
        Smoke.pump(300); SmokeUi.settle()
        assertEquals("the Selection mode dropdown drops", 2, SmokeUi.windows().size)
        shot(s, "$tone-selection-mode")
        Finger.back()
        Qa18MenusShots.pick(s, ToolId.LASSO)
        Finger.tap(s, "Freehand lasso")
        Smoke.pump(300); SmokeUi.settle()
        assertEquals("the lasso kind dropdown drops", 2, SmokeUi.windows().size)
        shot(s, "$tone-lasso-kind")
        Finger.back()
        Smoke.assertQuiet(s.c, "menus over a $tone canvas")
    }
}
