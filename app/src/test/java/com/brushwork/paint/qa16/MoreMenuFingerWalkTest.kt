package com.brushwork.paint.qa16

import android.app.Activity
import android.content.Intent
import android.view.View
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.editor.EditorScreen
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.ui.editor.chrome.Clickables
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Every entry of the More menu (§3.7.6), picked by a finger — the menu opened by the "More
 * options" circle and scrolled by a finger until the entry shows — does what its label says:
 * copy, paste, the pickers of the two imports, the SVG / PDF export sheets, PNG / JPG export and
 * Share, the Canvas, Increments and Settings panels, Flip view, Fit to screen, 100 %, Reset
 * rotation and Save now. Plus I10 and 40 dp targets with the menu open, alone and over the layer
 * window (where the menu leaves "Import picture" to the window's own button).
 */
internal object MoreWalk {

    /** The menu's window: the newest one while the menu is open. */
    private fun popup(): View = SmokeUi.windows().last()

    private fun SemanticsNode.clickable(): SemanticsNode? {
        var n: SemanticsNode? = this
        while (n != null && n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent
        return n
    }

    /** The row of the entry labelled [label] in the menu's window. */
    private fun row(window: View, label: String): SemanticsNode? =
        RobolectricUi.elements()
            .filter { it.window === window && it.node.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == label } == true }
            .firstNotNullOfOrNull { it.node.clickable() }

    /** What shows of the entry labelled [label] (window px, clipped by the scroll; empty when scrolled out). */
    private fun entry(window: View, label: String): androidx.compose.ui.geometry.Rect? = row(window, label)?.boundsInWindow

    /** Where the entry's middle is laid out (window px), shown or scrolled out above or below. */
    private fun laidOutMiddle(window: View, label: String): Float? =
        row(window, label)?.let { it.positionInWindow.y + it.size.height / 2f }

    /** The menu's scrolling body (window px). */
    private fun body(window: View): androidx.compose.ui.geometry.Rect =
        RobolectricUi.elements().first { it.window === window && it.node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }.bounds

    /** Opens the More menu with a finger. */
    fun open(s: ChromeScreen) {
        Finger.tap(s, "More options")
        Smoke.pump(300); settle()
        assertEquals("the More menu drops", 2, SmokeUi.windows().size)
    }

    /**
     * Opens the menu, scrolls it with a finger (slow drags on its body, up or down) until the
     * entry [label] shows whole, and taps it. The menu closes ([windowsAfter] windows are left;
     * null when the entry opens a window of its own).
     */
    fun pick(s: ChromeScreen, label: String, windowsAfter: Int? = 1) {
        open(s)
        val w = popup()
        val minRow = 40f * s.density - 1f
        var shown = entry(w, label) ?: throw AssertionError("More has no \"$label\"")
        var tries = 0
        while (shown.height < minRow && tries++ < 12) {
            val b = body(w)
            val x = b.left + b.width / 2f
            // Laid out below the body's middle (shown, or scrolled out at the bottom: an entry
            // scrolled out shows nothing, so its laid-out place decides): push the list up.
            val up = (laidOutMiddle(w, label) ?: shown.center.y) > b.center.y
            if (up) RobolectricUi.drag(w, x to b.top + b.height * 0.8f, x to b.top + b.height * 0.5f, x to b.top + b.height * 0.25f)
            else RobolectricUi.drag(w, x to b.top + b.height * 0.25f, x to b.top + b.height * 0.5f, x to b.top + b.height * 0.8f)
            Smoke.pump(300); settle()
            shown = entry(w, label) ?: throw AssertionError("\"$label\" left the menu")
        }
        assertTrue("More › $label shows whole after $tries drags: $shown", shown.height >= minRow)
        RobolectricUi.tap(w, shown.center.x, shown.center.y)
        Smoke.pump(300); settle()
        assertFalse("More › $label closes the menu", SmokeUi.windows().contains(w))
        if (windowsAfter != null) assertEquals("More › $label: windows", windowsAfter, SmokeUi.windows().size)
    }

    /** The picker a menu entry started (and answers it with "cancelled"). */
    fun pickerStarted(s: ChromeScreen, what: String): Intent {
        val shadow = shadowOf(s.activity)
        val started = shadow.nextStartedActivityForResult ?: throw AssertionError("$what: no picker was started")
        shadow.receiveResult(started.intent, Activity.RESULT_CANCELED, null)
        settle()
        return started.intent
    }

    /** Window px of the canvas's middle. */
    fun middle(s: ChromeScreen): Pair<Float, Float> = s.root.center.x to s.root.center.y
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.morewalksandbox"])
class MoreMenuFingerWalkTest {
    @Test
    fun everyMoreEntryDoesWhatItSays() = Walk.run("More menu") { h ->
        val s = h.editor(IbisDocs.page())
        var saves = 0
        s.activity.setContent { BrushworkTheme { EditorScreen(s.c, onExit = {}, onSaveNow = { saves++ }) } }
        settle()
        s.c.selectLayer(1); settle()

        // Copy layer → the clipboard holds the red disc; Paste → a new layer placed with Transform.
        assertNull(s.c.clipboard)
        MoreWalk.pick(s, "Copy layer")
        assertNotNull("Copy layer copied", s.c.clipboard)
        val before = s.c.doc.layers.size
        MoreWalk.pick(s, "Paste")
        assertEquals("Paste adds a layer", before + 1, s.c.doc.layers.size)
        assertEquals("…placed with Transform", ToolId.TRANSFORM, s.c.activeToolId)
        assertTrue("…waiting for ✓ / ✕", s.c.currentTool.hasPendingWork)
        Finger.tap(s, "Apply transform edit")
        assertFalse(s.c.currentTool.hasPendingWork)
        Finger.tap(s, "Tools (current: Transform)")
        Finger.tap(s, "Brush")
        assertEquals(ToolId.BRUSH, s.c.activeToolId)

        // The two imports start their pickers.
        MoreWalk.pick(s, "Import picture")
        MoreWalk.pickerStarted(s, "Import picture")
        MoreWalk.pick(s, "Import SVG or PDF…")
        val vector = MoreWalk.pickerStarted(s, "Import SVG or PDF…")
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, vector.action)

        // The vector exports open their sheets; Back closes them.
        for ((entry, title) in listOf("Export SVG…" to "Export SVG", "Export PDF…" to "Export PDF")) {
            MoreWalk.pick(s, entry, windowsAfter = null)
            assertTrue("$entry opens \"$title\"", SmokeUi.has(title, exact = true))
            Finger.back()
            assertFalse("Back closes \"$title\"", SmokeUi.has(title, exact = true))
        }

        // PNG, JPG and Share run and finish.
        for (entry in listOf("Export PNG", "Export JPG", "Share")) {
            MoreWalk.pick(s, entry)
            assertTrue("$entry finished", Smoke.pumpUntil { settle(1); s.c.busyMessage == null })
            assertFalse("$entry: no failure", SmokeUi.has("failed"))
        }

        // The panels: Canvas and Increments in the editor's panel host; Settings in its own window.
        MoreWalk.pick(s, "Canvas…")
        Walk.panelThenBack("Canvas")
        MoreWalk.pick(s, "Increments…")
        SmokeUi.assertIncrementsPanelShown()
        Finger.back()
        assertTrue("Back closed Increments", SmokeUi.sheetTitles().isEmpty() && SmokeUi.pillTitles().isEmpty())
        MoreWalk.pick(s, "Settings", windowsAfter = null)
        assertTrue("Settings opens: ${SmokeUi.windows().size} windows, panels ${SmokeUi.sheetTitles()}", SmokeUi.windows().size == 2 || SmokeUi.sheetTitles().isNotEmpty())
        Finger.back()
        assertTrue("Back closed Settings", SmokeUi.windows().size == 1 && SmokeUi.sheetTitles().isEmpty() && SmokeUi.pillTitles().isEmpty())

        // The view: flip, zoom by two fingers then fit, 100 %, rotate by two fingers then reset.
        MoreWalk.pick(s, "Flip view")
        assertTrue("Flip view mirrors the view", s.c.viewMirrored)
        MoreWalk.pick(s, "Flip view")
        assertFalse("…and back", s.c.viewMirrored)

        val fit = s.c.viewTransform.zoom
        val (cx, cy) = MoreWalk.middle(s)
        s.touch.idle(200)
        s.touch.pinch(cx - 60f to cy, cx + 60f to cy, cx - 150f to cy, cx + 150f to cy)
        settle()
        assertTrue("two fingers zoomed in: ${s.c.viewTransform.zoom} vs $fit", s.c.viewTransform.zoom > fit * 1.5f)
        MoreWalk.pick(s, "Fit to screen")
        assertEquals("Fit to screen", fit, s.c.viewTransform.zoom, fit * 0.02f)
        MoreWalk.pick(s, "100% (actual pixels)")
        assertEquals("100%: one document pixel per screen pixel", 1f, s.c.viewTransform.zoom, 0.01f)

        val r = 150f
        val a = Math.toRadians(40.0)
        s.touch.idle(200)
        s.touch.pinch(
            cx - r to cy, cx + r to cy,
            cx - r * cos(a).toFloat() to cy - r * sin(a).toFloat(), cx + r * cos(a).toFloat() to cy + r * sin(a).toFloat(),
        )
        settle()
        assertTrue("two fingers rotated the view: ${s.c.viewTransform.rotationDeg}°", abs(s.c.viewTransform.rotationDeg) > 5f)
        MoreWalk.pick(s, "Reset rotation")
        assertEquals("Reset rotation", 0f, s.c.viewTransform.rotationDeg, 0.5f)

        MoreWalk.pick(s, "Save now")
        assertEquals("Save now saves", 1, saves)
        assertNull("no tool is left waiting", (s.c.tools[ToolId.TRANSFORM] as TransformTool).transformState)
        Smoke.assertQuiet(s.c, "More menu")
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.moreauditsandbox"])
class MoreMenuLabelAuditTest {
    @Test
    fun theMoreMenuRepeatsNothingOnScreen() = Walk.run("More audit") { h ->
        val s = h.editor(IbisDocs.page(), setup = IbisDocs::fourKinds)
        MoreWalk.open(s)
        val alone = Clickables.onScreen(s)
        assertTrue("More's entries are on screen: ${alone.size}", alone.any { "Export PNG" in it.labels })
        StateAudit.assertUnique("More menu", alone)
        StateAudit.assertFingerSized(s, "More menu", alone)
        Finger.back()

        Finger.tap(s, "Open layers (active layer 5)")
        Smoke.pump(300); settle()
        val window = s.tagged(ChromeTags.LAYER_WINDOW) ?: throw AssertionError("no layer window")
        MoreWalk.open(s)
        val over = Clickables.ownLabelsInside(Clickables.onScreen(s), window)
        val menu = over.filter { it.window !== s.activity.window.decorView }
        assertTrue("More over the layer window: ${menu.size} entries", menu.size >= 5)
        assertFalse("the menu leaves Import picture to the window", menu.any { "Import picture" in it.labels })
        StateAudit.assertUnique("More over the layer window", over)
        StateAudit.assertFingerSized(s, "More over the layer window", menu)
        Finger.back()
        assertNotNull("Back closed the menu only", s.tagged(ChromeTags.LAYER_WINDOW))
        Smoke.assertQuiet(s.c, "More audit")
    }
}
