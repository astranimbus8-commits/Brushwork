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
        dog.interrupt()
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

        // ---- every tool's options strip
        for (id in ToolId.entries) {
            Smoke.step("tool $id")
            c.selectTool(id)
            settle()
            assertEquals(id, c.activeToolId)
            assertWindowsLaidOut()
            Smoke.assertQuiet(c, "tool $id")
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
            closeSheets(activity) { screenKey++ }
        }
        for (entry in listOf("Grid", "Stabilizer", "Settings")) {
            click("More options")
            assertWindowsLaidOut(2)
            click(entry, exact = true)
            settle()
            assertWindowsLaidOut(2)
            Smoke.assertQuiet(c, "menu $entry")
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
        text.startTextAt(200f, 150f)
        settle()
        assertWindowsLaidOut(2)
        text.setText("Smoke\ntest")
        settle()
        text.confirmEditor()
        settle()
        assertTrue(text.hasPendingWork)
        text.numbersOpen = true
        settle()
        assertWindowsLaidOut(2)
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
