package com.brushwork.paint.ui.layers

import android.view.KeyEvent
import android.view.View
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.ui.color.RobolectricUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Measures and audits the layer window under Robolectric: parts by test tag, controls by label,
 * every clickable's size (≥ 40 dp, design §3.7.11) and its label (unique among the clickables
 * shown together, I10). Sizes are compared in dp ([density] px per dp).
 */
internal class LayerWindowProbe(private val density: Float) {

    /** Placed nodes of the windows shown since the test's baseline (activity first, then popups). */
    fun elements(): List<RobolectricUi.Element> {
        val windows = SmokeUi.windows()
        return RobolectricUi.elements().filter { it.window in windows && it.node.layoutInfo.isPlaced }
    }

    fun tagged(tag: String): Rect =
        elements().lastOrNull { it.node.config.getOrNull(SemanticsProperties.TestTag) == tag }?.bounds
            ?: throw AssertionError("nothing tagged $tag")

    /** Unclipped height (dp) of the part tagged [tag] (a row half scrolled out of the list is still a full row). */
    fun taggedHeightDp(tag: String): Float =
        elements().lastOrNull { it.node.config.getOrNull(SemanticsProperties.TestTag) == tag }?.node?.size?.height?.let { dp(it.toFloat()) }
            ?: throw AssertionError("nothing tagged $tag")

    fun hasTag(tag: String): Boolean = elements().any { it.node.config.getOrNull(SemanticsProperties.TestTag) == tag }

    /** The clickable (or the slider) labelled exactly [label], or the clickable around that label. */
    fun control(label: String): Rect {
        val e = elements().lastOrNull { label in it.node.ownLabels() || label in it.node.texts() }
            ?: throw AssertionError("nothing labelled \"$label\"; shown: ${SmokeUi.shown().take(120)}")
        var n: SemanticsNode? = e.node
        while (n != null && !n.isControl()) n = n.parent
        return (n ?: e.node).boundsInWindow
    }

    fun dp(px: Float): Float = px / density

    fun assertDp(what: String, expected: Float, px: Float, tolerance: Float = 1f) =
        assertEquals("$what (dp)", expected, dp(px), tolerance)

    fun assertSize(what: String, w: Float, h: Float, r: Rect) {
        assertDp("$what width", w, r.width)
        assertDp("$what height", h, r.height)
    }

    /** Clickables and sliders whose centre lies inside [area] (window px) of the [window]. */
    fun controlsIn(area: Rect, window: View? = null): List<Pair<SemanticsNode, Rect>> =
        elements()
            .filter { (window == null || it.window === window) && it.node.isControl() && area.contains(it.bounds.center) }
            .map { it.node to it.bounds }

    /**
     * Every control in [area] is at least 40 × 40 dp (half a dp of rounding allowed), measured
     * unclipped (a row half scrolled out of the list is still a full-size target).
     */
    fun assertTouchTargets(where: String, area: Rect, window: View? = null) {
        val small = controlsIn(area, window).map { it.first }
            .filter { n -> dp(n.size.width.toFloat()) < 39.5f || dp(n.size.height.toFloat()) < 39.5f }
            .map { n -> "${n.label()} ${"%.1f".format(dp(n.size.width.toFloat()))}×${"%.1f".format(dp(n.size.height.toFloat()))}" }
        assertTrue("$where: touch targets under 40 dp: $small", small.isEmpty())
    }

    /** The labels of the controls in [area]; fails when two controls share one (I10). */
    fun assertUniqueLabels(where: String, area: Rect, window: View? = null): Set<String> {
        val labels = controlsIn(area, window).map { (n, _) -> n.label() }
        val blank = labels.filter { it.isBlank() }
        assertTrue("$where: a control without a label", blank.isEmpty())
        val dup = labels.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue("$where: labels shown twice: $dup (all: $labels)", dup.isEmpty())
        return labels.toSet()
    }

    /** Texts of the items of the open dropdown menus (popup windows after the activity's). */
    fun menuItems(activityWindow: View): List<String> =
        elements().filter { it.window !== activityWindow && it.node.isControl() }.map { it.node.label() }

    companion object {
        /** Back on every popup window shown over [activityWindow] (a dropdown menu dismisses). */
        fun pressBackOnPopups(activityWindow: View) {
            for (w in SmokeUi.windows().filter { it !== activityWindow }.asReversed()) {
                w.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
                w.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
            }
            SmokeUi.settle()
        }

        /** A clickable, a slider, or a drag handle (custom accessibility actions). */
        fun SemanticsNode.isControl(): Boolean =
            config.getOrNull(SemanticsActions.OnClick) != null || config.getOrNull(SemanticsActions.SetProgress) != null ||
                config.getOrNull(SemanticsActions.CustomActions) != null

        fun SemanticsNode.ownLabels(): List<String> =
            config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() + listOfNotNull(config.getOrNull(SemanticsActions.OnClick)?.label)

        fun SemanticsNode.texts(): List<String> = config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()

        private fun SemanticsNode.inner(pick: (SemanticsNode) -> List<String>): List<String> =
            children.flatMap { c -> if (c.isControl()) emptyList() else pick(c) + c.inner(pick) }

        /**
         * What a control is called: its own description or click label; else the descriptions
         * inside it (an icon's); else the texts inside it (a caption).
         */
        fun SemanticsNode.label(): String =
            ownLabels().firstOrNull()
                ?: inner { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }.firstOrNull()
                ?: (texts() + inner { it.texts() }).joinToString(" ")
    }
}
