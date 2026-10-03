package com.brushwork.paint.smoke

import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushPresetStore
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.Selection
import com.brushwork.paint.model.SelectionMode
import com.brushwork.paint.segmentation.SegTestImages
import com.brushwork.paint.segmentation.SmartTarget
import com.brushwork.paint.smoke.Smoke.P
import com.brushwork.paint.smoke.SmokeUi.assertWindowsLaidOut
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.select.SelectionEdits
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.editor.CanvasView
import com.brushwork.paint.ui.editor.EditorScreen
import com.brushwork.paint.ui.editor.SliderMath
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * The editor chrome at runtime (v1.1, in v1.6's ibisPaint layout; Robolectric, real Skia, a
 * 360 x 760 dp hdpi phone): the brush slider rows above the bottom bar and their typed values, the
 * non-modal layer window over the bottom of the screen (drawing above it keeps working, a tap
 * outside closes it), the selection bar's copy / cut / paste / deselect flow into a placed
 * "Pasted" layer, duplicating only the selection, step-wise undo of a curve from the top row's
 * Undo and the new settings.
 *
 * Like [EditorSmokeTest], this class has its own sandbox (Compose's frame clock only runs in the
 * first test of one) and does all of its UI work in ONE test split into sections.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-hdpi", instrumentedPackages = ["com.brushwork.paint.smoke.chromesandbox"])
class ChromeSmokeTest {

    private val failures = mutableListOf<Throwable>()
    private val activities = mutableListOf<org.robolectric.android.controller.ActivityController<*>>()
    private val blue = 0xFF2266CC.toInt()

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

    /** The editor on [c] in a fresh activity. */
    private fun editor(c: (ComponentActivity) -> EditorController): Pair<ComponentActivity, EditorController> {
        SmokeUi.markBaseline()
        val ctl = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activities += ctl
        val activity = ctl.get()
        val controller = c(activity)
        activity.setContent { BrushworkTheme { EditorScreen(controller, onExit = {}, onSaveNow = {}) } }
        settle()
        controller.tools // builds the tools (they load the stored brush presets)
        settle()
        return activity to controller
    }

    private fun EditorController.seed() {
        editWholeLayer(activeLayer, "Seed") { b -> Canvas(b).drawRect(120f, 90f, 260f, 200f, Paint().apply { color = blue }) }
    }

    private fun canvasOf(activity: ComponentActivity): CanvasView =
        Smoke.find(activity.window.decorView, CanvasView::class.java) ?: throw AssertionError("no canvas view")

    /** Window pixel of document point ([x], [y]). */
    private fun screen(activity: ComponentActivity, c: EditorController, x: Float, y: Float): Pair<Float, Float> {
        val loc = IntArray(2)
        canvasOf(activity).getLocationInWindow(loc)
        val p = c.viewTransform.docToScreen(x, y)
        return (p.x + loc[0]) to (p.y + loc[1])
    }

    /** Document point under window pixel ([x], [y]). */
    private fun doc(activity: ComponentActivity, c: EditorController, x: Float, y: Float): Vec2 {
        val loc = IntArray(2)
        canvasOf(activity).getLocationInWindow(loc)
        val p = c.viewTransform.screenToDoc(x - loc[0], y - loc[1])
        return Vec2(p.x, p.y)
    }

    /** Bounds (window pixels) of the layer window, as the editor places it (v1.6 sizing contract). */
    private fun layersWindowBounds(): Rect? = RobolectricUi.elements()
        .lastOrNull { it.node.layoutInfo.isPlaced && it.node.config.getOrNull(SemanticsProperties.TestTag) == com.brushwork.paint.ui.editor.chrome.ChromeTags.LAYER_WINDOW }?.bounds

    private fun sliderNamed(name: String) = RobolectricUi.elements().last { e ->
        e.node.config.contains(SemanticsActions.SetProgress) &&
            e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(name) == true
    }

    @Test
    fun editorChromeV11() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        section("slider bar and typed values") { sliderBar() }
        section("layers window is non-modal") { layersWindow() }
        section("selection bar: copy, paste, cut, deselect") { selectionBar() }
        section("smart select results are copyable") { smartSelectCopy() }
        section("the Undo button takes back one curve point") { curveUndo() }
        dog.interrupt()
        val errors = Smoke.errorLogs()
        if (errors.isNotEmpty()) failures += AssertionError("error logs:\n" + errors.joinToString("\n"))
        if (failures.isNotEmpty()) {
            val first = failures.first()
            failures.drop(1).forEach { first.addSuppressed(it) }
            throw first
        }
    }

    // ================================================================== slider bar

    private fun sliderBar() {
        val (activity, c) = editor { Smoke.controller(it) }
        c.brush = c.brush.copy(size = 20f, opacity = 1f)
        settle()
        assertWindowsLaidOut()
        val touch = Smoke.Touch(activity.window.decorView)

        // The ibisPaint slider rows sit directly above the bottom bar, with the values readable.
        val size = sliderNamed("Brush size")
        val opacity = sliderNamed("Brush opacity")
        val bar = SmokeUi.find("Open color picker", exact = true) ?: throw AssertionError("no bottom bar")
        assertTrue("size above opacity above the bottom bar", size.bounds.bottom <= opacity.bounds.top + 1f && opacity.bounds.bottom <= bar.bounds.top + 1f)
        assertTrue("values shown (ibisPaint's \"20.0\" and \"100\")", has("20.0", exact = true) && has("100", exact = true))
        assertTrue("sliders are wide: ${size.bounds.width}", size.bounds.width > 180f)
        SmokeUi.assertIdle("slider bar at rest", settleMs = 600)

        // Dragging right grows the brush (relative: no jump on touch), and the size is saved.
        val sb = size.bounds
        touch.send(MotionEvent.ACTION_DOWN, P(0, sb.center.x, sb.center.y))
        touch.idle(16)
        assertEquals("touching doesn't change the size", 20f, c.brush.size)
        for (s in 1..8) { touch.idle(16); touch.send(MotionEvent.ACTION_MOVE, P(0, sb.center.x + s * 10f, sb.center.y)) }
        settle(2)
        assertTrue("size preview while dragging", has("Brush size ${SliderMath.formatSize(c.brush.size)} px"))
        touch.send(MotionEvent.ACTION_UP, P(0, sb.center.x + 80f, sb.center.y))
        settle()
        val grown = c.brush.size
        assertTrue("dragging right grew the brush: $grown", grown > 20f)
        assertEquals("the new size is saved with the preset", grown, BrushPresetStore.get(activity).current(ToolId.BRUSH).size)
        assertTrue("value text follows", has(SliderMath.formatSizeFixed(grown), exact = true))

        // Dragging the opacity slider left lowers the opacity.
        val ob = sliderNamed("Brush opacity").bounds
        RobolectricUi.drag(activity.window.decorView, (ob.center.x + 40f) to ob.center.y, (ob.center.x - 60f) to ob.center.y)
        assertTrue("opacity dropped: ${c.brush.opacity}", c.brush.opacity < 1f)
        Smoke.assertQuiet(c, "slider drags")

        // Tap the size value, type it, Done.
        click("Type brush size")
        assertWindowsLaidOut(2)
        assertTrue("size dialog", has("Brush size", exact = true))
        SmokeUi.typeAndDone("Brush size", "25")
        assertEquals(25f, c.brush.size)
        assertEquals("dialog closed", 1, SmokeUi.windows().size)
        // Garbage is refused: the value stays and the dialog says why.
        click("Type brush size")
        SmokeUi.typeAndDone("Brush size", "abc")
        assertEquals(25f, c.brush.size)
        assertTrue("invalid input reported", has("Type a number"))
        click("Cancel", exact = true)
        assertEquals(1, SmokeUi.windows().size)
        // Out of range is clamped; commas work.
        click("Type brush size")
        SmokeUi.typeAndDone("Brush size", "5000")
        assertEquals(SliderMath.MAX_BRUSH_SIZE, c.brush.size)
        click("Type brush size")
        SmokeUi.typeAndDone("Brush size", "2,5")
        assertEquals(2.5f, c.brush.size)
        // -/+ step the value; OK applies it.
        click("Type brush size")
        SmokeUi.tap("Increase Brush size")
        settle(2)
        click("OK", exact = true)
        assertEquals(3f, c.brush.size)
        // Opacity in percent.
        click("Type brush opacity")
        SmokeUi.typeAndDone("Brush opacity", "40")
        assertEquals(0.4f, c.brush.opacity, 1e-6f)
        assertTrue(has("40", exact = true))

        // The bar follows the painting tool: the eraser has its own size.
        click("Switch to eraser")
        assertEquals(ToolId.ERASER, c.activeToolId)
        assertTrue("eraser slider", has("Eraser size"))
        click("Type eraser size")
        SmokeUi.typeAndDone("Eraser size", "33")
        assertEquals(33f, c.eraser.size)
        assertEquals("the brush kept its size", 3f, c.brush.size)
        click("Eraser on: switch to")
        assertEquals(ToolId.BRUSH, c.activeToolId)

        // Settings: left-handed mirrors the rows (ibisPaint's values on the left move to the
        // right); hold-to-pick can be turned off.
        click("More options")
        click("Settings", exact = true)
        click("Left-handed layout", exact = true)
        assertTrue(c.settings.leftHanded)
        assertTrue(c.settings.longPressEyedropper)
        click("Hold finger to pick color", exact = true)
        assertFalse(c.settings.longPressEyedropper)
        click("Close", exact = true)
        assertEquals(1, SmokeUi.windows().size)
        val chip = SmokeUi.find("Type brush size") ?: throw AssertionError("no size value")
        assertTrue("value right of the slider when left-handed", chip.bounds.left >= sliderNamed("Brush size").bounds.right - 1f)
        Smoke.assertQuiet(c, "slider bar done")
    }

    // ================================================================== layers window

    private fun layersWindow() {
        // A portrait artwork: its top part shows above the layer window, which covers the bottom
        // of the screen (and the slider rows) as in ibisPaint.
        val w = 300
        val h = 600
        val (activity, c) = editor { Smoke.controller(it, Smoke.document(w, h, layers = 2)) }
        c.seed()
        c.brush = c.brush.copy(size = 12f, opacity = 1f, hardness = 1f, taperStart = 0f, taperEnd = 0f)
        c.color = 0xFFCC0000.toInt()
        settle()
        val root = activity.window.decorView
        val density = activity.resources.displayMetrics.density

        click("Open layers")
        assertEquals("non-modal: no extra window", 1, SmokeUi.windows().size)
        val wb = layersWindowBounds() ?: throw AssertionError("no layers window; shown: ${SmokeUi.shown().take(60)}")
        // ibisPaint's placement (§3.7.7): x 5, w − 10 wide, its bottom on the bottom bar, over
        // the slider rows.
        val bar = SmokeUi.find("Open color picker", exact = true)?.bounds ?: throw AssertionError("no bottom bar")
        assertEquals("at x 5", 5f, wb.left / density, 1f)
        assertEquals("w − 10 wide", root.width / density - 10f, wb.width / density, 1f)
        assertEquals("its bottom on the bottom bar", bar.top, wb.bottom, density)
        assertTrue("over the slider rows: $wb", wb.top < bar.top - 80f * density)
        assertTrue("at most 520 dp tall: ${wb.height / density} dp", wb.height / density <= 520.5f)
        assertTrue("the window shows the layers", has("Layer 2", exact = true) && has("Layer 1", exact = true))
        SmokeUi.assertIdle("layers window open", settleMs = 600)

        // Drawing above the window works while it is open.
        val docTop = screen(activity, c, 0f, 0f).second
        assertTrue("some artwork is above the window: doc at $docTop, window at ${wb.top}", docTop + 24f * density < wb.top)
        val docY = doc(activity, c, 0f, (docTop + wb.top) / 2f).y
        val left = screen(activity, c, 30f, docY)
        val right = screen(activity, c, 270f, docY)
        val touch = Smoke.Touch(root)
        val layer = c.activeLayer
        val n0 = c.undoManager.undoCount
        touch.idle(300)
        touch.stroke(left, right)
        settle()
        assertEquals("the stroke above the window was drawn", n0 + 1, c.undoManager.undoCount)
        assertTrue("painted under the finger", layer.bitmap.getPixel(150, docY.toInt()) ushr 24 > 0)
        assertTrue("still open", layersWindowBounds() != null)

        // A drag that starts on the window never reaches the canvas.
        val before = IntArray(w * h).also { layer.bitmap.getPixels(it, 0, w, 0, 0, w, h) }
        val a = (wb.left + wb.width * 0.55f) to (wb.top + 20f * density)
        val b = (wb.left + wb.width * 0.2f) to (wb.top + 22f * density)
        touch.idle(300)
        touch.stroke(a, b)
        settle()
        assertEquals("no stroke through the window", n0 + 1, c.undoManager.undoCount)
        assertTrue("no pixels through the window", before.contentEquals(IntArray(w * h).also { layer.bitmap.getPixels(it, 0, w, 0, 0, w, h) }))
        Smoke.assertQuiet(c, "drawing beside the window")

        // Duplicate with a selection copies only the selected pixels of that one layer.
        c.setSelection(Selection.fromBytes(ByteArray(w * h) { i -> if (i % w in 100..199 && i / w in 80..159) -1 else 0 }, w, h))
        settle()
        val source = c.activeLayer
        val layers0 = c.doc.layers.size
        click("Duplicate layer (selected pixels only)")
        assertEquals(layers0 + 1, c.doc.layers.size)
        val copy = c.activeLayer
        assertTrue(copy !== source)
        assertEquals("Duplicate selection", c.undoManager.undoLabel)
        assertEquals("selected + painted: copied", source.bitmap.getPixel(150, 120), copy.bitmap.getPixel(150, 120))
        assertTrue(copy.bitmap.getPixel(150, 120) ushr 24 > 0)
        assertEquals("painted but not selected: not copied", 0, copy.bitmap.getPixel(240, 180))
        assertTrue(source.bitmap.getPixel(240, 180) ushr 24 > 0)
        c.undo()
        c.deselect()
        settle()
        assertEquals(layers0, c.doc.layers.size)

        // A panel (drawn in the editor's own window) hides the window; it comes back when the
        // panel is closed.
        click("Open color picker")
        SmokeUi.assertPanelShown("Color")
        assertNull("hidden under a panel", layersWindowBounds())
        click("Close", exact = true)
        settle()
        assertNotNull("back after the panel", layersWindowBounds())

        // A tap on the canvas above the window closes it, and paints nothing.
        val undoTap = c.undoManager.undoCount
        val pixelsTap = IntArray(w * h).also { layer.bitmap.getPixels(it, 0, w, 0, 0, w, h) }
        val tapAt = screen(activity, c, 150f, docY)
        touch.idle(300)
        touch.tap(tapAt.first, tapAt.second)
        settle()
        assertNull("a tap outside closed the window", layersWindowBounds())
        assertEquals("the tap drew nothing", undoTap, c.undoManager.undoCount)
        assertTrue(pixelsTap.contentEquals(IntArray(w * h).also { layer.bitmap.getPixels(it, 0, w, 0, 0, w, h) }))
        click("Open layers")
        assertNotNull(layersWindowBounds())

        // The Layers button toggles it; Back closes it too.
        click("Close layers (active layer")
        assertNull(layersWindowBounds())
        click("Open layers")
        assertNotNull(layersWindowBounds())
        activity.onBackPressedDispatcher.onBackPressed()
        settle()
        assertNull("Back closed the window", layersWindowBounds())
        click("Open layers")
        click("Close layers", exact = true)
        assertNull(layersWindowBounds())

        // With the window open, pending tool work keeps its ✓ / ✕ reachable.
        click("Open layers")
        c.selectTool(ToolId.SHAPE)
        Smoke.pump(60)
        assertTrue((c.currentTool as com.brushwork.paint.tools.vector.ShapeTool).ensurePending())
        settle()
        val apply = SmokeUi.find("Apply shape edit") ?: throw AssertionError("no apply button")
        val wb2 = layersWindowBounds()!!
        assertTrue("✓ not under the window: ${apply.bounds} vs $wb2", apply.bounds.right <= wb2.left || apply.bounds.bottom <= wb2.top)
        click("Apply shape edit")
        assertFalse(c.currentTool.hasPendingWork)
        c.selectTool(ToolId.BRUSH)
        settle()

        // Messages never cover the window (a snackbar would sit on its action row and take its taps).
        c.toast("Snackbar while the layers window is open")
        settle()
        val snack = SmokeUi.find("Snackbar while the layers window is open", exact = true) ?: throw AssertionError("no snackbar")
        val wb3 = layersWindowBounds() ?: throw AssertionError("layers window gone")
        assertFalse("snackbar over the layers window: ${snack.bounds} vs $wb3", snack.bounds.overlaps(wb3))
        // The snackbar's timeout runs on the wall clock.
        assertTrue("snackbar gone", Smoke.pumpUntil(12_000) { settle(1); !has("Snackbar while the layers window is open") })
        assertNotNull("the window stays open", layersWindowBounds())

        // A filter preview hides the window; it is back once the filter is cancelled.
        c.startFilter(com.brushwork.paint.filters.FilterRegistry.all.first())
        settle()
        val session = c.filterSession ?: throw AssertionError("no filter session: ${c.message}")
        assertNull("hidden during the filter", layersWindowBounds())
        assertTrue("filter preview finished", Smoke.pumpUntil { settle(1); c.filterSession?.let { !it.isRendering } ?: true })
        session.cancel()
        settle()
        assertNull(c.filterSession)
        assertNotNull("the window is back after the filter", layersWindowBounds())
        Smoke.assertQuiet(c, "layers window done")
    }

    // ================================================================== selection bar

    private fun selectionBar() {
        val (activity, c) = editor { Smoke.controller(it, Smoke.document(400, 300, layers = 2)) }
        c.seed()
        settle()
        assertFalse("no selection bar without a selection", has("Copy selection"))

        // Select with the rectangle selection tool, by touch.
        c.selectTool(ToolId.MARQUEE)
        settle()
        val touch = Smoke.Touch(activity.window.decorView)
        touch.idle(300)
        touch.stroke(screen(activity, c, 100f, 80f), screen(activity, c, 200f, 160f))
        assertTrue("selection made", Smoke.pumpUntil { c.selection != null })
        settle()
        val sel = c.selection!!
        assertTrue("selection bar shown", has("Copy selection") && has("Clear the selection"))

        // Picking the transform tool to move the selection keeps the bar: its lift is untouched.
        c.selectTool(ToolId.TRANSFORM)
        Smoke.pump(100)
        settle()
        assertTrue("the transform tool lifted the selection", c.currentTool.hasPendingWork)
        assertTrue("bar stays for an untouched lift", has("Copy selection") && has("Clear the selection"))
        assertFalse("an untouched lift is not the user's work", c.currentTool.hasUserChanges)
        // The bar's edits work on that lift: ONE undo step each and no stale preview.
        val lifted = c.activeLayer
        val n1 = c.undoManager.undoCount
        click("Delete the selected pixels")
        Smoke.pump(100)
        settle()
        assertEquals("Delete during a lift is one step", n1 + 1, c.undoManager.undoCount)
        assertEquals("Clear", c.undoManager.undoLabel)
        assertEquals("selected pixels deleted", 0, lifted.bitmap.getPixel(150, 120))
        assertEquals("outside the selection untouched", blue, lifted.bitmap.getPixel(230, 180))
        Smoke.assertQuiet(c, "delete during a lift")
        c.undo()
        Smoke.pump(100)
        settle()
        assertEquals("undo brought the pixels back", blue, lifted.bitmap.getPixel(150, 120))
        val tr0 = c.currentTool as TransformTool
        if (!tr0.hasPendingWork) { tr0.start(); Smoke.pump(100); settle() }
        assertTrue("lifted again", tr0.hasPendingWork && !tr0.hasUserChanges)
        val n2 = c.undoManager.undoCount
        click("Cut selection")
        Smoke.pump(100)
        settle()
        assertEquals("Cut during a lift is one step", n2 + 1, c.undoManager.undoCount)
        assertEquals("Cut", c.undoManager.undoLabel)
        assertEquals("cut pixels are gone", 0, lifted.bitmap.getPixel(150, 120))
        assertEquals("cut pixels are on the clipboard", blue, c.clipboard!!.bitmap.getPixel(150 - sel.bounds.left, 120 - sel.bounds.top))
        Smoke.assertQuiet(c, "cut during a lift")
        c.undo()
        Smoke.pump(100)
        settle()
        assertEquals(blue, lifted.bitmap.getPixel(150, 120))
        Smoke.assertQuiet(c, "undo of the cut")
        c.selectTool(ToolId.MARQUEE)
        settle()
        assertEquals("the selection survived", sel.bounds, c.selection?.bounds)

        // Copy, then paste: a "Pasted" layer at the same place, being placed with the transform tool.
        click("Copy", exact = true)
        val clip = c.clipboard ?: throw AssertionError("nothing copied; message ${c.message}")
        assertEquals(sel.bounds.left, clip.left)
        assertEquals(sel.bounds.top, clip.top)
        val source = c.activeLayer
        val layers0 = c.doc.layers.size
        click("Paste", exact = true)
        Smoke.pump(100)
        settle()
        assertEquals(layers0 + 1, c.doc.layers.size)
        assertEquals("Pasted", c.activeLayer.name)
        assertEquals(ToolId.TRANSFORM, c.activeToolId)
        val tr = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        assertTrue("placing the pasted pixels", tr.isPlacement && tr.hasPendingWork)
        val st = tr.transformState!!.bounds()
        assertEquals("pasted where it was copied", clip.left.toFloat(), st.left, 0.5f)
        assertEquals(clip.top.toFloat(), st.top, 0.5f)
        assertFalse("the bar steps aside while placing", has("Copy selection"))
        click("Apply transform edit")
        Smoke.pump(100)
        settle()
        assertEquals("Paste", c.undoManager.undoLabel)
        val pasted = c.activeLayer
        assertEquals("painted + selected pixel pasted in place", blue, pasted.bitmap.getPixel(150, 120))
        assertEquals("selected but unpainted: empty", 0, pasted.bitmap.getPixel(105, 85))
        assertEquals("painted but not selected: not pasted", 0, pasted.bitmap.getPixel(230, 180))
        assertEquals("the source is unchanged", blue, source.bitmap.getPixel(150, 120))
        Smoke.assertQuiet(c, "paste committed")

        // Cut from the source layer: one "Cut" step clears the selected pixels.
        c.selectLayer(source)
        c.selectTool(ToolId.BRUSH)
        settle()
        assertTrue(has("Cut selection"))
        click("Cut", exact = true)
        assertEquals("Cut", c.undoManager.undoLabel)
        assertEquals(0, source.bitmap.getPixel(150, 120))
        assertEquals("outside the selection untouched", blue, source.bitmap.getPixel(230, 180))
        c.undo()
        assertEquals(blue, source.bitmap.getPixel(150, 120))

        // Delete the selected pixels, invert, deselect.
        click("Delete the selected pixels")
        assertEquals(0, source.bitmap.getPixel(150, 120))
        c.undo()
        click("Invert the selection")
        assertTrue("inverted", c.selection!!.mask.getPixel(10, 10) ushr 24 > 0)
        click("Invert the selection")
        click("Clear the selection")
        assertNull("deselected", c.selection)

        // With something copied and no selection: a paste bar that can be hidden.
        settle()
        assertTrue("paste bar", has("Paste as a new layer") && has("Hide the paste bar"))
        click("Hide the paste bar")
        assertFalse(has("Paste as a new layer"))

        // Paste from the top bar menu; discarding the placement leaves no layer behind.
        val layers1 = c.doc.layers.size
        click("More options")
        click("Paste", exact = true)
        Smoke.pump(100)
        settle()
        assertEquals(layers1 + 1, c.doc.layers.size)
        click("Discard transform edit")
        Smoke.pump(200)
        settle()
        assertEquals("discarded paste leaves no layer", layers1, c.doc.layers.size)

        // "More" opens the selection menu, which has the clipboard actions on top.
        c.selectTool(ToolId.BRUSH)
        c.selectAll()
        settle()
        click("Selection menu", exact = true)
        SmokeUi.assertPanelShown("Selection")
        assertTrue(has("Copy", exact = true) && has("Cut", exact = true) && has("Paste", exact = true) && has("Deselect", exact = true))
        click("Deselect", exact = true)
        assertNull(c.selection)
        click("Close", exact = true)
        c.selectTool(ToolId.BRUSH)
        settle()
        Smoke.assertQuiet(c, "selection bar done")
    }

    // ================================================================== smart select

    private fun smartSelectCopy() {
        val (_, c) = editor { a ->
            val d = Smoke.document(160, 120, layers = 1)
            com.brushwork.paint.engine.BitmapUtils.writePixelBuffer(d.layers[0].bitmap, SegTestImages.skyOverFoliage(160, 120))
            Smoke.controller(a, d)
        }
        SelectionEdits.smartSelect(c, SmartTarget.SKY, SelectionMode.REPLACE)
        assertTrue("smart select finished", Smoke.pumpUntil(90_000) { settle(1); c.busyMessage == null })
        val sky = c.selection ?: throw AssertionError("nothing selected: ${c.message}")
        settle()
        assertTrue("selection bar for a smart selection", has("Copy selection"))
        click("Copy", exact = true)
        val clip = c.clipboard ?: throw AssertionError("not copied")
        assertEquals(sky.bounds.left, clip.left)
        assertEquals(sky.bounds.top, clip.top)
        assertEquals(sky.bounds.width(), clip.bitmap.width)
        Smoke.assertQuiet(c, "smart select copy")
    }

    // ================================================================== curve undo

    private fun curveUndo() {
        val (_, c) = editor { Smoke.controller(it) }
        // Undo is off while there is nothing to take back: the transform tool's own untouched
        // lift is not something the user did.
        Canvas(c.activeLayer.bitmap).drawRect(50f, 50f, 150f, 150f, Paint().apply { color = blue })
        c.activeLayer.markChanged()
        c.invalidateDoc(null)
        c.selectTool(ToolId.TRANSFORM)
        Smoke.pump(100)
        settle()
        assertTrue("lifted, untouched", c.currentTool.hasPendingWork && !c.currentTool.hasUserChanges)
        assertFalse(c.canUndo)
        assertFalse("Undo off for an untouched lift", SmokeUi.isEnabled("Undo"))
        c.selectTool(ToolId.BRUSH)
        settle()
        assertFalse("still nothing to undo", SmokeUi.isEnabled("Undo"))

        c.selectTool(ToolId.CURVE)
        settle()
        val curve = c.tools.getValue(ToolId.CURVE) as CurveTool
        for (p in listOf(Vec2(60f, 60f), Vec2(200f, 240f), Vec2(340f, 60f))) curve.addAnchor(p)
        settle()
        click("Undo", exact = true)
        assertEquals("one point taken back", 2, curve.anchors.size)
        assertTrue("feedback names the step", has("Undo: last point", exact = true))
        assertTrue("the curve is still being edited", curve.hasPendingWork)
        click("Discard curve edit")
        assertFalse(curve.hasPendingWork)
        c.selectTool(ToolId.BRUSH)
        settle()
        assertSame(ToolId.BRUSH, c.activeToolId)
        Smoke.assertQuiet(c, "curve undo")
    }
}
