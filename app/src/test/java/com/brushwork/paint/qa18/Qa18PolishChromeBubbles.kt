package com.brushwork.paint.qa18

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.select.LassoTool
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.ui.common.StripLabels18
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.ui.editor.chrome.Clickables
import kotlin.math.abs

/**
 * v1.8 polish (request items 8 and 9, QA: icon-only strip items had no long-press bubble): what
 * the long-press classes share. [census] finds every icon-only control of the options strip (row 1
 * and its More row) and of the bar under it (the selection bar or the object bar, and the object
 * bar's More row) as the screen shows them, and holds a finger on each ([press]): the bubble must
 * say the control's name under it, and nothing else may change ([Snap]).
 */
internal object Qa18PolishChromeBubbles {

    /** Between a control's bottom and its bubble (LongPressLabel's gap, dp). */
    private const val GAP_DP = 4f

    /** The bubble's padding around its text (dp): its tagged box is inset by these. */
    private const val PAD_X_DP = 8f
    private const val PAD_Y_DP = 4f

    /** The lasso's point count under its ✓ ("3 pt"): the ✓ is the control, the count a readout. */
    private val POINTS = Regex("""\d+ pt""")

    /** The texts [n] shows (its own and its descendants', down to the next clickable), screen-reader hidden or not. */
    fun visibleTexts(n: SemanticsNode): List<String> {
        val out = mutableListOf<String>()
        fun walk(x: SemanticsNode) {
            out += x.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
            x.config.getOrNull(SemanticsProperties.EditableText)?.let { out += it.text }
            for (c in x.children) if (c.config.getOrNull(SemanticsActions.OnClick) == null) walk(c)
        }
        walk(n)
        return out.filter { it.isNotBlank() }
    }

    /**
     * A control known by its icon alone: it shows no text (the lasso's ✓ shows its point count
     * only). A number readout ("48" by a size icon) shows its value: it is not one.
     */
    fun iconOnly(item: Clickables.Item): Boolean = visibleTexts(item.node).all { POINTS.matches(it.trim()) }

    /** Its name: its description (an icon button's sits on its icon) or its click label. */
    fun name(item: Clickables.Item): String? = item.own.firstOrNull()

    /** Shows whole (not scrolled partly past a bar's edge). */
    private fun whole(item: Clickables.Item): Boolean = abs(item.bounds.width - item.width) < 0.5f && abs(item.bounds.height - item.height) < 0.5f

    /** The bubble's bounds (dp), or null. */
    fun bubbleAt(s: ChromeScreen): Rect? = s.tagged(V18Tags.LONG_PRESS_BUBBLE)

    private fun SemanticsNode.tag(): String? = config.getOrNull(SemanticsProperties.TestTag)

    private val CHROME = setOf(ChromeTags.OPTIONS_STRIP, V18Tags.STRIP_MORE_ROW, ChromeTags.SELECTION_BAR)

    /** What the strips and the bar show and say: texts, names, states, on / off, picked, enabled. */
    private fun chrome(s: ChromeScreen): List<String> = s.placed().map { it.node }.filter { n ->
        val up = generateSequence(n) { it.parent }.map { it.tag() }.toList()
        up.any { it in CHROME } && V18Tags.LONG_PRESS_BUBBLE !in up
    }.mapNotNull { n ->
        val c = n.config
        listOfNotNull(
            c.getOrNull(SemanticsProperties.Text)?.joinToString { it.text },
            c.getOrNull(SemanticsProperties.EditableText)?.text,
            c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(),
            c.getOrNull(SemanticsProperties.StateDescription),
            c.getOrNull(SemanticsProperties.ToggleableState)?.name,
            c.getOrNull(SemanticsProperties.Selected)?.toString(),
            if (c.getOrNull(SemanticsProperties.Disabled) != null) "disabled" else null,
        ).joinToString("|").ifEmpty { null }
    }

    /** What the current tool holds that a strip control changes. */
    private fun tool(s: ChromeScreen): String = when (val t = s.c.currentTool) {
        is TransformTool -> listOf(t.transformState, t.mode, t.interpolation, t.keepAspect, t.scaleFromCenter, t.meshColumns, t.meshRows, t.isMeshChanged)
        is LassoTool -> listOf(t.kind, t.vertexCount, t.curve.count, t.curve.selected)
        else -> emptyList()
    }.toString() + " pending ${s.c.currentTool.hasPendingWork}, steps ${s.c.currentTool.canUndoStep}/${s.c.currentTool.canRedoStep}"

    /** What a stray action would change. */
    data class Snap(
        val canvas: Fingers18.CanvasState,
        val windows: Int,
        val sheets: List<String>,
        val pills: List<String>,
        val clipboard: Int,
        val selection: Int,
        val vectorMode: Boolean,
        val layers: Int,
        val activeLayer: Int,
        val snapping: Boolean,
        val tool: String,
        val chrome: List<String>,
    )

    fun snap(s: ChromeScreen) = Snap(
        Fingers18.canvasState(s), SmokeUi.windows().size, SmokeUi.sheetTitles(), SmokeUi.pillTitles(),
        System.identityHashCode(s.c.clipboard), System.identityHashCode(s.c.selection), s.c.isVectorMode,
        s.c.doc.layers.size, s.c.doc.activeLayerIndex, s.c.snapping.enabled, tool(s), chrome(s),
    )

    /** Where [a] and [b] differ, by field. */
    fun diff(a: Snap, b: Snap): String {
        val fields = Snap::class.java.declaredFields.onEach { it.isAccessible = true }
        return fields.filter { it.get(a) != it.get(b) }.joinToString("; ") { f ->
            if (f.name == "chrome") {
                @Suppress("UNCHECKED_CAST") val x = f.get(a) as List<String>
                @Suppress("UNCHECKED_CAST") val y = f.get(b) as List<String>
                "chrome: -${x - y.toSet()} +${y - x.toSet()}"
            } else {
                "${f.name}: ${f.get(a)} -> ${f.get(b)}"
            }
        }
    }

    /** One control held: its name and what went wrong (empty: none). */
    class Held(val name: String, val problems: List<String>)

    /**
     * A finger held on [item] (800 ms, then lifted): the bubble says [name] while it is held and
     * after, right under the item (centered, or kept on the screen), and nothing else changed.
     */
    fun press(s: ChromeScreen, item: Clickables.Item, name: String, where: String): Held {
        val problems = mutableListOf<String>()
        val before = snap(s)
        var held: String? = null
        var heldAt: Rect? = null
        Fingers18.longPressAt(s, item.bounds) {
            held = Fingers18.bubble()
            heldAt = bubbleAt(s)
        }
        val what = "$where: \"$name\" at ${item.bounds}"
        val at = heldAt
        if (held != name || at == null) {
            problems += "$what: the bubble says \"$held\""
        } else {
            if (abs(at.top - PAD_Y_DP - (item.bounds.bottom + GAP_DP)) > 1.5f) problems += "$what: the bubble is not under it ($at)"
            val centered = abs(at.center.x - item.bounds.center.x) <= 1.5f
            val kept = at.left <= PAD_X_DP + 0.5f || at.right >= s.widthDp - PAD_X_DP - 0.5f
            if (!centered && !kept) problems += "$what: the bubble is not centered under it ($at)"
        }
        if (Fingers18.bubble() != name) problems += "$what: the bubble went as the finger lifted (${Fingers18.bubble()})"
        val after = snap(s)
        if (after != before) problems += "$what: a long press changed ${diff(before, after)}"
        return Held(name, problems)
    }

    /** The icon-only controls inside [area] (dp) that show whole, left to right, top to bottom. */
    fun iconOnlyIn(s: ChromeScreen, area: Rect): List<Clickables.Item> = Clickables.onScreen(s, inside = area)
        .filter { whole(it) && iconOnly(it) && name(it) != null }
        .sortedWith(compareBy({ (it.bounds.top / 8f).toInt() }, { it.bounds.left }))

    /**
     * Holds a finger on every icon-only control of the strip and the bar as [s] shows them now,
     * each More row opened by its toggle (and closed after); [seen] keys (`<tool>|<name>`) are
     * skipped and added to. Returns what was held.
     */
    fun census(s: ChromeScreen, where: String, seen: MutableSet<String>): List<Held> {
        val out = mutableListOf<Held>()
        val tool = s.c.activeToolId.name
        fun hold(area: Rect, part: String) {
            for (item in iconOnlyIn(s, area)) {
                val name = name(item) ?: continue
                if (!seen.add("$tool|$name")) continue
                out += press(s, item, name, "$where, $part")
            }
        }
        val row1 = s.tagged(ChromeTags.OPTIONS_STRIP)?.takeIf { it.width > 0f && it.height > 0f }
        if (row1 != null) {
            hold(row1, "row 1")
            val toggle = s.tagged(V18Tags.STRIP_TOGGLE)?.takeIf { row1.contains(it.center) }
            if (toggle != null) {
                openMore(s, StripLabels18.MORE_TOGGLE, where)
                s.tagged(V18Tags.STRIP_MORE_ROW)?.let { hold(it, "More row") }
                closeMore(s, StripLabels18.MORE_TOGGLE, where)
            }
        }
        val bar = s.tagged(ChromeTags.SELECTION_BAR)?.takeIf { it.width > 0f && it.height > 0f }
        if (bar != null) {
            hold(bar, "bar")
            if (Finger.control(s, StripLabels18.MORE_OBJECT) != null) {
                openMore(s, StripLabels18.MORE_OBJECT, where)
                s.tagged(V18Tags.STRIP_MORE_ROW)?.let { hold(it, "object bar's More row") }
                closeMore(s, StripLabels18.MORE_OBJECT, where)
            }
        }
        return out
    }

    private fun openMore(s: ChromeScreen, toggle: String, where: String) {
        if (Fingers18.state(s, toggle) == StripLabels18.EXPANDED) return
        Finger.tap(s, toggle)
        Smoke.pump(300); SmokeUi.settle()
        check(Fingers18.state(s, toggle) == StripLabels18.EXPANDED) { "$where: \"$toggle\" opens no row" }
    }

    private fun closeMore(s: ChromeScreen, toggle: String, where: String) {
        if (Fingers18.state(s, toggle) != StripLabels18.EXPANDED) return
        Finger.tap(s, toggle)
        Smoke.pump(300); SmokeUi.settle()
        check(Fingers18.state(s, toggle) == StripLabels18.COLLAPSED) { "$where: \"$toggle\" does not close its row" }
    }

    /**
     * A finger held 800 ms on the middle of [r] (pixels of [window], a popup's or a dialog's own
     * window), then lifted; [held] runs before it lifts.
     */
    fun longPressIn(window: View, r: android.graphics.RectF, held: () -> Unit = {}) {
        val t0 = SystemClock.uptimeMillis()
        fun send(action: Int) {
            val e = MotionEvent.obtain(t0, SystemClock.uptimeMillis(), action, r.centerX(), r.centerY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            window.dispatchTouchEvent(e)
            e.recycle()
        }
        send(MotionEvent.ACTION_DOWN)
        Fingers18.holdRealTime(800)
        held()
        send(MotionEvent.ACTION_UP)
        Smoke.pump(300); SmokeUi.settle()
    }
}
