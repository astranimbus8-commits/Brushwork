package com.brushwork.paint.qa18

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import com.brushwork.paint.assist.SymmetryMaps
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.common.MirrorLabels18
import com.brushwork.paint.ui.common.StripLabels18
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeLayout
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.ui.editor.chrome.Clickables
import com.brushwork.paint.ui.tools.strip.StripLayout
import com.brushwork.paint.vector.CopyMaps
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.select.ObjectTestKit
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

/**
 * v1.8 (design §3.9, items 8 and 9): the v1.8 chrome on a small 360 × 640 dp phone, without and
 * with the VECTOR cell, with real touch events:
 * - every tool's strip (the inventory as merged, converted or not): row 1 on screen, a converted
 *   row 1 whole (nothing clipped, nothing scrolls, I18), the VECTOR cell first on it, the 44 dp
 *   toggle at its end; the opened More row 4 dp under it, on screen, at most
 *   `StripLayout.MAX_MORE_LINES` lines tall, and what it shows on screen;
 * - row 1's budget (§3.9's table: 284 dp with the toggle, 240 dp with the toggle and the VECTOR
 *   cell): an item that wide shows whole, a finger reaches it and the toggle, nothing overlaps;
 * - the vector object bar: its one row (seven buttons and "Show more object actions") inside the
 *   screen, finger-sized, and its More row (Forward, Backward, Array, "Detach mirror copies") on
 *   screen under it;
 * - the top row: the circles `ChromeLayout.topRow` keeps at 360 dp, on screen in order, "More
 *   options" (the three dots) last;
 * - long-press bubbles at the screen's right edge (the More circle, the strip and object
 *   toggles) stay whole on screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.chromenarrowsandbox"])
class ChromeV18NarrowUiTest {

    /** The top row's labels by slot (v1.6 I10). */
    private val slotLabels = mapOf(
        ChromeLayout.TopSlot.UNDO to "Undo", ChromeLayout.TopSlot.REDO to "Redo", ChromeLayout.TopSlot.VECTOR to "Vector",
        ChromeLayout.TopSlot.SELECTION to "Selection", ChromeLayout.TopSlot.STABILIZER to "Stabilizer", ChromeLayout.TopSlot.GRID to "Grid",
        ChromeLayout.TopSlot.RULER to "Ruler", ChromeLayout.TopSlot.MORE to "More options",
    )

    private val vectorCell = "Vector mode is on"

    @Test
    fun theV18ChromeFitsA360dpPhone() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        val h = ChromeHarness()
        for (vector in listOf(false, true)) {
            val with = if (vector) "with" else "without"
            h.section("every tool's strip, $with the VECTOR cell") { everyStrip(h, vector) }
            h.section("row 1's budget, $with the VECTOR cell") { budget(vector) }
        }
        h.section("the object bar") { objectBar(h) }
        h.section("the top row") { topRow(h) }
        dog.interrupt()
        h.finish()
    }

    private fun doc(vector: Boolean): Document = Smoke.document(400, 300, layers = 2, whiteBottom = true).also { d ->
        if (vector) {
            d.layers += Layer(d.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(400, 300)).also { it.vector = VectorContent.EMPTY }
            d.activeLayerIndex = 2
        }
    }

    private fun onScreen(s: ChromeScreen, what: String, r: Rect) {
        assertTrue("$what on screen (360 × 640 dp): $r", r.left >= -0.5f && r.right <= s.widthDp + 0.5f && r.top >= -0.5f && r.bottom <= s.heightDp + 0.5f)
    }

    /** The bubble's bounds (dp) while a finger holds [label]'s control. */
    private fun bubbleOf(s: ChromeScreen, label: String): Rect {
        var r: Rect? = null
        var text: String? = null
        Fingers18.longPress(s, label) {
            text = Fingers18.bubble()
            r = s.tagged(V18Tags.LONG_PRESS_BUBBLE)
        }
        assertEquals("the bubble of \"$label\"", label, text)
        return r ?: throw AssertionError("no bubble for \"$label\"")
    }

    // ------------------------------------------------------------------ every tool's strip

    private fun everyStrip(h: ChromeHarness, vector: Boolean) {
        val s = h.editor(doc(vector))
        val c = s.c
        assertEquals("360 dp wide", 360f, s.widthDp, 1f)
        var walked = 0
        var converted = 0
        for (id in ToolId.entries) {
            c.selectTool(id)
            SmokeUi.settle()
            // A tool that opens a window of its own as it is picked: back to the editor.
            repeat(3) { if (SmokeUi.windows().size > 1) Finger.back() }
            val what = "${id.label} strip"
            val row1 = s.tagged(ChromeTags.OPTIONS_STRIP)?.takeIf { it.width > 0f && it.height > 0f } ?: continue
            walked++
            onScreen(s, "$what: row 1", row1)
            if (vector && c.isVectorMode) {
                val cell = Fingers18.described(s, vectorCell)?.let { s.dp(it.boundsInWindow) } ?: throw AssertionError("$what: no VECTOR cell")
                assertTrue("$what: the VECTOR cell on row 1: $cell in $row1", row1.contains(cell.center))
            }
            if (s.tagged(V18Tags.STRIP_ROW1) == null) continue // a legacy strip scrolls sideways, as in v1.7
            converted++
            val items = Clickables.onScreen(s, inside = row1)
            for (item in items) {
                val name = item.own.firstOrNull() ?: "?"
                assertEquals("$what: \"$name\" whole (nothing clipped)", item.width, item.bounds.width, 0.5f)
                assertTrue("$what: \"$name\" inside row 1", item.bounds.left >= row1.left - 0.5f && item.bounds.right <= row1.right + 0.5f)
                assertNull("$what: row 1 never scrolls (\"$name\")", StripTestKit.horizontalScroller(item.node))
            }
            if (vector && c.isVectorMode) {
                val cell = s.dp(Fingers18.described(s, vectorCell)!!.boundsInWindow)
                for (item in items) assertTrue("$what: the VECTOR cell comes first", cell.right <= item.bounds.left + 0.5f)
            }
            val toggle = s.tagged(V18Tags.STRIP_TOGGLE) ?: continue
            assertEquals("$what: the toggle is 44 dp", StripLayout.TOGGLE_DP, toggle.width, 0.5f)
            assertTrue("$what: the toggle ends row 1", row1.contains(toggle.center) && items.all { it.bounds.right <= toggle.left + 0.5f || it.bounds == toggle })
            Finger.tap(s, StripLabels18.MORE_TOGGLE)
            Smoke.pump(300); SmokeUi.settle()
            val more = s.tagged(V18Tags.STRIP_MORE_ROW) ?: throw AssertionError("$what: the toggle opens no More row")
            onScreen(s, "$what: the More row", more)
            assertEquals("$what: 4 dp under row 1", row1.bottom + StripLayout.GAP_DP, more.top, 1f)
            assertTrue("$what: at most ${StripLayout.MAX_MORE_LINES} lines", more.height <= StripLayout.moreRowDp(StripLayout.MAX_MORE_LINES) + 0.5f)
            for (item in Clickables.onScreen(s, inside = more)) onScreen(s, "$what: \"${item.own.firstOrNull()}\" on the More row", item.bounds)
            s.tagged(ChromeTags.PILL_SLOT)?.takeIf { it.height > 0f }?.let { pill ->
                assertTrue("$what: the pill under the More row", pill.top >= more.bottom - 0.5f)
            }
            Finger.tap(s, StripLabels18.MORE_TOGGLE)
            Smoke.pump(300); SmokeUi.settle()
            assertNull("$what: the toggle closes the More row", s.tagged(V18Tags.STRIP_MORE_ROW))
        }
        c.selectTool(ToolId.BRUSH)
        SmokeUi.settle()
        System.err.println("[narrow] ${if (vector) "vector" else "raster"}: $walked strips walked, $converted converted")
        assertTrue("the tools' strips were walked: $walked", walked >= 20)
        Smoke.assertQuiet(c, "every strip at 360 dp")
    }

    // ------------------------------------------------------------------ row 1's budget

    private fun budget(vector: Boolean) {
        val room = if (vector) 240f else 284f
        FakeStripEditor.open(doc(vector), FakeStrips.spec(1, row1Width = room.dp)).use { e ->
            val s = e.screen
            assertEquals(vector, s.c.isVectorMode)
            val row1 = s.tagged(ChromeTags.OPTIONS_STRIP) ?: throw AssertionError("no row 1")
            onScreen(s, "row 1", row1)
            val item = Finger.control(s, FakeStrips.ROW1) ?: throw AssertionError("no row 1 item")
            assertEquals("a $room dp item shows whole", room, item.width, 0.5f)
            val toggle = Finger.control(s, StripLabels18.MORE_TOGGLE) ?: throw AssertionError("no toggle")
            assertEquals("the toggle is 44 dp", StripLayout.TOGGLE_DP, toggle.width, 0.5f)
            assertTrue("the toggle after the item: $item, $toggle", toggle.left >= item.right + StripLayout.GAP_DP - 0.5f)
            for ((what, r) in listOf("the item" to item, "the toggle" to toggle)) {
                assertTrue("$what inside row 1: $r in $row1", r.left >= row1.left - 0.5f && r.right <= row1.right + 0.5f)
            }
            if (vector) {
                val cell = s.dp(Fingers18.described(s, vectorCell)!!.boundsInWindow)
                assertEquals("the VECTOR cell is 40 dp", StripLayout.VECTOR_CHIP_DP, cell.width, 0.5f)
                assertTrue("the item after the VECTOR cell", item.left >= cell.right + StripLayout.GAP_DP - 0.5f && cell.left >= row1.left - 0.5f)
            }
            FakeStrips.taps.clear()
            Finger.tap(s, FakeStrips.ROW1)
            assertEquals("a finger reaches the item", 1, FakeStrips.taps[FakeStrips.ROW1])
            Finger.tap(s, StripLabels18.MORE_TOGGLE)
            Smoke.pump(300); SmokeUi.settle()
            val more = s.tagged(V18Tags.STRIP_MORE_ROW) ?: throw AssertionError("the toggle opens no More row")
            onScreen(s, "the More row", more)
            onScreen(s, "the toggle's bubble", bubbleOf(s, StripLabels18.MORE_TOGGLE))
            Finger.tap(s, StripLabels18.MORE_TOGGLE)
            Smoke.pump(300); SmokeUi.settle()
            assertNull("closed again", s.tagged(V18Tags.STRIP_MORE_ROW))
            Smoke.assertQuiet(s.c, "row 1's budget")
        }
    }

    // ------------------------------------------------------------------ the object bar

    private fun objectBar(h: ChromeHarness) {
        val kit = ObjectTestKit(400, 300)
        val s = h.editor(doc(vector = true)) { c ->
            val layer = c.doc.layers[2]
            val mirror = CopyMaps.of(SymmetryMaps.transforms(SymmetrySettings(type = SymmetryType.MIRROR), 400, 300))
            c.vectors.addObjects(layer, listOf(ObjectCopies.withCopies(kit.box(40f, 40f, 120f, 110f), mirror)), "Add")
            c.vectors.setSelection(layer, setOf(1L))
        }
        val c = s.c
        Smoke.pumpUntil(10_000) { !c.vectors.isRendering }
        SmokeUi.settle()
        val bar = s.tagged(ChromeTags.SELECTION_BAR) ?: throw AssertionError("no object bar")
        onScreen(s, "the object bar", bar)
        val row1 = listOf(
            "Delete the selected objects", "Duplicate the selected objects", "Bring to front", "Send to back",
            "Recolor with the main color", "Transform the selected objects", "Deselect the objects",
        ).map { label -> label to (Finger.control(s, label) ?: throw AssertionError("\"$label\" not on row 1")) }
        for ((label, r) in row1) {
            onScreen(s, "\"$label\"", r)
            assertTrue("\"$label\" finger-sized: $r", r.width >= 40f - 0.5f && r.height >= 40f - 0.5f)
            assertEquals("\"$label\" on row 1", row1[0].second.top, r.top, 0.5f)
        }
        for (i in 1 until row1.size) assertTrue("left to right, no overlap", row1[i - 1].second.right <= row1[i].second.left + 0.5f)
        val toggle = Finger.control(s, StripLabels18.MORE_OBJECT) ?: throw AssertionError("no \"${StripLabels18.MORE_OBJECT}\"")
        onScreen(s, "the object toggle", toggle)
        assertEquals("the object toggle is 44 dp", 44f, toggle.width, 0.5f)
        assertTrue("the toggle ends row 1", toggle.left >= row1.last().second.right - 0.5f)

        Finger.tap(s, StripLabels18.MORE_OBJECT)
        assertEquals(StripLabels18.EXPANDED, Fingers18.state(s, StripLabels18.MORE_OBJECT))
        val more = s.tagged(V18Tags.STRIP_MORE_ROW) ?: throw AssertionError("no object More row")
        onScreen(s, "the object More row", more)
        for (label in listOf("Bring forward", "Send backward", ArrayLabels.FROM_OBJECTS, MirrorLabels18.DETACH)) {
            val r = Finger.control(s, label) ?: throw AssertionError("\"$label\" not on the More row")
            onScreen(s, "\"$label\"", r)
            assertTrue("\"$label\" on the More row", more.contains(r.center))
            assertTrue("\"$label\" finger-sized: $r", r.width >= 40f - 0.5f && r.height >= 40f - 0.5f)
        }
        onScreen(s, "the object toggle's bubble", bubbleOf(s, StripLabels18.MORE_OBJECT))
        assertEquals("still open", StripLabels18.EXPANDED, Fingers18.state(s, StripLabels18.MORE_OBJECT))
        Finger.tap(s, StripLabels18.MORE_OBJECT)
        assertNull(s.tagged(V18Tags.STRIP_MORE_ROW))
        Smoke.assertQuiet(c, "object bar at 360 dp")
        kit.close()
    }

    // ------------------------------------------------------------------ the top row

    private fun topRow(h: ChromeHarness) {
        val s = h.editor()
        val top = s.tagged(ChromeTags.TOP_ROW) ?: throw AssertionError("no top row")
        onScreen(s, "the top row", top)
        val kept = ChromeLayout.topRow(s.widthDp).slots.map { slotLabels.getValue(it) }
        assertEquals("More options is the last circle", "More options", kept.last())
        val circles = kept.map { label -> label to (Finger.control(s, label) ?: throw AssertionError("no \"$label\" circle")) }
        for ((label, r) in circles) {
            onScreen(s, "\"$label\"", r)
            assertTrue("\"$label\" in the top row", top.contains(r.center))
        }
        for (i in 1 until circles.size) assertTrue("left to right, no overlap", circles[i - 1].second.right <= circles[i].second.left + 0.5f)
        val folded = slotLabels.values - kept.toSet()
        for (label in folded) assertFalse("\"$label\" folded into the More menu", SmokeUi.has(label, exact = true))
        onScreen(s, "the More circle's bubble", bubbleOf(s, "More options"))
        assertEquals("a long press opens no menu", 1, SmokeUi.windows().size)
        Finger.tap(s, "More options")
        Smoke.pump(300); SmokeUi.settle()
        assertEquals("a tap does", 2, SmokeUi.windows().size)
        for (label in folded) assertNotNull("\"$label\" in the More menu", SmokeUi.find(label, exact = true))
        Finger.back()
        Smoke.assertQuiet(s.c, "top row at 360 dp")
    }
}
