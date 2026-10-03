package com.brushwork.paint.qa16

import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * A finger on the editor: real touch events at points in dp of the editor's root (what a user
 * hits, whatever is on top there), the Back key, and the chrome's controls found by the names
 * a screen reader gives them.
 */
internal object Finger {

    private fun send(v: View, down: Long, time: Long, action: Int, x: Float, y: Float) {
        val e = MotionEvent.obtain(down, time, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        v.dispatchTouchEvent(e)
        e.recycle()
    }

    /** Window px of editor dp. */
    fun px(s: ChromeScreen, x: Float, y: Float): Pair<Float, Float> {
        val r = s.root
        return (r.left + x * s.density) to (r.top + y * s.density)
    }

    /** A tap (down, 50 ms, up) at ([x], [y]) dp of the editor, in the editor's window. */
    fun tapAt(s: ChromeScreen, x: Float, y: Float) {
        val (px, py) = px(s, x, y)
        val v = s.activity.window.decorView
        val t = SystemClock.uptimeMillis()
        send(v, t, t, MotionEvent.ACTION_DOWN, px, py)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        send(v, t, t + 50, MotionEvent.ACTION_UP, px, py)
        settle()
    }

    /** A slow drag (no fling) from [from] to [to], dp of the editor. */
    fun slowDrag(s: ChromeScreen, from: Pair<Float, Float>, to: Pair<Float, Float>) {
        val v = s.activity.window.decorView
        val (ax, ay) = px(s, from.first, from.second)
        val (bx, by) = px(s, to.first, to.second)
        val looper = shadowOf(Looper.getMainLooper())
        val t0 = SystemClock.uptimeMillis()
        send(v, t0, t0, MotionEvent.ACTION_DOWN, ax, ay)
        for (i in 1..24) {
            looper.idleFor(Duration.ofMillis(16))
            send(v, t0, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, ax + (bx - ax) * i / 24f, ay + (by - ay) * i / 24f)
        }
        repeat(6) {
            looper.idleFor(Duration.ofMillis(20))
            send(v, t0, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, bx, by)
        }
        send(v, t0, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, bx, by)
        settle()
    }

    /** The Back key, to the newest window (a dialog's, else the activity's). */
    fun back() {
        val w = SmokeUi.windows().last()
        w.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        w.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        settle()
    }

    private fun SemanticsNode.clickableSelfOrAncestor(): SemanticsNode? {
        var n: SemanticsNode? = this
        while (n != null && n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent
        return n
    }

    private fun SemanticsNode.labels(): List<String> =
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            listOfNotNull(config.getOrNull(SemanticsActions.OnClick)?.label)

    /**
     * The visible bounds (dp; clipped by a scrolling parent) of the clickable labelled [label]
     * whose element lies in [region] (dp), or null.
     */
    fun control(s: ChromeScreen, label: String, region: Rect? = null, exact: Boolean = true): Rect? {
        val hits = s.placed().filter { e -> e.node.labels().any { if (exact) it == label else it.contains(label) } }
            .mapNotNull { it.node.clickableSelfOrAncestor() }
            // Placed in [region] (a cell scrolled out of view shows nothing, but is laid out there).
            .filter { n ->
                val x = (n.positionInWindow.x + n.size.width / 2f) / s.density - s.dp(s.root).left
                region == null || (x >= region.left && x <= region.right)
            }
        return hits.lastOrNull()?.let { s.dp(it.boundsInWindow) }
    }

    /** The bounds (dp) of the (last) element labelled [label] itself, or null. */
    fun element(s: ChromeScreen, label: String, exact: Boolean = true): Rect? =
        s.placed().lastOrNull { e -> e.node.labels().any { if (exact) it == label else it.contains(label) } }?.let { s.dp(it.node.boundsInWindow) }

    /** Taps the control labelled [label] at the centre of what shows of it. */
    fun tap(s: ChromeScreen, label: String, exact: Boolean = true) {
        val r = control(s, label, exact = exact) ?: throw AssertionError("no \"$label\" on screen; shown: ${SmokeUi.shown().take(120)}")
        if (r.width <= 0f || r.height <= 0f) throw AssertionError("\"$label\" shows nothing: $r")
        tapAt(s, r.center.x, r.center.y)
    }

    /** The tool menu's bounds (dp), or null when it is closed. */
    fun toolMenu(s: ChromeScreen): Rect? = s.tagged(ChromeTags.TOOL_MENU)

    /** The layer window's bounds (dp), or null when it is closed. */
    fun layerWindow(s: ChromeScreen): Rect? = s.tagged(ChromeTags.LAYER_WINDOW)
}
