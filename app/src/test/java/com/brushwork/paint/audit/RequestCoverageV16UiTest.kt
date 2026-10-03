package com.brushwork.paint.audit

import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.core.view.WindowCompat
import com.brushwork.paint.engine.live.LiveAdjust
import com.brushwork.paint.model.IncrementKind
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.text.frames.TextFrameTool
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.tools.vector.CurveKind
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.editor.EditorIcons
import com.brushwork.paint.ui.editor.ToolMenu
import com.brushwork.paint.ui.editor.ToolMenuEntry
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.Clickables
import com.brushwork.paint.ui.editor.chrome.ChromeLayout
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * REQUEST COVERAGE AUDIT (v1.6 §3.7.11): every clause of the user's v1.6 request, reached the way
 * the user reaches it on their phone (ZTE Axon 50 Lite, 392 × 873 dp, fingers) through the
 * ibisPaint main screen — the top row, the tool menu, the More menu, the slider rows, the bottom
 * bar, the layer window — driven by the I10 labels, and shown to do what was asked; then the same
 * entry points on a 360 dp phone.
 *
 * The chrome (area E) is checked in full here. Each clause also checks the controls its own area
 * adds ("Longer handles", "Letter scaling", the frame gesture, the pill's "#", the layer window's
 * strip…): on a branch where that area has not merged yet they are reported as pending; at the
 * integration pass the lead sets [REQUIRE_MERGED_AREAS] and every one of them must be there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.audit.requestv16sandbox"])
class RequestCoverageV16UiTest {

    companion object {
        /** True once areas A, B, C, D, F and G are merged (§6.2): their own controls must then be on screen. */
        const val REQUIRE_MERGED_AREAS = true
    }

    private val pending = mutableListOf<String>()

    /** Whether area [area]'s control [label] is shown; absent = pending (or a failure once every area is merged). */
    private fun areaControl(area: String, label: String, exact: Boolean = true): Boolean {
        if (has(label, exact)) return true
        if (REQUIRE_MERGED_AREAS) throw AssertionError("area $area: \"$label\" is not on screen; shown: ${SmokeUi.shown().take(80)}")
        pending += "$area: \"$label\""
        return false
    }

    private lateinit var s: ChromeScreen
    private val c get() = s.c

    private fun editor(): ChromeScreen {
        s = h.editor()
        c.snapping.enabled = false
        settle()
        return s
    }

    private lateinit var h: ChromeHarness

    // ------------------------------------------------------------------ touches and helpers

    private fun stroke(vararg doc: Pair<Float, Float>) {
        s.touch.idle(300)
        s.touch.stroke(*doc.map { s.screen(it.first, it.second) }.toTypedArray())
        settle(4)
    }

    private fun tap(x: Float, y: Float) {
        s.touch.idle(300)
        val (sx, sy) = s.screen(x, y)
        s.touch.tap(sx, sy)
        settle(4)
    }

    /** Picks [label] in the ibisPaint tool menu (bottom bar slot 2), scrolling the menu to it as a finger would. */
    private fun tool(label: String) {
        click("Tools (current:")
        assertNotNull("the tool menu is open", s.tagged(ChromeTags.TOOL_MENU))
        scrollMenuTo(label)
        click(label, exact = true)
        assertNull("a pick closes the menu", s.tagged(ChromeTags.TOOL_MENU))
    }

    /** The clickable cell labelled [label] (its node, not clipped by the menu). */
    private fun cell(label: String): androidx.compose.ui.semantics.SemanticsNode? {
        var n = SmokeUi.find(label, exact = true)?.node
        while (n != null && n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent
        return n
    }

    /** Scrolls the tool menu (as a finger would) until the whole cell [label] shows. */
    private fun scrollMenuTo(label: String) {
        fun inside() = cell(label)?.let { n -> n.boundsInWindow.height >= n.size.height - 1f && n.boundsInWindow.width >= n.size.width - 1f } == true
        if (inside()) return
        val node = SmokeUi.find(label, exact = true)?.node ?: throw AssertionError("\"$label\" is not in the tool menu")
        var n: androidx.compose.ui.semantics.SemanticsNode? = node
        while (n != null && n.config.getOrNull(SemanticsActions.ScrollBy) == null) n = n.parent
        val scroll = requireNotNull(n?.config?.getOrNull(SemanticsActions.ScrollBy)?.action) { "\"$label\" is clipped and the menu does not scroll" }
        repeat(30) {
            if (inside()) return
            scroll.invoke(0f, 60f * s.density)
            settle(2)
        }
        throw AssertionError("\"$label\" never scrolled into the tool menu")
    }

    private fun slider(name: String): RobolectricUi.Element = RobolectricUi.elements().last { e ->
        e.node.layoutInfo.isPlaced && e.node.config.contains(SemanticsActions.SetProgress) &&
            e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.contains(name) } == true
    }

    private fun setSlider(name: String, v: Float) {
        requireNotNull(slider(name).node.config.getOrNull(SemanticsActions.SetProgress)?.action).invoke(v)
        settle(4)
    }

    private fun seed(layer: Layer, l: Float, t: Float, r: Float, b: Float, color: Int) {
        c.editWholeLayer(layer, "Seed") { bmp -> Canvas(bmp).drawRect(l, t, r, b, Paint().apply { this.color = color }) }
        settle()
    }

    private fun closeDialogs() {
        repeat(4) {
            if (SmokeUi.windows().size <= 1 && SmokeUi.sheetTitles().none { it != "Tools" }) return
            val closer = listOf("Close", "Cancel", "Done", "OK").firstOrNull { SmokeUi.find(it, exact = true) != null } ?: return
            click(closer, exact = true)
        }
    }

    private fun backKey() {
        SmokeUi.windows().last().let { w ->
            w.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK))
            w.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK))
        }
        settle()
    }

    @Test
    fun everyClauseOfTheV16RequestIsReachableAndWorks() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        h = ChromeHarness()
        h.section("make adjustment masks less laggy") { adjustmentLag() }
        h.section("another type of curve that works more like a blender path") { blenderPath() }
        h.section("an option to scale the bezier curves handles") { handleScale() }
        h.section("an option everywhere to put a number for increments, scale stuff with that increment") { increments() }
        h.section("scale text letter by letter, beginning to end or end to beginning, with a slider; centered, base or top") { letterScaling() }
        h.section("another tool for text: multiple text boxes that share or link text, like indesign") { textFrames() }
        h.section("rework the UI as similar as it can be to ibis paint") { ibisMainScreen() }
        h.section("X and Y: small, the slider is the number itself, X and Y in beveled squares") { coordinatePill() }
        h.section("copy the size of the ibis paint layers ui and placement of icons there") { layerWindow() }
        h.section("all of it within reach on a 360 dp phone") { at360dp() }
        dog.interrupt()
        if (pending.isNotEmpty()) System.err.println("RequestCoverageV16UiTest: controls of areas not merged on this branch: $pending")
        h.finish()
    }

    // ================================================================== 1. adjustment lag (A)

    private fun adjustmentLag() {
        editor()
        // Tests run the exact path (I8); a live session is an opt-in.
        assertEquals(LiveAdjust.Policy.EXACT, c.liveAdjust.policy)
        tool("Masks")
        assertEquals(ToolId.MASK, c.activeToolId)
        // A radial part on the canvas makes a Tone adjustment layer with a mask (what lagged);
        // the Masks tool's Components sheet has the switch (area A).
        click("+ Radial", exact = true)
        stroke(200f to 150f, 230f to 150f, 260f to 150f)
        assertTrue("an adjustment layer with a mask", c.activeLayer.isAdjustmentLayer)
        click("Components (")
        areaControl("A", "Fast adjustment preview", exact = true)
        closeDialogs()
        // Settings (More › Settings): the switch, written through to the app settings.
        click("More options")
        click("Settings", exact = true)
        assertTrue("the Settings dialog", has("Editor settings", exact = true))
        val before = c.settings.fastAdjustPreview
        assertTrue("the switch is in Settings", has("Fast adjustment preview", exact = true))
        click("Fast adjustment preview", exact = true)
        assertEquals("switched", !before, c.settings.fastAdjustPreview)
        click("Fast adjustment preview", exact = true)
        assertEquals("and back", before, c.settings.fastAdjustPreview)
        closeDialogs()
        // Settings is also a cell of the tool menu (ibisPaint's place).
        click("Tools (current:")
        scrollMenuTo("Settings")
        click("Settings", exact = true)
        assertTrue(has("Fast adjustment preview", exact = true))
        closeDialogs()
        Smoke.assertQuiet(c, "adjustment lag")
    }

    // ================================================================== 2. the Path tool (B)

    private fun blenderPath() {
        editor()
        val layer = c.doc.layers[1]
        tool("Path")
        assertEquals(ToolId.PATH, c.activeToolId)
        val path = c.currentTool as CurveTool
        assertEquals(CurveKind.PATH, path.kind)
        assertTrue("the tools button names it", has("Tools (current: Path)", exact = true))
        assertTrue("its own glyph", EditorIcons.tool(ToolId.PATH) !== EditorIcons.tool(ToolId.CURVE))
        c.brush = c.brush.copy(size = 8f, opacity = 1f)
        tap(80f, 200f)
        tap(200f, 80f)
        tap(320f, 200f)
        assertTrue("points placed", path.hasPendingWork)
        areaControl("B", "Cyclic", exact = false)
        val steps = c.undoManager.undoCount
        click("Apply path edit")
        assertFalse(path.hasPendingWork)
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        val ink = (0 until 300 step 2).sumOf { y -> (0 until 400 step 2).count { x -> layer.bitmap.getPixel(x, y) ushr 24 > 0 } }
        assertTrue("the path was drawn: $ink", ink > 50)
        Smoke.assertQuiet(c, "path")
    }

    // ================================================================== 3. handle scale (B)

    private fun handleScale() {
        editor()
        tool("Curve")
        val curve = c.currentTool as CurveTool
        assertEquals(CurveKind.CURVE, curve.kind)
        tap(80f, 150f)
        tap(200f, 60f)
        tap(320f, 150f)
        curve.select(1)
        settle()
        if (areaControl("B", "Longer handles")) {
            val before = curve.anchors
            // ‹ › are hold-to-repeat arrows; a screen reader presses them with their click action
            // (one step, like a quick tap).
            click("Longer handles", exact = true)
            assertTrue("› made the handles longer", curve.anchors != before)
            // The "Handle scale" slider (10–400 %, logarithmic, 100 % at about 0.62): well above
            // 100 % here.
            setSlider("Handle scale", 0.85f)
            assertTrue("the handles got longer", curve.anchors != before)
        }
        // "Handle size" on screen is a global setting (Curve settings › Handle size), 75–200 %.
        assertTrue(c.settings.curveHandleScale in 0.75f..2f)
        curve.discard()
        settle()
    }

    // ================================================================== 4. increments everywhere (G, E's slider rows)

    private fun increments() {
        editor()
        c.brush = c.brush.copy(size = 20f, opacity = 0.5f)
        settle()
        // Off (I8): the v1.5 steps.
        click("Bigger brush", exact = true)
        assertEquals("off: the v1.5 step", 21f, c.brush.size, 0.001f)
        click("Smaller brush", exact = true)
        assertEquals(20f, c.brush.size, 0.001f)
        click("More opacity", exact = true)
        assertEquals("off: 1 %", 0.51f, c.brush.opacity, 1e-4f)
        click("Less opacity", exact = true)

        // More › Increments…: the sheet with the switch.
        click("More options")
        assertTrue(has("Increments…", exact = true))
        click("Increments…", exact = true)
        val sheet = SmokeUi.assertIncrementsPanelShown()
        assertFalse(c.increments.state.enabled)
        click("Use increments", exact = true)
        assertTrue("switched on", c.increments.state.enabled)
        click("Close", exact = true)
        assertFalse("\"$sheet\" closed", sheet in SmokeUi.sheetTitles())
        // The More entry shows the state.
        click("More options")
        val entry = Clickables.onScreen(s).single { "Increments…" in it.labels }
        assertTrue("Increments… is checked: ${entry.labels}", "On" in entry.labels)
        backKey()

        // The brush rows step by the Size / Percent increments.
        c.increments.update { it.with(IncrementKind.SIZE, 5f).with(IncrementKind.PERCENT, 5f) }
        settle()
        click("Bigger brush", exact = true)
        assertEquals("by 5 px", 25f, c.brush.size, 0.001f)
        click("Bigger brush", exact = true)
        assertEquals(30f, c.brush.size, 0.001f)
        click("Smaller brush", exact = true)
        assertEquals(25f, c.brush.size, 0.001f)
        click("More opacity", exact = true)
        assertEquals("by 5 %", 0.55f, c.brush.opacity, 1e-4f)
        // A drag of the track lands on multiples of the step (the ends stay reachable).
        for (f in listOf(0.43f, 0.61f, 0.77f)) {
            setSlider("Brush size", f)
            val v = c.brush.size
            assertTrue("$v is a multiple of 5 (or an end)", v == 0.5f || v == 1000f || kotlin.math.abs(v / 5f - Math.round(v / 5f)) < 1e-3f)
        }
        setSlider("Brush opacity", 0.33f)
        assertEquals("opacity on a 5 % multiple", 0.35f, c.brush.opacity, 1e-4f)
        // A typed value is never quantized.
        click("Type brush size")
        SmokeUi.typeAndDone("Brush size", "37")
        assertEquals("typed 37 stays 37", 37f, c.brush.size, 0.001f)
        // The readout of a stepped gesture shows in the InfoChip slot.
        c.increments.readout = "+30 px"
        settle()
        assertTrue("the readout chip", has("+30 px", exact = true))
        c.increments.readout = null
        Smoke.pump(400)
        settle()
        assertFalse(has("+30 px", exact = true))
        // Long-press a value: the Step popup (area G).
        val value = SmokeUi.find("Type brush size")!!.node
        var n: androidx.compose.ui.semantics.SemanticsNode? = value
        while (n != null && n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent
        val longClick = n?.config?.getOrNull(SemanticsActions.OnLongClick)?.action
        if (longClick != null) {
            longClick.invoke()
            settle()
            areaControl("G", "Step for", exact = false)
            closeDialogs()
            // Only a popup still up takes the Back key (on the editor's own window Back would leave it).
            if (SmokeUi.windows().size > 1) backKey()
            assertEquals("the popup is closed", 1, SmokeUi.windows().size)
        } else {
            areaControl("G", "Step for", exact = false)
        }
        // Settings has the same section.
        click("More options")
        click("Settings", exact = true)
        assertTrue("Settings › Increments", has("Use increments", exact = true))
        click("Use increments", exact = true)
        assertFalse("switched off from Settings", c.increments.state.enabled)
        closeDialogs()
        click("Bigger brush", exact = true)
        assertEquals("off again: the v1.5 step", 38f, c.brush.size, 0.001f)
        Smoke.assertQuiet(c, "increments")
    }

    // ================================================================== 5. letter scaling (C)

    private fun letterScaling() {
        editor()
        tool("Text")
        val text = c.currentTool as TextTool
        tap(200f, 150f)
        assertTrue("the text editor", text.editorOpen)
        SmokeUi.field("Text").type("ELTON JOHN")
        settle()
        click("OK", exact = true)
        settle()
        // The options strip's "Letters" chip (accessible label "Letter scaling", area C) opens the
        // letter-scaling controls for the pending text: on with a slider, the direction, and the
        // Center / Baseline / Top line the letters keep.
        if (areaControl("C", "Letter scaling")) {
            click("Letter scaling", exact = true)
            if (areaControl("C", "Scale letters")) {
                click("Scale letters", exact = true)
                assertTrue("letter scaling on", text.item!!.spec.letterScale.isOn)
                areaControl("C", "Smallest letter")
                for (label in listOf("Letters: Center", "Letters: Baseline", "Letters: Top")) areaControl("C", label)
                areaControl("C", com.brushwork.paint.tools.text.LetterScaleDirection.START_TO_END.label)
                if (areaControl("C", com.brushwork.paint.tools.text.LetterScaleDirection.END_TO_START.label)) {
                    click(com.brushwork.paint.tools.text.LetterScaleDirection.END_TO_START.label, exact = true)
                    assertEquals(com.brushwork.paint.tools.text.LetterScaleDirection.END_TO_START, text.item!!.spec.letterScale.direction)
                }
                if (has("Letters: Baseline", exact = true)) {
                    click("Letters: Baseline", exact = true)
                    assertEquals(com.brushwork.paint.tools.text.LetterScaleAlign.BASELINE, text.item!!.spec.letterScale.align)
                }
            }
            closeDialogs()
        }
        click("Apply text edit")
        assertTrue("a text layer", c.activeLayer.isTextLayer)
        Smoke.assertQuiet(c, "letter scaling")
    }

    // ================================================================== 6. linked text frames (D)

    private fun textFrames() {
        editor()
        tool("Text frames")
        assertEquals(ToolId.TEXT_FRAMES, c.activeToolId)
        assertTrue(c.currentTool is TextFrameTool)
        assertTrue("the tools button names it", has("Tools (current: Text frames)", exact = true))
        assertTrue("its own glyph", EditorIcons.tool(ToolId.TEXT_FRAMES) !== EditorIcons.tool(ToolId.FRAME_DIVIDER))
        // Drag a frame: the story editor opens; OK creates the frame (area D).
        val layers = c.doc.layers.size
        stroke(60f to 60f, 120f to 100f, 200f to 140f)
        if (SmokeUi.windows().size > 1 || runCatching { SmokeUi.field("Text") }.isSuccess) {
            SmokeUi.field("Text").type("Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore.")
            settle()
            click("OK", exact = true)
            settle()
            assertEquals("a frame layer", layers + 1, c.doc.layers.size)
            assertTrue("it is a frame of a story", c.textThreads.isFrame(c.activeLayer))
        } else {
            if (REQUIRE_MERGED_AREAS) throw AssertionError("area D: dragging with Text frames opened no story editor")
            pending += "D: drag a frame → story editor"
        }
        Smoke.assertQuiet(c, "text frames")
    }

    // ================================================================== 7. the ibisPaint main screen (E)

    private fun ibisMainScreen() {
        editor()
        val (st, nav) = s.insetsDp()
        val w = s.widthDp
        val hh = s.heightDp
        // Status bar icons are dark over the light surround.
        val bars = WindowCompat.getInsetsController(s.activity.window, s.activity.window.decorView)
        assertTrue("dark status bar icons", bars.isAppearanceLightStatusBars)
        // The top row: ibisPaint's 8 circles in its order.
        val top = listOf("Undo", "Redo", "Vector", "Selection", "Stabilizer", "Grid", "Ruler", "More options")
        top.forEachIndexed { i, label ->
            val b = s.clickable(label) ?: throw AssertionError("no \"$label\" in the top row")
            // Centres 24, 72, then 128 + 48·(i − 2): ibisPaint's 8 dp gap after Redo (v16 polish).
            assertEquals("$label at 24 + 48·$i (+ 8 after Redo)", 24f + 48f * i + (if (i >= 2) 8f else 0f), b.center.x, 1f)
            assertEquals("$label in the top row", st + 24f, b.center.y, 1f)
        }
        // The bottom bar: ibisPaint's 7 slots in its order.
        val bottom = listOf("Switch to eraser", "Tools (current: Brush)", "Open brush settings", "Open color picker", "Hide interface", "Open layers (active layer 2)", "Back to gallery")
        bottom.forEachIndexed { i, label ->
            val b = s.clickable(label) ?: throw AssertionError("no \"$label\" in the bottom bar")
            assertEquals("$label at slot $i", 28f + 56f * i, b.center.x, 1f)
            assertEquals("$label in the bar", hh - nav - 25f, b.center.y, 1f)
        }
        // The brush rows: value, −, track, + (size over opacity).
        val size = s.clickable("Type brush size")!!
        val opacity = s.clickable("Type brush opacity")!!
        assertTrue("size over opacity", size.bottom <= opacity.top + 0.5f)
        assertTrue("ibisPaint's readout \"20.0\"-style", has(com.brushwork.paint.ui.editor.SliderMath.formatSizeFixed(c.brush.size), exact = true))
        assertTrue(s.clickable("Smaller brush")!!.right <= s.clickable("Bigger brush")!!.left)
        // The tool menu: two columns, ibisPaint's order first, then Brushwork's tools.
        click("Tools (current: Brush)")
        val menu = s.tagged(ChromeTags.TOOL_MENU)!!
        assertEquals(150f, menu.width, 1f)
        val transform = s.clickable("Transform")!!
        val wand = s.clickable("Magic wand")!!
        assertEquals("Transform | Magic wand on one row", transform.top, wand.top, 0.5f)
        assertTrue(transform.right <= wand.left + 0.5f)
        val lasso = s.clickable("Lasso")!!
        val filters = s.clickable("Filters")!!
        assertEquals("Lasso | Filters", lasso.top, filters.top, 0.5f)
        assertTrue("row 2 under row 1", lasso.top >= transform.bottom - 0.5f)
        assertEquals(ToolMenuEntry.Canvas, ToolMenu.entries[14])
        click("Tools (current: Brush)")
        // The More menu: the document's name and size head it.
        click("More options")
        assertTrue("the header", has("Smoke · 400 × 300 px", exact = true))
        for (e in listOf("Canvas…", "Increments…", "Settings", "Fit to screen", "Export PNG")) assertTrue("\"$e\" in More", has(e, exact = true))
        click("Canvas…", exact = true)
        SmokeUi.assertPanelShown("Canvas")
        closeDialogs()
        // Hide interface: the chrome fades, the bottom bar stays, the canvas does not move.
        val centre = s.screen(200f, 150f)
        click("Hide interface")
        Smoke.pump(300)
        settle()
        assertNull(s.tagged(ChromeTags.TOP_ROW))
        assertNull(s.tagged(ChromeTags.SLIDER_ROWS))
        assertNotNull(s.tagged(ChromeTags.BOTTOM_BAR))
        assertEquals(centre, s.screen(200f, 150f))
        click("Show interface")
        Smoke.pump(300)
        settle()
        assertNotNull(s.tagged(ChromeTags.TOP_ROW))
        assertEquals(w, s.widthDp, 0.5f)
        Smoke.assertQuiet(c, "ibis main screen")
    }

    // ================================================================== 8. the X / Y pill (G)

    private fun coordinatePill() {
        editor()
        val (st, _) = s.insetsDp()
        val layer = c.doc.layers[1]
        seed(layer, 100f, 80f, 220f, 180f, 0xFF2266CC.toInt())
        tool("Transform")
        val tt = c.currentTool as TransformTool
        assertTrue(Smoke.pumpUntil { settle(1); tt.transformState != null })
        settle()
        val slot = s.tagged(ChromeTags.PILL_SLOT) ?: throw AssertionError("no X / Y pill")
        assertEquals("the pill under the options strip (y 135)", ChromeLayout.pillTop(st), slot.top, 1f)
        assertEquals("at x 8", 8f, slot.left, 1f)
        for (label in listOf("X slider", "Y slider")) assertTrue("\"$label\"", RobolectricUi.elements().any { it.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true })
        assertTrue("typed", has("Type X") && has("Type Y"))
        // The pill's own look (area G): 32 dp tall with the "#" increments cell.
        if (areaControl("G", "Increments")) {
            assertEquals("a small pill", 32f, slot.height, 1.5f)
        }
        val steps = c.undoManager.undoCount
        setSlider("X slider", 300f)
        assertEquals(300f, tt.anchorPosition!!.x, 0.5f)
        click("Apply transform edit")
        assertTrue(Smoke.pumpUntil { settle(1); !tt.hasPendingWork && c.busyMessage == null })
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
    }

    // ================================================================== 9. the layer window (F; E sizes it)

    private fun layerWindow() {
        editor()
        val (_, nav) = s.insetsDp()
        val hh = s.heightDp
        click("Open layers (active layer 2)")
        val lw = s.tagged(ChromeTags.LAYER_WINDOW) ?: throw AssertionError("no layer window")
        assertEquals("ibisPaint's 382 dp", 382f, lw.width, 1f)
        assertEquals("ibisPaint's 520 dp", 520f, lw.height, 1f)
        assertEquals("at x 5", 5f, lw.left, 1f)
        assertEquals("its bottom on the bottom bar", hh - nav - 50f, lw.bottom, 1f)
        assertTrue("over the slider rows", lw.top < hh - nav - 130f)
        assertTrue("the bottom bar shows it open", has("Close layers (active layer 2)", exact = true))
        for (label in listOf("Close layers", "Add layer", "More layer actions", "Choose blend mode")) {
            assertTrue("\"$label\" in the window", has(label, exact = true))
        }
        // Area F's ibisPaint placement: + in the left column, the 9-icon strip at the right.
        if (areaControl("F", "Filters for this layer")) {
            val add = s.clickable("Add layer")!!
            assertTrue("+ in the left column: $add", add.right <= lw.left + 8f + 100f + 1f)
            val fx = s.clickable("Filters for this layer")!!
            // The 40 dp strip ends at the main block's 6 dp right pad (§3.7.7).
            assertTrue("FX in the right strip: $fx in $lw", fx.left >= lw.right - 6f - 40f - 1f && fx.right <= lw.right + 0.5f)
        }
        areaControl("F", "Transparency: light checker")
        areaControl("F", "New special layer")
        val layers = c.doc.layers.size
        click("Add layer", exact = true)
        assertEquals(layers + 1, c.doc.layers.size)
        click("Close layers", exact = true)
        assertNull(s.tagged(ChromeTags.LAYER_WINDOW))
        Smoke.assertQuiet(c, "layer window")
    }

    // ================================================================== 10. 360 dp

    private fun at360dp() {
        RuntimeEnvironment.setQualifiers("w360dp-h760dp-xxhdpi")
        try {
            editor()
            assertEquals(360f, s.widthDp, 0.5f)
            // All 8 circles at a 44 dp pitch, every target ≥ 40 dp.
            val top = listOf("Undo", "Redo", "Vector", "Selection", "Stabilizer", "Grid", "Ruler", "More options")
            top.forEachIndexed { i, label ->
                val b = s.clickable(label) ?: throw AssertionError("no \"$label\" at 360 dp")
                assertEquals("$label at 22 + 44·$i (+ 8 after Redo)", 22f + 44f * i + (if (i >= 2) 8f else 0f), b.center.x, 1f)
                assertTrue("$label ≥ 40 dp", b.width >= 40f - 0.5f && b.height >= 40f - 0.5f)
            }
            // Every tool of the request in the tool menu.
            for (t in listOf("Path", "Text frames", "Curve", "Text", "Masks", "Transform", "Filters", "Canvas", "Settings")) {
                click("Tools (current:")
                scrollMenuTo(t)
                val b = s.clickable(t)!!
                assertTrue("\"$t\" inside the screen: $b", b.left >= 0f && b.right <= 360.5f)
                assertTrue("\"$t\" finger-sized: $b", b.width >= 40f && b.height >= 40f)
                click("Tools (current:")
            }
            // More › Increments… and the layer window (w − 10 wide).
            click("More options")
            assertTrue(has("Increments…", exact = true))
            backKey()
            click("Open layers")
            val lw = s.tagged(ChromeTags.LAYER_WINDOW)!!
            assertEquals(350f, lw.width, 1f)
            assertTrue("inside the screen", lw.left >= 0f && lw.right <= 360.5f)
            click("Close layers", exact = true)
            Smoke.assertQuiet(c, "360 dp")
        } finally {
            RuntimeEnvironment.setQualifiers("w392dp-h873dp-xxhdpi")
        }
    }
}
