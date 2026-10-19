package com.brushwork.paint.qa19

import android.content.Context
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.toSize
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.UiPrefs
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.live.StrokePreview
import com.brushwork.paint.filters.art.CaBlend
import com.brushwork.paint.filters.art.ChromaticAberrationMovingFilter
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa18.Fingers18
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.tools.text.TextBoxSpec
import com.brushwork.paint.tools.text.TextJustify
import com.brushwork.paint.tools.text.TextKerningMode
import com.brushwork.paint.tools.text.TextListType
import com.brushwork.paint.tools.text.TextPathNodeEditor
import com.brushwork.paint.tools.text.TextPathNodeEntry
import com.brushwork.paint.tools.text.TextPathType
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.CurveLabels17
import com.brushwork.paint.ui.common.EraseLabels19
import com.brushwork.paint.ui.common.LayerLabels19
import com.brushwork.paint.ui.common.PlaceLabels19
import com.brushwork.paint.ui.common.PointLabels19
import com.brushwork.paint.ui.common.StabilityLabels19
import com.brushwork.paint.ui.common.StripLabels18
import com.brushwork.paint.ui.common.TextLabels19
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.common.V19Tags
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.placement.LIST_TYPE_TAG_PREFIX
import com.brushwork.paint.ui.placement.TEXT_PATH_ENTRY_TAG_PREFIX
import com.brushwork.paint.ui.points.ThicknessRowLabels
import com.brushwork.paint.ui.vector.ShapeStripItems
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.draw.VectorEraseMode
import com.brushwork.paint.vector.draw.VectorEraserModes
import com.brushwork.paint.vector.select.ObjectEdits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * v1.9 F3 (R3 (a), (e)): the cases of the sticky-rows tests ([Qa19StickyRowsUiTest] at 392 dp,
 * [Qa19StickyRowsNarrowUiTest] at 360 dp and the font scales of I27), on the real editor with
 * the Shape tool, whose options strip has a More row at both widths, drawing on the raster layer
 * ("Editable" off: a committed shape is one step of pixels; a drag leaves it pending, editable,
 * until it is committed, [drawShape]). Positions are dp of the editor; touches are real events
 * through the editor's window.
 */
internal object Qa19StickyRows {
    /** The Shape tool's own row: what the tests open, by its toggle. */
    val TOOL = ToolId.SHAPE

    /**
     * The editor on screen with [TOOL] active, at the device's [fontScale], on a tall artwork
     * (300 × 600) whose fit is bound by the height at 392 and 360 dp, so its top is the fit
     * inset's top plus the fit margin, and a row opened after the fit lies over it. Every editor
     * a test opens starts with empty [UiPrefs] (`Smoke.controller`); a [relaunch] keeps them as
     * the last editor left them, as the file outlives the app's process.
     */
    fun editor(h: Qa19Harness, fontScale: Float = 1f, relaunch: Boolean = false): ChromeScreen {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val rows = UiPrefs(app).stripOpenRows
        val objectRow = UiPrefs(app).objectBarMoreOpen
        val s = h.editor(Smoke.document(300, 600, layers = 2, whiteBottom = true), fontScale = fontScale) { c ->
            if (relaunch) {
                UiPrefs(app).stripOpenRows = rows
                UiPrefs(app).objectBarMoreOpen = objectRow
            }
            c.snapping.enabled = false
            c.selectTool(TOOL)
        }
        (s.c.tools.getValue(TOOL) as ShapeTool).update { it.copy(editable = false) }
        settle()
        assertEquals(TOOL, s.c.activeToolId)
        return s
    }

    fun settle() {
        Smoke.pump(300)
        SmokeUi.settle()
    }

    /** The strip's toggle says Expanded. */
    fun expanded(s: ChromeScreen): Boolean = Fingers18.state(s, StripLabels18.MORE_TOGGLE) == StripLabels18.EXPANDED

    /** The strip's More row (dp), or null when it does not show. */
    fun row(s: ChromeScreen): Rect? = s.tagged(V18Tags.STRIP_MORE_ROW)

    /** [TOOL]'s row is remembered open ([UiPrefs.stripOpenRows]). */
    fun remembered(s: ChromeScreen): Boolean = TOOL.name in UiPrefs(s.activity).stripOpenRows

    /** Opens the row by its toggle, unless it shows already (a remembered row). */
    fun open(s: ChromeScreen) {
        if (!expanded(s)) Finger.tap(s, StripLabels18.MORE_TOGGLE)
        assertOpen(s, "opened by its toggle")
    }

    /** Closes the row by its toggle (the only way, v1.9), so the next section starts with nothing remembered. */
    fun closeByToggle(s: ChromeScreen) {
        assertTrue("open before its toggle closes it", expanded(s))
        Finger.tap(s, StripLabels18.MORE_TOGGLE)
        settle()
        assertNull("the toggle closes the row", row(s))
        assertFalse("…collapsed", expanded(s))
        assertFalse("…and forgets it", remembered(s))
    }

    fun assertOpen(s: ChromeScreen, why: String) {
        settle()
        assertNotNull("$why: the More row shows", row(s))
        assertTrue("$why: expanded", expanded(s))
        assertTrue("$why: remembered", remembered(s))
    }

    /** The row's lines (dp): the bounds of the clickables on it, one rect per line, top first. */
    fun lines(s: ChromeScreen): List<Rect> {
        val r = row(s) ?: throw AssertionError("no More row")
        val controls = s.placed().mapNotNull { e ->
            if (e.node.config.getOrNull(SemanticsActions.OnClick) == null) return@mapNotNull null
            val b = s.dp(e.bounds)
            b.takeIf { it.width > 0f && it.center.y > r.top && it.center.y < r.bottom && it.center.x > r.left && it.center.x < r.right }
        }
        assertTrue("the More row shows controls", controls.isNotEmpty())
        return controls.groupBy { (it.center.y / 8f).toInt() }.values.map { line ->
            Rect(line.minOf { it.left }, line.minOf { it.top }, line.maxOf { it.right }, line.maxOf { it.bottom })
        }.sortedBy { it.top }
    }

    /** Window px of editor dp. */
    fun px(s: ChromeScreen, x: Float, y: Float): Pair<Float, Float> = (s.root.left + x * s.density) to (s.root.top + y * s.density)

    /** One finger from ([x0], [y0]) to ([x1], [y1]) (dp), slowly, in the editor's window. */
    fun drag(s: ChromeScreen, x0: Float, y0: Float, x1: Float, y1: Float) {
        val a = px(s, x0, y0)
        val b = px(s, x1, y1)
        s.touch.stroke(a, ((a.first + b.first) / 2f) to ((a.second + b.second) / 2f), b)
        settle()
    }

    /**
     * A shape dragged from ([x0], [y0]) to ([x1], [y1]) (dp): the drag reached the canvas when it
     * leaves the shape pending; it is then committed as its check mark or a tap outside it would
     * (so the next drag never lands on its handles).
     */
    fun drawShape(s: ChromeScreen, why: String, x0: Float, y0: Float, x1: Float, y1: Float) {
        val tool = s.c.tools.getValue(TOOL) as ShapeTool
        assertNull("$why: no shape pending before the drag", tool.box)
        drag(s, x0, y0, x1, y1)
        assertNotNull("$why: the drag drew a shape", tool.box)
        tool.commit()
        settle()
        assertNull("$why: …committed", tool.box)
    }

    /** Where the canvas shows document point ([x], [y]), dp of the editor. */
    fun onScreen(s: ChromeScreen, x: Float, y: Float): Pair<Float, Float> {
        val (px, py) = s.screen(x, y)
        return (px - s.root.left) / s.density to (py - s.root.top) / s.density
    }

    /** The artwork's bounds on screen (dp). */
    fun artwork(s: ChromeScreen): RectF {
        val (l, t) = onScreen(s, 0f, 0f)
        val (r, b) = onScreen(s, s.c.doc.width.toFloat(), s.c.doc.height.toFloat())
        return RectF(l, t, r, b)
    }

    /** The view (zoom, rotation and where the artwork's corner is), to see it never moved. */
    fun view(s: ChromeScreen): List<Float> {
        val (x, y) = s.screen(0f, 0f)
        return listOf(s.c.viewTransform.zoom, s.c.viewTransform.rotationDeg, x, y)
    }

    /** No sheet, dialog or popup over the editor, and the tool unchanged: nothing on the row acted. */
    fun assertNothingActed(s: ChromeScreen, why: String) {
        assertEquals("$why: no window over the editor", 1, SmokeUi.windows().size)
        assertEquals("$why: the tool stays", TOOL, s.c.activeToolId)
    }

    /**
     * Two fingers: the first lands on the canvas at ([x0], [y0]), the second on the row at
     * ([x1], [y1]) (dp); they spread apart along the line between them, the canvas' finger
     * 120 dp and the row's 10 dp (a pinch that zooms in), and lift, the row's finger first, or the
     * canvas' first when [canvasLiftsFirst] (the row's finger is then the gesture's last, its lift
     * seen by the row only).
     */
    fun pinchFrom(s: ChromeScreen, x0: Float, y0: Float, x1: Float, y1: Float, canvasLiftsFirst: Boolean = false) {
        val t = s.touch
        val (ax, ay) = px(s, x0, y0)
        val (bx, by) = px(s, x1, y1)
        val len = kotlin.math.hypot(ax - bx, ay - by)
        val ux = (ax - bx) / len
        val uy = (ay - by) / len
        val far = 120f * s.density
        val near = 10f * s.density
        t.send(MotionEvent.ACTION_DOWN, Smoke.P(0, ax, ay))
        t.idle(16)
        t.send(MotionEvent.ACTION_POINTER_DOWN, Smoke.P(0, ax, ay), Smoke.P(1, bx, by), index = 1)
        var canvas = Smoke.P(0, ax, ay)
        var onRow = Smoke.P(1, bx, by)
        for (i in 1..12) {
            t.idle(16)
            val k = i / 12f
            canvas = Smoke.P(0, ax + ux * far * k, ay + uy * far * k)
            onRow = Smoke.P(1, bx - ux * near * k, by - uy * near * k)
            t.send(MotionEvent.ACTION_MOVE, canvas, onRow)
        }
        t.idle(16)
        t.send(MotionEvent.ACTION_POINTER_UP, canvas, onRow, index = if (canvasLiftsFirst) 0 else 1)
        t.idle(16)
        t.send(MotionEvent.ACTION_UP, if (canvasLiftsFirst) onRow else canvas)
        t.idle(50)
        settle()
    }

    /**
     * A switch on the row (its last control with a state) takes a real tap, and a second one
     * puts it back: the row's controls act again once the canvas gesture is over.
     */
    fun assertRowTakesTaps(s: ChromeScreen, why: String) {
        val r = row(s) ?: throw AssertionError("$why: no More row")
        fun switchAt(at: Offset?): Pair<Offset, String>? = s.placed().mapNotNull { e ->
            val state = e.node.config.getOrNull(SemanticsProperties.StateDescription) ?: return@mapNotNull null
            if (e.node.config.getOrNull(SemanticsActions.OnClick) == null) return@mapNotNull null
            val b = s.dp(e.bounds)
            if (b.width <= 0f || !r.contains(b.center) || (at != null && !b.contains(at))) null else b.center to state
        }.lastOrNull()
        val (at, before) = switchAt(null) ?: throw AssertionError("$why: a switch on the row")
        for ((n, expected) in listOf(1 to false, 2 to true)) {
            s.touch.idle(300)
            val (x, y) = px(s, at.x, at.y)
            s.touch.tap(x, y)
            settle()
            val now = switchAt(at)?.second
            assertEquals("$why: tap $n on the row's switch (was $before, now $now)", expected, now == before)
        }
    }

    /** Two fingers tap the canvas around ([x], [y]) (dp): undo. */
    fun twoFingerTap(s: ChromeScreen, x: Float, y: Float) {
        s.touch.twoFingerTap(px(s, x - 40f, y), px(s, x + 40f, y))
        settle()
    }

    /** A tap outside the newest window (a popup's ACTION_OUTSIDE, as the system sends it). */
    fun tapOutsideTheTopWindow() {
        val w = SmokeUi.windows().last()
        val t = SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(t, t, MotionEvent.ACTION_OUTSIDE, -10f, -10f, 0)
        w.dispatchTouchEvent(e)
        e.recycle()
        settle()
    }

    /**
     * R3 (a): canvas touches through the open row (opened after the fit, over the artwork's top):
     * opening it moves nothing; a stroke drawn under it lands, one step; one that starts beside a
     * short line lands; a pinch with a finger on the row zooms, with no stroke and no step, also
     * when its canvas finger lifts first (the row's controls then take taps again); a two-finger
     * tap undoes, also with one finger on the row. The row stays through all of it, remembered,
     * and nothing on it acts.
     */
    fun canvasTouches(s: ChromeScreen, where: String) {
        val c = s.c
        assertNull("$where: the row starts closed", row(s))
        val before = view(s)
        open(s)
        assertEquals("$where: opening the row never moves the canvas", before, view(s))
        val r = row(s)!!
        val art = artwork(s)
        assertTrue("$where: the row (opened after the fit) lies over the artwork's top: $r, $art", r.bottom > art.top && r.top < art.bottom)
        val lines = lines(s)
        val first = lines.first()

        // One finger from the canvas below the row up under its first line: the stroke lands.
        val blank = Fingers18.canvasState(s)
        drawShape(s, "$where: a stroke drawn under the row", first.center.x, r.bottom + 50f, first.center.x - 20f, first.center.y)
        val one = Fingers18.canvasState(s)
        assertEquals("$where: a stroke drawn under the row: one step", blank.undo + 1, one.undo)
        assertNotEquals("$where: …it painted", blank.pixels, one.pixels)
        assertNothingActed(s, "$where: a stroke under the row")
        assertOpen(s, "$where: a stroke under the row")

        // One that starts beside a short line (content-width, end-aligned under the toggle) lands.
        val short = lines.maxBy { it.left }
        if (short.left - r.left >= 48f) {
            val x = (r.left + short.left) / 2f
            drawShape(s, "$where: a stroke from beside a short line", x, short.center.y, x + 10f, r.bottom + 60f)
            val two = Fingers18.canvasState(s)
            assertEquals("$where: a stroke from beside a short line: one step", one.undo + 1, two.undo)
            assertNotEquals("$where: …it painted", one.pixels, two.pixels)
            assertNothingActed(s, "$where: a stroke beside a short line")
            assertOpen(s, "$where: a stroke beside a short line")
        }

        // A pinch: one finger on the canvas, the other on the row's first control. It zooms.
        val painted = Fingers18.canvasState(s)
        val x0 = (r.left + r.right) / 2f
        pinchFrom(s, x0 - 30f, r.bottom + 90f, first.left + 12f, first.center.y)
        val zoomed = Fingers18.canvasState(s)
        assertTrue("$where: a pinch with a finger on the row zooms in (${painted.zoom} to ${zoomed.zoom})", zoomed.zoom > painted.zoom * 1.05f)
        assertEquals("$where: …no step", painted.undo, zoomed.undo)
        assertEquals("$where: …no stroke", painted.pixels, zoomed.pixels)
        assertNull("$where: …no shape left open", (c.tools.getValue(TOOL) as ShapeTool).box)
        assertNothingActed(s, "$where: a pinch with a finger on the row")
        assertOpen(s, "$where: a pinch")

        // The same pinch with its canvas finger lifting first: the row's finger ends the gesture
        // (its lift reaches the canvas through the row), so the row's controls take taps again.
        pinchFrom(s, x0 - 30f, r.bottom + 90f, first.left + 12f, first.center.y, canvasLiftsFirst = true)
        val zoomedAgain = Fingers18.canvasState(s)
        assertTrue("$where: a pinch whose canvas finger lifts first zooms in (${zoomed.zoom} to ${zoomedAgain.zoom})", zoomedAgain.zoom > zoomed.zoom * 1.05f)
        assertEquals("$where: …no step", zoomed.undo, zoomedAgain.undo)
        assertEquals("$where: …no stroke", zoomed.pixels, zoomedAgain.pixels)
        assertNothingActed(s, "$where: a pinch whose last finger lifts on the row")
        assertRowTakesTaps(s, "$where: after a pinch whose last finger lifted on the row")
        assertOpen(s, "$where: a pinch whose last finger lifts on the row")

        // Two-finger taps undo, both fingers on the canvas or one on the row (lifting last), and
        // the row stays.
        drawShape(s, "$where: one more stroke", x0 - 40f, r.bottom + 100f, x0 + 40f, r.bottom + 160f)
        val drawn = Fingers18.canvasState(s)
        twoFingerTap(s, x0, r.bottom + 120f)
        val undone = Fingers18.canvasState(s)
        assertEquals("$where: a two-finger tap undoes one step", drawn.undo - 1, undone.undo)
        assertTrue("$where: …redo", undone.redo)
        assertOpen(s, "$where: a two-finger tap (a history tap never changes the memory)")
        s.touch.idle(300)
        s.touch.twoFingerTap(px(s, x0, r.bottom + 120f), px(s, first.left + 12f, first.center.y))
        settle()
        val undoneAgain = Fingers18.canvasState(s)
        assertEquals("$where: a two-finger tap with one finger on the row undoes one step", undone.undo - 1, undoneAgain.undo)
        assertNothingActed(s, "$where: a two-finger tap with one finger on the row")
        assertOpen(s, "$where: a two-finger tap with one finger on the row")
        Smoke.assertQuiet(c, where)
    }

    // ------------------------------------------------------------------ v1.9 Gate 2: the new items

    /**
     * v1.9 Gate 2 (R3 with A1, A2, A3, A5, A6 and A9; design 3.4): the row stays open while each
     * new v1.9 strip item is used, and shows again, open, once the item's popup, menu, dialog,
     * sheet or panel closes; those still close as in v1.8 (a pick, Done, an outside tap, Close).
     * One section each, on [where] at the device's [fontScale]:
     * - Polyline with two points selected: the group thickness row (› and its slider) and
     *   "Bevel points" with its popup (A1);
     * - Curve erasing a vector layer: the Stroke kind menu, the row-1 erase-mode cell and its
     *   chooser, and the "Turn into pixels" dialog (A1, A5);
     * - Shape on a vector layer: the More row's Duplicate, Bring forward and Send backward (A9);
     *   on a pixel layer, "New layer each time" in the Shape settings sheet (R14);
     * - the vector object bar's extra row: its Bring forward and Send backward (it stays open as
     *   the strip's row does);
     * - Text: "New layer each time" in the More row; justify, kerning and lists in the text sheet;
     *   an open text's Duplicate, Bring forward and Send backward; a text path's nodes (A3);
     * - the Chromatic aberration panel's blend mode (A2), over the Shape tool's row;
     * - the ••• menu's Settings with "Drawing preview" (A6; shown as the lead would ship it,
     *   `StrokePreview.SHIPPED` being false), over the Shape tool's row.
     * Each section checks its tool's own strip row ([assertRowOf]); the object bar's section also
     * checks the extra row, which hides the strip's while it shows (one More row at a time).
     */
    fun newItems(h: Qa19Harness, where: String, fontScale: Float) {
        h.section("$where: Polyline's group thickness row and Bevel points") { polylinePoints(h, where, fontScale) }
        h.section("$where: Curve's erase-mode cell, its chooser and the pixels dialog") { curveErasing(h, where, fontScale) }
        h.section("$where: Shape's Duplicate, Bring forward and Send backward") { shapeObjects(h, where, fontScale) }
        h.section("$where: Shape's New layer each time, in its settings sheet") { shapeSettings(h, where, fontScale) }
        h.section("$where: the object bar's extra row") { objectBarRow(h, where, fontScale) }
        h.section("$where: Text's New layer each time, and justify, kerning and lists in its sheet") { textSheet(h, where, fontScale) }
        h.section("$where: an open text's Duplicate, Bring forward, Send backward, and a text path's nodes") { textObjects(h, where, fontScale) }
        h.section("$where: the Chromatic aberration blend mode over the row") { blendOverTheRow(h, where, fontScale) }
        h.section("$where: the More options menu and Settings over the row") { settingsOverTheRow(h, where, fontScale) }
    }

    /** [tool]'s row shows (the strip's, not the object bar's), its toggle says Expanded, and [tool] remembers it. */
    fun assertRowOf(s: ChromeScreen, tool: ToolId, why: String) {
        settle()
        assertNotNull("$why: the More row shows; shown: ${SmokeUi.shown().take(60)}", StripTestKit.stripMoreRow())
        assertEquals("$why: expanded", StripLabels18.EXPANDED, StripTestKit.toggle()?.config?.getOrNull(SemanticsProperties.StateDescription))
        assertTrue("$why: remembered", tool.name in UiPrefs(s.activity).stripOpenRows)
    }

    /** [tool]'s row is remembered open (while a sheet, dialog or panel may cover it). */
    fun assertRemembered(s: ChromeScreen, tool: ToolId, why: String) {
        settle()
        assertTrue("$why: the row stays remembered", tool.name in UiPrefs(s.activity).stripOpenRows)
    }

    /** Opens [tool]'s row by a finger on its toggle, unless it shows already. */
    fun openRowOf(s: ChromeScreen, tool: ToolId) {
        if (StripTestKit.toggle()?.config?.getOrNull(SemanticsProperties.StateDescription) != StripLabels18.EXPANDED) {
            s.touch.idle(300)
            Finger.tap(s, StripLabels18.MORE_TOGGLE)
        }
        assertRowOf(s, tool, "$tool's row opened by its toggle")
    }

    /** Closes [tool]'s row by a finger on its toggle: the only way; it is forgotten. */
    fun closeRowOf(s: ChromeScreen, tool: ToolId) {
        assertRowOf(s, tool, "open before its toggle closes it")
        s.touch.idle(300)
        Finger.tap(s, StripLabels18.MORE_TOGGLE)
        settle()
        assertNull("the toggle closes $tool's row", StripTestKit.stripMoreRow())
        assertFalse("…and forgets it", tool.name in UiPrefs(s.activity).stripOpenRows)
    }

    /** No popup, menu, dialog or sheet window over the editor's. */
    fun assertNoWindowOver(why: String) {
        settle()
        assertEquals("$why: no window over the editor", 1, SmokeUi.windows().size)
    }

    /** A finger taps the control labelled [label] in the editor's window (a strip, bar or row item). */
    private fun tapItem(s: ChromeScreen, label: String) {
        s.touch.idle(300)
        Finger.tap(s, label)
        settle()
    }

    /** Clicks the clickable labelled [label] (its own text or description, or a child's text) under the node tagged [tag]. */
    private fun clickUnder(tag: String, label: String, why: String) {
        fun labels(n: SemanticsNode): List<String> =
            n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
                n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                n.children.flatMap { c ->
                    c.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + c.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                }
        val n = RobolectricUi.elements().map { it.node }.lastOrNull { n ->
            n.layoutInfo.isPlaced && StripTestKit.under(n, tag) && n.config.getOrNull(SemanticsActions.OnClick) != null && label in labels(n)
        } ?: throw AssertionError("$why: no \"$label\" under $tag; shown: ${SmokeUi.shown().take(60)}")
        requireNotNull(n.config.getOrNull(SemanticsActions.OnClick)?.action).invoke()
        settle()
    }

    /** Clicks the node tagged [tag]. */
    private fun clickTag(tag: String, why: String) {
        val n = StripTestKit.tagged(tag) ?: throw AssertionError("$why: no $tag; shown: ${SmokeUi.shown().take(60)}")
        val action = n.config.getOrNull(SemanticsActions.OnClick)?.action ?: throw AssertionError("$why: $tag is not clickable")
        action()
        settle()
    }

    /** A 400 × 300 editor at [fontScale] with [tool] active; layer 2 is a vector layer when [vector]. */
    private fun itemEditor(h: Qa19Harness, tool: ToolId, fontScale: Float, vector: Boolean = false, setup: (EditorController) -> Unit = {}): ChromeScreen {
        val doc = Smoke.document(400, 300, layers = 2, whiteBottom = true)
        if (vector) doc.layers[1].vector = VectorContent.EMPTY
        val s = h.editor(doc, fontScale = fontScale) { c ->
            c.snapping.enabled = false
            setup(c)
            c.selectTool(tool)
        }
        settle()
        assertEquals(tool, s.c.activeToolId)
        return s
    }

    /** A filled box of document px ([l], [t]) to ([r], [b]) (a vector object). */
    private fun box(l: Float, t: Float, r: Float, b: Float): VPath = VPath(
        0, subpaths = listOf(VSubpath(listOf(VAnchor(l, t, true), VAnchor(r, t, true), VAnchor(r, b, true), VAnchor(l, b, true)), closed = true)),
        fill = VPaint.Solid(0xFF3070C0.toInt()),
    )

    /** Polyline, two of four points selected: the group thickness row, then Bevel points and its popup (A1, R1 and R18). */
    private fun polylinePoints(h: Qa19Harness, where: String, fontScale: Float) {
        val id = ToolId.POLYLINE
        val s = itemEditor(h, id, fontScale) { c ->
            c.settings.curveBevelAmount = AppSettings.DEFAULT_BEVEL_AMOUNT
            c.settings.curveBevelSegments = AppSettings.DEFAULT_BEVEL_SEGMENTS
        }
        val t = s.c.tools.getValue(id) as CurveTool
        for (p in listOf(Vec2(60f, 200f), Vec2(130f, 90f), Vec2(200f, 160f), Vec2(270f, 80f))) assertTrue(t.addAnchor(p))
        t.selectPoints(PointSelection.of(t.pointCount, 1, 2))
        t.setWidths(t.pointSelection.indices, floatArrayOf(0.4f, 1.2f))
        t.endNumericEdit()
        settle()
        openRowOf(s, id)

        // The group thickness row (R1): a finger on ›, then its slider.
        assertNotNull("$where: the group thickness row", StripTestKit.tagged(V19Tags.GROUP_THICKNESS))
        val thin = t.anchors
        tapItem(s, ThicknessRowLabels.THICKER)
        assertNotEquals("$where: › thickens the points", thin, t.anchors)
        assertRowOf(s, id, "$where: after ›")
        val slider = RobolectricUi.elements().map { it.node }.lastOrNull { n ->
            n.layoutInfo.isPlaced && StripTestKit.under(n, V19Tags.GROUP_THICKNESS) && n.config.getOrNull(SemanticsActions.SetProgress) != null
        } ?: throw AssertionError("$where: the group slider")
        val range = requireNotNull(slider.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)) { "$where: the slider's range" }
        val thick = t.anchors
        val to = if (range.current < (range.range.start + range.range.endInclusive) / 2f) range.range.endInclusive else range.range.start
        requireNotNull(slider.config.getOrNull(SemanticsActions.SetProgress)?.action).invoke(to)
        settle()
        assertNotEquals("$where: the slider sets the points' thickness", thick, t.anchors)
        assertRowOf(s, id, "$where: after the group slider")
        assertNoWindowOver("$where: the group thickness row")

        // Bevel points (R18): its popup over the row, which stays; Done closes the popup.
        tapItem(s, PointLabels19.BEVEL)
        assertTrue("$where: the bevel popup", t.bevelPopupShown && StripTestKit.tagged(V19Tags.BEVEL_POPUP) != null)
        assertRowOf(s, id, "$where: with the bevel popup open")
        clickUnder(V19Tags.BEVEL_POPUP, PointLabels19.MORE_SEGMENTS, where)
        assertEquals("$where: More segments", 2, t.bevelSession?.segments)
        assertRowOf(s, id, "$where: after More segments")
        clickUnder(V19Tags.BEVEL_POPUP, BEVEL_DONE, where)
        assertFalse("$where: Done closes the popup", t.bevelPopupShown)
        assertNoWindowOver("$where: the bevel popup closed")
        assertRowOf(s, id, "$where: the bevel popup closed")
        closeRowOf(s, id)
    }

    /**
     * Curve erasing a vector layer (A1, A5, R11): the Stroke kind menu closes on an outside tap
     * and on its pick, the erase-mode cell's chooser on its pick, and the pixels dialog on Cancel
     * and on its confirm; the row stays through all of it.
     */
    private fun curveErasing(h: Qa19Harness, where: String, fontScale: Float) {
        val id = ToolId.CURVE
        val s = itemEditor(h, id, fontScale, vector = true)
        val c = s.c
        c.vectors.addObjects(c.doc.activeLayer, listOf(box(40f, 70f, 230f, 230f)), "Add")
        VectorEraserModes.setMode(c, VectorEraseMode.OBJECT)
        val t = c.tools.getValue(id) as CurveTool
        for (p in listOf(Vec2(60f, 200f), Vec2(130f, 90f), Vec2(200f, 160f))) assertTrue(t.addAnchor(p))
        t.deselect()
        settle()
        openRowOf(s, id)

        // The Stroke kind menu: an outside tap closes it; its last row turns erasing on.
        tapItem(s, CurveLabels17.STROKE_KIND)
        assertTrue("$where: the Stroke kind menu", Qa19Ui.has(EraseLabels19.INSTEAD_OF_PAINT, exact = true))
        assertRowOf(s, id, "$where: with the Stroke kind menu open")
        tapOutsideTheTopWindow()
        assertNoWindowOver("$where: an outside tap closes the Stroke kind menu")
        assertRowOf(s, id, "$where: the Stroke kind menu closed by an outside tap")
        tapItem(s, CurveLabels17.STROKE_KIND)
        Qa19Ui.click(EraseLabels19.INSTEAD_OF_PAINT, exact = true)
        assertTrue("$where: erasing", t.eraseOn)
        assertNoWindowOver("$where: the pick closes the Stroke kind menu")
        assertRowOf(s, id, "$where: erasing")

        // The erase-mode cell (row 1) and its chooser: a pick closes it.
        val objectCell = EraseLabels19.MODE_CELL.format(VectorEraseMode.OBJECT.label)
        assertNotNull("$where: the mode cell", StripTestKit.tagged(V19Tags.ERASE_MODE_CELL))
        tapItem(s, objectCell)
        assertNotNull("$where: the chooser", StripTestKit.tagged(V19Tags.ERASE_MODE_PREFIX + VectorEraseMode.PARTIAL.name))
        assertRowOf(s, id, "$where: with the erase-mode chooser open")
        clickTag(V19Tags.ERASE_MODE_PREFIX + VectorEraseMode.PARTIAL.name, where)
        assertEquals("$where: Partial chosen", VectorEraseMode.PARTIAL, VectorEraserModes.mode(c))
        assertNoWindowOver("$where: the pick closes the chooser")
        assertRowOf(s, id, "$where: Partial chosen")

        // "Turn into pixels": its dialog covers the row, which shows again on Cancel and on the confirm.
        val partialCell = EraseLabels19.MODE_CELL.format(VectorEraseMode.PARTIAL.label)
        tapItem(s, partialCell)
        clickTag(V19Tags.ERASE_PIXELS, where)
        assertTrue("$where: the pixels dialog", Qa19Ui.has(EraseLabels19.PIXELS_TITLE, exact = true))
        assertRemembered(s, id, "$where: under the pixels dialog")
        Qa19Ui.click(PIXELS_CANCEL, exact = true)
        assertFalse("$where: Cancel closes the dialog", Qa19Ui.has(EraseLabels19.PIXELS_TITLE, exact = true))
        assertTrue("$where: …the layer stays vector", c.doc.activeLayer.isVectorLayer)
        assertNoWindowOver("$where: the pixels dialog cancelled")
        assertRowOf(s, id, "$where: the pixels dialog cancelled")
        tapItem(s, partialCell)
        clickTag(V19Tags.ERASE_PIXELS, where)
        Qa19Ui.click(EraseLabels19.PIXELS_CONFIRM, exact = true)
        assertFalse("$where: the layer is pixels now", c.doc.activeLayer.isVectorLayer)
        assertNoWindowOver("$where: the pixels dialog confirmed")
        assertRowOf(s, id, "$where: the pixels dialog confirmed")
        closeRowOf(s, id)
        VectorEraserModes.setMode(c, VectorEraseMode.OBJECT)
    }

    /** Shape on a vector layer (A9, R14): an open shape object's Duplicate, Bring forward and Send backward in the More row. */
    private fun shapeObjects(h: Qa19Harness, where: String, fontScale: Float) {
        val id = ToolId.SHAPE
        val s = itemEditor(h, id, fontScale, vector = true)
        val c = s.c
        val tool = c.tools.getValue(id) as ShapeTool
        val a = s.screen(100f, 80f)
        val b = s.screen(300f, 220f)
        s.touch.stroke(a, ((a.first + b.first) / 2f) to ((a.second + b.second) / 2f), b)
        settle()
        assertNotNull("$where: the drag drew a shape", tool.box)
        openRowOf(s, id)
        val layer = c.doc.activeLayer
        val objects = layer.vector?.objects?.size ?: 0
        val steps = c.undoManager.undoCount
        tapItem(s, LayerLabels19.DUPLICATE)
        // The new shape is placed first, its own step, then copied, one step (ShapeTool.duplicateOpen).
        assertEquals("$where: the new shape's step and Duplicate's", steps + 2, c.undoManager.undoCount)
        assertEquals("$where: …the shape and its copy in the layer", objects + 2, layer.vector?.objects?.size ?: 0)
        assertRowOf(s, id, "$where: after Duplicate")
        for (label in listOf(ObjectEdits.Arrange.BACKWARD.label, ObjectEdits.Arrange.FORWARD.label)) {
            tapItem(s, label)
            assertNoWindowOver("$where: $label")
            assertRowOf(s, id, "$where: after $label")
        }
        assertTrue("$where: the shape stays open", tool.hasPendingWork)
        closeRowOf(s, id)
    }

    /** Shape on a pixel layer, editable (R14): "New layer each time" in the settings sheet, which covers the row; Close shows it again. */
    private fun shapeSettings(h: Qa19Harness, where: String, fontScale: Float) {
        val id = ToolId.SHAPE
        val s = itemEditor(h, id, fontScale)
        val tool = s.c.tools.getValue(id) as ShapeTool
        tool.update { it.copy(editable = true, newLayerEachTime = false) }
        settle()
        openRowOf(s, id)
        Qa19Ui.click(ShapeStripItems.SETTINGS_LABEL, exact = true)
        assertTrue("$where: the settings sheet", Smoke.pumpUntil(5_000) { Qa19Ui.has(PlaceLabels19.NEW_LAYER_EACH_TIME, exact = true) })
        assertRemembered(s, id, "$where: under the settings sheet")
        Qa19Ui.click(PlaceLabels19.NEW_LAYER_EACH_TIME, exact = true)
        assertTrue("$where: New layer each time on", tool.settings.newLayerEachTime)
        Qa19Ui.click(PlaceLabels19.NEW_LAYER_EACH_TIME, exact = true)
        assertFalse("$where: …and off", tool.settings.newLayerEachTime)
        Qa19Ui.click(SHEET_CLOSE, exact = true)
        assertFalse("$where: Close closes the sheet", Qa19Ui.has(PlaceLabels19.NEW_LAYER_EACH_TIME, exact = true))
        assertNoWindowOver("$where: the settings sheet closed")
        assertRowOf(s, id, "$where: the settings sheet closed")
        closeRowOf(s, id)
    }

    /**
     * The vector object bar's extra row (R3): with an object selected under Shape, its toggle
     * opens it (remembered in [UiPrefs.objectBarMoreOpen]; the strip's row hides, remembered);
     * Bring forward and Send backward act and it stays open; its toggle closes it and the strip's
     * row shows again.
     */
    private fun objectBarRow(h: Qa19Harness, where: String, fontScale: Float) {
        val id = ToolId.SHAPE
        val s = itemEditor(h, id, fontScale, vector = true)
        val c = s.c
        val layer = c.doc.activeLayer
        val ids = c.vectors.addObjects(layer, listOf(box(40f, 40f, 160f, 140f), box(120f, 100f, 260f, 220f)), "Add")
        openRowOf(s, id)
        c.vectors.setSelection(layer, setOf(ids.first()))
        settle()
        assertNotNull("$where: the object bar's toggle; shown: ${SmokeUi.shown().take(60)}", StripTestKit.objectToggle())
        s.touch.idle(300)
        Finger.tap(s, StripLabels18.MORE_OBJECT)
        settle()
        fun assertObjectRow(why: String) {
            settle()
            assertTrue("$why: the object bar's row shows", StripTestKit.objectRowShown())
            assertTrue("$why: …remembered", UiPrefs(s.activity).objectBarMoreOpen)
            assertNull("$why: one row at a time: the strip's hides", StripTestKit.stripMoreRow())
            assertTrue("$why: …remembered", id.name in UiPrefs(s.activity).stripOpenRows)
        }
        assertObjectRow("$where: opened by its toggle")
        val order = { layer.vector!!.objects.map { it.id } }
        val before = order()
        tapItem(s, ObjectEdits.Arrange.FORWARD.label)
        assertNotEquals("$where: Bring forward", before, order())
        assertNoWindowOver("$where: Bring forward")
        assertObjectRow("$where: after Bring forward")
        tapItem(s, ObjectEdits.Arrange.BACKWARD.label)
        assertEquals("$where: Send backward", before, order())
        assertObjectRow("$where: after Send backward")
        s.touch.idle(300)
        Finger.tap(s, StripLabels18.MORE_OBJECT)
        settle()
        assertFalse("$where: its toggle closes the object bar's row", StripTestKit.objectRowShown())
        assertFalse("$where: …and forgets it", UiPrefs(s.activity).objectBarMoreOpen)
        assertRowOf(s, id, "$where: the object bar's row closed: the strip's shows again")
        c.vectors.setSelection(null, emptySet())
        settle()
        closeRowOf(s, id)
    }

    /** Text (A3, R4, R5, R7, R14): "New layer each time" in the More row; the text sheet covers the row and its justify, kerning and lists act. */
    private fun textSheet(h: Qa19Harness, where: String, fontScale: Float) {
        val id = ToolId.TEXT
        val s = itemEditor(h, id, fontScale)
        val t = s.c.tools.getValue(id) as TextTool
        t.newLayerEachTime = false
        settle()
        openRowOf(s, id)
        tapItem(s, PlaceLabels19.NEW_LAYER_EACH_TIME)
        assertTrue("$where: New layer each time on", t.newLayerEachTime)
        assertRowOf(s, id, "$where: New layer each time on")
        tapItem(s, PlaceLabels19.NEW_LAYER_EACH_TIME)
        assertFalse("$where: …and off", t.newLayerEachTime)
        assertRowOf(s, id, "$where: New layer each time off")

        t.startTextAt(200f, 150f)
        t.setText(TEXT)
        t.updateSpec { it.copy(box = TextBoxSpec(width = 300f)) }
        Qa19Ui.settle(20)
        assertTrue("$where: the text sheet", t.editorOpen)
        assertRemembered(s, id, "$where: under the text sheet")
        SmokeUi.click(TextLabels19.JUSTIFY, exact = true)
        assertEquals("$where: Justify lines", TextJustify.ON, t.item!!.spec.justify)
        clickTag(V19Tags.KERN_PREFIX + TextKerningMode.OPTICAL.name, where)
        assertEquals("$where: Optical kerning", TextKerningMode.OPTICAL, t.item!!.spec.kerningMode)
        clickTag(LIST_TYPE_TAG_PREFIX + TextListType.BULLETS.name, where)
        assertEquals("$where: Bullets", TextListType.BULLETS, t.item!!.spec.list.type)
        assertRemembered(s, id, "$where: after justify, kerning and lists")
        t.confirmEditor()
        settle()
        assertFalse("$where: the sheet closed", t.editorOpen)
        assertNoWindowOver("$where: the text sheet closed")
        assertRowOf(s, id, "$where: the text sheet closed, the text open")
        assertTrue(t.commitItem())
        settle()
        assertRowOf(s, id, "$where: the text placed")
        closeRowOf(s, id)
    }

    /**
     * Text (A3, R12, R14): an open text of a layer of two: Duplicate, Send backward and Bring
     * forward in the More row; then a text path's nodes (the sheet's "Edit as path", the node
     * bar's Add point and ✓), after which the row shows again.
     */
    private fun textObjects(h: Qa19Harness, where: String, fontScale: Float) {
        val id = ToolId.TEXT
        val s = itemEditor(h, id, fontScale)
        val c = s.c
        val t = c.tools.getValue(id) as TextTool
        t.newLayerEachTime = false
        settle()
        openRowOf(s, id)
        fun tapDoc(x: Float, y: Float) {
            c.pointerDown(ToolPoint(x, y))
            c.pointerUp(ToolPoint(x, y))
            settle()
        }
        fun place(text: String, x: Float, y: Float) {
            tapDoc(x, y)
            assertTrue("$where: a new text at ($x, $y)", t.editorOpen && t.editingNew)
            t.setText(text)
            t.confirmEditor()
            settle()
            assertTrue(t.commitItem())
            settle()
        }
        place("One", 60f, 60f)
        place("Two", 300f, 240f)
        tapDoc(60f, 60f)
        assertEquals("$where: the bottom text open", 0, t.openIndex)
        assertRowOf(s, id, "$where: a text open")
        val steps = c.undoManager.undoCount
        tapItem(s, LayerLabels19.DUPLICATE)
        assertEquals("$where: Duplicate is one step", steps + 1, c.undoManager.undoCount)
        assertEquals("$where: the copy is open", 1, t.openIndex)
        assertRowOf(s, id, "$where: after Duplicate")
        tapItem(s, ObjectEdits.Arrange.BACKWARD.label)
        assertEquals("$where: Send backward", 0, t.openIndex)
        assertRowOf(s, id, "$where: after Send backward")
        tapItem(s, ObjectEdits.Arrange.FORWARD.label)
        assertEquals("$where: Bring forward", 1, t.openIndex)
        assertNoWindowOver("$where: Bring forward")
        assertRowOf(s, id, "$where: after Bring forward")
        t.discard()
        settle()

        // A text path's nodes: the entry closes the sheet; ✓ on the node bar brings it back.
        t.startTextAt(180f, 200f)
        t.setText("Along the path")
        t.setPath(t.item!!.path.copy(type = TextPathType.CURVE))
        Qa19Ui.settle(20)
        assertTrue("$where: the text sheet", t.editorOpen)
        clickTag(TEXT_PATH_ENTRY_TAG_PREFIX + TextPathNodeEntry.EDIT_AS_PATH.name, where)
        assertTrue("$where: editing the path's points", t.nodeEditor.active)
        assertRemembered(s, id, "$where: editing a text path's points")
        val points = t.nodeEditor.count
        val add = s.clickable(TextPathNodeEditor.ADD_POINT_STEP) ?: throw AssertionError("$where: no Add point; shown: ${SmokeUi.shown().take(60)}")
        val (ax, ay) = px(s, add.center.x, add.center.y)
        s.touch.idle(300)
        s.touch.tap(ax, ay)
        settle()
        assertEquals("$where: a finger on Add point", points + 1, t.nodeEditor.count)
        assertRemembered(s, id, "$where: after Add point")
        Qa19Ui.click(TextLabels19.DONE_POINTS, exact = true)
        assertFalse("$where: ✓ ends the editing", t.nodeEditor.active)
        assertTrue("$where: …back in the sheet", t.editorOpen)
        t.cancelEditor()
        t.discard()
        settle()
        assertNoWindowOver("$where: the text path's sheet closed")
        assertRowOf(s, id, "$where: the text path discarded")
        closeRowOf(s, id)
    }

    /**
     * The Chromatic aberration panel (A2) over the Shape tool's open row: the row stays
     * remembered; the blend mode menu closes on its pick; "Cancel filter" closes the panel and the
     * row shows again.
     */
    private fun blendOverTheRow(h: Qa19Harness, where: String, fontScale: Float) {
        val s = editor(h, fontScale)
        val c = s.c
        open(s)
        c.startFilter(ChromaticAberrationMovingFilter())
        settle()
        val session = requireNotNull(c.filterSession) { "$where: the panel opened" }
        assertTrue("$where: under the panel the row stays remembered", remembered(s))
        val mode = CaBlend.modeParam
        val label = RobolectricUi.elements().lastOrNull { e -> e.node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == mode.label } == true }
            ?: throw AssertionError("$where: no \"${mode.label}\"; shown: ${SmokeUi.shown().take(60)}")
        // Where the nodes are laid out, scrolled into the panel or not (a clipped bound is empty).
        fun extent(n: SemanticsNode): Rect = Rect(n.positionInWindow, n.size.toSize())
        val at = extent(label.node)
        val button = RobolectricUi.elements().filter { e ->
            val r = extent(e.node)
            e.window === label.window && e.node.config.getOrNull(SemanticsActions.OnClick) != null && r.left >= at.right - 1f &&
                r.center.y > at.top - 2f && r.center.y < at.bottom + 2f
        }.minByOrNull { extent(it.node).left } ?: throw AssertionError("$where: no button beside \"${mode.label}\"")
        requireNotNull(button.node.config.getOrNull(SemanticsActions.OnClick)?.action).invoke()
        settle()
        assertTrue("$where: the blend mode menu", Qa19Ui.has(LayerBlendMode.MULTIPLY.label, exact = true))
        Qa19Ui.click(LayerBlendMode.MULTIPLY.label, exact = true)
        assertEquals("$where: Multiply", LayerBlendMode.MULTIPLY.ordinal, session.values.choice(mode.key))
        assertTrue("$where: after the pick the row stays remembered", remembered(s))
        Qa19Ui.click(CANCEL_FILTER, exact = true)
        assertTrue("$where: Cancel filter closes the panel", session.isClosed)
        assertNoWindowOver("$where: the panel closed")
        assertOpen(s, "$where: the Chromatic aberration panel closed")
        closeByToggle(s)
    }

    /**
     * The ••• menu (it still closes as in v1.8) and its Settings dialog, which covers the row, with
     * "Drawing preview" (A6, shown here as the lead would ship it); Close shows the row again.
     */
    private fun settingsOverTheRow(h: Qa19Harness, where: String, fontScale: Float) {
        val wasAvailable = StrokePreview.available
        StrokePreview.available = true
        try {
            val s = editor(h, fontScale)
            open(s)
            s.touch.idle(300)
            Finger.tap(s, MORE_OPTIONS)
            settle()
            assertEquals("$where: the More options menu drops", 2, SmokeUi.windows().size)
            assertOpen(s, "$where: with the More options menu open")
            Qa19Ui.click(SETTINGS, exact = true)
            assertTrue("$where: the Settings dialog", Qa19Ui.has(SETTINGS_TITLE, exact = true))
            assertEquals("$where: the pick closes the More options menu", 2, SmokeUi.windows().size)
            assertTrue("$where: under Settings the row stays remembered", remembered(s))
            Qa19Ui.click(StabilityLabels19.EXACT_PIXELS, exact = true)
            assertEquals("$where: Exact pixels", AppSettings.DRAW_PREVIEW_EXACT, s.c.settings.drawPreview)
            Qa19Ui.click(StabilityLabels19.FASTER, exact = true)
            assertEquals("$where: Faster", AppSettings.DRAW_PREVIEW_FASTER, s.c.settings.drawPreview)
            Qa19Ui.click(SHEET_CLOSE, exact = true)
            assertFalse("$where: Close closes Settings", Qa19Ui.has(SETTINGS_TITLE, exact = true))
            assertNoWindowOver("$where: Settings closed")
            assertOpen(s, "$where: Settings closed")
            closeByToggle(s)
        } finally {
            StrokePreview.available = wasAvailable
        }
    }

    /** BevelPopup's button (a private constant of the frozen popup). */
    private const val BEVEL_DONE = "Done"

    /** The pixels dialog's dismiss button (a v1.8 word of the dialogs, `EraseLabels19` has none). */
    private const val PIXELS_CANCEL = "Cancel"

    /** The filter panel's close button (`FilterUi`, its content description). */
    private const val CANCEL_FILTER = "Cancel filter"

    /** The sheet's and the Settings dialog's close button (`BwSheet`, "Close"). */
    private const val SHEET_CLOSE = "Close"

    /** The top bar's ••• button, its Settings row and the dialog's title (v1.6's words). */
    private const val MORE_OPTIONS = "More options"
    private const val SETTINGS = "Settings"
    private const val SETTINGS_TITLE = "Editor settings"

    /** The text of the text sheet's section: long enough to wrap in a 300 px box. */
    private const val TEXT = "Brushwork sets justified lines flush with both edges of the box, word by word."
}
