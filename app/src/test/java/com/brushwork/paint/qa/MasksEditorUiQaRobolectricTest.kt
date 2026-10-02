package com.brushwork.paint.qa

import android.graphics.Canvas
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.EditorController
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.clone.CloneTool
import com.brushwork.paint.tools.mask.AdjustmentEdit
import com.brushwork.paint.tools.mask.MaskTool
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.editor.CanvasView
import com.brushwork.paint.ui.editor.EditorScreen
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.5 final QA in the real editor on the narrowest phone the app targets (360 dp): Tools grid →
 * Masks → + Linear → a finger drag on the canvas → Adjust… (Tone's sliders, Amount reachable) →
 * Components (every action reachable) → the layers window's "Edit adjustment"; Filters → search
 * "exposure" → Tone → "As adjustment layer"; the clone stamp's strip. Everything a finger needs
 * must be on screen or scrollable into the window.
 *
 * Own sandbox; all UI work in ONE test split into sections (Compose's frame clock only serves
 * the first test of a sandbox).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h740dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa.masksuisandbox"])
class MasksEditorUiQaRobolectricTest {

    private val failures = mutableListOf<Throwable>()
    private val activities = mutableListOf<org.robolectric.android.controller.ActivityController<*>>()

    private fun section(name: String, block: () -> Unit) {
        Smoke.scopeErrors.clear()
        Smoke.step("section $name")
        try {
            block()
            if (Smoke.scopeErrors.isNotEmpty()) throw AssertionError("coroutine errors: ${Smoke.scopeErrors}", Smoke.scopeErrors.first())
        } catch (t: Throwable) {
            System.err.println("=== SECTION FAILED: $name")
            t.printStackTrace()
            failures += AssertionError("[$name] $t", t)
        } finally {
            activities.forEach { runCatching { it.pause().stop().destroy() } }
            activities.clear()
            runCatching { settle() }
        }
    }

    private class Screen(val activity: ComponentActivity, val c: EditorController) {
        val canvas: CanvasView get() = Smoke.find(activity.window.decorView, CanvasView::class.java) ?: throw AssertionError("no canvas")
        val touch = Smoke.Touch(activity.window.decorView)
        val width: Int get() = activity.window.decorView.width

        fun screen(x: Float, y: Float): Pair<Float, Float> {
            val loc = IntArray(2)
            canvas.getLocationInWindow(loc)
            val p = c.viewTransform.docToScreen(x, y)
            return (p.x + loc[0]) to (p.y + loc[1])
        }
    }

    private fun editor(): Screen {
        SmokeUi.markBaseline()
        val ctl = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activities += ctl
        val activity = ctl.get()
        val c = Smoke.controller(activity, Smoke.document(400, 300, layers = 2, whiteBottom = true))
        // A photo-like top layer: stripes of two colors.
        val p = Paint()
        Canvas(c.doc.layers[1].bitmap).apply {
            for (x in 0 until 400 step 8) { p.color = if ((x / 8) % 2 == 0) 0xFF3366AA.toInt() else 0xFFDDAA44.toInt(); drawRect(x.toFloat(), 0f, x + 8f, 300f, p) }
        }
        activity.setContent { BrushworkTheme { EditorScreen(c, onExit = {}, onSaveNow = {}) } }
        settle()
        c.tools
        settle()
        return Screen(activity, c)
    }

    /** The horizontally scrolling element that contains the element labelled [label]. */
    private fun horizontalScrollerOf(label: String): RobolectricUi.Element {
        val target = SmokeUi.find(label, exact = true) ?: throw AssertionError("no \"$label\"; shown: ${SmokeUi.shown().take(80)}")
        var n = target.node.parent
        while (n != null && n.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) == null) n = n.parent
        requireNotNull(n) { "\"$label\" is not in a horizontal scroller" }
        return RobolectricUi.Element(n, target.window)
    }

    private fun verticalScrollerOf(label: String): RobolectricUi.Element {
        val target = SmokeUi.find(label, exact = true) ?: throw AssertionError("no \"$label\"; shown: ${SmokeUi.shown().take(80)}")
        var n = target.node.parent
        while (n != null && n.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) == null) n = n.parent
        requireNotNull(n) { "\"$label\" is not in a vertical scroller" }
        return RobolectricUi.Element(n, target.window)
    }

    /**
     * Scrolls [scroller] (as a finger would) until the element labelled [label] is fully inside
     * it, and returns whether it got there.
     */
    private var lastScroll = ""

    private fun scrollIntoView(scroller: RobolectricUi.Element, label: String, horizontal: Boolean): Boolean {
        val scrollBy = requireNotNull(scroller.node.config.getOrNull(SemanticsActions.ScrollBy)?.action) { "not scrollable" }
        repeat(40) {
            val e = SmokeUi.find(label, exact = true) ?: run { lastScroll = "\"$label\" not found"; return false }
            val se = RobolectricUi.elements().firstOrNull { it.node.id == scroller.node.id } ?: run { lastScroll = "scroller gone"; return false }
            val s = se.bounds
            val range = se.node.config.getOrNull(if (horizontal) SemanticsProperties.HorizontalScrollAxisRange else SemanticsProperties.VerticalScrollAxisRange)
            // (boundsInWindow is clipped by the scroller: a node scrolled out of view has none.)
            val pos = e.node.positionInWindow
            val b = androidx.compose.ui.geometry.Rect(pos.x, pos.y, pos.x + e.node.size.width, pos.y + e.node.size.height)
            lastScroll = "\"$label\" at $b in $s, scroll ${range?.value?.invoke()} / ${range?.maxValue?.invoke()}"
            if (horizontal) {
                if (b.left >= s.left - 1f && b.right <= s.right + 1f) return true
                scrollBy(if (b.right > s.right) minOf(200f, b.right - s.right + 4f) else -minOf(200f, s.left - b.left + 4f), 0f)
            } else {
                if (b.top >= s.top - 1f && b.bottom <= s.bottom + 1f) return true
                scrollBy(0f, if (b.bottom > s.bottom) minOf(300f, b.bottom - s.bottom + 4f) else -minOf(300f, s.top - b.top + 4f))
            }
            settle(2)
        }
        return false
    }

    private fun assertReachable(where: String, scroller: RobolectricUi.Element, label: String, horizontal: Boolean) {
        val ok = scrollIntoView(scroller, label, horizontal)
        assertTrue("$where: \"$label\" can be scrolled into view ($lastScroll)", ok)
    }

    @Test
    fun masksAdjustAndCloneInTheEditorAt360dp() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        section("Tools grid → Masks → + Linear → drag → Adjust → Components") { masksFlow() }
        section("Filters → exposure → Tone → As adjustment layer") { asAdjustmentLayer() }
        section("clone stamp strip") { cloneStrip() }
        dog.interrupt()
        if (failures.isNotEmpty()) {
            val first = failures.first()
            failures.drop(1).forEach { first.addSuppressed(it) }
            throw first
        }
    }

    private fun masksFlow() {
        val s = editor()
        val c = s.c
        click("Tools (current:")
        SmokeUi.assertPanelShown("Tools")
        click("Masks", exact = true)
        assertEquals(ToolId.MASK, c.activeToolId)
        val tool = c.tools.getValue(ToolId.MASK) as MaskTool
        // Every chip of the strip can be scrolled into the 360 dp window.
        val strip = horizontalScrollerOf("+ Linear")
        for (label in listOf("New Tone", "+ Linear", "+ Radial", "+ Brush", "Add", "Subtract", "Intersect", "Invert", "Adjust…", "Components (0)")) {
            assertReachable("strip", strip, label, horizontal = true)
            val b = SmokeUi.find(label, exact = true)!!.bounds
            assertTrue("\"$label\" inside the window: $b (width ${s.width})", b.left >= 0f && b.right <= s.width + 1f)
        }
        scrollIntoView(strip, "+ Linear", horizontal = true)
        click("+ Linear", exact = true)
        assertEquals(MaskTool.Kind.LINEAR, tool.armed)
        val steps = c.undoManager.undoCount
        s.touch.stroke(s.screen(80f, 150f), s.screen(200f, 150f), s.screen(320f, 150f))
        settle()
        val adj = c.activeLayer
        assertTrue("a finger drag made the adjustment layer", adj.isAdjustmentLayer)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertNotNull(adj.maskSpec)
        assertTrue(has("Components (1)", exact = true))

        // Adjust…: Tone's sliders; Exposure changes live, Amount is reachable; one step on close.
        assertTrue(scrollIntoView(horizontalScrollerOf("Adjust…"), "Adjust…", horizontal = true))
        assertTrue(SmokeUi.isEnabled("Adjust…"))
        click("Adjust…", exact = true)
        SmokeUi.assertPanelShown("Adjust: ${adj.name}")
        for (label in listOf("Exposure", "Contrast", "Highlights", "Shadows", "Whites", "Blacks", "Amount")) {
            assertReachable("Adjust", verticalScrollerOf("Exposure"), label, horizontal = false)
        }
        scrollIntoView(verticalScrollerOf("Exposure"), "Exposure", horizontal = false)
        val before = c.undoManager.undoCount
        // (A press-and-repeat button: real taps.)
        repeat(3) { SmokeUi.tap("Increase Exposure", exact = true); settle(2) }
        val exp = adj.adjustment!!.values["exposure"]?.toString()?.toFloatOrNull() ?: 0f
        assertTrue("exposure moved: $exp", exp > 0f)
        assertEquals("live: no step", before, c.undoManager.undoCount)
        click("Close", exact = true)
        assertFalse(tool.adjustOpen)
        assertEquals(before + 1, c.undoManager.undoCount)
        assertEquals(AdjustmentEdit.LABEL, c.undoManager.undoLabel)

        // Components: every row and action can be reached; Invert mask is one step.
        scrollIntoView(horizontalScrollerOf("Components (1)"), "Components (1)", horizontal = true)
        click("Components (1)", exact = true)
        SmokeUi.assertPanelShown("Mask of ${adj.name}")
        for (label in listOf("Linear 1", "Invert mask", "Density", "Use mask as selection", "Apply a filter through this mask…", "Convert to pixel mask", "Delete mask", "Apply to layer below", "Safe compositing")) {
            assertReachable("Components", verticalScrollerOf("Invert mask"), label, horizontal = false)
        }
        scrollIntoView(verticalScrollerOf("Invert mask"), "Invert mask", horizontal = false)
        val n = c.undoManager.undoCount
        click("Invert mask", exact = true)
        assertTrue(adj.maskSpec!!.invert)
        assertEquals(n + 1, c.undoManager.undoCount)
        click("Close", exact = true)
        assertFalse(tool.componentsOpen)

        // The layers window's "Edit adjustment" opens the Adjust sheet again.
        click("Open layers")
        click("More layer actions")
        click("Edit adjustment", exact = true)
        SmokeUi.assertPanelShown("Adjust: ${adj.name}")
        assertEquals(ToolId.MASK, c.activeToolId)
        click("Close", exact = true)
        Smoke.assertQuiet(c, "masks flow")
    }

    private fun asAdjustmentLayer() {
        val s = editor()
        val c = s.c
        val photo = c.activeLayer
        click("Tools (current:")
        click("Filters", exact = true)
        SmokeUi.assertPanelShown("Filters")
        SmokeUi.field("Search filters").let { it.focus(); settle(2); it.type("exposure") }
        settle(4)
        click("Tone", exact = true)
        val session = c.filterSession ?: throw AssertionError("no Tone session")
        assertEquals("adjust.tone", session.filter.id)
        assertTrue("As adjustment layer is offered", has("As adjustment layer", exact = true))
        val steps = c.undoManager.undoCount
        click("Add as adjustment layer")
        assertNull("the session closed", c.filterSession)
        val adj = c.activeLayer
        assertTrue(adj.isAdjustmentLayer)
        assertEquals(c.doc.indexOf(photo) + 1, c.doc.indexOf(adj))
        assertEquals(steps + 1, c.undoManager.undoCount)
        Smoke.assertQuiet(c, "as adjustment layer")
    }

    private fun cloneStrip() {
        val s = editor()
        val c = s.c
        click("Tools (current:")
        click("Clone stamp", exact = true)
        assertEquals(ToolId.CLONE, c.activeToolId)
        val tool = c.tools.getValue(ToolId.CLONE) as CloneTool
        val strip = horizontalScrollerOf("Set source")
        for (label in listOf(CloneTool.HINT, "Set source", "Aligned", "Sample: This layer", "Show source")) {
            assertReachable("clone strip", strip, label, horizontal = true)
        }
        scrollIntoView(strip, "Set source", horizontal = true)
        click("Set source", exact = true)
        assertTrue(tool.armed)
        s.touch.tap(s.screen(100f, 100f).first, s.screen(100f, 100f).second)
        settle()
        assertFalse(tool.armed)
        val src = tool.anchor.source
        assertNotNull(src)
        assertEquals(100f, src!!.x, 2f)
        scrollIntoView(strip, "Aligned", horizontal = true)
        click("Aligned", exact = true)
        assertFalse(tool.aligned)
        Smoke.assertQuiet(c, "clone strip")
    }
}
