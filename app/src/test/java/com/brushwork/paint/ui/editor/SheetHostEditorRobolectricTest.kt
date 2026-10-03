package com.brushwork.paint.ui.editor

import android.graphics.Canvas
import android.graphics.Paint
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.EditorController
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.pillTitles
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.smoke.SmokeUi.sheetTitles
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.editor.chrome.ChromeTags
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
 * The editor's non-modal menus and its layers window on the user's phone (392 x 873 dp), in the
 * real [EditorScreen]:
 * - a panel is drawn in the editor's own window on the bottom bar (v1.6: over the slider rows,
 *   as in ibisPaint); touching the canvas
 *   folds it into a pill and the touch works on the canvas (pinch zoom, strokes; the text being
 *   edited stays and can be dragged and pinched meanwhile); the pill brings it back as it was
 *   (same scroll position);
 * - stacked panels, the minimize button, the panel's own button (also when a tool's sheet covers
 *   the panel), Back;
 * - a tool's sheet opened from the options strip closes the tool menu (Back then closes the sheet);
 * - the layers window closes on a tap outside it (the tap does nothing else) but not on a pinch
 *   or a stroke, and deletes layers without asking, with Undo in the message.
 *
 * One test in its own sandbox: Compose's frame clock only serves the first test of a sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.editor.sheethostsandbox"])
class SheetHostEditorRobolectricTest {

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
        val density = activity.resources.displayMetrics.density

        /** Window pixel of document point ([x], [y]). */
        fun screen(x: Float, y: Float): Pair<Float, Float> {
            val loc = IntArray(2)
            canvas.getLocationInWindow(loc)
            val p = c.viewTransform.docToScreen(x, y)
            return (p.x + loc[0]) to (p.y + loc[1])
        }

        fun pixels(): IntArray = IntArray(c.doc.width * c.doc.height).also {
            c.activeLayer.bitmap.getPixels(it, 0, c.doc.width, 0, 0, c.doc.width, c.doc.height)
        }

        /** Top of the bottom bar (window px; v1.6, ibisPaint's bar): its colour slot spans the bar's height. */
        fun hotbarTop(): Float = (SmokeUi.find("Open color picker", exact = true) ?: throw AssertionError("no bottom bar")).bounds.top

        /**
         * Bottom of the top chrome (window px): the top row's circles, then the options strip
         * (4 + 44 dp) and its 8 dp gap — the canvas fit inset (137 dp on the reference phone).
         */
        fun topChromeBottom(): Float {
            val more = SmokeUi.find("More options", exact = true) ?: throw AssertionError("no top row")
            return more.bounds.bottom + 56f * density
        }

        /** A spot on the canvas between the top chrome and [below] (window px). */
        fun freeCanvasSpot(below: Float): Pair<Float, Float> {
            val top = topChromeBottom()
            assertTrue("room for the canvas between $top and $below", below - top > 100f * density)
            return (activity.window.decorView.width / 2f) to (top + below) / 2f
        }

        fun pinchAt(center: Pair<Float, Float>, from: Float = 40f, to: Float = 80f) {
            touch.idle(300)
            touch.pinch(
                center.first - from to center.second, center.first + from to center.second,
                center.first - to to center.second, center.first + to to center.second,
            )
            settle()
        }

        fun pressBack() {
            val root = activity.window.decorView
            root.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            root.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
            settle()
        }
    }

    private fun editor(layers: Int = 2, width: Int = 400, height: Int = 300): Screen {
        SmokeUi.markBaseline()
        val ctl = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activities += ctl
        val activity = ctl.get()
        val c = Smoke.controller(activity, Smoke.document(width, height, layers = layers, whiteBottom = true))
        activity.setContent { BrushworkTheme { EditorScreen(c, onExit = {}, onSaveNow = {}) } }
        settle()
        c.tools
        c.brush = c.brush.copy(size = 10f, opacity = 1f, hardness = 1f, taperStart = 0f, taperEnd = 0f)
        c.color = RED
        settle()
        return Screen(activity, c)
    }

    /** The vertical scroll of the shown panel's body: (element, value, max). */
    private fun panelScroll(): Triple<RobolectricUi.Element, Float, Float> {
        val panel = SmokeUi.sheetPanel() ?: throw AssertionError("no panel")
        val pb = panel.bounds
        val e = RobolectricUi.elements().firstOrNull { e ->
            e.node.layoutInfo.isPlaced && e.node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null &&
                e.bounds.top >= pb.top - 1f && e.bounds.bottom <= pb.bottom + 1f
        } ?: throw AssertionError("no scrolling body in the panel")
        val r = e.node.config[SemanticsProperties.VerticalScrollAxisRange]
        return Triple(e, r.value(), r.maxValue())
    }

    @Test
    fun menusMinimizeAndTheLayersWindowClosesOutside() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        section("a panel sits above the hotbar in the editor's window") { panelPlacement() }
        section("touching the canvas minimizes; the pill restores it as it was") { minimizeAndRestore() }
        section("a stroke on the canvas goes through and minimizes") { strokeThrough() }
        section("minimize button, the panel's own button, Back") { buttonsAndBack() }
        section("stacked panels") { stackedPanels() }
        section("the text editor minimizes; its text can be moved and pinched meanwhile") { textEditor() }
        section("a panel's button brings it back on top of a tool's sheet") { panelButtonUnderToolSheet() }
        section("the Layers button makes room; the pill comes back") { layersButtonWithPanel() }
        section("a tool's sheet from the options strip closes the tool menu; Back closes the sheet") { toolSheetOverToolMenu() }
        section("layers window: tap outside closes, pinch and strokes don't") { layersTapOutside() }
        section("layers are deleted without asking, Undo in the message") { deleteWithoutDialog() }
        dog.interrupt()
        val errors = Smoke.errorLogs()
        if (errors.isNotEmpty()) failures += AssertionError("error logs:\n" + errors.joinToString("\n"))
        if (failures.isNotEmpty()) {
            val first = failures.first()
            failures.drop(1).forEach { first.addSuppressed(it) }
            throw first
        }
    }

    // ================================================================== panels

    private fun panelPlacement() {
        val s = editor()
        click("Open brush settings")
        SmokeUi.assertPanelShown("Brush")
        val panel = SmokeUi.sheetPanel()!!.bounds
        val hotbarTop = s.hotbarTop()
        // The slider bar's brush size slider: the lowest one on screen.
        val sizeSlider = RobolectricUi.elements().filter { e ->
            e.node.config.contains(SemanticsActions.SetProgress) &&
                e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.contains("Brush size") } == true
        }.maxByOrNull { it.bounds.bottom }?.bounds ?: throw AssertionError("no slider bar")
        // v1.6 §3.7.9: the panel's bottom sits on the bottom bar's top (over the slider rows).
        assertTrue("on the bottom bar: panel ${panel.bottom} vs bar $hotbarTop", panel.bottom <= hotbarTop + 1f && hotbarTop - panel.bottom <= 1f * s.density)
        assertTrue("over the slider bar: panel $panel vs slider $sizeSlider", panel.bottom >= sizeSlider.bottom && panel.top <= sizeSlider.top)
        val screenH = s.activity.resources.configuration.screenHeightDp * s.density
        assertTrue("at most half the screen: ${panel.height / s.density} dp", panel.height <= screenH / 2f + 2f * s.density)
        assertTrue("the hotbar stays usable", SmokeUi.isEnabled("Open color picker"))
        assertTrue("the panel's controls are there", has("Size", exact = true))
        SmokeUi.assertIdle("panel open", settleMs = 600)
        Smoke.assertQuiet(s.c, "panel placement")
    }

    private fun minimizeAndRestore() {
        val s = editor()
        click("Open brush settings")
        SmokeUi.assertPanelShown("Brush")
        // Scroll the panel's body part of the way down.
        val (body, v0, max) = panelScroll()
        assertTrue("the brush panel scrolls: max $max", max > 40f)
        requireNotNull(body.node.config.getOrNull(SemanticsActions.ScrollBy)).action!!.invoke(0f, max / 2f)
        settle()
        val scrolled = panelScroll().second
        assertTrue("scrolled: $v0 -> $scrolled", scrolled > v0 + 10f)

        // Pinch on the canvas above the panel: the view zooms, the panel folds into its pill.
        val z0 = s.c.viewTransform.zoom
        val undo0 = s.c.undoManager.undoCount
        s.pinchAt(s.freeCanvasSpot(SmokeUi.sheetPanel()!!.bounds.top))
        assertEquals("the pinch zoomed the view", z0 * 2f, s.c.viewTransform.zoom, 0.05f)
        assertEquals("nothing drawn", undo0, s.c.undoManager.undoCount)
        assertEquals("no panel on screen", emptyList<String>(), sheetTitles())
        assertEquals("the pill waits", listOf("Brush"), pillTitles())
        assertFalse("its controls are gone from the screen", has("Size", exact = true))
        assertEquals("still one window", 1, SmokeUi.windows().size)
        SmokeUi.assertIdle("minimized", settleMs = 600)

        // More gestures while minimized: another pinch works too.
        s.pinchAt(s.freeCanvasSpot(s.hotbarTop() - 120f * s.density), from = 60f, to = 30f)
        assertEquals(z0, s.c.viewTransform.zoom, 0.05f)
        assertEquals(listOf("Brush"), pillTitles())

        // The pill restores the panel exactly as it was.
        click("Show Brush")
        SmokeUi.assertPanelShown("Brush")
        assertEquals("no pill while the panel shows", emptyList<String>(), pillTitles())
        assertEquals("same scroll position", scrolled, panelScroll().second, 1f)
        click("Close", exact = true)
        assertFalse(SmokeUi.menuOpen())
        Smoke.assertQuiet(s.c, "minimize and restore")
    }

    private fun strokeThrough() {
        val s = editor()
        click("Open color picker")
        SmokeUi.assertPanelShown("Color")
        val panelTop = SmokeUi.sheetPanel()!!.bounds.top
        val undo0 = s.c.undoManager.undoCount
        // A stroke across the document above the panel.
        val y = (maxOf(s.topChromeBottom(), s.screen(0f, 0f).second) + panelTop) / 2f
        val docY = s.c.viewTransform.screenToDoc(0f, y - IntArray(2).also { s.canvas.getLocationInWindow(it) }[1]).y
        assertTrue("the stroke row is on the document: $docY", docY in 5f..295f)
        s.touch.idle(300)
        s.touch.stroke(s.screen(60f, docY), s.screen(340f, docY))
        settle()
        assertEquals("the stroke reached the canvas", undo0 + 1, s.c.undoManager.undoCount)
        assertEquals("painted", RED, s.c.activeLayer.bitmap.getPixel(200, docY.toInt()))
        assertEquals(listOf("Color"), pillTitles())
        // Its ✕ closes it for good.
        click("Close Color", exact = true)
        assertFalse(SmokeUi.menuOpen())
        Smoke.assertQuiet(s.c, "stroke through")
    }

    private fun buttonsAndBack() {
        val s = editor()
        // The ▾ button minimizes; the panel's own button brings it back.
        click("Open color picker")
        click("Minimize", exact = true)
        assertEquals(listOf("Color"), pillTitles())
        click("Open color picker")
        SmokeUi.assertPanelShown("Color")
        // Another panel replaces it (one chrome panel at a time).
        click("Open brush settings")
        SmokeUi.assertPanelShown("Brush")
        assertFalse(has("Hex", exact = true))
        // Back closes an expanded panel...
        s.pressBack()
        assertFalse("Back closed the panel", SmokeUi.menuOpen())
        // ...and a minimized one.
        click("Open brush settings")
        click("Minimize", exact = true)
        assertEquals(listOf("Brush"), pillTitles())
        s.pressBack()
        assertFalse("Back closed the minimized panel", SmokeUi.menuOpen())
        // With nothing open, Back is the host's again (the editor's exit), not swallowed here.
        Smoke.assertQuiet(s.c, "buttons and back")
    }

    private fun stackedPanels() {
        val s = editor()
        s.c.selectAll()
        settle()
        click("Selection", exact = true)
        SmokeUi.assertPanelShown("Selection")
        click("Fill with…", exact = true)
        SmokeUi.assertPanelShown("Fill selection with")
        assertFalse("the panel below is hidden", has("Select all", exact = true))
        // Minimized, the pill names the top one and counts the one below; no ✕ (it has Cancel / OK).
        click("Minimize", exact = true)
        assertEquals(listOf("Fill selection with"), pillTitles())
        assertTrue(has("1 more below"))
        assertFalse(has("Close Fill selection with"))
        click("Show Fill selection with")
        // Cancel closes only the color picker: the selection panel is back.
        click("Cancel", exact = true)
        SmokeUi.assertPanelShown("Selection")
        assertTrue(has("Select all", exact = true))
        s.pressBack()
        assertFalse(SmokeUi.menuOpen())
        assertNotNull("nothing happened to the selection", s.c.selection)
        Smoke.assertQuiet(s.c, "stacked")
    }

    private fun textEditor() {
        val s = editor()
        s.c.selectTool(ToolId.TEXT)
        settle()
        val text = s.c.tools.getValue(ToolId.TEXT) as TextTool
        val p = s.screen(200f, 60f)
        s.touch.idle(300)
        s.touch.tap(p.first, p.second)
        settle()
        assertTrue("editor open", text.editorOpen)
        SmokeUi.assertPanelShown("Add text")
        // The host's focus handling leaves a new text's field focused (keyboard up at once).
        assertEquals("the text field has the focus", true, SmokeUi.field("Text").node.config.getOrNull(SemanticsProperties.Focused))
        SmokeUi.field("Text").type("Hello")
        settle()
        assertEquals("Hello", text.item?.text)
        // The hotbar stays usable, but its Undo can't throw the typed text away.
        click("Undo", exact = true)
        assertTrue(text.editorOpen)
        assertEquals("Hello", text.item?.text)
        assertTrue("it says why", has(HistoryLabels.BLOCKED_BY_TEXT_EDITOR, exact = true))
        // Zooming the canvas minimizes the editor; the text and the editor stay.
        val z0 = s.c.viewTransform.zoom
        s.pinchAt(s.freeCanvasSpot(SmokeUi.sheetPanel()!!.bounds.top))
        assertTrue("zoomed", s.c.viewTransform.zoom > z0 * 1.5f)
        assertEquals(listOf("Add text"), pillTitles())
        assertTrue("still editing", text.editorOpen)
        assertEquals("Hello", text.item?.text)
        assertFalse("its pill has no ✕: Cancel / OK finish it", has("Close Add text"))

        // While the editor waits in its pill, the text itself can be moved: a drag on it...
        val z1 = s.c.viewTransform.zoom
        val undo0 = s.c.undoManager.undoCount
        val layers0 = s.c.doc.layers.size
        val t0 = requireNotNull(text.item)
        val from = s.screen(t0.cx, t0.cy)
        s.touch.idle(300)
        s.touch.stroke(from, from.first + 40f * s.density to from.second + 20f * s.density)
        settle()
        val t1 = requireNotNull(text.item)
        assertTrue("dragged: (${t0.cx}, ${t0.cy}) -> (${t1.cx}, ${t1.cy})", t1.cx > t0.cx + 5f && t1.cy > t0.cy + 2f)
        assertEquals("the text didn't change", "Hello", t1.text)
        assertTrue("still editing after the drag", text.editorOpen)
        assertEquals(listOf("Add text"), pillTitles())
        // ...a tap away from it neither places it nor starts another text...
        val here = s.screen(t1.cx, t1.cy)
        s.touch.idle(300)
        s.touch.tap(here.first, here.second + 100f * s.density)
        settle()
        assertTrue("still editing after a tap", text.editorOpen)
        assertEquals("the tap changed nothing", t1, text.item)
        assertEquals("nothing placed", layers0, s.c.doc.layers.size)
        assertEquals(undo0, s.c.undoManager.undoCount)
        // ...nor does a two-finger tap (undo) throw it away...
        s.touch.idle(300)
        s.touch.twoFingerTap(here.first - 30f * s.density to here.second + 100f * s.density, here.first + 30f * s.density to here.second + 100f * s.density)
        settle()
        assertTrue("still editing after a two-finger tap", text.editorOpen)
        assertEquals(t1, text.item)
        assertEquals(undo0, s.c.undoManager.undoCount)
        assertEquals("the view is where it was", z1, s.c.viewTransform.zoom, 0.01f)
        // ...and two fingers on it scale it (the view stays).
        val size0 = t1.spec.sizePx
        val d = s.density
        s.touch.idle(300)
        s.touch.pinch(here.first - 8f * d to here.second, here.first + 8f * d to here.second, here.first - 16f * d to here.second, here.first + 16f * d to here.second)
        settle()
        val t2 = requireNotNull(text.item)
        assertTrue("pinched bigger: $size0 -> ${t2.spec.sizePx}", t2.spec.sizePx > size0 * 1.5f)
        assertEquals("the view didn't zoom", z1, s.c.viewTransform.zoom, 0.01f)
        assertTrue(text.editorOpen)
        assertEquals(listOf("Add text"), pillTitles())

        click("Show Add text")
        SmokeUi.assertPanelShown("Add text")
        assertEquals("the typed text is still there", "Hello", SmokeUi.field("Text").text)
        click("OK", exact = true)
        assertFalse(text.editorOpen)
        assertEquals("Hello", text.item?.text)
        assertEquals("OK keeps the moved, bigger text", t2, text.item)
        text.discard()
        s.c.selectTool(ToolId.BRUSH)
        settle()
        Smoke.assertQuiet(s.c, "text editor")
    }

    private fun panelButtonUnderToolSheet() {
        val s = editor()
        click("Open color picker")
        SmokeUi.assertPanelShown("Color")
        s.c.selectTool(ToolId.TEXT)
        settle()
        val text = s.c.tools.getValue(ToolId.TEXT) as TextTool
        // A tap on the canvas folds the Color panel away and starts a text: its editor on top.
        val spot = s.freeCanvasSpot(SmokeUi.sheetPanel()!!.bounds.top)
        s.touch.idle(300)
        s.touch.tap(spot.first, spot.second)
        settle()
        assertTrue(text.editorOpen)
        SmokeUi.assertPanelShown("Add text")
        // The Color button brings the Color panel back on top of it (not the text editor).
        click("Open color picker")
        SmokeUi.assertPanelShown("Color")
        assertTrue("the text editor waits below", text.editorOpen)
        click("Minimize", exact = true)
        assertEquals(listOf("Color"), pillTitles())
        assertTrue(has("1 more below"))
        // Closing Color leaves the text editor.
        click("Close Color", exact = true)
        assertEquals(listOf("Add text"), pillTitles())
        click("Show Add text")
        SmokeUi.assertPanelShown("Add text")
        click("Cancel", exact = true)
        assertFalse(text.editorOpen)
        assertFalse(SmokeUi.menuOpen())
        s.c.selectTool(ToolId.BRUSH)
        settle()
        Smoke.assertQuiet(s.c, "panel button under a tool sheet")
    }

    private fun layersButtonWithPanel() {
        val s = editor()
        click("Open color picker")
        SmokeUi.assertPanelShown("Color")
        click("Open layers")
        assertTrue("the layers window is shown", has("Close layers", exact = true))
        assertEquals("the panel made room", emptyList<String>(), sheetTitles())
        assertEquals("its pill waits until the window closes", emptyList<String>(), pillTitles())
        click("Close layers", exact = true)
        assertEquals(listOf("Color"), pillTitles())
        // Opening a panel over the layers window hides the window; closing the panel brings it back.
        click("Open layers")
        click("Open brush settings")
        SmokeUi.assertPanelShown("Brush")
        assertFalse(has("Close layers", exact = true))
        click("Close", exact = true)
        assertTrue("the window is back", has("Close layers", exact = true))
        // But touching the canvas while a panel covers it closes the hidden window.
        click("Open brush settings")
        s.pinchAt(s.freeCanvasSpot(SmokeUi.sheetPanel()!!.bounds.top))
        assertEquals(listOf("Brush"), pillTitles())
        assertFalse("no window popping up in the panel's place", has("Close layers", exact = true))
        click("Close Brush", exact = true)
        // So does the panel's minimize button: the pill shows, not the window.
        click("Open layers")
        click("Open brush settings")
        SmokeUi.assertPanelShown("Brush")
        click("Minimize", exact = true)
        assertEquals(listOf("Brush"), pillTitles())
        assertFalse("minimized to see the canvas: the hidden window stays closed", has("Close layers", exact = true))
        click("Close Brush", exact = true)
        assertFalse(has("Close layers", exact = true))
        assertFalse(SmokeUi.menuOpen())
        Smoke.assertQuiet(s.c, "layers button")
    }

    /** The node tagged [tag] is placed on screen. */
    private fun shown(tag: String): Boolean = RobolectricUi.elements().any {
        it.node.layoutInfo.isPlaced && it.node.config.getOrNull(SemanticsProperties.TestTag) == tag
    }

    /** Clicks the options strip's control showing or named [label] (the strip scrolls: it may be out of view). */
    private fun clickInOptionsStrip(label: String) {
        val strip = RobolectricUi.elements().last {
            it.node.layoutInfo.isPlaced && it.node.config.getOrNull(SemanticsProperties.TestTag) == ChromeTags.OPTIONS_STRIP
        }.node
        fun find(n: SemanticsNode): SemanticsNode? {
            if (n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true) return n
            if (n.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it == label } == true) return n
            for (child in n.children) find(child)?.let { return it }
            return null
        }
        var n = find(strip) ?: throw AssertionError("no \"$label\" in the options strip")
        while (n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent ?: throw AssertionError("\"$label\" is not clickable")
        n.config[SemanticsActions.OnClick].action!!.invoke()
        settle()
    }

    private fun toolSheetOverToolMenu() {
        val s = editor()
        s.c.selectTool(ToolId.SHAPE)
        settle()
        click("Tools (current: Shape)", exact = true)
        assertTrue("the tool menu is open", shown(ChromeTags.TOOL_MENU))
        // The Shape tool's own "Settings" chip, known as "Shape settings" (I10), on the options strip beside the open menu.
        clickInOptionsStrip("Shape settings")
        SmokeUi.assertPanelShown("Shape")
        assertFalse("the tool menu closed: it would stay under the sheet", shown(ChromeTags.TOOL_MENU))
        // Back closes the sheet on top (not a menu hidden under it), and the menu stays closed.
        s.pressBack()
        assertFalse("Back closed the sheet", SmokeUi.menuOpen())
        assertFalse("the menu doesn't come back", shown(ChromeTags.TOOL_MENU))
        // The menu still opens over a minimized sheet: the pill waits meanwhile and comes back.
        clickInOptionsStrip("Shape settings")
        click("Minimize", exact = true)
        assertEquals(listOf("Shape"), pillTitles())
        click("Tools (current: Shape)", exact = true)
        assertTrue("the menu opens over the minimized sheet", shown(ChromeTags.TOOL_MENU))
        assertEquals("the pill waits under the menu", emptyList<String>(), pillTitles())
        click("Tools (current: Shape)", exact = true)
        assertFalse(shown(ChromeTags.TOOL_MENU))
        assertEquals("the pill is back", listOf("Shape"), pillTitles())
        click("Close Shape", exact = true)
        assertFalse(SmokeUi.menuOpen())
        s.c.selectTool(ToolId.BRUSH)
        settle()
        Smoke.assertQuiet(s.c, "tool sheet over the tool menu")
    }

    // ================================================================== layers window

    /** The layer window's bounds (window px), placed by the editor (v1.6: ibisPaint's 382 × 520 dp over the bottom). */
    private fun layerWindow(): androidx.compose.ui.geometry.Rect = RobolectricUi.elements().last {
        it.node.layoutInfo.isPlaced && it.node.config.getOrNull(SemanticsProperties.TestTag) == ChromeTags.LAYER_WINDOW
    }.bounds

    private fun layersTapOutside() {
        // A portrait artwork: its top part shows above the layer window, which covers the bottom
        // of the screen as in ibisPaint.
        val s = editor(width = 300, height = 600)
        val c = s.c
        click("Open layers")
        assertTrue(has("Close layers", exact = true))
        val window = layerWindow()
        val before = s.pixels()
        val undo0 = c.undoManager.undoCount
        // A tap on the artwork above the window: the window closes, nothing is painted.
        val docTop = s.screen(0f, 0f).second
        assertTrue("artwork above the window: $docTop vs ${window.top}", docTop + 40f * s.density < window.top)
        val spot = s.screen(150f, 0f).first to (docTop + window.top) / 2f
        s.touch.idle(300)
        s.touch.tap(spot.first, spot.second)
        settle()
        assertFalse("the tap closed the window", has("Close layers", exact = true))
        assertEquals("the tap did nothing else", undo0, c.undoManager.undoCount)
        assertTrue("no dot", before.contentEquals(s.pixels()))

        // A pinch outside zooms and keeps it open.
        click("Open layers")
        val z0 = c.viewTransform.zoom
        s.pinchAt(s.freeCanvasSpot(window.top), from = 30f, to = 60f)
        assertEquals(z0 * 2f, c.viewTransform.zoom, 0.05f)
        assertTrue("still open after the pinch", has("Close layers", exact = true))
        click("More options")
        click("Fit to screen", exact = true)
        assertTrue("still open", has("Close layers", exact = true))

        // A stroke outside (across the artwork above the window) draws and keeps it open.
        val y = (s.screen(0f, 0f).second + window.top) / 2f
        val docY = c.viewTransform.screenToDoc(0f, y - IntArray(2).also { s.canvas.getLocationInWindow(it) }[1]).y
        assertTrue("the stroke row is on the artwork: $docY", docY in 5f..595f)
        s.touch.idle(300)
        s.touch.stroke(s.screen(40f, docY), s.screen(260f, docY))
        settle()
        assertEquals("the stroke was drawn", undo0 + 1, c.undoManager.undoCount)
        assertEquals(RED, c.activeLayer.bitmap.getPixel(150, docY.toInt()))
        assertTrue("still open after the stroke", has("Close layers", exact = true))

        // A tap on the window itself keeps it open (its rows and buttons work as usual).
        val w2 = layerWindow()
        RobolectricUi.tap(s.activity.window.decorView, w2.left + 12f * s.density, w2.top + 20f * s.density)
        assertTrue("a tap on the window keeps it", has("Close layers", exact = true))

        // A tap on the chrome that no button takes (the options strip's padding) closes it too.
        val strip = RobolectricUi.elements().last {
            it.node.layoutInfo.isPlaced && it.node.config.getOrNull(SemanticsProperties.TestTag) == ChromeTags.OPTIONS_STRIP
        }.bounds
        RobolectricUi.tap(s.activity.window.decorView, strip.left + 3f * s.density, strip.center.y)
        assertFalse("a tap on the options strip closed the window", has("Close layers", exact = true))
        // ...while the chrome's buttons keep working with it open (a real tap on Undo).
        click("Open layers")
        SmokeUi.tap("Undo", exact = true)
        assertEquals("undo worked with the window open", undo0, c.undoManager.undoCount)
        assertTrue(has("Close layers", exact = true))
        click("Close layers", exact = true)
        Smoke.assertQuiet(c, "layers tap outside")
    }

    private fun deleteWithoutDialog() {
        val s = editor(layers = 3)
        val c = s.c
        c.editWholeLayer(c.activeLayer, "Seed") { b -> Canvas(b).drawRect(50f, 50f, 150f, 150f, Paint().apply { color = RED }) }
        val doomed = c.activeLayer
        click("Open layers")
        click("Delete layer")
        assertEquals("deleted at once", 2, c.doc.layers.size)
        assertNull(c.doc.layerById(doomed.id))
        assertEquals("no dialog", 1, SmokeUi.windows().size)
        assertTrue("the message says so", has("Layer deleted", exact = true))
        assertTrue("the window stays open", has("Close layers", exact = true))
        // The message's Undo brings it back.
        val undo = RobolectricUi.elements().last { e ->
            e.node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "Undo" } == true
        }
        var n = undo.node
        while (n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent ?: throw AssertionError("Undo not clickable")
        n.config[SemanticsActions.OnClick].action!!.invoke()
        settle()
        assertEquals("Undo restored it", 3, c.doc.layers.size)
        assertEquals(doomed, c.activeLayer)
        assertEquals(RED, doomed.bitmap.getPixel(100, 100))
        assertTrue("the window is still open", has("Close layers", exact = true))
        click("Close layers", exact = true)
        Smoke.assertQuiet(c, "delete")
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
    }
}
