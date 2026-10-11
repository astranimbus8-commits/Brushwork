package com.brushwork.paint.qa18

import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.ui.common.StripLabels18
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.ui.tools.strip.StripLayout
import com.brushwork.paint.vector.VectorContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.util.Collections

/**
 * v1.8 integration (v18I, review): row 1 below 360 dp in the real editor, with real touch events,
 * on a 320 × 640 dp window (a larger Display size on the user's phone). `StripRow1NarrowTest`
 * measures the host alone through semantics actions; here a finger does it:
 * - Brush, Curve, Shape, Lasso and Transform (a picture lifted on the raster layer), on a raster
 *   and on a vector layer: the editor composes without the I18 check throwing, row 1 is on
 *   screen, and where the tool has a More row its 44 dp toggle shows whole on screen and a finger
 *   tap on it opens the row on screen (and another closes it);
 * - a fake row 1 of two 200 dp items (wider than the window, never leaving row 1) and a More
 *   item: the second item starts cut; a slow finger drag across row 1 scrolls it to its end
 *   (the item then shows whole, the toggle stays put, the drag fires nothing and changes nothing
 *   on the canvas); a finger tap then reaches the scrolled-in item (where the first one was
 *   before the scroll); the toggle opens the More row and its item takes a tap; a drag back
 *   brings the first item back whole.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w320dp-h640dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.row1narrowfingersandbox"])
class StripRow1NarrowFingerTest {

    private val tools = listOf(ToolId.BRUSH, ToolId.CURVE, ToolId.SHAPE, ToolId.LASSO, ToolId.TRANSFORM)

    /** Exceptions Compose hands to the main thread's handler (a composition that throws). */
    private val thrown: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())

    private var toggles = 0

    @Test
    fun belowThe360dpBudgetsAFingerScrollsRow1AndOpensTheMoreRow() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val thread = Thread.currentThread()
        val handler = thread.uncaughtExceptionHandler
        thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, t -> thrown += t }
        val dog = Smoke.watchdog()
        val h = ChromeHarness()
        try {
            for (vector in listOf(false, true)) {
                h.section("the tools' strips on a ${if (vector) "vector" else "raster"} layer") { toolStrips(h, vector) }
            }
            h.section("a row 1 wider than the window") { tooWide() }
        } finally {
            thread.uncaughtExceptionHandler = handler
        }
        dog.interrupt()
        if (thrown.isNotEmpty()) throw AssertionError("the editor threw at 320 dp", thrown.first())
        h.finish()
        System.err.println("[row1 narrow finger] toggles tapped open: $toggles")
        // Not vacuous: most of these tools have a More row at 320 dp.
        assertTrue("toggles tapped open: $toggles", toggles >= MIN_TOGGLES)
    }

    private fun doc(vector: Boolean): Document = Smoke.document(400, 300, layers = 2, whiteBottom = true).also { d ->
        if (vector) {
            d.layers += Layer(d.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(400, 300)).also { it.vector = VectorContent.EMPTY }
            d.activeLayerIndex = 2
        }
    }

    private fun onScreen(s: ChromeScreen, what: String, r: Rect) {
        assertTrue("$what on screen (320 × 640 dp): $r", r.left >= -0.5f && r.right <= s.widthDp + 0.5f && r.top >= -0.5f && r.bottom <= s.heightDp + 0.5f)
    }

    private fun noThrow(what: String) {
        if (thrown.isNotEmpty()) throw AssertionError("$what: the editor threw at 320 dp", thrown.first())
    }

    private fun expanded(s: ChromeScreen): String? = Fingers18.state(s, StripLabels18.MORE_TOGGLE)

    // ------------------------------------------------------------------ the tools' strips

    private fun toolStrips(h: ChromeHarness, vector: Boolean) {
        val s = h.editor(doc(vector))
        val c = s.c
        assertEquals("a 320 dp window", 320f, s.widthDp, 1f)
        assertEquals(vector, c.isVectorMode)
        if (!vector) {
            // A picture on the active layer: Transform lifts it (the box modes).
            assertTrue(c.editWholeLayer(c.activeLayer, "Seed") { b -> Canvas(b).drawRect(100f, 60f, 300f, 240f, Paint().apply { color = 0xFF2266CC.toInt() }) })
            SmokeUi.settle()
        }
        for (id in tools) {
            val what = "${id.name}${if (vector) "@vector" else ""}"
            c.selectTool(id)
            SmokeUi.settle()
            repeat(3) { if (SmokeUi.windows().size > 1) Finger.back() }
            if (id == ToolId.TRANSFORM && !vector) {
                val t = c.currentTool as TransformTool
                assertTrue("$what: lifted", Smoke.pumpUntil { SmokeUi.settle(); t.transformState != null })
                SmokeUi.settle()
            }
            noThrow(what)
            assertEquals("$what: selected", id, c.activeToolId)
            val row1 = s.tagged(ChromeTags.OPTIONS_STRIP) ?: throw AssertionError("$what: no options strip")
            onScreen(s, "$what: row 1", row1)
            assertNotNull("$what: row 1 is the strip's fixed row", s.tagged(V18Tags.STRIP_ROW1))
            val toggle = Finger.control(s, StripLabels18.MORE_TOGGLE) ?: continue
            assertEquals("$what: the toggle shows whole (44 dp)", StripLayout.TOGGLE_DP, toggle.width, 0.5f)
            onScreen(s, "$what: the toggle", toggle)
            assertTrue("$what: the toggle on row 1", row1.contains(toggle.center))
            assertEquals("$what: collapsed", StripLabels18.COLLAPSED, expanded(s))
            Finger.tap(s, StripLabels18.MORE_TOGGLE)
            Smoke.pump(300); SmokeUi.settle()
            noThrow("$what: the More row")
            assertEquals("$what: a finger on the toggle opens the More row", StripLabels18.EXPANDED, expanded(s))
            val more = s.tagged(V18Tags.STRIP_MORE_ROW) ?: throw AssertionError("$what: the toggle opens no More row")
            onScreen(s, "$what: the More row", more)
            toggles++
            Finger.tap(s, StripLabels18.MORE_TOGGLE)
            Smoke.pump(300); SmokeUi.settle()
            assertNull("$what: a second tap closes it", s.tagged(V18Tags.STRIP_MORE_ROW))
            assertEquals("$what: collapsed again", StripLabels18.COLLAPSED, expanded(s))
        }
        c.selectTool(ToolId.BRUSH)
        SmokeUi.settle()
        noThrow("back to Brush")
        Smoke.assertQuiet(c, "the tools' strips at 320 dp")
    }

    // ------------------------------------------------------------------ a fake row 1 wider than the window

    /** Row 1's scroller below 360 dp: the scrolling ancestor of the item described [label]. */
    private fun scroller(s: ChromeScreen, label: String): SemanticsNode {
        val node = Fingers18.described(s, label) ?: throw AssertionError("no \"$label\" placed")
        return StripTestKit.horizontalScroller(node) ?: throw AssertionError("\"$label\": row 1 does not scroll below 360 dp")
    }

    private fun scrolled(n: SemanticsNode): Float = n.config[SemanticsProperties.HorizontalScrollAxisRange].value()

    private fun scrollMax(n: SemanticsNode): Float = n.config[SemanticsProperties.HorizontalScrollAxisRange].maxValue()

    private fun tooWide() {
        val spec = FakeStrips.items(listOf(ONE to 200.dp, TWO to 200.dp), listOf(MORE to 120.dp))
        FakeStripEditor.open(doc(vector = false), spec).use { e ->
            val s = e.screen
            val c = s.c
            assertEquals("a 320 dp window", 320f, s.widthDp, 1f)
            noThrow("too wide")
            val viewport = s.dp(scroller(s, ONE).boundsInWindow)
            val toggle0 = Finger.control(s, StripLabels18.MORE_TOGGLE) ?: throw AssertionError("no toggle")
            assertEquals("the toggle shows whole", StripLayout.TOGGLE_DP, toggle0.width, 0.5f)
            onScreen(s, "the toggle", toggle0)
            assertTrue("the toggle after the scrolling cells: $toggle0, $viewport", toggle0.left >= viewport.right - 0.5f)

            // Before: the first item whole, the second cut at the viewport's end.
            assertEquals("not scrolled yet", 0f, scrolled(scroller(s, ONE)), 0.5f)
            assertTrue("row 1 can scroll", scrollMax(scroller(s, ONE)) > 100f * s.density)
            val one0 = Finger.control(s, ONE) ?: throw AssertionError("no \"$ONE\"")
            assertEquals("\"$ONE\" whole", 200f, one0.width, 0.5f)
            val cut = Finger.control(s, TWO)
            assertTrue("\"$TWO\" cut before the scroll: $cut", cut == null || cut.width < 199f)

            // A slow finger drag right to left across row 1 scrolls it to its end.
            val before = Fingers18.canvasState(s)
            FakeStrips.taps.clear()
            val y = viewport.center.y
            Finger.slowDrag(s, (viewport.right - 20f) to y, (viewport.left + 20f) to y)
            Smoke.pump(300); SmokeUi.settle()
            val sc = scroller(s, TWO)
            assertEquals("the drag scrolled row 1 to its end", scrollMax(sc), scrolled(sc), 2f)
            val two = Finger.control(s, TWO) ?: throw AssertionError("no \"$TWO\" after the scroll")
            assertEquals("\"$TWO\" whole after the scroll", 200f, two.width, 0.5f)
            assertTrue("\"$TWO\" inside row 1's cells: $two, $viewport", two.left >= viewport.left - 0.5f && two.right <= viewport.right + 0.5f)
            // Its middle is where the first item was: a tap there reaches it only after the scroll.
            assertTrue("\"$TWO\"'s middle where \"$ONE\" was before the scroll: $two, $one0", one0.contains(two.center))
            assertEquals("the toggle stayed put", toggle0, Finger.control(s, StripLabels18.MORE_TOGGLE))
            assertTrue("the drag fired nothing: ${FakeStrips.taps}", FakeStrips.taps.isEmpty())
            assertEquals("the drag left the canvas alone", before, Fingers18.canvasState(s))

            // A finger tap reaches the scrolled-in item.
            Finger.tap(s, TWO)
            assertEquals("a finger reaches \"$TWO\"", 1, FakeStrips.taps[TWO])
            assertNull("not \"$ONE\"", FakeStrips.taps[ONE])

            // The toggle opens the More row; its item takes a tap.
            Finger.tap(s, StripLabels18.MORE_TOGGLE)
            Smoke.pump(300); SmokeUi.settle()
            assertEquals(StripLabels18.EXPANDED, expanded(s))
            val more = s.tagged(V18Tags.STRIP_MORE_ROW) ?: throw AssertionError("the toggle opens no More row")
            onScreen(s, "the More row", more)
            val moreItem = Finger.control(s, MORE) ?: throw AssertionError("no \"$MORE\" on the More row")
            assertTrue("\"$MORE\" on the More row: $moreItem in $more", more.contains(moreItem.center))
            Finger.tap(s, MORE)
            assertEquals("a finger reaches \"$MORE\"", 1, FakeStrips.taps[MORE])
            Finger.tap(s, StripLabels18.MORE_TOGGLE)
            Smoke.pump(300); SmokeUi.settle()
            assertNull("closed again", s.tagged(V18Tags.STRIP_MORE_ROW))

            // A drag back brings the first item back whole.
            Finger.slowDrag(s, (viewport.left + 20f) to y, (viewport.right - 20f) to y)
            Smoke.pump(300); SmokeUi.settle()
            assertEquals("scrolled back to the start", 0f, scrolled(scroller(s, ONE)), 2f)
            assertEquals("\"$ONE\" whole again", 200f, Finger.control(s, ONE)!!.width, 0.5f)
            Finger.tap(s, ONE)
            assertEquals("a finger reaches \"$ONE\"", 1, FakeStrips.taps[ONE])
            assertEquals("nothing else fired", 1, FakeStrips.taps[TWO])
            assertEquals("the canvas never changed", before, Fingers18.canvasState(s))
            noThrow("too wide")
            Smoke.assertQuiet(c, "a row 1 wider than the window")
        }
    }

    private companion object {
        const val ONE = "Wide one"
        const val TWO = "Wide two"
        const val MORE = "Wide more"

        /** Toggles a finger opened over both layer kinds (5 tools each): 7 of the 10 strips have a More row at 320 dp. */
        const val MIN_TOGGLES = 7
    }
}
