package com.brushwork.paint.smoke

import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.smoke.Smoke.P
import com.brushwork.paint.smoke.SmokeUi.assertWindowsLaidOut
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.frame.FrameDividerTool
import com.brushwork.paint.tools.select.LassoTool
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.ui.assist.GridPanel
import com.brushwork.paint.ui.assist.RulerPanel
import com.brushwork.paint.ui.assist.StabilizerPanel
import com.brushwork.paint.ui.brush.BrushPanel
import com.brushwork.paint.ui.canvas.CanvasAdjustDialog
import com.brushwork.paint.ui.color.ColorPickerDialog
import com.brushwork.paint.ui.color.ColorPickerPanel
import com.brushwork.paint.ui.editor.CanvasView
import com.brushwork.paint.ui.editor.EditorPrefs
import com.brushwork.paint.ui.editor.EditorScreen
import com.brushwork.paint.ui.editor.EditorSettingsDialog
import com.brushwork.paint.ui.filters.FilterBrowser
import com.brushwork.paint.ui.gallery.NewCanvasDialog
import com.brushwork.paint.ui.layers.LayersPanel
import com.brushwork.paint.ui.selection.SelectionPanel
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * The whole editor UI at runtime (Robolectric, real Skia, phone-sized hdpi screen): the real
 * [EditorScreen] with a real [EditorController] on a 400 x 300 document, every tool's options
 * strip, every panel and dialog opened the way a user opens them, the tool sheets opened through
 * the tools' own Compose state, and touch input through the composed canvas.
 *
 * Compose's frame clock only runs in the first test of a Robolectric sandbox, so this class has
 * its own sandbox and does all of its UI work in ONE test, split into sections whose failures
 * are collected (one broken panel doesn't hide the others).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-hdpi", instrumentedPackages = ["com.brushwork.paint.smoke.editorsandbox"])
class EditorSmokeTest {

    private val failures = mutableListOf<Throwable>()

    private val activities = mutableListOf<org.robolectric.android.controller.ActivityController<*>>()

    private fun section(name: String, block: () -> Unit) {
        Smoke.scopeErrors.clear()
        try {
            block()
            if (Smoke.scopeErrors.isNotEmpty()) throw AssertionError("coroutine errors: ${Smoke.scopeErrors}", Smoke.scopeErrors.first())
        } catch (t: Throwable) {
            System.err.println("=== SECTION FAILED: $name")
            t.printStackTrace()
            failures += AssertionError("[$name] $t", t)
        } finally {
            // Close this section's screens so the next one starts with no windows.
            activities.forEach { runCatching { it.pause().stop().destroy() } }
            activities.clear()
            runCatching { settle() }
        }
    }

    private fun newActivity(): ComponentActivity {
        SmokeUi.markBaseline()
        val ctl = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activities += ctl
        return ctl.get()
    }

    /** Pixel (x, y) of the canvas view in its window. */
    private fun canvasOrigin(v: View): Pair<Float, Float> {
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        return loc[0].toFloat() to loc[1].toFloat()
    }

    private fun EditorController.seedContent(layerIndex: Int = doc.layers.lastIndex) {
        editWholeLayer(doc.layers[layerIndex], "Seed") { b ->
            Canvas(b).drawRect(120f, 90f, 260f, 200f, Paint().apply { color = 0xFF2266CC.toInt() })
        }
    }

    @Test
    fun wholeEditorUi() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        section("editor screen, tools, panels, touch") { editorScreen() }
        section("panels composed directly") { panelsDirect() }
        section("editor branches: slider, busy, filter layout, pinch, export") { editorBranches() }
        section("layers panel operated") { layersPanel() }
        section("selection, canvas and brush panels operated") { panelActions() }
        section("number fields refuse NaN and infinity") { numberFields() }
        section("main activity end to end") { mainActivity() }
        dog.interrupt()
        // Failures that are only logged (swallowed exceptions) count as failures too.
        val errors = Smoke.errorLogs()
        if (errors.isNotEmpty()) failures += AssertionError("error logs:\n" + errors.joinToString("\n"))
        if (failures.isNotEmpty()) {
            val first = failures.first()
            failures.drop(1).forEach { first.addSuppressed(it) }
            throw first
        }
    }

    // ================================================================== editor screen

    private fun editorScreen() {
        val activity = newActivity()
        val c = Smoke.controller(activity)
        var exits = 0
        var saves = 0
        var screenKey by mutableIntStateOf(0)
        activity.setContent {
            BrushworkTheme {
                androidx.compose.runtime.key(screenKey) {
                    EditorScreen(c, onExit = { exits++ }, onSaveNow = { saves++ })
                }
            }
        }
        settle()
        assertWindowsLaidOut()
        val canvas = Smoke.find(activity.window.decorView, CanvasView::class.java)
            ?: throw AssertionError("no CanvasView in the composed hierarchy")
        assertTrue("canvas has a size: ${canvas.width}x${canvas.height}", canvas.width > 0 && canvas.height > 0)
        assertTrue("fit zoom set", c.viewTransform.zoom > 0f)
        Smoke.assertQuiet(c, "first frame")
        SmokeUi.assertIdle("editor at rest")

        // ---- every tool's options strip
        for (id in ToolId.entries) {
            Smoke.step("tool $id")
            c.selectTool(id)
            settle()
            assertEquals(id, c.activeToolId)
            assertWindowsLaidOut()
            Smoke.assertQuiet(c, "tool $id")
            SmokeUi.assertIdle("tool $id", settleMs = 600)
        }
        c.selectTool(ToolId.BRUSH)
        settle()

        // ---- eraser toggle from the hotbar
        click("Switch to eraser")
        assertEquals(ToolId.ERASER, c.activeToolId)
        click("Eraser on: switch to")
        assertEquals(ToolId.BRUSH, c.activeToolId)

        // ---- tool picker sheet: pick a tool from it
        click("Tools (current: Brush)")
        assertWindowsLaidOut(2)
        assertTrue("tool grid shown", has("Frame divider"))
        click("Magic wand", exact = true)
        assertEquals(ToolId.MAGIC_WAND, c.activeToolId)
        closeSheets(activity) { screenKey++ }
        c.selectTool(ToolId.BRUSH)
        settle()

        // ---- every panel through the chrome
        val panels = listOf(
            "Open brush settings" to "PRESETS",
            "Open color picker" to "Previous",
            "Open layers" to "Layer 2",
            "Filters" to "Filters",
            "Selection" to "Select all",
            "Canvas" to "Canvas",
            "Ruler" to "Ruler",
        )
        for ((opener, expected) in panels) {
            click(opener)
            settle()
            assertWindowsLaidOut(2)
            assertTrue("\"$opener\" shows \"$expected\"", has(expected))
            Smoke.assertQuiet(c, "panel $opener")
            SmokeUi.assertIdle("panel $opener")
            closeSheets(activity) { screenKey++ }
        }
        for (entry in listOf("Grid", "Stabilizer", "Settings")) {
            click("More options")
            assertWindowsLaidOut(2)
            click(entry, exact = true)
            settle()
            assertWindowsLaidOut(2)
            Smoke.assertQuiet(c, "menu $entry")
            SmokeUi.assertIdle("panel $entry")
            closeSheets(activity) { screenKey++ }
        }
        click("More options")
        click("Save now", exact = true)
        assertEquals(1, saves)
        click("More options")
        click("Flip view", exact = true)
        assertTrue(c.viewMirrored)
        click("More options")
        click("Flip view", exact = true)
        assertFalse(c.viewMirrored)
        click("More options")
        click("Fit to screen", exact = true)

        // ---- touch through the window (Compose interop → CanvasView → controller → brush)
        touchThroughWindow(activity, c)

        // ---- tool sheets via each tool's own Compose state
        toolSheets(activity, c) { screenKey++ }

        // ---- back to the gallery
        click("Back to gallery")
        assertEquals(1, exits)
        Smoke.assertQuiet(c, "end of editor screen")
    }

    /** Closes every sheet/dialog on top of the activity (Close / Cancel buttons, else [reset]). */
    private fun closeSheets(activity: ComponentActivity, reset: () -> Unit) {
        Smoke.step("close sheets")
        repeat(4) {
            if (SmokeUi.windows().size <= 1) return
            val closer = listOf("Close", "Cancel", "Done").firstOrNull { SmokeUi.find(it, exact = true) != null }
            if (closer == null) { reset(); settle(); return@repeat }
            click(closer, exact = true)
            settle()
        }
        if (SmokeUi.windows().size > 1) { reset(); settle() }
        assertEquals("sheets closed", 1, SmokeUi.windows().size)
    }

    private fun touchThroughWindow(activity: ComponentActivity, c: EditorController) {
        c.selectTool(ToolId.BRUSH)
        c.brush = c.brush.copy(size = 12f, opacity = 1f)
        c.color = 0xFFCC0000.toInt()
        settle()
        val canvas = Smoke.find(activity.window.decorView, CanvasView::class.java)!!
        val root = activity.window.decorView
        val (ox, oy) = canvasOrigin(canvas)
        val t = c.viewTransform
        fun screen(dx: Float, dy: Float) = t.docToScreen(dx, dy).let { (it.x + ox) to (it.y + oy) }
        val layer = c.activeLayer
        val undo0 = c.undoManager.undoCount
        val touch = Smoke.Touch(root)
        touch.stroke(screen(100f, 150f), screen(300f, 150f))
        settle()
        Smoke.assertQuiet(c, "stroke through the window")
        assertEquals("one undo step for the stroke", undo0 + 1, c.undoManager.undoCount)
        val px = layer.bitmap.getPixel(200, 150)
        assertTrue("stroke painted the document where the finger went: ${Integer.toHexString(px)}", px ushr 24 > 0)
        assertEquals("nothing painted far from the stroke", 0, layer.bitmap.getPixel(200, 40))
        // Two-finger tap through the window undoes it.
        touch.idle(300)
        touch.twoFingerTap(screen(150f, 120f), screen(250f, 120f))
        settle()
        assertEquals("two-finger tap undid the stroke", 0, layer.bitmap.getPixel(200, 150))
        assertEquals(undo0, c.undoManager.undoCount)
        Smoke.assertQuiet(c, "two-finger undo through the window")
    }

    // ================================================================== tool sheets

    private fun toolSheets(activity: ComponentActivity, c: EditorController, reset: () -> Unit) {
        // Shape: pending shape, Settings and Numbers sheets, apply with the ✓ button.
        c.selectTool(ToolId.SHAPE)
        settle()
        val shape = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        click("Settings", exact = true)
        assertWindowsLaidOut(2)
        assertTrue(has("Stroke width"))
        closeSheets(activity, reset)
        click("Numbers", exact = true)
        assertTrue("Numbers creates a pending shape", shape.hasPendingWork)
        assertWindowsLaidOut(2)
        assertTrue(has("Rotation"))
        closeSheets(activity, reset)
        for (type in com.brushwork.paint.tools.vector.ShapeType.entries) {
            shape.update { it.copy(type = type) }
            settle()
        }
        shape.update { it.copy(type = com.brushwork.paint.tools.vector.ShapeType.ARROW) }
        click("Settings", exact = true)
        closeSheets(activity, reset)
        click("Numbers", exact = true)
        closeSheets(activity, reset)
        val undoShape = c.undoManager.undoCount
        click("Apply shape edit")
        assertFalse(shape.hasPendingWork)
        assertEquals(undoShape + 1, c.undoManager.undoCount)
        Smoke.assertQuiet(c, "shape applied")

        // Curve / polyline: anchors, selected point actions, both sheets, discard with ✕.
        for (id in listOf(ToolId.CURVE, ToolId.POLYLINE)) {
            c.selectTool(id)
            settle()
            val curve = c.tools.getValue(id) as CurveTool
            click("Numbers", exact = true)
            assertWindowsLaidOut(2)
            click("Add point", exact = true)
            click("Add point", exact = true)
            closeSheets(activity, reset)
            curve.addAnchor(Vec2(300f, 60f))
            curve.select(1)
            settle()
            assertTrue(has("Delete point"))
            click("Settings", exact = true)
            assertWindowsLaidOut(2)
            closeSheets(activity, reset)
            click("Numbers", exact = true)
            assertTrue(has("Point 2 of"))
            closeSheets(activity, reset)
            click("Discard ${id.label.lowercase()} edit")
            assertFalse(curve.hasPendingWork)
            Smoke.assertQuiet(c, "$id discarded")
        }

        // Text: editor dialog, numbers sheet, apply.
        c.selectTool(ToolId.TEXT)
        settle()
        val text = c.tools.getValue(ToolId.TEXT) as TextTool
        val canvasView = Smoke.find(activity.window.decorView, CanvasView::class.java)!!
        val (tox, toy) = canvasOrigin(canvasView)
        fun tapDoc(x: Float, y: Float) {
            val p = c.viewTransform.docToScreen(x, y)
            Smoke.Touch(activity.window.decorView).apply { idle(300); tap(p.x + tox, p.y + toy) }
            settle()
        }
        // A tap on the canvas starts a text and opens the editor; a cancelled new text is gone.
        tapDoc(200f, 150f)
        assertTrue("editor open", text.editorOpen && has("Add text", exact = true))
        click("Cancel", exact = true)
        assertFalse("cancelled new text removed", text.hasPendingWork)
        tapDoc(200f, 150f)
        assertWindowsLaidOut(2)
        SmokeUi.field("Text").type("Smoke\ntest")
        settle()
        for (b in listOf("Bold", "Italic", "Vertical text", "Vertical text")) click(b, exact = true)
        click("Use drawing color", exact = true)
        click("OK", exact = true)
        assertTrue(text.hasPendingWork)
        assertEquals("Smoke\ntest", text.item!!.text)
        assertTrue(text.item!!.spec.bold && text.item!!.spec.italic && !text.item!!.spec.vertical)
        // Reopen from the strip, change and cancel: back to what it was.
        click("Edit text", exact = true)
        assertTrue(has("Edit text", exact = true))
        SmokeUi.field("Text").type("changed")
        click("Cancel", exact = true)
        assertEquals("Smoke\ntest", text.item!!.text)
        click("Numbers", exact = true)
        assertWindowsLaidOut(2)
        assertTrue(text.numbersOpen)
        closeSheets(activity, reset)
        text.numbersOpen = false
        settle()
        val layersBefore = c.doc.layers.size
        click("Apply text edit")
        assertEquals("text becomes a layer", layersBefore + 1, c.doc.layers.size)
        Smoke.assertQuiet(c, "text applied")

        // Transform: lift real content, strip + numbers sheet + interpolation menu, apply.
        c.selectLayer(c.doc.layers.lastIndex)
        c.seedContent()
        c.selectTool(ToolId.TRANSFORM)
        Smoke.pump(200)
        settle()
        val transform = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        assertTrue("transform lifted the content", transform.hasPendingWork)
        assertNotNull(c.renderOverride)
        click("Rotate 90° clockwise")
        click("Smooth", exact = true)
        settle()
        assertWindowsLaidOut(2)
        click("Nearest", exact = true)
        transform.numbersOpen = true
        settle()
        assertWindowsLaidOut(2)
        transform.numbersOpen = false
        settle()
        val undoT = c.undoManager.undoCount
        click("Apply transform edit")
        Smoke.pump(100)
        assertEquals(undoT + 1, c.undoManager.undoCount)
        Smoke.assertQuiet(c, "transform applied")

        // Lasso polygon: pending vertices shown in the strip, closed from the strip.
        c.selectTool(ToolId.LASSO)
        settle()
        val lasso = c.tools.getValue(ToolId.LASSO) as LassoTool
        lasso.setPolygonMode(true)
        for ((x, y) in listOf(50f to 50f, 250f to 60f, 200f to 220f)) {
            c.pointerDown(ToolPoint(x, y))
            c.pointerUp(ToolPoint(x, y))
        }
        settle()
        assertEquals(3, lasso.vertexCount)
        assertTrue(has("3 pt"))
        click("Close polygon")
        assertTrue("polygon selection made", Smoke.pumpUntil { c.selection != null })
        settle()
        click("Selection menu")
        assertWindowsLaidOut(2)
        closeSheets(activity, reset)
        c.deselect()
        lasso.setPolygonMode(false)
        settle()

        // Selection-tool strips: slider chips and choice chips open their menus.
        c.selectTool(ToolId.MAGIC_WAND)
        settle()
        click("Tolerance")
        assertWindowsLaidOut(2)
        closeSheets(activity, reset)
        c.selectTool(ToolId.FILL)
        settle()
        click("Close gaps")
        closeSheets(activity, reset)
        click("Refer:")
        closeSheets(activity, reset)
        c.selectTool(ToolId.EYEDROPPER)
        settle()
        click("Size:")
        closeSheets(activity, reset)

        // Frame divider: settings sheet, frame layer, grid dialog.
        c.selectTool(ToolId.FRAME_DIVIDER)
        settle()
        val frame = c.tools.getValue(ToolId.FRAME_DIVIDER) as FrameDividerTool
        click("New frame layer")
        assertWindowsLaidOut(2)
        assertTrue(frame.createFrameLayer())
        frame.settingsOpen = false
        settle()
        click("Rows × columns")
        assertWindowsLaidOut(2)
        assertTrue(frame.applyGrid(2, 3))
        frame.gridOpen = false
        settle()
        Smoke.assertQuiet(c, "frame divider")

        // Brush strip opens the brush panel for the current paint tool.
        c.selectTool(ToolId.SMUDGE)
        settle()
        click("Choose brush")
        assertWindowsLaidOut(2)
        closeSheets(activity, reset)
        c.selectTool(ToolId.RULER)
        settle()
        c.selectTool(ToolId.BRUSH)
        settle()
        Smoke.assertQuiet(c, "tool sheets done")
    }

    // ================================================================== editor branches

    private fun editorBranches() {
        val activity = newActivity()
        val c = Smoke.controller(activity)
        c.seedContent()
        activity.setContent { BrushworkTheme { EditorScreen(c, onExit = {}, onSaveNow = {}) } }
        settle()
        c.tools
        c.brush = c.brush.copy(size = 20f)
        settle()
        val root = activity.window.decorView
        val touch = Smoke.Touch(root)

        // Side slider: a real vertical drag shows the size preview and changes the size.
        Smoke.step("side slider drag")
        val slider = com.brushwork.paint.ui.color.RobolectricUi.elements().last { e ->
            e.node.config.contains(androidx.compose.ui.semantics.SemanticsActions.SetProgress) &&
                e.node.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription) { null }?.contains("Brush size") == true
        }
        val sb = slider.bounds
        touch.send(MotionEvent.ACTION_DOWN, P(0, sb.center.x, sb.center.y))
        for (s in 1..8) { touch.idle(16); touch.send(MotionEvent.ACTION_MOVE, P(0, sb.center.x, sb.center.y - s * 12f)) }
        settle(2)
        assertWindowsLaidOut()
        touch.send(MotionEvent.ACTION_UP, P(0, sb.center.x, sb.center.y - 96f))
        settle()
        assertTrue("dragging up grew the brush: ${c.brush.size}", c.brush.size > 20f)
        Smoke.assertQuiet(c, "side slider")

        // Busy overlay with a Stop button; the canvas ignores touches meanwhile.
        Smoke.step("busy overlay")
        var stopped = false
        var release: (() -> Unit)? = null
        c.runBusy("Working hard", onCancel = { stopped = true }) {
            kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont -> release = { cont.resumeWith(Result.success(Unit)) } }
        }
        settle()
        assertTrue(has("Working hard"))
        val undo0 = c.undoManager.undoCount
        val canvas = Smoke.find(root, CanvasView::class.java)!!
        val (ox, oy) = canvasOrigin(canvas)
        fun screen(x: Float, y: Float) = c.viewTransform.docToScreen(x, y).let { (it.x + ox) to (it.y + oy) }
        touch.stroke(screen(50f, 50f), screen(300f, 200f))
        assertEquals("no stroke through the busy overlay", undo0, c.undoManager.undoCount)
        click("Stop", exact = true)
        assertTrue("Stop reached the operation", stopped)
        release!!.invoke()
        settle()
        Smoke.assertQuiet(c, "busy finished")
        assertFalse(has("Working hard"))

        // A filter preview swaps the tool strip and hotbar for the filter panel, and back.
        Smoke.step("filter session layout")
        assertTrue(has("Choose brush"))
        c.startFilter(com.brushwork.paint.filters.FilterRegistry.all.first())
        settle()
        assertNotNull(c.filterSession)
        assertFalse("tool options hidden during a filter", has("Choose brush"))
        assertFalse("hotbar hidden during a filter", has("Open color picker"))
        assertWindowsLaidOut()
        assertTrue("filter preview finished", Smoke.pumpUntil { settle(1); c.filterSession?.let { !it.isRendering } ?: true })
        c.filterSession?.cancel()
        settle()
        assertTrue(has("Choose brush"))
        Smoke.assertQuiet(c, "filter cancelled")

        // Pinch through the window: zoom readout, no paint, no undo.
        Smoke.step("pinch")
        val z0 = c.viewTransform.zoom
        val cx = canvas.width / 2f + ox
        val cy = canvas.height / 2f + oy
        touch.idle(300)
        touch.pinch(cx - 60f to cy, cx + 60f to cy, cx - 120f to cy, cx + 120f to cy)
        assertEquals(z0 * 2f, c.viewTransform.zoom, 0.02f)
        assertEquals(undo0, c.undoManager.undoCount)
        Smoke.assertQuiet(c, "pinch")

        // Editor settings apply live: with two-finger undo off, a two-finger tap undoes nothing.
        Smoke.step("settings toggles")
        click("More options")
        click("Fit to screen", exact = true)
        touch.idle(300)
        val n0 = c.undoManager.undoCount
        touch.stroke(screen(40f, 150f), screen(360f, 150f))
        val strokes = c.undoManager.undoCount
        assertEquals("the stroke reached the canvas", n0 + 1, strokes)
        click("More options")
        click("Settings", exact = true)
        click("Two-finger tap to undo", exact = true)
        assertFalse(c.settings.twoFingerUndo)
        closeSheets(activity) {}
        touch.idle(300)
        touch.twoFingerTap(screen(150f, 150f), screen(250f, 150f))
        settle()
        assertEquals("two-finger undo is off", strokes, c.undoManager.undoCount)
        c.settings.twoFingerUndo = true
        touch.idle(300)
        touch.twoFingerTap(screen(150f, 150f), screen(250f, 150f))
        settle()
        assertEquals(strokes - 1, c.undoManager.undoCount)

        // An untouched transform lift doesn't block Redo; pressing it drops the lift and redoes.
        Smoke.step("redo with an untouched lift")
        c.selectTool(ToolId.TRANSFORM)
        Smoke.pump(100)
        settle()
        assertTrue(
            "lifted: layer ${c.activeLayer} visible=${c.activeLayer.visible} locked=${c.activeLayer.locked} " +
                "sel=${c.selection} busy=${c.busyMessage} msg=${c.message} shown=${SmokeUi.shown().take(40)}",
            c.currentTool.hasPendingWork,
        )
        assertTrue("Redo enabled", SmokeUi.isEnabled("Redo"))
        click("Redo", exact = true)
        assertEquals(strokes, c.undoManager.undoCount)
        c.selectTool(ToolId.BRUSH)
        settle()
        Smoke.assertQuiet(c, "redo with lift")

        // Export and share from the editor menu run under the busy overlay and finish cleanly.
        for (entry in listOf("Export PNG", "Export JPG", "Share")) {
            click("More options")
            click(entry, exact = true)
            assertTrue("$entry finished", Smoke.pumpUntil { settle(1); c.busyMessage == null })
            assertFalse("$entry: no failure message", has("failed"))
            Smoke.assertQuiet(c, entry)
        }
        // (Whether Share reaches the system chooser can't be checked here: FileProvider matches its
        // roots with '/', which a Windows test host's cache path doesn't use. The PNG encoding is
        // checked in EditorRuntimeSmokeTest.exportAndShareFilesAreWritten.)
    }

    // ================================================================== panel actions

    private fun panelActions() {
        val activity = newActivity()
        val c = Smoke.controller(activity, Smoke.document(400, 300, layers = 2, whiteBottom = true))
        c.seedContent()
        c.undoManager.clear()
        var which by mutableStateOf(-1)
        activity.setContent {
            BrushworkTheme {
                val close = { which = -1 }
                when (which) {
                    0 -> SelectionPanel(c, close)
                    1 -> CanvasAdjustDialog(c, close)
                    2 -> BrushPanel(c, close)
                }
            }
        }
        settle()
        fun open(i: Int) { which = i; settle(); assertWindowsLaidOut(2) }
        fun waitIdle(where: String) {
            assertTrue("$where finished", Smoke.pumpUntil { settle(1); c.busyMessage == null })
            Smoke.assertQuiet(c, where)
        }

        // Selection menu: every action, each from a freshly opened sheet (most close it).
        Smoke.step("selection panel")
        open(0); click("Select all", exact = true); assertNotNull(c.selection)
        click("Invert", exact = true); assertNull("inverting everything selects nothing", c.selection)
        click("Select all", exact = true)
        c.setSelection(com.brushwork.paint.model.Selection.fromBytes(ByteArray(400 * 300) { i -> if (i % 400 in 100..299 && i / 400 in 80..219) -1 else 0 }, 400, 300))
        settle()
        for (action in listOf("Grow", "Shrink", "Feather")) {
            if (which != 0) open(0)
            click(action, exact = true)
            waitIdle(action)
            assertNotNull("$action keeps a selection", c.selection)
        }
        open(0); click("Layer opacity", exact = true); waitIdle("layer opacity")
        open(0); click("Sky", exact = true); waitIdle("smart select")
        for (action in listOf("Fill", "Clear", "Copy to new layer", "Cut to new layer")) {
            if (c.selection == null) click("Select all", exact = true).also { settle() }
            open(0)
            click(action, exact = true)
            waitIdle(action)
        }
        if (c.selection == null) c.selectAll()
        open(0); click("Fill with…", exact = true)
        assertTrue("color dialog open", Smoke.pumpUntil { settle(1); has("OK", exact = true) })
        click("OK", exact = true)
        waitIdle("fill with")
        assertEquals(-1, which)

        // Canvas dialog: one operation per tab, through its own buttons.
        Smoke.step("canvas dialog")
        c.deselect()
        open(1); SmokeUi.clickTab("Rotate & flip"); click("Rotate 90° clockwise", exact = true); waitIdle("rotate")
        assertEquals(300, c.doc.width); assertEquals(400, c.doc.height)
        click("Flip horizontally", exact = true); waitIdle("flip")
        SmokeUi.clickTab("Color mode"); click("Grayscale", exact = true); click("Convert to Grayscale", exact = true); waitIdle("grayscale")
        assertEquals(com.brushwork.paint.model.ColorMode.GRAYSCALE, c.doc.colorMode)
        if (which != 1) open(1)
        SmokeUi.clickTab("Resolution"); click("72 dpi", exact = true); click("Set 72 dpi", exact = true); waitIdle("dpi")
        assertEquals(72f, c.doc.dpi)
        if (which != 1) open(1)
        SmokeUi.clickTab("Image size")
        SmokeUi.typeAndDone("Width", "150")
        click("Resize image", exact = true); waitIdle("resize image")
        assertEquals(150, c.doc.width)
        if (which != 1) open(1)
        SmokeUi.clickTab("Canvas size")
        SmokeUi.typeAndDone("Height", "260")
        click("Change canvas size", exact = true); waitIdle("canvas size")
        assertEquals(260, c.doc.height)
        if (which != 1) open(1)
        SmokeUi.clickTab("Trim & crop")
        c.doc.layers[0].bitmap.eraseColor(0); c.doc.layers[0].markChanged(); c.invalidateDoc(null) // transparent edges to trim
        which = -1; settle(); open(1); SmokeUi.clickTab("Trim & crop"); settle()
        click("Trim", exact = true); waitIdle("trim")
        which = -1
        settle()
        var g = 50
        while (c.canUndo && g-- > 0) { c.undo(); waitIdle("undo") }
        assertEquals("back to the original size", 400, c.doc.width)
        assertEquals(300, c.doc.height)

        // Brush panel: pick every preset, then reset an edited one.
        Smoke.step("brush panel")
        open(2)
        val presets = com.brushwork.paint.brush.BrushLibrary.presetsFor(ToolId.BRUSH)
        for (p in presets) {
            click("Use ${p.name}", exact = true)
            assertEquals(p.id, c.brush.id)
        }
        c.brush = c.brush.copy(size = c.brush.size + 7f)
        settle()
        click("Reset ${c.brush.name} to default")
        SmokeUi.clickIn("Reset \"${c.brush.name}\"?", "Reset")
        assertEquals(presets.last().size, c.brush.size)
        which = -1
        settle()
        Smoke.assertQuiet(c, "panel actions")
    }

    // ================================================================== layers panel

    private fun layersPanel() {
        val activity = newActivity()
        val c = Smoke.controller(activity, Smoke.document(400, 300, layers = 3))
        val colors = listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt())
        c.doc.layers.forEachIndexed { i, l -> Canvas(l.bitmap).drawRect(40f * i, 30f * i, 200f + 40f * i, 150f + 30f * i, Paint().apply { color = colors[i] }) }
        c.undoManager.clear()
        val originalLayers = c.doc.layers.toList()
        val originalPixels = originalLayers.map { l -> IntArray(400 * 300).also { l.bitmap.getPixels(it, 0, 400, 0, 0, 400, 300) } }
        var open by mutableStateOf(true)
        activity.setContent { BrushworkTheme { if (open) LayersPanel(c, { open = false }, onImportPicture = {}) } }
        settle()
        assertWindowsLaidOut(2)
        fun quiet(where: String) { settle(4); Smoke.assertQuiet(c, where); assertWindowsLaidOut(2) }

        click("Layer 2", exact = true); assertEquals(1, c.doc.activeLayerIndex); quiet("select row")
        click("Add layer"); assertEquals(4, c.doc.layers.size); quiet("add")
        click("Duplicate layer"); assertEquals(5, c.doc.layers.size); quiet("duplicate")
        click("Move layer up"); quiet("up")
        click("Move layer down"); quiet("down")
        click("Merge down"); assertEquals(4, c.doc.layers.size); quiet("merge")
        click("Delete layer"); SmokeUi.clickIn("Delete layer?", "Delete"); assertEquals(3, c.doc.layers.size); quiet("delete")
        click("Choose blend mode"); click("Multiply", exact = true)
        assertEquals(com.brushwork.paint.model.LayerBlendMode.MULTIPLY, c.activeLayer.blendMode); quiet("blend")
        for (t in listOf("Clipping", "α lock", "Lock")) { click(t, exact = true); click(t, exact = true); quiet("toggle $t") }
        click("Hide layer"); click("Show layer"); quiet("visibility")
        click("Layer mask"); click("Add mask", exact = true); assertNotNull(c.activeLayer.mask); quiet("add mask")
        for (item in listOf("Invert mask", "Disable mask", "Enable mask", "Edit layer content", "Edit mask", "Apply mask")) {
            click("Layer mask"); click(item, exact = true); quiet(item)
        }
        assertEquals(null, c.activeLayer.mask)
        click("Layer mask"); click("Add mask", exact = true); click("Layer mask"); click("Delete mask", exact = true); quiet("delete mask")
        click("More layer actions"); click("Rename…", exact = true)
        SmokeUi.typeAndDone("Name", "Renamed")
        assertEquals("Renamed", c.activeLayer.name); quiet("rename")
        for (item in listOf("Flip horizontal", "Flip vertical")) { click("More layer actions"); click(item, exact = true); quiet(item) }
        click("More layer actions"); click("Fill", exact = false); quiet("fill")
        click("More layer actions"); click("Clear", exact = false); quiet("clear")

        // Opacity: a real drag on the slider = live preview, ONE undo step on release.
        val slider = RobolectricUiElements.slider()
        val undo0 = c.undoManager.undoCount
        val b = slider.bounds
        com.brushwork.paint.ui.color.RobolectricUi.drag(slider.window, (b.right - 20f) to b.center.y, (b.left + b.width * 0.3f) to b.center.y)
        quiet("opacity drag")
        assertTrue("opacity changed: ${c.activeLayer.opacity}", c.activeLayer.opacity < 0.9f)
        assertEquals("one undo step for the drag", undo0 + 1, c.undoManager.undoCount)
        assertEquals("Opacity", c.undoManager.undoLabel)

        // Long-press a row and drag it down one row: the layer moves once.
        val topName = c.doc.layers.last().name
        val row = SmokeUi.find(topName, exact = true) ?: throw AssertionError("no row \"$topName\"")
        val rb = row.bounds
        val touch = Smoke.Touch(row.window)
        val order0 = c.doc.layers.map { it.name }
        Smoke.step("reorder drag")
        touch.send(MotionEvent.ACTION_DOWN, P(0, rb.center.x, rb.center.y))
        touch.holdRealTime(800)
        for (s in 1..12) { touch.idle(16); touch.send(MotionEvent.ACTION_MOVE, P(0, rb.center.x, rb.center.y + s * 12f)) }
        touch.idle(16)
        touch.send(MotionEvent.ACTION_UP, P(0, rb.center.x, rb.center.y + 144f))
        touch.idle(300)
        quiet("reorder drag")
        assertTrue("the dragged top layer moved down: $order0 -> ${c.doc.layers.map { it.name }}", c.doc.layers.last().name != topName)

        // Everything done through the panel undoes back to the original stack.
        open = false
        settle()
        var guard = 200
        while (c.canUndo && guard-- > 0) c.undo()
        assertEquals(originalLayers, c.doc.layers.toList())
        originalLayers.forEachIndexed { i, l ->
            val now = IntArray(400 * 300).also { l.bitmap.getPixels(it, 0, 400, 0, 0, 400, 300) }
            assertTrue("layer $i pixels restored", originalPixels[i].contentEquals(now))
            assertEquals(1f, l.opacity)
            assertEquals(com.brushwork.paint.model.LayerBlendMode.NORMAL, l.blendMode)
            assertNull(l.mask)
        }
        Smoke.assertQuiet(c, "layers undone")
    }

    /** The (only) slider on screen: the element with a progress range. */
    private object RobolectricUiElements {
        fun slider() = com.brushwork.paint.ui.color.RobolectricUi.elements().last { e ->
            e.node.config.contains(androidx.compose.ui.semantics.SemanticsActions.SetProgress)
        }
    }

    // ================================================================== number fields

    private fun numberFields() {
        val activity = newActivity()
        val c = Smoke.controller(activity)
        c.seedContent()
        var which by mutableStateOf(0)
        var created: com.brushwork.paint.storage.NewCanvasSpec? = null
        activity.setContent {
            BrushworkTheme {
                when (which) {
                    1 -> GridPanel(c) {}
                    2 -> RulerPanel(c) {}
                    3 -> NewCanvasDialog(creating = false, onDismiss = {}, onCreate = { created = it })
                    4 -> com.brushwork.paint.ui.placement.TransformNumbersSheet(c.tools.getValue(ToolId.TRANSFORM) as TransformTool)
                    5 -> com.brushwork.paint.ui.placement.TextNumbersSheet(c.tools.getValue(ToolId.TEXT) as TextTool)
                }
            }
        }
        settle()
        val bad = listOf("NaN", "Infinity", "-Infinity", "1e999")

        which = 1
        c.updateGrid(c.grid.copy(enabled = true))
        settle()
        for (t in bad) {
            SmokeUi.typeAndDone("Spacing", t)
            SmokeUi.typeAndLeave("Offset X", t)
            SmokeUi.typeAndDone("Bold line every", t)
        }
        val g = c.grid
        assertTrue("grid stays finite: $g", g.spacingPx.isFinite() && g.offsetXPx.isFinite() && g.spacingPx >= 1f)

        which = 2
        settle()
        for (t in bad) {
            SmokeUi.typeAndDone("Center X", t)
            SmokeUi.typeAndLeave("Angle", t)
            SmokeUi.typeAndDone("Nudge step", t)
        }
        val r = c.ruler
        assertTrue("ruler stays finite: $r", r.centerX.isFinite() && r.angleDeg.isFinite() && r.nudgeStep.isFinite())

        c.selectTool(ToolId.TRANSFORM)
        Smoke.pump(100)
        val tr = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        assertTrue(tr.hasPendingWork)
        which = 4
        settle()
        val transformLabels = SmokeUi.shown().filter { it in listOf("X", "Y", "Width", "Height", "Rotation", "Scale") }.distinct()
        assertEquals(6, transformLabels.size)
        for (label in transformLabels) {
            for (t in bad) SmokeUi.typeAndDone(label, t)
        }
        val st = tr.transformState!!
        assertTrue("transform stays finite: $st", st.width.isFinite() && st.height.isFinite() && st.bounds().left.isFinite())
        tr.commit()
        c.selectTool(ToolId.TEXT)
        val text = c.tools.getValue(ToolId.TEXT) as TextTool
        text.startTextAt(100f, 100f)
        text.setText("A")
        text.confirmEditor()
        which = 5
        settle()
        val textLabels = SmokeUi.shown().filter { it in listOf("Center X", "Center Y", "Size", "Rotation") }.distinct()
        assertEquals(4, textLabels.size)
        for (label in textLabels) {
            for (t in bad) SmokeUi.typeAndDone(label, t)
        }
        val item = text.item!!
        assertTrue("text stays finite: $item", item.cx.isFinite() && item.cy.isFinite() && item.spec.sizePx.isFinite())
        text.discard()

        which = 3
        settle()
        click("Custom", exact = true)
        for (t in bad) {
            SmokeUi.typeAndDone("Width", t)
            SmokeUi.typeAndDone("Resolution", t)
        }
        SmokeUi.typeAndDone("Width", "640")
        SmokeUi.typeAndDone("Height", "480")
        click("Create", exact = true)
        val spec = created ?: throw AssertionError("Create did not deliver a canvas")
        assertEquals(640, spec.width)
        assertEquals(480, spec.height)
        assertTrue("dpi ${spec.dpi}", spec.dpi.isFinite() && spec.dpi > 0f)
        Smoke.assertQuiet(c, "number fields")
    }

    // ================================================================== main activity

    private fun mainActivity() {
        SmokeUi.markBaseline()
        val ctl = Robolectric.buildActivity(com.brushwork.paint.MainActivity::class.java).setup()
        activities += ctl
        val activity = ctl.get()
        val app = activity.application as com.brushwork.paint.BrushworkApp
        Smoke.step("gallery")
        assertTrue("gallery loaded", Smoke.pumpUntil { settle(1); has("Your gallery is empty") })
        SmokeUi.assertIdle("gallery")

        // A project created through the repository appears and opens from its card.
        val id = kotlinx.coroutines.runBlocking {
            app.repository.create(com.brushwork.paint.storage.NewCanvasSpec("Smoke art", 400, 300, 300f))
        }
        assertTrue("new project listed", Smoke.pumpUntil { settle(1); has("Smoke art") })
        click("Smoke art", exact = true)
        Smoke.step("open editor")
        assertTrue("editor opened", Smoke.pumpUntil {
            settle(1)
            (app.editorSession?.state as? com.brushwork.paint.EditorSession.State.Ready) != null &&
                Smoke.find(activity.window.decorView, CanvasView::class.java)?.width ?: 0 > 0
        })
        val session = app.editorSession!!
        val c = (session.state as com.brushwork.paint.EditorSession.State.Ready).controller
        assertEquals(id, c.doc.id)
        assertEquals(2, c.doc.layers.size)
        settle()
        c.tools
        c.brush = c.brush.copy(size = 10f, opacity = 1f, hardness = 1f, taperStart = 0f, taperEnd = 0f)
        c.color = 0xFF112233.toInt()
        val canvas = Smoke.find(activity.window.decorView, CanvasView::class.java)!!
        val (ox, oy) = canvasOrigin(canvas)
        fun screen(x: Float, y: Float) = c.viewTransform.docToScreen(x, y).let { (it.x + ox) to (it.y + oy) }
        val touch = Smoke.Touch(activity.window.decorView)
        touch.stroke(screen(50f, 50f), screen(350f, 250f))
        settle()
        val drawn = c.activeLayer
        assertEquals("stroke painted through MainActivity", 0xFF112233.toInt(), drawn.bitmap.getPixel(200, 150))

        // Leaving the app saves (onStop), coming back keeps the editor.
        ctl.pause().stop()
        assertTrue("saved on stop", Smoke.pumpUntil { drawn.savedVersion == drawn.contentVersion })
        ctl.start().resume()
        settle()
        assertSame(session, app.editorSession)

        // Recreated (a configuration change the activity doesn't handle itself, e.g. font size):
        // the same session and framing, a new canvas view that still draws and undoes.
        val zoom = c.viewTransform.zoom
        val undoBefore = c.undoManager.undoCount
        // Robolectric's recreate() needs frames to run by themselves (the new window's view root).
        org.robolectric.shadows.ShadowChoreographer.setPaused(false)
        ctl.recreate()
        org.robolectric.shadows.ShadowChoreographer.setPaused(true)
        val act = ctl.get()
        settle()
        assertSame("the open document survives recreation", session, app.editorSession)
        val canvas2 = Smoke.find(act.window.decorView, CanvasView::class.java) ?: throw AssertionError("no canvas after recreation")
        assertTrue(canvas2 !== canvas)
        assertEquals("same framing", zoom, c.viewTransform.zoom, 1e-4f)
        val (ox2, oy2) = canvasOrigin(canvas2)
        fun screen2(x: Float, y: Float) = c.viewTransform.docToScreen(x, y).let { (it.x + ox2) to (it.y + oy2) }
        val touch2 = Smoke.Touch(act.window.decorView)
        touch2.stroke(screen2(50f, 150f), screen2(350f, 150f))
        settle()
        assertEquals("the recreated canvas draws", undoBefore + 1, c.undoManager.undoCount)
        touch2.idle(300)
        touch2.twoFingerTap(screen2(150f, 100f), screen2(250f, 100f))
        settle()
        assertEquals("two-finger undo on the recreated canvas", undoBefore, c.undoManager.undoCount)

        // Rotation: the activity handles it itself (configChanges), so the same editor is resized
        // to landscape: the canvas refits, still draws under the finger, and the layers panel
        // switches to its side-by-side layout.
        Smoke.step("rotate to landscape")
        org.robolectric.RuntimeEnvironment.setQualifiers("w760dp-h360dp-land-hdpi")
        ctl.configurationChange()
        settle()
        assertSame("rotation keeps the activity", act, ctl.get())
        val canvasL = Smoke.find(act.window.decorView, CanvasView::class.java) ?: throw AssertionError("no canvas in landscape")
        assertTrue("landscape canvas: ${canvasL.width}x${canvasL.height}", canvasL.width > canvasL.height)
        run {
            val (oxL, oyL) = canvasOrigin(canvasL)
            fun screenL(x: Float, y: Float) = c.viewTransform.docToScreen(x, y).let { (it.x + oxL) to (it.y + oyL) }
            val n = c.undoManager.undoCount
            val tl = Smoke.Touch(act.window.decorView)
            tl.idle(300)
            tl.stroke(screenL(60f, 280f), screenL(340f, 280f))
            settle()
            assertEquals(n + 1, c.undoManager.undoCount)
            assertEquals("landscape stroke under the finger", 0xFF112233.toInt(), c.activeLayer.bitmap.getPixel(200, 280))
            c.undo()
            settle()
        }
        click("Open layers")
        assertWindowsLaidOut(2)
        assertTrue(has("Add layer"))
        SmokeUi.assertIdle("layers panel in landscape")
        closeSheets(act) {}
        org.robolectric.RuntimeEnvironment.setQualifiers("w360dp-h760dp-port-hdpi")
        ctl.configurationChange()
        settle()
        Smoke.assertQuiet(c, "rotated back")

        // Back to the gallery: the editor closes after saving; the project has the stroke.
        touch2.stroke(screen2(50f, 250f), screen2(350f, 50f))
        click("Back to gallery", settleAfter = false)
        Smoke.step("closing editor")
        // The save on the way out copies the layers one by one while the editor is still on
        // screen; anything drawn after that would be dropped with the controller. So the canvas
        // refuses input from the moment Back is pressed.
        assertTrue(session.closed)
        assertEquals("the editor shows it is saving (and ignores input)", "Saving…", c.busyMessage)
        val undoAtBack = c.undoManager.undoCount
        val topAtBack = IntArray(400 * 300).also { c.activeLayer.bitmap.getPixels(it, 0, 400, 0, 0, 400, 300) }
        touch2.stroke(screen2(20f, 280f), screen2(380f, 280f))
        assertEquals("no stroke while closing", undoAtBack, c.undoManager.undoCount)
        assertTrue("no pixels while closing", topAtBack.contentEquals(IntArray(400 * 300).also { c.activeLayer.bitmap.getPixels(it, 0, 400, 0, 0, 400, 300) }))
        assertTrue("back in the gallery", Smoke.pumpUntil { settle(1); app.editorSession == null && has("New canvas") })
        val saved = kotlinx.coroutines.runBlocking { app.repository.load(id) }
        val layer = saved.layers[saved.activeLayerIndex]
        assertEquals(0xFF112233.toInt(), layer.bitmap.getPixel(200, 150))
        // (275, 100) is on the second stroke only: (50, 250) -> (350, 50).
        assertEquals("the second stroke was saved on exit", 0xFF112233.toInt(), layer.bitmap.getPixel(275, 100))
        for ((i, l) in c.doc.layers.withIndex()) {
            val mem = IntArray(400 * 300).also { l.bitmap.getPixels(it, 0, 400, 0, 0, 400, 300) }
            val disk = IntArray(400 * 300).also { saved.layers[i].bitmap.getPixels(it, 0, 400, 0, 0, 400, 300) }
            assertTrue("layer $i: what was on screen when the editor closed is what was saved", mem.contentEquals(disk))
        }

        // Gallery card menu: rename, duplicate, export, share, delete.
        fun projects() = kotlinx.coroutines.runBlocking { app.repository.list() }
        assertTrue("gallery lists the project", Smoke.pumpUntil { settle(1); has("More options for Smoke art") })
        click("More options for Smoke art")
        click("Rename", exact = true)
        SmokeUi.typeAndDone("Name", "Renamed art")
        assertTrue("renamed", Smoke.pumpUntil { settle(1); has("Renamed art", exact = true) })
        click("More options for Renamed art")
        click("Duplicate", exact = true)
        assertTrue("duplicated", Smoke.pumpUntil { settle(1); projects().size == 2 })
        for (entry in listOf("Export PNG", "Export JPG", "Share")) {
            click("More options for Renamed art", exact = false)
            click(entry, exact = true)
            // The gallery ignores other actions while its busy overlay is up.
            assertTrue("$entry: busy overlay gone", Smoke.pumpUntil { settle(1); !has("Exporting") && !has("Preparing to share") })
            assertTrue("$entry: gallery usable again", has("New canvas"))
        }
        val copyId = projects().first { it.id != id }.id
        val copyName = projects().first { it.id == copyId }.name
        // On a Windows test host a file the gallery is reading at that moment (thumbnail, list
        // refresh) can't be deleted and the gallery says "Could not delete…"; Android unlinks open
        // files. Only that message allows another try; anything else fails.
        for (attempt in 1..3) {
            click("More options for $copyName")
            click("Delete", exact = true)
            assertTrue("delete asks first", Smoke.pumpUntil { settle(1); has("Delete artwork?", exact = true) })
            SmokeUi.clickIn("Delete artwork?", "Delete")
            var seen = ""
            val done = Smoke.pumpUntil(8_000) {
                settle(1)
                SmokeUi.shown().firstOrNull { it.startsWith("Could not delete") }?.let { seen = it }
                projects().size == 1 || seen.isNotEmpty()
            }
            if (projects().size == 1) break
            System.err.println("[smoke] delete attempt $attempt: message \"$seen\"; projects ${projects().map { it.name }}")
            assertTrue("delete neither happened nor reported a problem", done && seen.startsWith("Could not delete"))
            Smoke.pumpUntil(6_000) { settle(1); !has("Could not delete") }
        }
        assertEquals("deleted", 1, projects().size)
        assertTrue(has("Renamed art", exact = true))

        // New canvas through the dialog opens the editor on it; system back returns to the gallery.
        click("New canvas")
        assertWindowsLaidOut(2)
        click("Create", exact = true)
        assertTrue("created canvas opens", Smoke.pumpUntil {
            settle(1)
            app.editorSession?.state is com.brushwork.paint.EditorSession.State.Ready && app.editorSession?.projectId != id
        })
        settle()
        act.onBackPressedDispatcher.onBackPressed()
        assertTrue("system back closes the editor", Smoke.pumpUntil { settle(1); app.editorSession == null && has("Renamed art") })
    }

    // ================================================================== direct composition

    private fun panelsDirect() {
        val activity = newActivity()
        val c = Smoke.controller(activity)
        c.seedContent()
        var which by mutableStateOf(-1)
        val prefs = EditorPrefs(c.settings)
        val names = listOf(
            "BrushPanel", "ColorPickerPanel", "ColorPickerDialog", "LayersPanel", "CanvasAdjustDialog",
            "RulerPanel", "GridPanel", "StabilizerPanel", "SelectionPanel", "FilterBrowser",
            "EditorSettingsDialog", "NewCanvasDialog",
        )
        activity.setContent {
            BrushworkTheme {
                val close = { which = -1 }
                when (which) {
                    0 -> BrushPanel(c, close)
                    1 -> ColorPickerPanel(c, close)
                    2 -> ColorPickerDialog(0x80336699.toInt(), onPick = {}, onDismiss = close, showAlpha = true)
                    3 -> LayersPanel(c, close, onImportPicture = close)
                    4 -> CanvasAdjustDialog(c, close)
                    5 -> RulerPanel(c, close)
                    6 -> GridPanel(c, close)
                    7 -> StabilizerPanel(c, close)
                    8 -> SelectionPanel(c, close)
                    9 -> FilterBrowser(c, close)
                    10 -> EditorSettingsDialog(prefs, close)
                    11 -> NewCanvasDialog(creating = false, onDismiss = close, onCreate = {})
                }
            }
        }
        settle()
        for ((i, name) in names.withIndex()) {
            which = i
            settle()
            assertWindowsLaidOut(2)
            Smoke.assertQuiet(c, name)
            SmokeUi.assertIdle(name)
            which = -1
            settle()
            assertEquals("$name closed", 1, SmokeUi.windows().size)
        }
        // Grayscale document + a selection + a mask: panels that show those states.
        c.doc.colorMode = com.brushwork.paint.model.ColorMode.GRAYSCALE
        c.onDocumentGeometryChanged()
        c.selectAll()
        c.addMask(c.activeLayer, fromSelection = true)
        for (i in listOf(1, 3, 4, 8)) {
            which = i
            settle()
            assertWindowsLaidOut(2)
            which = -1
            settle()
        }
    }
}
