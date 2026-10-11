package com.brushwork.paint.testing

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.StripLabels18
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.editor.chrome.ChromeTags

/**
 * v1.8 (F3, design §4.9; frozen, the lead's): reaching the options strip's More row from tests
 * that drive the UI through [RobolectricUi] directly (`SmokeUi.find` and `click` do the same for
 * the smoke screens). A converted strip keeps its rarer controls on the More row, closed until its
 * toggle ([StripLabels18.MORE_TOGGLE], tag [V18Tags.STRIP_TOGGLE]) opens it, and its row 1 never
 * scrolls (I18): a control with no horizontal scroller around it is already in view.
 *
 * Two toggles and two More rows (v1.8 integration). The vector object bar's toggle
 * ([StripLabels18.MORE_OBJECT]) and its More row reuse the strip's tags ([V18Tags.STRIP_TOGGLE],
 * [V18Tags.STRIP_MORE_ROW]) and its Expanded / Collapsed states (area H). So every strip helper
 * here is scoped to the OPTIONS STRIP, never "the last node tagged ...":
 * - the strip's toggle is the [V18Tags.STRIP_TOGGLE] node inside the strip's row 1 ([V18Tags.STRIP_ROW1]);
 *   the object bar's is the one outside any row 1 ([isObjectToggle]);
 * - the strip's More row hangs under the strip's panel, outside it (`StripHost`): it is the
 *   [V18Tags.STRIP_MORE_ROW] node that is not the object bar's. The object bar's sits inside the
 *   bar ([ChromeTags.SELECTION_BAR], the tag the editor gives the bar), or, for a bar composed on
 *   its own, beside a node that holds no strip row 1 ([isObjectMoreRow]).
 *
 * [openMoreRow] / [closeMoreRow] / [toggle] / [moreRowShown] / [reach] / [inConvertedStrip] reach
 * the strip's row only; [openObjectRow] / [closeObjectRow] / [objectToggle] / [objectRowShown]
 * reach the object bar's. One More row shows at a time (H): opening either closes the other.
 */
object StripTestKit {
    /** The placed node tagged [tag] (the last one), or null. */
    fun tagged(tag: String): SemanticsNode? = RobolectricUi.elements().lastOrNull { e ->
        e.node.layoutInfo.isPlaced && e.node.tag() == tag
    }?.node

    /** The last placed node [accept] takes, or null. */
    private fun placed(accept: (SemanticsNode) -> Boolean): SemanticsNode? =
        RobolectricUi.elements().lastOrNull { e -> e.node.layoutInfo.isPlaced && accept(e.node) }?.node

    private fun SemanticsNode.tag(): String? = config.getOrNull(SemanticsProperties.TestTag)

    /** [node] or one of its ancestors is tagged [tag]. */
    fun under(node: SemanticsNode, tag: String): Boolean = generateSequence(node) { it.parent }.any { it.tag() == tag }

    /** [node] or a node below it is tagged [tag]. */
    private fun holds(node: SemanticsNode, tag: String): Boolean = node.tag() == tag || node.children.any { holds(it, tag) }

    /** [node] is the options strip's More-row toggle: tagged [V18Tags.STRIP_TOGGLE], at the end of the strip's row 1. */
    fun isStripToggle(node: SemanticsNode): Boolean = node.tag() == V18Tags.STRIP_TOGGLE && under(node, V18Tags.STRIP_ROW1)

    /** [node] is the vector object bar's toggle ([StripLabels18.MORE_OBJECT]): tagged [V18Tags.STRIP_TOGGLE], outside any strip row 1. */
    fun isObjectToggle(node: SemanticsNode): Boolean = node.tag() == V18Tags.STRIP_TOGGLE && !under(node, V18Tags.STRIP_ROW1)

    /**
     * [node] is the vector object bar's More row: tagged [V18Tags.STRIP_MORE_ROW] inside the bar
     * ([ChromeTags.SELECTION_BAR] in the editor), or beside nothing that holds a strip row 1 (a bar
     * composed on its own). The strip's More row is a sibling of its panel, which holds row 1.
     */
    fun isObjectMoreRow(node: SemanticsNode): Boolean =
        node.tag() == V18Tags.STRIP_MORE_ROW &&
            (under(node, ChromeTags.SELECTION_BAR) || node.parent?.let { !holds(it, V18Tags.STRIP_ROW1) } ?: true)

    /** [node] is the options strip's More row: tagged [V18Tags.STRIP_MORE_ROW] and not the object bar's. */
    fun isStripMoreRow(node: SemanticsNode): Boolean = node.tag() == V18Tags.STRIP_MORE_ROW && !isObjectMoreRow(node)

    /** The strip's More-row toggle, when the tool has a More row (never the object bar's). */
    fun toggle(): SemanticsNode? = placed(::isStripToggle)

    /** The vector object bar's More-row toggle ([StripLabels18.MORE_OBJECT]), when the bar shows it. */
    fun objectToggle(): SemanticsNode? = placed(::isObjectToggle)

    /** The strip's More row, when it shows (never the object bar's). */
    fun stripMoreRow(): SemanticsNode? = placed(::isStripMoreRow)

    /** The vector object bar's More row, when it shows. */
    fun objectMoreRow(): SemanticsNode? = placed(::isObjectMoreRow)

    /** The strip's More row shows (its node is placed). The object bar's does not count. */
    fun moreRowShown(): Boolean = stripMoreRow() != null

    /** The vector object bar's More row shows. */
    fun objectRowShown(): Boolean = objectMoreRow() != null

    /** Clicks [t] when it says [from] (Collapsed: opens, Expanded: closes), then settles. True when it clicked. */
    private fun flip(t: SemanticsNode?, from: String): Boolean {
        if (t == null) return false
        val state = t.config.getOrNull(SemanticsProperties.StateDescription)
        if (from == StripLabels18.EXPANDED && state != StripLabels18.EXPANDED) return false
        if (from == StripLabels18.COLLAPSED && state == StripLabels18.EXPANDED) return false
        val click = t.config.getOrNull(SemanticsActions.OnClick)?.action ?: return false
        click.invoke()
        RobolectricUi.settle(8, 50)
        return true
    }

    /**
     * Opens the strip's More row through its toggle when the toggle shows and the row is closed,
     * then lets the UI settle. True when it opened it (false: no toggle, or already open). With
     * vector objects selected, the object bar's toggle is never the one clicked (its row closes, H).
     */
    fun openMoreRow(): Boolean = flip(toggle(), StripLabels18.COLLAPSED)

    /**
     * Closes the strip's More row through its toggle when it is open, then lets the UI settle (an
     * open row swallows the next canvas touch, design D25). True when it closed it.
     */
    fun closeMoreRow(): Boolean = flip(toggle(), StripLabels18.EXPANDED)

    /**
     * Opens the vector object bar's More row (Bring forward, Send backward, Array, Detach mirror
     * copies) through its toggle when the bar shows it closed, then settles. True when it opened
     * it. The strip's More row closes as it opens (one row at a time, H).
     */
    fun openObjectRow(): Boolean = flip(objectToggle(), StripLabels18.COLLAPSED)

    /** Closes the vector object bar's More row when it is open, then settles. True when it closed it. */
    fun closeObjectRow(): Boolean = flip(objectToggle(), StripLabels18.EXPANDED)

    /**
     * [find] now; when it finds nothing and the strip has a closed More row, opens it and tries
     * once more.
     */
    fun <T : Any> reach(find: () -> T?): T? = find() ?: if (openMoreRow()) find() else null

    /**
     * [node] sits on a converted strip: its row 1 or its More row (neither scrolls at 360 dp and wider,
     * except a crowded last More line; below 360 dp row 1 scrolls sideways). The vector object bar's
     * More row is not the strip's.
     */
    fun inConvertedStrip(node: SemanticsNode): Boolean =
        generateSequence(node) { it.parent }.any { it.tag() == V18Tags.STRIP_ROW1 || isStripMoreRow(it) }

    /** [node] sits on the vector object bar's More row. */
    fun inObjectRow(node: SemanticsNode): Boolean = generateSequence(node) { it.parent }.any(::isObjectMoreRow)

    /**
     * The nearest ancestor of [node] (itself included) that takes ScrollBy, or null (row 1 never
     * scrolls at 360 dp and wider; below 360 dp this is row 1's scroller).
     */
    fun horizontalScroller(node: SemanticsNode): SemanticsNode? {
        var p: SemanticsNode? = node
        while (p != null && p.config.getOrNull(SemanticsActions.ScrollBy) == null) p = p.parent
        return p
    }
}
