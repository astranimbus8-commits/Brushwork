package com.brushwork.paint.ui.color

import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * Drives real Compose windows (activity, dialogs, bottom sheets) under Robolectric without the
 * Compose test library: finds elements through the semantics tree and sends real MotionEvents to
 * the window that shows them.
 */
internal object RobolectricUi {

    fun settle(steps: Int = 20, stepMs: Long = 100) =
        repeat(steps) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(stepMs)) }

    /** Root views of every window (activity first, then dialogs/sheets in opening order). */
    @Suppress("UNCHECKED_CAST")
    fun windowRoots(): List<View> {
        val wmg = Class.forName("android.view.WindowManagerGlobal")
        val inst = wmg.getMethod("getInstance").invoke(null)
        val f = wmg.getDeclaredField("mViews").apply { isAccessible = true }
        return (f.get(inst) as List<View>).toList()
    }

    private fun composeRoots(v: View): List<ViewRootForTest> = when (v) {
        is ViewRootForTest -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { composeRoots(v.getChildAt(it)) }
        else -> emptyList()
    }

    /** A semantics node and the window root view its [bounds] (window pixels) refer to. */
    class Element(val node: SemanticsNode, val window: View) {
        val bounds: Rect get() = node.boundsInWindow
        val text: String? get() = node.config.getOrNull(SemanticsProperties.EditableText)?.text
        val focused: Boolean get() = node.config.getOrNull(SemanticsProperties.Focused) == true
        val stateDescription: String? get() = node.config.getOrNull(SemanticsProperties.StateDescription)

        fun tap() = tap(window, bounds.center.x, bounds.center.y)
        fun focus() { requireNotNull(node.config.getOrNull(SemanticsActions.RequestFocus)?.action).invoke() }
        fun type(text: String) { requireNotNull(node.config.getOrNull(SemanticsActions.SetText)?.action).invoke(AnnotatedString(text)) }
    }

    /**
     * All (unmerged) semantics nodes of every window. Pending Compose layout is run first: under
     * Robolectric a sheet's content can otherwise stay unplaced until the next input event.
     */
    fun elements(): List<Element> {
        val out = mutableListOf<Element>()
        for (window in windowRoots()) {
            fun walk(n: SemanticsNode) { out += Element(n, window); n.children.forEach(::walk) }
            composeRoots(window).forEach { root ->
                root.measureAndLayoutForTest()
                walk(root.semanticsOwner.unmergedRootSemanticsNode)
            }
        }
        return out
    }

    fun hasText(fragment: String): Boolean = elements().any { e -> e.shownText().any { it.contains(fragment) } }

    /** The element showing exactly [text]; the last one if several windows show it. */
    fun byText(text: String): Element = elements().last { e -> e.shownText().any { it == text } }

    /** The element whose content description contains [fragment]. */
    fun byDescription(fragment: String): Element =
        elements().last { e -> e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.contains(fragment) } == true }

    /** Editable text fields, top to bottom. */
    fun textFields(): List<Element> =
        elements().filter { it.node.config.getOrNull(SemanticsActions.SetText) != null }.sortedBy { it.bounds.top }

    private fun Element.shownText(): List<String> = node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()

    private fun send(v: View, downTime: Long, time: Long, action: Int, x: Float, y: Float) {
        val e = MotionEvent.obtain(downTime, time, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        v.dispatchTouchEvent(e)
        e.recycle()
    }

    fun tap(v: View, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        send(v, t, t, MotionEvent.ACTION_DOWN, x, y)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        send(v, t, t + 50, MotionEvent.ACTION_UP, x, y)
        settle()
    }

    /** One finger through [points] (window pixels), with interpolated moves in between. */
    fun drag(v: View, vararg points: Pair<Float, Float>) {
        val t0 = SystemClock.uptimeMillis()
        var t = t0
        send(v, t0, t, MotionEvent.ACTION_DOWN, points[0].first, points[0].second)
        for (i in 1 until points.size) {
            val (ax, ay) = points[i - 1]
            val (bx, by) = points[i]
            for (s in 1..8) {
                t += 8
                send(v, t0, t, MotionEvent.ACTION_MOVE, ax + (bx - ax) * s / 8f, ay + (by - ay) * s / 8f)
            }
        }
        t += 8
        send(v, t0, t, MotionEvent.ACTION_UP, points.last().first, points.last().second)
        settle()
    }

    /**
     * Touch points of the wheel shown by [wheel] (its "Color wheel" element), from the same
     * geometry the wheel uses; [density] is px per dp.
     */
    class WheelPoints(wheel: Element, density: Float) {
        private val b = wheel.bounds
        private val layout = WheelLayout(b.width, b.width * 0.12f, 4f * density)
        val center = Pair(b.left + layout.center, b.top + layout.center)
        /** Middle of the ring at hue 0 (top). */
        val ringTop = Pair(b.left + layout.hueX(0f), b.top + layout.hueY(0f))
        /** Offsets from the center in units of half the saturation/brightness square. */
        fun square(dx: Float, dy: Float) = Pair(center.first + dx * layout.squareHalf, center.second + dy * layout.squareHalf)
    }
}
