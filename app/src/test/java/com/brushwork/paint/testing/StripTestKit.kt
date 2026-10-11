package com.brushwork.paint.testing

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.StripLabels18
import com.brushwork.paint.ui.common.V18Tags

/**
 * v1.8 (F3, design §4.9; frozen, the lead's): reaching the options strip's More row from tests
 * that drive the UI through [RobolectricUi] directly (`SmokeUi.find` and `click` do the same for
 * the smoke screens). A converted strip keeps its rarer controls on the More row, closed until its
 * toggle ([StripLabels18.MORE_TOGGLE], tag [V18Tags.STRIP_TOGGLE]) opens it, and its row 1 never
 * scrolls (I18): a control with no horizontal scroller around it is already in view.
 */
object StripTestKit {
    /** The placed node tagged [tag] (the last one), or null. */
    fun tagged(tag: String): SemanticsNode? = RobolectricUi.elements().lastOrNull { e ->
        e.node.layoutInfo.isPlaced && e.node.config.getOrNull(SemanticsProperties.TestTag) == tag
    }?.node

    /** The strip's More-row toggle, when the tool has a More row. */
    fun toggle(): SemanticsNode? = tagged(V18Tags.STRIP_TOGGLE)

    /** The More row shows (its node is placed). */
    fun moreRowShown(): Boolean = tagged(V18Tags.STRIP_MORE_ROW) != null

    /**
     * Opens the More row through its toggle when the toggle shows and the row is closed, then
     * lets the UI settle. True when it opened it (false: no toggle, or already open).
     */
    fun openMoreRow(): Boolean {
        val t = toggle() ?: return false
        if (t.config.getOrNull(SemanticsProperties.StateDescription) == StripLabels18.EXPANDED) return false
        val click = t.config.getOrNull(SemanticsActions.OnClick)?.action ?: return false
        click.invoke()
        RobolectricUi.settle(8, 50)
        return true
    }

    /**
     * Closes the More row through its toggle when it is open, then lets the UI settle (an open row
     * swallows the next canvas touch, design D25). True when it closed it.
     */
    fun closeMoreRow(): Boolean {
        val t = toggle() ?: return false
        if (t.config.getOrNull(SemanticsProperties.StateDescription) != StripLabels18.EXPANDED) return false
        val click = t.config.getOrNull(SemanticsActions.OnClick)?.action ?: return false
        click.invoke()
        RobolectricUi.settle(8, 50)
        return true
    }

    /**
     * [find] now; when it finds nothing and the strip has a closed More row, opens it and tries
     * once more.
     */
    fun <T : Any> reach(find: () -> T?): T? = find() ?: if (openMoreRow()) find() else null

    /** [node] sits on a converted strip: its row 1 or its More row (neither scrolls, except a crowded last More line). */
    fun inConvertedStrip(node: SemanticsNode): Boolean {
        var p: SemanticsNode? = node
        while (p != null) {
            val tag = p.config.getOrNull(SemanticsProperties.TestTag)
            if (tag == V18Tags.STRIP_ROW1 || tag == V18Tags.STRIP_MORE_ROW) return true
            p = p.parent
        }
        return false
    }

    /** The nearest ancestor of [node] (itself included) that takes ScrollBy, or null (row 1 never scrolls). */
    fun horizontalScroller(node: SemanticsNode): SemanticsNode? {
        var p: SemanticsNode? = node
        while (p != null && p.config.getOrNull(SemanticsActions.ScrollBy) == null) p = p.parent
        return p
    }
}
