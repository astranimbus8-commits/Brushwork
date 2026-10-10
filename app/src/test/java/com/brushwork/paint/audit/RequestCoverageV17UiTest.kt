package com.brushwork.paint.audit

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.qa16.QaCurves
import com.brushwork.paint.qa16.item
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.pathfinder.PathfinderTool
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextKern
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.tools.vector.CornerStyle
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.common.CurveLabels17
import com.brushwork.paint.ui.common.ExpressionLabels
import com.brushwork.paint.ui.common.FolderLabels
import com.brushwork.paint.ui.common.KerningLabels
import com.brushwork.paint.ui.common.PathfinderLabels
import com.brushwork.paint.ui.common.PillLabels
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.common.SavedSelectionLabels
import com.brushwork.paint.ui.common.TransformLabels17
import com.brushwork.paint.ui.common.V17Tags
import com.brushwork.paint.ui.editor.HistoryLabels
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.ui.layers.LayerLabels
import com.brushwork.paint.ui.placement.MeshStepperLabels
import com.brushwork.paint.ui.tools.INCREMENTS_LABEL
import com.brushwork.paint.ui.vector.POINT_THICKNESS_LABEL
import com.brushwork.paint.vector.VPath
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertArrayEquals
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
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * REQUEST COVERAGE AUDIT (v1.7 §6.2): every item 1–20 of the user's v1.7 request, done the way
 * the user does it on the phone (fingers on the canvas, the tool menu, the options strip, the
 * X / Y pill, the selection bar, the layer window, dialogs), found by the I10 labels and the
 * V17Tags, and shown to keep its user-visible promise. Each item is one section in a fresh
 * editor; the items run in three groups, each at both QA sizes (392 dp, the user's ZTE Axon 50
 * Lite, and 360 dp): six classes, one UI test each, each its own sandbox.
 *
 * Setting up what an item starts from (pixels to work on, a selection, a text or shape layer) is
 * done in code as the area tests do; the item's own action always goes through the screen.
 */
internal class RequestCoverageV17 private constructor(private val h: ChromeHarness, private val widthDp: Float) {

    /** The three item groups, about seven sections each. */
    enum class Group { POINTS, OBJECTS, LAYERS }

    companion object {
        fun run(group: Group, widthDp: Float) {
            ShadowLog.stream = null
            SmokeUi.installTestRecomposer()
            val dog = Smoke.watchdog(limitMs = 60_000)
            val h = ChromeHarness()
            val t = RequestCoverageV17(h, widthDp)
            val at = "at ${widthDp.roundToInt()} dp"
            when (group) {
                Group.POINTS -> {
                    h.section("1 select and edit several points $at") { t.severalPoints() }
                    h.section("2 and 6 a shape corner's own roundness, then the shape as a path $at") { t.shapeCornerToPath() }
                    h.section("4 sharp points in a path $at") { t.sharpPoints() }
                    h.section("5 a path's point thickness counts at once $at") { t.pathLens() }
                    h.section("7 fill a path $at") { t.fillPath() }
                    h.section("19 extend a path from its start $at") { t.extendFromStart() }
                }
                Group.OBJECTS -> {
                    h.section("9, 12 and 13 the pill: steps, Scale X / Y, a whole curve moved and scaled, delete $at") { t.pillOnACurve() }
                    h.section("11 transform a text and a shape without rasterizing $at") { t.transformKeepsObjects() }
                    h.section("15 equations in numbers $at") { t.equations() }
                    h.section("16 free deform with a mesh $at") { t.freeDeform() }
                    h.section("17 kerning $at") { t.kerning() }
                }
                Group.LAYERS -> {
                    h.section("3 a live array $at") { t.liveArray() }
                    h.section("8 layer folders $at") { t.folders() }
                    h.section("10 two and three finger taps over the UI $at") { t.historyOverUi() }
                    h.section("14 saved selections $at") { t.savedSelections() }
                    h.section("18 symmetry rulers $at") { t.symmetry() }
                    h.section("20 pathfinder $at") { t.pathfinder() }
                }
            }
            dog.interrupt()
            ArrayDraw.clearCaches()
            h.finish()
        }

        private const val RED = 0xFFDD2211.toInt()
        private const val BLUE = 0xFF2244CC.toInt()
    }

    private lateinit var s: ChromeScreen
    private lateinit var ui: Qa16Ui
    private val c: EditorController get() = s.c

    // ================================================================== the editor and fingers

    private fun editor(doc: Document = Smoke.document(400, 300, layers = 2, whiteBottom = true)): ChromeScreen {
        s = h.editor(doc) { it.snapping.enabled = false }
        ui = Qa16Ui(s)
        settle()
        assertEquals("the phone is ${widthDp.roundToInt()} dp wide", widthDp, s.widthDp, 1f)
        return s
    }

    private fun tap(x: Float, y: Float) = ui.tap(x, y)

    private fun tap(p: Vec2) = ui.tap(p.x, p.y)

    /** Picks [label] in the tool menu, scrolling it as a finger would. */
    private fun tool(label: String) {
        ui.tool(label)
        settle()
    }

    /** Brings the control [label] wholly into view (scrolling its strip or sheet), then taps it. */
    private fun press(label: String, minDp: Float = 32f) {
        ui.reach(label, minDp)
        click(label, exact = true)
    }

    private fun steps(): Int = c.undoManager.undoCount

    /** The in-tool steps of [t] (undone and redone again). */
    private fun steps(t: CurveTool): Int {
        var n = 0
        while (t.undoStep()) n++
        repeat(n) { t.redoStep() }
        settle(2)
        return n
    }

    /** Pixels of [layer] with any alpha in [l, r) × [t, b). */
    private fun ink(layer: Layer, l: Int, t: Int, r: Int, b: Int): Int {
        var n = 0
        for (y in t until b) for (x in l until r) if (layer.bitmap.getPixel(x, y) ushr 24 > 0) n++
        return n
    }

    private fun count(layer: Layer, color: Int): Int {
        val b = layer.bitmap
        val px = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        return px.count { it == color }
    }

    private fun seed(layer: Layer, l: Float, t: Float, r: Float, b: Float, color: Int) {
        c.editWholeLayer(layer, "Seed") { bmp -> Canvas(bmp).drawRect(l, t, r, b, Paint().apply { this.color = color }) }
        settle()
    }

    private fun rectSelection(l: Float, t: Float, r: Float, b: Float): Selection =
        Selection.fromPath(Path().apply { addRect(l, t, r, b, Path.Direction.CW) }, c.doc.width, c.doc.height, antiAlias = false)

    /** The state description of [label]'s node, or of its nearest clickable ancestor. */
    private fun stateOf(label: String): String? {
        var n: SemanticsNode? = SmokeUi.find(label, exact = true)?.node
        n?.config?.getOrNull(SemanticsProperties.StateDescription)?.let { return it }
        while (n != null && n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent
        return n?.config?.getOrNull(SemanticsProperties.StateDescription)
    }

    /** The placed element whose content description is exactly [label]. */
    private fun described(label: String): RobolectricUi.Element? = RobolectricUi.elements().lastOrNull { e ->
        e.node.layoutInfo.isPlaced && e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it == label } == true
    }

    private fun applyEdit(label: String) {
        click(label)
        assertTrue("$label: done", Smoke.pumpUntil(10_000) { settle(1); !c.currentTool.hasPendingWork && c.busyMessage == null && !c.vectors.isRendering })
        settle()
    }

    private fun openLayers() {
        click("Open layers (active layer")
        Smoke.pump(600)
        settle()
        assertNotNull("the layer window is open", s.tagged(ChromeTags.LAYER_WINDOW))
    }

    // ================================================================== 1. several points

    private fun severalPoints() {
        editor()
        tool("Curve")
        val tool = c.currentTool as CurveTool
        for (p in listOf(60f to 150f, 130f to 90f, 200f to 170f, 270f to 90f, 340f to 150f)) tap(p.first, p.second)
        assertEquals("five points by five taps", 5, tool.pointCount)

        // "Select several": taps add points to the selection.
        press(PointLabels.SELECT_SEVERAL)
        assertTrue(tool.selectSeveral)
        assertTrue("its hint", has(PointLabels.SEVERAL_HINT, exact = true))
        tap(tool.pointAt(1))
        tap(tool.pointAt(3))
        assertEquals("two points picked by two taps", listOf(1, 3), tool.pointSelection.indices)

        // One typed thickness edits both, as ONE in-tool step.
        val n = steps(tool)
        press("Type $POINT_THICKNESS_LABEL")
        SmokeUi.typeAndDone(POINT_THICKNESS_LABEL, "50")
        assertEquals(listOf(1f, 0.5f, 1f, 0.5f, 1f), tool.anchors.map { it.width })
        assertEquals("one in-tool step", n + 1, steps(tool))

        // "Select all points": the values differ ("Mixed"); "*2" doubles each.
        press(PointLabels.SELECT_ALL)
        assertEquals(5, tool.pointSelection.count)
        assertTrue(has(PointLabels.DESELECT_ALL, exact = true))
        assertTrue("the thickness reads Mixed: ${SmokeUi.shown().take(60)}", has(PointLabels.MIXED, exact = true))
        press("Type $POINT_THICKNESS_LABEL")
        SmokeUi.typeAndDone(POINT_THICKNESS_LABEL, "*2")
        assertEquals(listOf(2f, 1f, 2f, 1f, 2f), tool.anchors.map { it.width })
        assertEquals(n + 2, steps(tool))
        Smoke.assertQuiet(c, "several points")
    }

    // ================================================================== 2 and 6. shape corners, then a path

    private fun shapeCornerToPath() {
        editor()
        tool("Shape")
        val tool = c.currentTool as ShapeTool
        tool.update { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE_FILL, corner = CornerStyle.SHARP, keepProportions = false, fromCenter = false) }
        ui.stroke(120f to 80f, 200f to 140f, 300f to 220f)
        assertTrue("a pending rectangle", tool.hasPendingWork)
        press("Points")
        assertTrue(tool.pointsMode)
        assertEquals(4, tool.pointCount)

        // 2: one corner, tapped, gets its own roundness.
        tap(tool.pointAt(0))
        assertEquals("the tapped corner", listOf(0), tool.pointSelection.indices)
        press("Type ${PointLabels.ROUNDNESS}")
        SmokeUi.typeAndDone(PointLabels.ROUNDNESS, "20")
        assertEquals("that corner only", listOf(20f, null, null, null), tool.docAnchors()!!.map { it.radius })

        // 6: "Turn into path": a path in a vector layer, open in the Path tool, one step.
        val before = steps()
        press(PointLabels.TO_PATH)
        assertTrue(Smoke.pumpUntil(10_000) { settle(1); !c.vectors.isRendering })
        settle()
        assertEquals(ToolId.PATH, c.activeToolId)
        assertEquals(HistoryLabels.TURN_INTO_PATH, c.undoManager.undoLabel)
        assertTrue("one step (the pending shape committed first)", steps() in before + 1..before + 2)
        val layer = c.activeLayer
        assertTrue(layer.isVectorLayer)
        assertNotNull("a NURBS path", (layer.vector!!.objects.single() as VPath).spline)
        val path = c.currentTool as CurveTool
        assertTrue("editable in the Path tool", path.isPath && path.pointCount >= 4)
        assertFalse("the converted corner is selected", path.pointSelection.isEmpty)
        assertTrue("its thickness and weight are there", has(POINT_THICKNESS_LABEL, exact = false))
        Smoke.assertQuiet(c, "shape corner to path")
    }

    // ================================================================== 4. sharp points

    private fun sharpPoints() {
        editor()
        tool("Path")
        val tool = c.currentTool as CurveTool
        for (p in listOf(60f to 150f, 130f to 80f, 200f to 200f, 270f to 80f, 340f to 150f)) tap(p.first, p.second)
        assertEquals(5, tool.pointCount)
        val mid = tool.spline!!.points[2]
        tap(mid.x, mid.y)
        assertEquals("the middle point, tapped", 2, tool.selectedPoint)
        press("Sharp corner")
        assertTrue("a corner", tool.spline!!.points[2].sharp)
        assertTrue(has("Smooth", exact = true))
        // The ends are always sharp: the chip says so and is off.
        val first = tool.spline!!.points[0]
        tap(first.x, first.y)
        assertEquals(0, tool.selectedPoint)
        assertTrue(has(PointLabels.ENDS_SHARP, exact = true))
        assertFalse(SmokeUi.isEnabled(PointLabels.ENDS_SHARP))
        Smoke.assertQuiet(c, "sharp points")
    }

    // ================================================================== 5. a path's thickness at once

    private fun pathLens() {
        editor()
        c.brush = c.brush.copy(size = 24f, opacity = 1f)
        tool("Path")
        val tool = c.currentTool as CurveTool
        tap(80f, 150f)
        tap(200f, 150f)
        tap(320f, 150f)
        assertEquals(3, tool.pointCount)
        // Both ends at 0 %, the middle at 100 %: the last point is selected after its tap.
        assertEquals(2, tool.selectedPoint)
        press("Type the point thickness")
        SmokeUi.typeAndDone("Thickness", "0")
        tap(80f, 150f)
        assertEquals(0, tool.selectedPoint)
        press("Type the point thickness")
        SmokeUi.typeAndDone("Thickness", "0")
        assertEquals(listOf(0f, 1f, 0f), tool.spline!!.points.map { it.width })
        applyEdit("Apply path edit")
        val layer = c.activeLayer
        val middle = ink(layer, 196, 120, 204, 180)
        val nearEnd = ink(layer, 96, 120, 104, 180)
        println("RequestCoverageV17 item 5 at $widthDp dp: ink at the middle $middle, near an end $nearEnd")
        assertTrue("three points draw a lens at once: the middle is drawn ($middle)", middle >= 8 * 6)
        assertTrue("thick in the middle ($middle), thin near the ends ($nearEnd)", middle > nearEnd * 3 / 2)
        Smoke.assertQuiet(c, "path lens")
    }

    // ================================================================== 7. fill a path

    private fun fillPath() {
        editor()
        tool("Path")
        val tool = c.currentTool as CurveTool
        tap(80f, 200f)
        tap(200f, 80f)
        ui.reach(CurveLabels17.FILL_ONLY, 32f)
        assertFalse("two points: no fill yet", SmokeUi.isEnabled(CurveLabels17.FILL_ONLY))
        tap(320f, 200f)
        press(CurveLabels17.FILL_ONLY)
        assertTrue(tool.settings.fill)
        applyEdit("Apply path edit")
        val layer = c.activeLayer
        // The curve's middle is (200, 140): between it and the chord is filled, above it not.
        assertTrue("filled inside", layer.bitmap.getPixel(200, 170) ushr 24 > 0)
        assertEquals("nothing above the curve", 0, layer.bitmap.getPixel(200, 110) ushr 24)
        Smoke.assertQuiet(c, "fill a path")
    }

    // ================================================================== 19. extend a path from its start

    private fun extendFromStart() {
        editor()
        c.brush = c.brush.copy(size = 10f, opacity = 1f)
        tool("Path")
        val tool = c.currentTool as CurveTool
        tap(100f, 150f)
        tap(300f, 150f)
        tap(100f, 150f)
        assertEquals("the first point, tapped", 0, tool.selectedPoint)
        tap(60f, 60f)
        assertEquals("a tap grows the path from its start", listOf(60f, 100f, 300f), tool.spline!!.points.map { it.x })
        assertEquals("the new start stays selected", 0, tool.selectedPoint)
        tap(40f, 220f)
        assertEquals(listOf(40f, 60f, 100f, 300f), tool.spline!!.points.map { it.x })
        applyEdit("Apply path edit")
        assertTrue("drawn from the new start", ink(c.activeLayer, 32, 212, 48, 228) > 0)
        Smoke.assertQuiet(c, "extend from start")
    }

    // ================================================================== 9, 12 and 13. the pill on a curve

    private fun pillOnACurve() {
        editor()
        tool("Curve")
        val tool = c.currentTool as CurveTool
        tap(100f, 200f)
        tap(200f, 100f)
        tap(300f, 200f)
        assertEquals(3, tool.pointCount)

        // 9: the "#" cell shows its step; row 2 has Scale X / Y, the chain and their own "#".
        assertEquals("the step next to #", "Off, step 10 px", described(INCREMENTS_LABEL)?.stateDescription)
        assertNotNull("the Scale row", s.tagged(V17Tags.PILL_SCALE_ROW))
        for (l in listOf(PillLabels.SCALE_X, PillLabels.SCALE_Y, PillLabels.KEEP_PROPORTIONS, PillLabels.SCALE_INCREMENTS)) assertTrue(l, has(l, exact = true))
        click(PillLabels.SCALE_INCREMENTS, exact = true)
        assertTrue("increments on", c.increments.enabled)
        assertEquals("On, step 10 px", described(INCREMENTS_LABEL)?.stateDescription)
        click(INCREMENTS_LABEL, exact = true)
        assertFalse(c.increments.enabled)

        // 12: every point selected, the pill moves and scales the whole curve.
        press(PointLabels.SELECT_ALL)
        assertEquals(3, tool.pointSelection.count)
        assertTrue("the pill names them", has(CurveTool.SELECTED_POINTS_LABEL, exact = true))
        val before = tool.anchors.map { it.pos }
        val cx = tool.pillPosition.position!!.x
        val target = cx.roundToInt() + 40
        val n = steps(tool)
        click("Type X")
        SmokeUi.typeAndDone("X", "$target")
        val dx = target - cx
        for ((i, p) in tool.anchors.withIndex()) {
            assertEquals("point $i moved with the others", before[i].x + dx, p.pos.x, 0.01f)
            assertEquals(before[i].y, p.pos.y, 0.01f)
        }
        assertEquals("one in-tool step", n + 1, steps(tool))
        fun box(): Pair<Float, Float> = tool.anchors.let { a -> (a.maxOf { it.pos.x } - a.minOf { it.pos.x }) to (a.maxOf { it.pos.y } - a.minOf { it.pos.y }) }
        val (w, hh) = box()
        click("Type ${PillLabels.SCALE_X}")
        SmokeUi.typeAndDone(PillLabels.SCALE_X, "200")
        val (w2, h2) = box()
        assertEquals("twice as wide", w * 2f, w2, 0.05f)
        assertEquals("and as high (proportions kept)", hh * 2f, h2, 0.05f)
        assertEquals(n + 2, steps(tool))

        // 13: the trash cell beside the pill: selected points, then the whole curve.
        press(PointLabels.DESELECT_ALL)
        assertTrue(tool.pointSelection.isEmpty)
        assertNotNull(s.tagged(V17Tags.PILL_TRASH))
        assertTrue(has(PillLabels.deleteObject("curve"), exact = true))
        tap(tool.pointAt(1))
        assertEquals(listOf(1), tool.pointSelection.indices)
        click(PillLabels.DELETE_POINTS, exact = true)
        assertEquals("one tap deletes the selected point", 2, tool.pointCount)
        click(PillLabels.deleteObject("curve"), exact = true)
        settle()
        assertFalse("the curve is gone", tool.hasPendingWork)
        assertNull("nothing to delete: no trash cell", s.tagged(V17Tags.PILL_TRASH))
        Smoke.assertQuiet(c, "the pill on a curve")
    }

    // ================================================================== 11. objects stay objects

    private fun transformKeepsObjects() {
        editor()
        val item = TextItem("Hello", spec = TextSpec(sizePx = 64f), cx = 200f, cy = 150f)
        val text = c.addLayerWithContent("Text", "Add text", textData = TextCodec.encode(item)) { cv: Canvas ->
            TextRenderer.drawItem(cv, item, TextRenderer.prepare(item), null)
        }!!
        settle()
        tool("Transform")
        val tt = c.currentTool as TransformTool
        assertTrue(Smoke.pumpUntil { settle(1); tt.transformState != null })
        assertEquals("a text is transformed as a text", TransformTool.Lifted.TEXT, tt.lifted)
        assertEquals("Distort says it would rasterize", TransformLabels17.RASTERIZE_TO_DEFORM, stateOf("Distort"))
        var before = steps()
        press("Rotate 90° clockwise")
        applyEdit("Apply transform edit")
        assertEquals("one step", before + 1, steps())
        val turned = text.item()
        assertEquals("still the text", "Hello", turned.text)
        val deg = ((turned.rotationDeg % 360f) + 360f) % 360f
        assertTrue("a quarter turn: $deg", abs(deg - 90f) < 0.5f || abs(deg - 270f) < 0.5f)
        assertEquals("the same size of type", 64f, turned.spec.sizePx, 0.05f)

        // A shape layer: scaled through the pill, it stays a shape.
        val shape = ShapeObject(ShapeType.RECTANGLE, cx = 150f, cy = 90f, w = 100f, h = 60f, style = ShapeStyle.FILL, fillColor = RED)
        val shapeLayer = c.addLayerWithContent("Shape", "Add shape", shapeData = ShapeCodec.encode(shape)) { cv ->
            cv.drawRect(100f, 60f, 200f, 120f, Paint().apply { color = RED })
        }!!
        settle()
        tool("Brush")
        tool("Transform")
        val t2 = c.currentTool as TransformTool
        assertTrue(Smoke.pumpUntil { settle(1); t2.transformState != null })
        assertEquals(TransformTool.Lifted.SHAPE, t2.lifted)
        before = steps()
        click("Type ${PillLabels.SCALE_X}")
        SmokeUi.typeAndDone(PillLabels.SCALE_X, "200")
        applyEdit("Apply transform edit")
        assertEquals(before + 1, steps())
        val scaled = ShapeCodec.decode(shapeLayer.shapeData) ?: throw AssertionError("the layer is no longer a shape")
        assertEquals("twice as wide", 200f, scaled.w, 0.5f)
        assertEquals("twice as high", 120f, scaled.h, 0.5f)
        Smoke.assertQuiet(c, "objects stay objects")
    }

    // ================================================================== 15. equations

    private fun equations() {
        editor()
        seed(c.activeLayer, 120f, 100f, 200f, 160f, RED)
        tool("Transform")
        val tt = c.currentTool as TransformTool
        assertTrue(Smoke.pumpUntil { settle(1); tt.transformState != null })
        click("Type X")
        assertNotNull("the operator keys", s.tagged(V17Tags.OPERATOR_KEYS))
        for (k in listOf(ExpressionLabels.PLUS, ExpressionLabels.MINUS, ExpressionLabels.TIMES, ExpressionLabels.DIVIDED)) assertTrue(k, has(k, exact = true))
        SmokeUi.field("X").type("100/2")
        settle(4)
        assertTrue("the result shows: ${SmokeUi.shown().take(60)}", SmokeUi.shown().any { it.startsWith("= 50") })
        SmokeUi.typeAndDone("X", "100/2")
        assertEquals("100/2 is 50", 50f, tt.anchorPosition!!.x, 1e-3f)
        // A leading "/" applies to the current value.
        click("Type X")
        SmokeUi.typeAndDone("X", "/2")
        assertEquals("/2 halves it", 25f, tt.anchorPosition!!.x, 1e-3f)
        val before = steps()
        applyEdit("Apply transform edit")
        assertEquals(before + 1, steps())
        Smoke.assertQuiet(c, "equations")
    }

    // ================================================================== 16. free deform

    private fun freeDeform() {
        editor()
        val layer = c.activeLayer
        seed(layer, 100f, 80f, 160f, 180f, RED)
        seed(layer, 160f, 80f, 220f, 180f, BLUE)
        tool("Transform")
        val tt = c.currentTool as TransformTool
        assertTrue(Smoke.pumpUntil { settle(1); tt.transformState != null })
        press(TransformLabels17.FREE_DEFORM)
        assertEquals(TransformTool.Mode.MESH, tt.mode)
        assertNotNull("the mesh controls", s.tagged(V17Tags.MESH))
        press(MeshStepperLabels.FEWER_COLUMNS)
        press(MeshStepperLabels.FEWER_ROWS)
        assertEquals("2 x 2 cells", 9, tt.pointCount)
        val centre = tt.pointAt(4)
        assertEquals(160f, centre.x, 1.5f)
        assertEquals(BLUE, layer.bitmap.getPixel(172, centre.y.roundToInt()))
        QaCurves.drag(s, centre, Vec2(20f, 0f))
        assertTrue("the mesh is deformed", tt.isMeshChanged)
        assertEquals("the vertex followed the finger", centre.x + 20f, tt.pointAt(4).x, 2f)
        val before = steps()
        applyEdit("Apply transform edit")
        assertEquals("ONE step", before + 1, steps())
        assertEquals(TransformLabels17.FREE_DEFORM, c.undoManager.undoLabel)
        assertEquals("the red half now reaches the moved vertex", RED, layer.bitmap.getPixel(172, centre.y.roundToInt()))
        Smoke.assertQuiet(c, "free deform")
    }

    // ================================================================== 17. kerning

    /** Puts the text field's cursor or selection at [start, end), as a finger does. */
    private fun selectText(start: Int, end: Int) {
        SmokeUi.field("Text").focus()
        settle(2)
        val set = SmokeUi.field("Text").node.config.getOrNull(SemanticsActions.SetSelection)?.action
            ?: throw AssertionError("the text field has no selection action")
        set(start, end, false)
        settle()
    }

    private fun kerning() {
        editor()
        tool("Text")
        val text = c.currentTool as TextTool
        tap(200f, 150f)
        assertTrue("the text editor", text.editorOpen)
        SmokeUi.field("Text").type("AVATAR")
        settle()
        selectText(1, 1)
        assertTrue("the gap named: ${SmokeUi.shown().take(80)}", has(KerningLabels.between("A", "V"), exact = true))
        val increase = "Increase ${KerningLabels.KERNING}"
        press(increase)
        assertEquals(listOf(TextKern(0, 10)), text.item!!.kerns)
        SmokeUi.typeAndDone(KerningLabels.KERNING, "-80")
        assertEquals(listOf(TextKern(0, -80)), text.item!!.kerns)
        // The font's own kerning switches off and on.
        assertTrue(text.item!!.spec.fontKerning)
        press(KerningLabels.FONT_KERNING)
        assertFalse(text.item!!.spec.fontKerning)
        press(KerningLabels.FONT_KERNING)
        assertTrue(text.item!!.spec.fontKerning)
        click("OK", exact = true)
        click("Apply text edit")
        settle()
        val layer = c.activeLayer
        assertTrue("a text layer", layer.isTextLayer)
        assertEquals("the kern is kept with the text", listOf(TextKern(0, -80)), layer.item().kerns)
        Smoke.assertQuiet(c, "kerning")
    }

    // ================================================================== 3. a live array

    private fun liveArray() {
        editor()
        seed(c.activeLayer, 60f, 60f, 100f, 100f, RED)
        c.setSelection(rectSelection(50f, 50f, 110f, 110f), recordUndo = false)
        settle(4)
        val before = steps()
        press(ArrayLabels.FROM_SELECTION)
        assertEquals("one step", before + 1, steps())
        assertEquals(HistoryLabels.ARRAY, c.undoManager.undoLabel)
        val layer = c.activeLayer
        assertNotNull("a live array", layer.array)
        assertEquals(ToolId.ARRAY, c.activeToolId)
        assertNotNull("the Array sheet", s.tagged(V17Tags.ARRAY_SHEET))
        assertEquals(3, layer.array!!.spec.count)
        assertTrue("the copies are drawn", Smoke.pumpUntil(10_000) { settle(1); count(layer, RED) >= 3 * 1600 })
        val three = count(layer, RED)
        // Still editable: one more copy appears at once.
        press("Increase Count")
        assertEquals(4, layer.array!!.spec.count)
        assertEquals(HistoryLabels.EDIT_ARRAY, c.undoManager.undoLabel)
        assertTrue("a fourth copy ($three red px for three)", Smoke.pumpUntil(10_000) { settle(1); count(layer, RED) >= three * 4 / 3 - 4 })
        Smoke.assertQuiet(c, "live array")
    }

    // ================================================================== 8. folders

    private fun folders() {
        editor()
        val (l1, l2) = c.doc.layers
        openLayers()
        val before = steps()
        click(LayerLabels.SPECIAL, exact = true)
        click(FolderLabels.NEW, exact = true)
        assertEquals("one step", before + 1, steps())
        assertEquals(HistoryLabels.NEW_FOLDER, c.undoManager.undoLabel)
        val folder = c.doc.layers.last()
        assertTrue(folder.isFolder)
        assertEquals("Folder 1", folder.name)
        assertEquals("above the active layer", listOf(l1, l2, folder), c.doc.layers)
        // The layer below goes into it from its menu.
        click(LayerLabels.selectRow(2), exact = true)
        assertEquals(l2, c.activeLayer)
        click(LayerLabels.MORE, exact = true)
        click(FolderLabels.MOVE_IN, exact = true)
        assertEquals("in the folder", folder.id, l2.parentId)
        assertEquals(HistoryLabels.MOVE_INTO_FOLDER, c.undoManager.undoLabel)
        // The folder closes and opens, no step.
        val n = steps()
        click(FolderLabels.close("Folder 1"), exact = true)
        assertTrue(has(FolderLabels.open("Folder 1"), exact = true))
        assertFalse("a closed folder hides its rows", has(LayerLabels.selectRow(2), exact = true))
        click(FolderLabels.open("Folder 1"), exact = true)
        assertTrue(has(LayerLabels.selectRow(2), exact = true))
        assertEquals("no step", n, steps())
        click(LayerLabels.CLOSE, exact = true)
        Smoke.assertQuiet(c, "folders")
    }

    // ================================================================== 10. history taps over the UI

    private fun historyOverUi() {
        editor()
        for (y in listOf(60f, 120f, 180f)) ui.stroke(80f to y, 200f to y + 10f, 320f to y + 20f)
        assertEquals("three strokes", 3, steps())
        fun at(label: String): Pair<Float, Float> {
            val r = Finger.control(s, label) ?: throw AssertionError("no \"$label\"; shown: ${SmokeUi.shown().take(60)}")
            return Finger.px(s, r.center.x, r.center.y)
        }
        // Two fingers on two buttons: one undo, neither button fires.
        s.touch.idle(400)
        s.touch.twoFingerTap(at("Open brush settings"), at("Open color picker"))
        settle()
        assertEquals("one undo", 2, steps())
        assertTrue("the feedback", SmokeUi.shown().any { it.startsWith("Undo: ") })
        assertTrue("nothing opened", SmokeUi.sheetTitles().isEmpty() && SmokeUi.pillTitles().isEmpty())
        // Three fingers on the bottom bar: one redo, no button fires.
        val bar = s.tagged(ChromeTags.BOTTOM_BAR) ?: throw AssertionError("no bottom bar")
        fun barAt(f: Float) = Finger.px(s, bar.left + bar.width * f, bar.center.y)
        s.touch.idle(400)
        s.touch.threeFingerTap(barAt(0.2f), barAt(0.5f), barAt(0.8f))
        settle()
        assertEquals("one redo", 3, steps())
        assertEquals(ToolId.BRUSH, c.activeToolId)
        assertNull("no tool menu", s.tagged(ChromeTags.TOOL_MENU))
        assertNull("no layer window", s.tagged(ChromeTags.LAYER_WINDOW))
        assertTrue(SmokeUi.sheetTitles().isEmpty() && SmokeUi.pillTitles().isEmpty())
        Smoke.assertQuiet(c, "history taps over the UI")
    }

    // ================================================================== 14. saved selections

    private fun savedSelections() {
        editor()
        val sel = rectSelection(40f, 40f, 160f, 120f)
        c.setSelection(sel, recordUndo = false)
        settle(4)
        val want = BitmapUtils.alpha8ToBytes(c.selection!!.mask)
        press(SavedSelectionLabels.SAVE)
        assertEquals(1, c.doc.savedSelections.size)
        val saved = c.doc.savedSelections.single()
        assertEquals("Selection 1", saved.name)
        assertEquals(HistoryLabels.SAVE_SELECTION, c.undoManager.undoLabel)
        press("Clear the selection")
        assertNull(c.selection)
        // The layer window lists it; its menu loads it back.
        openLayers()
        assertNotNull("its row", s.tagged(V17Tags.savedSelectionRow(saved.id)))
        click("Selection 1", exact = true)
        click(SavedSelectionLabels.LOAD, exact = true)
        assertTrue("loaded", Smoke.pumpUntil { settle(1); c.selection != null })
        assertArrayEquals("the same pixels", want, BitmapUtils.alpha8ToBytes(c.selection!!.mask))
        click(LayerLabels.CLOSE, exact = true)
        Smoke.assertQuiet(c, "saved selections")
    }

    // ================================================================== 18. symmetry

    private fun symmetry() {
        editor()
        c.brush = c.brush.copy(size = 10f, opacity = 1f)
        tool("Symmetry")
        assertEquals("the mirror is on", SymmetryType.MIRROR, c.symmetry.type)
        for (t in SymmetryType.entries) assertTrue("ruler \"${t.label}\"", has(t.label, exact = true))
        click("Done", exact = true)
        assertEquals("back to the brush", ToolId.BRUSH, c.activeToolId)
        val layer = c.activeLayer
        ui.stroke(60f to 150f, 100f to 140f, 140f to 150f)
        assertTrue("the stroke", ink(layer, 60, 130, 140, 170) > 50)
        assertTrue("and its mirror across the middle", ink(layer, 260, 130, 340, 170) > 50)
        Smoke.assertQuiet(c, "symmetry")
    }

    // ================================================================== 20. pathfinder

    private fun shapeLayer(l: Float, t: Float, r: Float, b: Float, color: Int): Layer {
        val o = ShapeObject(ShapeType.RECTANGLE, cx = (l + r) / 2f, cy = (t + b) / 2f, w = r - l, h = b - t, style = ShapeStyle.FILL, fillColor = color)
        return c.addLayerWithContent("Shape", "Add shape", shapeData = ShapeCodec.encode(o)) { cv ->
            cv.drawRect(l, t, r, b, Paint().apply { this.color = color })
        }!!
    }

    private fun pathfinder() {
        editor(Smoke.document(400, 300, layers = 1, whiteBottom = true))
        val a = shapeLayer(60f, 60f, 180f, 180f, RED)
        val b = shapeLayer(140f, 60f, 260f, 180f, BLUE)
        settle()
        val layersBefore = c.doc.layers.toList()
        tool("Pathfinder")
        val pf = c.currentTool as PathfinderTool
        pf.computeDispatcher = Dispatchers.Unconfined
        press(PathfinderLabels.SELECT_ALL)
        assertEquals(2, pf.count)
        assertTrue(has(PathfinderLabels.picked(2), exact = true))
        val before = steps()
        press(PathfinderLabels.UNITE, 40f)
        assertTrue("united", Smoke.pumpUntil(10_000) { settle(1); c.doc.layers.any { it.name == PathfinderLabels.resultLayer(1) } })
        settle()
        assertEquals("one step", before + 1, steps())
        assertEquals(HistoryLabels.pathfinder("Unite"), c.undoManager.undoLabel)
        assertEquals(listOf("Layer 1", PathfinderLabels.resultLayer(1)), c.doc.layers.map { it.name })
        assertFalse("both shapes are gone", a in c.doc.layers || b in c.doc.layers)
        val united = c.doc.layers.last()
        assertTrue(united.isVectorLayer)
        // One undo (the top row's Undo) brings both shapes back.
        click("Undo", exact = true)
        assertEquals(layersBefore, c.doc.layers)
        Smoke.assertQuiet(c, "pathfinder")
    }
}

// ====================================================================== the six classes

/** Items 1, 2, 4, 5, 6, 7, 19 (points and paths) on the user's phone (392 dp). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.audit.requestv17pointssandbox"])
class RequestCoverageV17UiTest {
    @Test
    fun pointsAndPathsKeepTheirPromiseAt392dp() = RequestCoverageV17.run(RequestCoverageV17.Group.POINTS, 392f)
}

/** Items 1, 2, 4, 5, 6, 7, 19 at 360 dp. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.audit.requestv17points360sandbox"])
class RequestCoverageV17Points360UiTest {
    @Test
    fun pointsAndPathsKeepTheirPromiseAt360dp() = RequestCoverageV17.run(RequestCoverageV17.Group.POINTS, 360f)
}

/** Items 9, 11, 12, 13, 15, 16, 17 (the pill, numbers, transforms, text) at 392 dp. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.audit.requestv17objectssandbox"])
class RequestCoverageV17ObjectsUiTest {
    @Test
    fun objectsKeepTheirPromiseAt392dp() = RequestCoverageV17.run(RequestCoverageV17.Group.OBJECTS, 392f)
}

/** Items 9, 11, 12, 13, 15, 16, 17 at 360 dp. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.audit.requestv17objects360sandbox"])
class RequestCoverageV17Objects360UiTest {
    @Test
    fun objectsKeepTheirPromiseAt360dp() = RequestCoverageV17.run(RequestCoverageV17.Group.OBJECTS, 360f)
}

/** Items 3, 8, 10, 14, 18, 20 (layers, history taps, tools) at 392 dp. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.audit.requestv17layerssandbox"])
class RequestCoverageV17LayersUiTest {
    @Test
    fun layersAndToolsKeepTheirPromiseAt392dp() = RequestCoverageV17.run(RequestCoverageV17.Group.LAYERS, 392f)
}

/** Items 3, 8, 10, 14, 18, 20 at 360 dp. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.audit.requestv17layers360sandbox"])
class RequestCoverageV17Layers360UiTest {
    @Test
    fun layersAndToolsKeepTheirPromiseAt360dp() = RequestCoverageV17.run(RequestCoverageV17.Group.LAYERS, 360f)
}
