package com.brushwork.paint.audit

import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.EditorController
import com.brushwork.paint.masks.LinearMask
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.Smoke.P
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.clone.CloneTool
import com.brushwork.paint.tools.mask.MaskTool
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.ui.common.CurveLabels17
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.editor.CanvasView
import com.brushwork.paint.ui.editor.EditorScreen
import com.brushwork.paint.ui.theme.BrushworkTheme
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.math.abs

/**
 * REQUEST COVERAGE AUDIT (v1.5): every clause of the user's request, reached the way the user
 * reaches it on their phone (ZTE Axon 50 Lite, 392 dp, fingers) — the top bar, the Tools grid,
 * the tool strips, sheets and real touches on the canvas — and shown to do what was asked.
 *
 * The request (verbatim clauses in the section names): "wrap text around image option"; "a clone
 * stamp like the one in photoshop with which you can select an area and it will clone it and also
 * move accordingly with the placement of your stroke"; "lightroom masks with gradients like linear,
 * radial and brush ... possible to edit them after ... compatible with filters"; "plain line in
 * curve scale with brush size"; "point thickness control in curves with a slider"; "transform show
 * the X and Y ... on another small menu below the one with the options to distort, delete, etc
 * with a slider"; "scaling ... when you have at least one finger inside the object selection box
 * or both but not when they are both just close"; "another filter that will be called tone ...
 * exposure, contrast, highlights, shadows, whites and blacks"; "change the filters icon to be
 * inside tools and add a vector icon there instead ... switch to a vector mode, and make working
 * vectors with the tools we already have"; "export it in pdf and svg and ... import these types
 * of files" (the exchange flows themselves: ExchangeQaEditorUiRobolectricTest,
 * GalleryExchangeQaUiRobolectricTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.audit.requestsandbox"])
class RequestCoverageV15UiTest {

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

    // ------------------------------------------------------------------ the editor and touches

    private lateinit var activity: ComponentActivity
    private lateinit var c: EditorController
    private lateinit var touch: Smoke.Touch

    /** The editor at the phone's size on a 400 x 300 artwork (white Background + Layer 2). */
    private fun editor(doc: Document = Smoke.document(400, 300, layers = 2, whiteBottom = true)): EditorController {
        SmokeUi.markBaseline()
        val ctl = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activities += ctl
        activity = ctl.get()
        c = Smoke.controller(activity, doc)
        activity.setContent { BrushworkTheme { EditorScreen(c, onExit = {}, onSaveNow = {}) } }
        settle()
        c.tools
        c.snapping.enabled = false
        settle()
        touch = Smoke.Touch(activity.window.decorView)
        return c
    }

    /** Window pixel of document point ([x], [y]). */
    private fun screen(x: Float, y: Float): Pair<Float, Float> {
        val v = Smoke.find(activity.window.decorView, CanvasView::class.java)!!
        val loc = IntArray(2).also { v.getLocationInWindow(it) }
        val p = c.viewTransform.docToScreen(x, y)
        return (p.x + loc[0]) to (p.y + loc[1])
    }

    private fun stroke(vararg doc: Pair<Float, Float>) {
        touch.idle(300)
        touch.stroke(*doc.map { screen(it.first, it.second) }.toTypedArray())
        settle(4)
    }

    private fun tap(x: Float, y: Float) {
        touch.idle(300)
        val (sx, sy) = screen(x, y)
        touch.tap(sx, sy)
        settle(4)
    }

    private fun longPress(x: Float, y: Float) {
        touch.idle(300)
        val (sx, sy) = screen(x, y)
        touch.send(MotionEvent.ACTION_DOWN, P(0, sx, sy))
        touch.holdRealTime(900)
        touch.send(MotionEvent.ACTION_UP, P(0, sx, sy))
        settle(4)
    }

    /** Picks [label] in the Tools grid (the hotbar's tools button). */
    private fun tool(label: String) {
        click("Tools (current:")
        SmokeUi.assertPanelShown("Tools")
        click(label, exact = true)
        settle()
    }

    /** Types [text] into the in-place editor of the slider value [label] ("Type a value for ..."). */
    private fun typeValue(label: String, text: String) {
        click("Type a value for $label")
        val field = RobolectricUi.textFields().lastOrNull { it.focused } ?: RobolectricUi.textFields().last()
        field.type(text)
        settle(4)
        requireNotNull((RobolectricUi.textFields().lastOrNull { it.focused } ?: RobolectricUi.textFields().last()).node.config[SemanticsActions.OnImeAction].action).invoke()
        settle(4)
    }

    /** Bounds of the clickable element labelled [label] (its own, or its clickable ancestor's). */
    private fun clickableBounds(label: String): androidx.compose.ui.geometry.Rect {
        val e = SmokeUi.find(label, exact = true) ?: throw AssertionError("no \"$label\"")
        var n: androidx.compose.ui.semantics.SemanticsNode? = e.node
        while (n != null && n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent
        return requireNotNull(n) { "\"$label\" is not clickable" }.boundsInWindow
    }

    /** Layout size (px) of the clickable element labelled [label] (its own, or its clickable ancestor's). */
    private fun clickableSize(label: String): androidx.compose.ui.unit.IntSize {
        val e = SmokeUi.find(label, exact = true) ?: throw AssertionError("no \"$label\"")
        var n: androidx.compose.ui.semantics.SemanticsNode? = e.node
        while (n != null && n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent
        return requireNotNull(n) { "\"$label\" is not clickable" }.size
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

    private fun closePanels() {
        repeat(4) {
            if (!SmokeUi.menuOpen()) return
            // v1.6: the tool menu has no ✕; its button (bottom bar slot 2) closes it.
            if ("Tools" in SmokeUi.sheetTitles()) {
                click("Tools (current:")
                return@repeat
            }
            val closer = listOf("Close", "Cancel", "Done").firstOrNull { SmokeUi.find(it, exact = true) != null } ?: return
            click(closer, exact = true)
        }
    }

    @Test
    fun everyClauseOfTheRequestIsReachableAndWorks() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        section("change the filters icon to be inside tools and add a vector icon there instead") { filtersInsideToolsVectorInTheBar() }
        section("another filter that will be called tone: exposure, contrast, highlights, shadows, whites, blacks") { toneFilter() }
        section("tap it to switch to a vector mode; working vectors with the tools we already have") { vectorMode() }
        section("a clone stamp: select an area, it clones it and moves with the placement of the stroke") { cloneStamp() }
        section("lightroom masks: linear, radial and brush; editable after; compatible with filters") { masks() }
        section("plain line in curves scales with brush size; point thickness with a slider") { curves() }
        section("transform: X and Y on a small menu below the one with distort, delete, with sliders") { transformCoordinates() }
        section("two-finger scaling: one finger inside the box or both, not both just close") { pinchRule() }
        section("wrap text around image") { wrapText() }
        section("export in pdf and svg, import these files") { exchangeEntries() }
        section("all of it within reach on a 360 dp phone") { at360dp() }
        dog.interrupt()
        if (failures.isNotEmpty()) {
            val first = failures.first()
            failures.drop(1).forEach { first.addSuppressed(it) }
            throw first
        }
    }

    // ================================================================== filters / vector icon

    private fun filtersInsideToolsVectorInTheBar() {
        editor()
        assertTrue("a Vector action in the top bar", has("Vector", exact = true))
        assertFalse("no Filters action in the top bar", has("Filters", exact = true))
        val vector = SmokeUi.find("Vector", exact = true)!!.bounds
        val selection = SmokeUi.find("Selection", exact = true)!!.bounds
        assertTrue("Vector comes first (where Filters was)", vector.left < selection.left)
        val button = clickableBounds("Vector")
        assertTrue("the Vector button is on screen and finger-sized: $button", button.left >= 0f && button.width >= 40f * 3f && button.height >= 40f * 3f)
        click("Tools (current:")
        SmokeUi.assertPanelShown("Tools")
        assertTrue("a Filters cell in the tool menu", has("Filters", exact = true))
        // v1.6: ibisPaint's tool menu (150 × 434 dp) shows its first rows at once — Filters among
        // them, beside Lasso — and scrolls to the others (the request's Masks, Clone stamp,
        // Curve, Text...), as ibisPaint's does.
        assertTrue("\"Filters\" is visible without scrolling the tool menu at 392 x 873 dp", scrollIntoView("Filters"))
        for (t in listOf("Masks", "Clone stamp", "Transform", "Text", "Shape", "Curve", "Ruler")) scrollIntoView(t)
        scrollIntoView("Filters")
        // Let the menu's scroll animation end: a finger landing on a moving list only stops it.
        Smoke.pump(600)
        settle()
        SmokeUi.tap("Filters", exact = true)
        settle()
        assertTrue("the tile opens the filter browser", has("Search filters"))
        closePanels()
    }

    // ================================================================== Tone

    private fun toneFilter() {
        editor()
        val layer = c.doc.layers[1]
        seed(layer, 0f, 0f, 400f, 300f, 0xFF606060.toInt())
        tool("Filters")
        assertTrue("Tone is listed", has("Tone", exact = true))
        click("Tone", exact = true)
        assertNotNull("Tone opens", c.filterSession)
        assertTrue(has("Tone", exact = true))
        for (p in listOf("Exposure", "Contrast", "Highlights", "Shadows", "Whites", "Blacks")) {
            assertTrue("\"$p\" slider", has("Increase $p", exact = true) && has(p, exact = true))
        }
        // The value typed in place (tap the number), then a press of +.
        typeValue("Exposure", "1.5")
        SmokeUi.tap("Increase Exposure", exact = true)
        assertEquals(1.51f, c.filterSession!!.values.float("exposure"), 1e-4f)
        assertTrue(Smoke.pumpUntil { settle(1); c.filterSession?.isRendering == false })
        click("Apply filter", exact = true)
        assertTrue("applied", Smoke.pumpUntil { settle(1); c.filterSession == null && c.busyMessage == null })
        val after = layer.bitmap.getPixel(200, 150)
        assertTrue("brighter: ${Integer.toHexString(after)}", (after and 0xFF) > 0x60)
        assertEquals("one undo step", 2, c.undoManager.undoCount)
    }

    // ================================================================== vector mode

    private fun vectorMode() {
        editor()
        val raster = c.activeLayer
        click("Vector", exact = true)
        assertTrue("vector mode is on", c.isVectorMode)
        assertTrue("the strip says so", has("Vector mode is on"))
        val vector = c.activeLayer
        assertTrue(vector.isVectorLayer)
        // Brush: a stroke becomes an object.
        c.brush = c.brush.copy(size = 10f, opacity = 1f)
        stroke(60f to 60f, 180f to 70f)
        assertEquals(1, vector.vector!!.objects.size)
        assertTrue(vector.vector!!.objects[0] is VStroke)
        // Shape: a rectangle object.
        tool("Shape")
        stroke(220f to 120f, 340f to 230f)
        click("Apply shape edit")
        assertTrue("a shape object", vector.vector!!.objects.any { it is VShape })
        // Bucket inside the rectangle: a fill object, the layer stays vector.
        tool("Bucket")
        val before = vector.vector
        assertEquals("empty inside the rectangle", 0, vector.bitmap.getPixel(280, 175) ushr 24)
        tap(280f, 175f)
        assertTrue("the bucket filled it", Smoke.pumpUntil { settle(1); vector.vector != before && vector.bitmap.getPixel(280, 175) ushr 24 == 0xFF })
        assertTrue(vector.isVectorLayer)
        // Eraser across the brush stroke: the stroke is cut (still objects, no pixels painted away elsewhere).
        tool("Eraser")
        val strokeBefore = vector.vector!!.objects.filterIsInstance<VStroke>()
        stroke(120f to 30f, 120f to 110f)
        assertTrue(vector.isVectorLayer)
        assertNotEquals("the eraser changed the stroke", strokeBefore, vector.vector!!.objects.filterIsInstance<VStroke>())
        assertEquals("erased under the eraser", 0, vector.bitmap.getPixel(120, 66) ushr 24)
        // Lasso around the shape: the objects get selected (the Object bar replaces the selection bar).
        tool("Lasso")
        stroke(200f to 100f, 370f to 100f, 370f to 260f, 200f to 260f, 200f to 100f)
        assertTrue("objects selected", Smoke.pumpUntil { settle(1); c.vectors.selectedIds.isNotEmpty() })
        assertNull("no pixel selection on a vector layer", c.selection)
        assertTrue("the Object bar", has("Duplicate", exact = true) && has("Recolor", exact = true))
        // Transform from the Object bar: moves the objects; ✓ keeps them objects.
        click("Transform", exact = true)
        assertEquals(ToolId.TRANSFORM, c.activeToolId)
        assertTrue(Smoke.pumpUntil { settle(1); c.currentTool.hasPendingWork })
        (c.currentTool as TransformTool).moveBy(-100f, 0f)
        settle()
        click("Apply transform edit")
        assertTrue(Smoke.pumpUntil { settle(1); !c.currentTool.hasPendingWork && c.busyMessage == null })
        assertTrue("still a vector layer", vector.isVectorLayer)
        val shape = vector.vector!!.objects.filterIsInstance<VShape>().single()
        assertEquals(180f, shape.shape.cx, 1.5f)
        // Tap Vector again: vector mode is off (the empty layer it started on became the vector
        // layer: the nearest raster layer below is active), the objects stay.
        assertEquals("the empty layer was converted in place", raster, vector)
        click("Vector", exact = true)
        assertFalse(c.isVectorMode)
        assertTrue(!c.activeLayer.isVectorLayer && !c.activeLayer.isAdjustmentLayer)
        assertTrue(vector.isVectorLayer && vector.vector!!.objects.isNotEmpty())
        Smoke.assertQuiet(c, "vector mode")
    }

    // ================================================================== clone stamp

    private fun cloneStamp() {
        editor()
        val layer = c.doc.layers[1]
        // The area to clone: red over blue.
        seed(layer, 60f, 60f, 110f, 100f, 0xFFFF0000.toInt())
        seed(layer, 60f, 100f, 110f, 140f, 0xFF0000FF.toInt())
        c.undoManager.clear()
        tool("Clone stamp")
        assertEquals(ToolId.CLONE, c.activeToolId)
        assertTrue("it says how to start", has(CloneTool.HINT, exact = true))
        assertTrue("Aligned is on", (c.currentTool as CloneTool).aligned)
        c.cloneBrush = c.cloneBrush.copy(size = 12f, hardness = 1f, opacity = 1f, flow = 1f)
        // Long-press the area: the source.
        longPress(80f, 80f)
        assertNotNull("source set", (c.currentTool as CloneTool).anchor.source)
        // A stroke elsewhere paints the area.
        stroke(280f to 80f, 300f to 80f)
        assertEquals("cloned red", 0xFFFF0000.toInt(), layer.bitmap.getPixel(290, 80))
        assertEquals(1, c.undoManager.undoCount)
        // The next stroke 40 px lower: the source moved with it (Aligned) — blue comes along.
        stroke(280f to 120f, 300f to 120f)
        assertEquals("aligned: the source followed the stroke", 0xFF0000FF.toInt(), layer.bitmap.getPixel(290, 120))
        // Not aligned: each stroke starts again at the source point (set anew: Aligned moved it).
        click("Aligned", exact = true)
        assertFalse((c.currentTool as CloneTool).aligned)
        longPress(80f, 80f)
        stroke(340f to 200f, 350f to 200f)
        stroke(340f to 260f, 350f to 260f)
        assertEquals("non-aligned starts at the source", 0xFFFF0000.toInt(), layer.bitmap.getPixel(342, 200))
        assertEquals("every stroke", 0xFFFF0000.toInt(), layer.bitmap.getPixel(342, 260))
        Smoke.assertQuiet(c, "clone")
    }

    // ================================================================== masks

    private fun masks() {
        editor()
        val photo = c.doc.layers[1]
        seed(photo, 0f, 0f, 400f, 300f, 0xFF404040.toInt())
        // Stripes at the bottom (a blur shows there).
        for (x in 0 until 400 step 8) seed(photo, x.toFloat(), 200f, x + 4f, 300f, 0xFFE0E0E0.toInt())
        c.undoManager.clear()
        tool("Masks")
        assertEquals(ToolId.MASK, c.activeToolId)
        for (k in listOf("+ Linear", "+ Radial", "+ Brush")) assertTrue("\"$k\" in the Masks strip", has(k, exact = true))
        // Linear: a gradient mask, a Tone adjustment layer by default.
        click("+ Linear", exact = true)
        stroke(40f to 150f, 200f to 150f, 360f to 150f)
        val tone = c.activeLayer
        assertTrue("an adjustment layer", tone.isAdjustmentLayer)
        assertEquals("Tone 1", tone.name)
        assertEquals("adjust.tone", tone.adjustment!!.filterId)
        assertTrue(tone.maskSpec!!.components.single() is LinearMask)
        // Its effect: Tone's sliders in the Adjust sheet.
        click("Adjust…", exact = true)
        assertTrue(has("Adjust: Tone 1", exact = true))
        typeValue("Exposure", "2")
        closePanels()
        settle()
        val flat = c.compositor.renderFlattened()
        val left = flat.getPixel(20, 150) and 0xFF
        val right = flat.getPixel(380, 150) and 0xFF
        assertTrue("the gradient: one end adjusted, the other not ($left / $right)", abs(left - right) > 20)
        flat.recycle()
        // Radial and brush components on the same mask.
        click("+ Radial", exact = true)
        stroke(200f to 150f, 230f to 150f, 260f to 150f)
        click("+ Brush", exact = true)
        stroke(60f to 260f, 160f to 260f)
        assertEquals("three components", 3, tone.maskSpec!!.components.size)
        assertTrue(has("Components (3)", exact = true))
        // Editable after: another tool, then back; the linear's end handle dragged.
        tool("Brush")
        tool("Masks")
        assertEquals(tone, c.activeLayer)
        val mt = c.currentTool as MaskTool
        val linear = tone.maskSpec!!.components.first { it is LinearMask } as LinearMask
        mt.select(linear.id)
        settle()
        val steps = c.undoManager.undoCount
        stroke(linear.x1 to linear.y1, linear.x1 - 40f to linear.y1 + 30f, linear.x1 - 80f to linear.y1 + 60f)
        val moved = tone.maskSpec!!.components.first { it.id == linear.id } as LinearMask
        assertNotEquals("the handle moved the gradient", linear, moved)
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        // Compatible with filters: another effect for the same mask...
        click("Adjust…", exact = true)
        click("Choose the effect", exact = true)
        click("Invert Color", exact = true)
        closePanels()
        settle()
        assertEquals("adjust.invert", tone.adjustment!!.filterId)
        // ...and any filter through the mask, as a selection.
        click("Components (3)", exact = true)
        click("Use mask as selection", exact = true)
        closePanels()
        assertNotNull("the mask is the selection", c.selection)
        val mask = IntArray(400 * 300).also { tone.mask!!.getPixels(it, 0, 400, 0, 0, 400, 300) }
        val before = IntArray(400 * 300).also { photo.bitmap.getPixels(it, 0, 400, 0, 0, 400, 300) }
        c.selectLayer(photo)
        settle()
        tool("Filters")
        click("Gaussian Blur", exact = true)
        assertNotNull(c.filterSession)
        assertTrue(Smoke.pumpUntil { settle(1); c.filterSession?.isRendering == false })
        click("Apply filter", exact = true)
        assertTrue(Smoke.pumpUntil { settle(1); c.filterSession == null && c.busyMessage == null })
        val after = IntArray(400 * 300).also { photo.bitmap.getPixels(it, 0, 400, 0, 0, 400, 300) }
        var inside = 0
        var outside = 0
        for (y in 205 until 295) for (x in 10 until 390) {
            val i = y * 400 + x
            val m = mask[i] and 0xFF
            if (m == 255 && after[i] != before[i]) inside++
            if (m == 0 && after[i] != before[i]) outside++
        }
        assertTrue("blurred where the mask is white: $inside", inside > 100)
        assertEquals("untouched where the mask is black", 0, outside)
        Smoke.assertQuiet(c, "masks")
    }

    // ================================================================== curves

    private fun curves() {
        editor()
        val layer = c.doc.layers[1]
        tool("Curve")
        val curve = c.currentTool as CurveTool
        click(CurveLabels17.STROKE_KIND, exact = true)
        click("Plain line", exact = true)
        assertEquals(CurveStroke.PLAIN, curve.settings.stroke)
        assertTrue("the width follows the brush size", curve.widthLinked)
        // The brush size slider changes the line.
        click("Type brush size")
        SmokeUi.typeAndDone("Brush size", "30")
        assertEquals(30f, curve.lineWidth, 0.01f)
        assertTrue("the strip shows it", has("30 px", exact = true))
        tap(80f, 150f)
        tap(320f, 150f)
        assertEquals(2, curve.anchors.size)
        // Point thickness: select a point, its slider.
        curve.select(1)
        settle()
        assertTrue("the thickness slider in the strip", RobolectricUi.elements().any { it.node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { d -> d.contains("Point thickness slider") } == true })
        setSlider("Point thickness slider", 250f)
        assertEquals(2.5f, curve.anchors[1].width, 0.01f)
        click("Apply curve edit")
        assertFalse(curve.hasPendingWork)
        // The committed line: 30 px at the plain end, about 2.5 x that at the thick end.
        fun thickness(x: Int): Int = (0 until 300).count { y -> layer.bitmap.getPixel(x, y) ushr 24 > 128 }
        val thin = thickness(82)
        val thick = thickness(318)
        assertTrue("the plain end is the brush size: $thin", thin in 26..34)
        assertTrue("the thick point is thicker: $thick", thick > thin * 2)
        Smoke.assertQuiet(c, "curves")
    }

    // ================================================================== transform X / Y

    private fun transformCoordinates() {
        editor()
        val layer = c.doc.layers[1]
        seed(layer, 100f, 80f, 220f, 180f, 0xFF2266CC.toInt())
        tool("Transform")
        val tt = c.currentTool as TransformTool
        assertTrue(Smoke.pumpUntil { settle(1); tt.transformState != null })
        for (label in listOf("Distort", "Delete")) assertTrue("\"$label\" in the Transform strip", has(label, exact = true))
        val options = SmokeUi.find("Delete", exact = true)!!.bounds
        val x = slider("X slider").bounds
        val y = slider("Y slider").bounds
        assertTrue("the X / Y menu is below the options: $options / $x", x.top >= options.bottom)
        assertTrue("Y beside X (v1.6 X / Y pill)", y.left >= x.right - 1f && abs(y.center.y - x.center.y) < 1f)
        assertTrue("the sliders fit the 392 dp screen", x.right <= activity.window.decorView.width + 0.5f)
        val steps = c.undoManager.undoCount
        setSlider("X slider", 300f)
        setSlider("Y slider", 60f)
        assertEquals(300f, tt.anchorPosition!!.x, 0.01f)
        assertEquals(60f, tt.anchorPosition!!.y, 0.01f)
        click("Apply transform edit")
        assertTrue(Smoke.pumpUntil { settle(1); !tt.hasPendingWork && c.busyMessage == null })
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals("the content moved to x 300", 0xFF2266CC.toInt(), layer.bitmap.getPixel(300, 60))
        assertEquals(0, layer.bitmap.getPixel(120, 150))
    }

    // ================================================================== pinch

    private fun pinchRule() {
        editor()
        val layer = c.doc.layers[1]
        seed(layer, 150f, 110f, 250f, 190f, 0xFF22AA44.toInt())
        tool("Transform")
        val tt = c.currentTool as TransformTool
        assertTrue(Smoke.pumpUntil { settle(1); tt.transformState != null })
        fun width() = tt.transformState!!.bounds().let { it.right - it.left }
        val w0 = width()
        val z0 = c.viewTransform.zoom
        // Both fingers just outside the box (about 15 dp away): the view zooms, the object doesn't.
        val dpDoc = 15f * activity.resources.displayMetrics.density / z0
        val a0 = screen(150f - dpDoc, 150f)
        val b0 = screen(250f + dpDoc, 150f)
        touch.idle(300)
        touch.pinch(a0, b0, a0.first - 60f to a0.second, b0.first + 60f to b0.second)
        settle()
        assertEquals("not scaled with both fingers beside it", w0, width(), 0.01f)
        assertTrue("the view zoomed instead", c.viewTransform.zoom > z0 * 1.05f)
        // One finger inside the box: the object scales.
        val z1 = c.viewTransform.zoom
        val inside = screen(200f, 150f)
        val outside = screen(300f, 150f)
        touch.idle(300)
        touch.pinch(inside, outside, inside, outside.first + 120f to outside.second)
        settle()
        assertTrue("scaled with one finger inside: ${width()} vs $w0", width() > w0 * 1.1f)
        assertEquals("the view stayed", z1, c.viewTransform.zoom, 1e-4f)
        // Both inside: scales too.
        val w1 = width()
        val i1 = screen(180f, 150f)
        val i2 = screen(220f, 150f)
        touch.idle(300)
        touch.pinch(i1, i2, i1.first - 50f to i1.second, i2.first + 50f to i2.second)
        settle()
        assertTrue("scaled with both inside", width() > w1 * 1.1f)
        tt.discard()
        settle()
    }

    // ================================================================== wrap

    private fun wrapText() {
        editor()
        val picture = c.doc.layers[1]
        c.renameLayer(picture, "Picture")
        c.editWholeLayer(picture, "Seed") { b -> Canvas(b).drawCircle(200f, 150f, 50f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2266CC.toInt() }) }
        c.undoManager.clear()
        tool("Text")
        val text = c.currentTool as TextTool
        tap(200f, 150f)
        assertTrue("the text editor", text.editorOpen)
        SmokeUi.field("Text").type("Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua.")
        settle()
        click("OK", exact = true)
        text.updateSpec { it.copy(sizePx = 16f) }
        settle()
        assertTrue("the Wrap option in the Text strip", has("Wrap around picture", exact = true))
        click("Wrap around picture", exact = true)
        assertTrue(has("Wrap around picture", exact = true))
        click("Picture", exact = true)
        closePanels()
        assertTrue("the text wraps", text.item!!.wrapActive)
        click("Apply text edit")
        val textLayer = c.activeLayer
        assertTrue(textLayer.isTextLayer)
        val item = TextCodec.decode(textLayer.textData)!!
        assertEquals(picture.id, item.wrap.sourceLayerId)
        // No letter on the picture.
        var ink = 0
        for (y in 105..195 step 3) for (x in 155..245 step 3) {
            val d = Math.hypot((x - 200).toDouble(), (y - 150).toDouble())
            if (d <= 46.0 && textLayer.bitmap.getPixel(x, y) ushr 24 > 0) ink++
        }
        assertEquals("the text flows around the picture", 0, ink)
        // Moving the picture re-flows the text in the same step; one undo restores both.
        c.selectLayer(picture)
        tool("Transform")
        val tt = c.currentTool as TransformTool
        assertTrue(Smoke.pumpUntil { settle(1); tt.transformState != null })
        tt.moveBy(-120f, 0f)
        val steps = c.undoManager.undoCount
        click("Apply transform edit")
        assertTrue(Smoke.pumpUntil { settle(1); !tt.hasPendingWork })
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertNotEquals("re-flowed", item, TextCodec.decode(textLayer.textData))
        c.undo()
        assertEquals("one undo restores the text too", item, TextCodec.decode(textLayer.textData))
    }

    // ================================================================== exchange entries

    private fun exchangeEntries() {
        editor()
        click("More options")
        for (entry in listOf("Export SVG…", "Export PDF…", "Import SVG or PDF…")) {
            assertTrue("\"$entry\" in the menu", has(entry, exact = true))
            assertTrue("\"$entry\" enabled", SmokeUi.isEnabled(entry))
        }
        click("Export SVG…", exact = true)
        assertTrue("the export sheet", has("Export SVG", exact = true) && has("Save as…", exact = true) && has("Share", exact = true))
        closePanels()
    }

    // ================================================================== 360 dp

    /**
     * [label] is on the screen, inside its window's width, and either inside its height or in a
     * list that scrolls to it; its clickable area is finger-sized (40 dp or more on one side, 24 dp
     * on the other, as Material's dense chips).
     */
    /**
     * True when [label] is visible as it is; otherwise the list it is in is scrolled (as a finger
     * would) until it is, and false is returned (it needed scrolling).
     */
    private fun scrollIntoView(label: String): Boolean {
        // Wholly visible: not clipped by the list it is in.
        fun visible() = SmokeUi.find(label, exact = true)?.let { e ->
            e.bounds.width > 0f && e.bounds.height >= e.node.size.height - 1f && e.bounds.width >= e.node.size.width - 1f
        } == true
        if (visible()) return true
        val e = SmokeUi.find(label, exact = true) ?: throw AssertionError("\"$label\" not shown: ${SmokeUi.shown().take(60)}")
        var n: androidx.compose.ui.semantics.SemanticsNode? = e.node
        while (n != null && n.config.getOrNull(SemanticsActions.ScrollBy) == null) n = n.parent
        val scroll = requireNotNull(n?.config?.getOrNull(SemanticsActions.ScrollBy)?.action) { "\"$label\" is clipped and nothing scrolls to it" }
        val sideways = n?.config?.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null
        fun by(d: Float) { if (sideways) scroll.invoke(d, 0f) else scroll.invoke(0f, d) }
        // To the start first, then on (as a finger would look for it). Each scroll is animated
        // and a new one cancels the running one, so let each finish before the next.
        repeat(30) { by(-400f); settle(2) }
        repeat(40) {
            if (visible()) return false
            by(60f)
            settle(2)
        }
        throw AssertionError("\"$label\" never scrolled into view")
    }

    private fun assertReachable(label: String) {
        scrollIntoView(label)
        val e = SmokeUi.find(label, exact = true) ?: throw AssertionError("\"$label\" not shown at 360 dp: ${SmokeUi.shown().take(60)}")
        assertTrue("\"$label\" has a size: ${e.bounds}", e.bounds.width > 0f && e.bounds.height > 0f)
        val root = e.window
        val b = e.bounds
        var n: androidx.compose.ui.semantics.SemanticsNode? = e.node
        var scrollsV = false
        var scrollsH = false
        while (n != null) {
            if (n.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null) scrollsV = true
            if (n.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null) scrollsH = true
            n = n.parent
        }
        assertTrue("\"$label\" inside the width or in a row that scrolls to it: $b in ${root.width}", (b.left >= -0.5f && b.right <= root.width + 0.5f) || scrollsH)
        assertTrue("\"$label\" inside the height or in a list that scrolls to it: $b in ${root.height}", b.bottom <= root.height + 0.5f || scrollsV)
        // The target's own size (a tool-menu cell partly under the menu's edge is still a whole cell).
        val c = clickableSize(label)
        val dp = activity.resources.displayMetrics.density
        assertTrue("\"$label\" is finger-sized: $c", maxOf(c.width, c.height) >= 40f * dp - 0.5f && minOf(c.width, c.height) >= 24f * dp - 0.5f)
    }

    private fun at360dp() {
        org.robolectric.RuntimeEnvironment.setQualifiers("w360dp-h640dp-xhdpi")
        try {
            editor()
            assertEquals(720, activity.window.decorView.width)
            // The top bar: Vector (where Filters was) and the menu.
            assertReachable("Vector")
            assertReachable("More options")
            // The Tools grid: every tool of the request.
            click("Tools (current:")
            SmokeUi.assertPanelShown("Tools")
            for (t in listOf("Filters", "Masks", "Clone stamp", "Curve", "Text", "Transform", "Shape", "Lasso", "Bucket", "Eraser")) assertReachable(t)
            closePanels()
            // The menu's exchange entries.
            click("More options")
            for (entry in listOf("Export SVG…", "Export PDF…", "Import SVG or PDF…")) assertReachable(entry)
            // (The menu closes by picking an entry; the sheet it opens by its ✕.)
            click("Export PDF…", exact = true)
            assertReachable("Save as…")
            closePanels()
            assertEquals("the menu and the sheet are closed", 1, SmokeUi.windows().size)
            // The Masks strip, the Transform strip's options and X / Y, the curve's point thickness.
            tool("Masks")
            for (k in listOf("+ Linear", "+ Radial", "+ Brush")) assertReachable(k)
            val layer = c.doc.layers[1]
            seed(layer, 100f, 80f, 220f, 180f, 0xFF2266CC.toInt())
            tool("Transform")
            assertTrue(Smoke.pumpUntil { settle(1); (c.currentTool as TransformTool).transformState != null })
            for (label in listOf("Distort", "Delete")) assertReachable(label)
            for (s in listOf("X slider", "Y slider")) {
                val b = slider(s).bounds
                assertTrue("$s fits: $b", b.left >= 0f && b.right <= 720.5f && b.bottom <= 1280.5f)
            }
            (c.currentTool as TransformTool).discard()
            settle()
            tool("Curve")
            val curve = c.currentTool as CurveTool
            tap(80f, 150f)
            tap(320f, 150f)
            curve.select(1)
            settle()
            val thick = slider("Point thickness slider").bounds
            assertTrue("the point thickness slider fits: $thick", thick.left >= 0f && thick.right <= 720.5f && thick.bottom <= 1280.5f)
            curve.discard()
            settle()
        } finally {
            org.robolectric.RuntimeEnvironment.setQualifiers("w392dp-h873dp-xxhdpi")
        }
    }
}
