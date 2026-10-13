package com.brushwork.paint.qa19

import android.content.Context
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.UiPrefs
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa18.Fingers18
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.ui.common.StripLabels18
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
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
}
