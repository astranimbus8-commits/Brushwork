package com.brushwork.paint.qa18

import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * v1.8 (area H): fingers on the editor for the qa18 chrome tests, beside [Finger]: long presses
 * with their bubble, one- and two-finger gestures on the canvas, and what the canvas shows (the
 * view, the active layer's pixels, the history) to tell a touch that acted from one that was
 * consumed (D25).
 */
internal object Fingers18 {

    fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** Holds for [ms] of REAL time while running the looper (Compose times a long press on the wall clock). */
    fun holdRealTime(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            Thread.sleep(10)
            idle(10)
        }
    }

    private fun send(v: View, downTime: Long, action: Int, x: Float, y: Float) {
        val e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        v.dispatchTouchEvent(e)
        e.recycle()
    }

    /** The long-press bubble's text, or null when none shows. */
    fun bubble(): String? = StripTestKit.tagged(V18Tags.LONG_PRESS_BUBBLE)?.let { b ->
        generateSequence(listOf(b)) { level -> level.flatMap { it.children }.ifEmpty { null } }.flatten()
            .firstNotNullOfOrNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text }
    }

    /** A finger held still on the control labelled [label] for 800 ms; [held] runs before it lifts. */
    fun longPress(s: ChromeScreen, label: String, held: () -> Unit = {}) =
        longPressAt(s, Finger.control(s, label) ?: throw AssertionError("no \"$label\" on screen"), held)

    /** A finger held still in the middle of [r] (dp) for 800 ms (a cell that is no clickable too); [held] runs before it lifts. */
    fun longPressAt(s: ChromeScreen, r: Rect, held: () -> Unit = {}) {
        val (x, y) = Finger.px(s, r.center.x, r.center.y)
        val v = s.activity.window.decorView
        val t0 = SystemClock.uptimeMillis()
        send(v, t0, MotionEvent.ACTION_DOWN, x, y)
        holdRealTime(800)
        held()
        send(v, t0, MotionEvent.ACTION_UP, x, y)
        Smoke.pump(300); SmokeUi.settle()
    }

    /**
     * A tap (down, 50 ms, up) on the control labelled [label], then only [afterMs] for the screen to
     * react, not a settle: what the test does next lands inside the chrome's 150 ms fades.
     */
    fun quickTap(s: ChromeScreen, label: String, afterMs: Long = 48) {
        val r = Finger.control(s, label) ?: throw AssertionError("no \"$label\" on screen")
        val (x, y) = Finger.px(s, r.center.x, r.center.y)
        val v = s.activity.window.decorView
        val t0 = SystemClock.uptimeMillis()
        send(v, t0, MotionEvent.ACTION_DOWN, x, y)
        idle(50)
        send(v, t0, MotionEvent.ACTION_UP, x, y)
        idle(afterMs)
    }

    /** The placed node whose content description is [label] (the last one), or null. */
    fun described(s: ChromeScreen, label: String): SemanticsNode? =
        s.placed().lastOrNull { e -> e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true }?.node

    /** The state description of the control described as [label] ("Expanded", "On"...). */
    fun state(s: ChromeScreen, label: String): String? = described(s, label)?.config?.getOrNull(SemanticsProperties.StateDescription)

    /** Bounds (dp) of the node tagged [tag] whose bounds contain the middle of [inside] (dp), or null. */
    fun taggedAround(s: ChromeScreen, tag: String, inside: Rect): Rect? =
        s.placed().filter { it.node.config.getOrNull(SemanticsProperties.TestTag) == tag }.map { s.dp(it.bounds) }
            .lastOrNull { it.contains(inside.center) }

    // ------------------------------------------------------------------ the canvas

    /** What a touch on the canvas can change: the view, the active layer's pixels, the history, the object selection. */
    data class CanvasState(val zoom: Float, val rotation: Float, val pixels: Int, val undo: Int, val redo: Boolean, val objects: Set<Long>, val tool: Any)

    fun canvasState(s: ChromeScreen): CanvasState {
        val c = s.c
        val bmp = c.activeLayer.bitmap
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return CanvasState(
            c.viewTransform.zoom, c.viewTransform.rotationDeg, px.contentHashCode(),
            c.undoManager.undoCount, c.canRedo, c.vectors.selectedIds.toSet(), c.activeToolId,
        )
    }

    /** One finger across the document from ([x0], [y0]) to ([x1], [y1]) (document points), slowly. */
    fun stroke(s: ChromeScreen, x0: Float, y0: Float, x1: Float, y1: Float) {
        val a = s.screen(x0, y0)
        val b = s.screen(x1, y1)
        s.touch.stroke(a, ((a.first + b.first) / 2f) to ((a.second + b.second) / 2f), b)
        Smoke.pump(300); SmokeUi.settle()
    }

    /** Two fingers spreading apart around document point ([x], [y]): a pinch that zooms in. */
    fun pinch(s: ChromeScreen, x: Float, y: Float) {
        val (cx, cy) = s.screen(x, y)
        val from = 30f * s.density
        val to = 90f * s.density
        val t = s.touch
        t.send(MotionEvent.ACTION_DOWN, Smoke.P(0, cx - from, cy))
        t.idle(16)
        t.send(MotionEvent.ACTION_POINTER_DOWN, Smoke.P(0, cx - from, cy), Smoke.P(1, cx + from, cy), index = 1)
        for (i in 1..12) {
            t.idle(16)
            val d = from + (to - from) * i / 12f
            t.send(MotionEvent.ACTION_MOVE, Smoke.P(0, cx - d, cy), Smoke.P(1, cx + d, cy))
        }
        t.idle(16)
        t.send(MotionEvent.ACTION_POINTER_UP, Smoke.P(0, cx - to, cy), Smoke.P(1, cx + to, cy), index = 1)
        t.idle(16)
        t.send(MotionEvent.ACTION_UP, Smoke.P(0, cx - to, cy))
        t.idle(50)
        Smoke.pump(300); SmokeUi.settle()
    }

    /** Two fingers tapping around document point ([x], [y]) (undo, when the canvas takes it). */
    fun twoFingerTap(s: ChromeScreen, x: Float, y: Float) {
        val (cx, cy) = s.screen(x, y)
        val d = 40f * s.density
        s.touch.twoFingerTap((cx - d) to cy, (cx + d) to cy)
        Smoke.pump(300); SmokeUi.settle()
    }
}
