package com.brushwork.paint.qa19

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.filters.FilterSession
import com.brushwork.paint.filters.art.CaBlend
import com.brushwork.paint.filters.art.ChromaticAberrationMovingFilter
import com.brushwork.paint.masks.AdjustmentEffects
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.FilterLabels19
import com.brushwork.paint.ui.common.FringeLabels18
import com.brushwork.paint.ui.common.V19Tags
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.ui.mask.MaskStripItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue

/**
 * v1.9 (R8, R10, A2's `Qa19FilterUndoUiTest` and its narrow twin): a filter panel in the editor,
 * driven by touches found by label or tag.
 * - Brightness / Contrast: a slider drag is one step; its row then shows the reset icon after the
 *   value; a two-finger tap in the panel (its first finger on the slider) undoes that step and
 *   leaves the panel open at its start (FC10).
 * - Chromatic aberration: "Blend mode" (a dropdown of the layers panel's modes) and "Opacity"
 *   after the v1.8 controls; a drag of Opacity and a pick of Multiply are a step each, and each
 *   row keeps its label, value and reset icon apart, on one line; a two-finger tap undoes the pick.
 *   The fringes' "No color" chip and their reset icon are a step each, and the chips under the
 *   fringes' label row stay put as the icon shows and goes (the row's height is constant).
 * - An adjustment layer's sheet: a press of + is "Change <label>" and its row keeps the value and
 *   the reset icon on one line (R8, at 360 dp).
 * Each part ends with [Qa19Ui.assertNoTextOverflow].
 */
internal object Qa19FilterUndo {

    /** Every part at the width of the calling class, at font scale 1 and each of [Qa19Ui.FONT_SCALES]. */
    fun run(h: Qa19Harness, widthDp: Float) {
        for (scale in listOf(1f) + Qa19Ui.FONT_SCALES) {
            val where = "${widthDp.toInt()} dp at font scale $scale"
            h.section("two-finger tap in a panel, $where") {
                val s = h.editor(fontScale = scale)
                assertEquals("the phone's width", widthDp, s.widthDp, 1f)
                twoFingerTapInAPanel(s, where)
            }
            h.section("the Chromatic aberration panel, $where") {
                chromaticAberration(h.editor(fontScale = scale), where)
            }
            h.section("an adjustment sheet, $where") {
                val s = h.editor(fontScale = scale) { c ->
                    c.selectTool(ToolId.MASK)
                    requireNotNull(c.addAdjustmentLayer(AdjustmentEffects.defaultSpec(FilterRegistry.byId("adjust.tone")!!), null))
                }
                adjustmentSheet(s, where)
            }
        }
    }

    private fun twoFingerTapInAPanel(s: ChromeScreen, where: String) {
        val c = s.c
        c.startFilter(FilterRegistry.byId("adjust.brightness_contrast")!!)
        Qa19Ui.settle()
        val session = requireNotNull(c.filterSession) { "$where: the panel opened" }
        val panel = panelOf("Brightness slider")

        // A drag of the slider: one step, named after it; the reset icon shows after the value.
        dragAcross(s, find(panel, "Brightness slider"), 0.5f, 0.8f)
        assertEquals("$where: the drag is one step", 1, session.history.depth)
        assertEquals("Brightness", session.undoLabel)
        assertTrue("$where: the drag moved the value", session.values.float("brightness") > 0f)
        assertRowApart(s, panel, "Brightness", V19Tags.FILTER_RESET_PREFIX + "brightness", where)
        Qa19Ui.assertNoTextOverflow("the Brightness / Contrast panel, $where", cutOnPurpose(session))

        // Two fingers tap the panel, the first on the slider (which moves it at its DOWN): the
        // step is undone, the first finger's move with it, and the panel stays open at its start.
        twoFingersOn(s, find(panel, "Brightness slider"))
        assertSame("$where: the panel stays open", session, c.filterSession)
        assertEquals("$where: the step undone", 0, session.history.depth)
        assertEquals("$where: back at the default", 0f, session.values.float("brightness"), 0f)
        assertFalse("$where: no step for the tap's first finger", session.canUndo)
        assertTrue("$where: the reset icon is gone with the change", tagIn(panel, V19Tags.FILTER_RESET_PREFIX + "brightness") == null)
        session.cancel()
        Qa19Ui.settle()
        Smoke.assertQuiet(c, "the Brightness / Contrast panel, $where")
    }

    private fun chromaticAberration(s: ChromeScreen, where: String) {
        val c = s.c
        c.startFilter(ChromaticAberrationMovingFilter())
        Qa19Ui.settle()
        val session: FilterSession = requireNotNull(c.filterSession) { "$where: the panel opened" }
        val panel = panelOf(CaBlend.modeParam.label)
        val opacity = CaBlend.opacityParam
        val mode = CaBlend.modeParam

        // Opacity: a drag down from 100 % is one step; its row keeps label, value and icon apart.
        dragAcross(s, find(panel, "${opacity.label} slider"), 0.9f, 0.45f)
        assertEquals(1, session.history.depth)
        assertEquals(opacity.label, session.undoLabel)
        assertTrue("$where: Opacity below 100 %", session.values.float(opacity.key) < 100f)
        assertRowApart(s, panel, opacity.label, V19Tags.FILTER_RESET_PREFIX + opacity.key, where)

        // Blend mode: the menu opens with the layers panel's modes; Multiply is one step.
        val label = find(panel, mode.label, exact = true)
        val button = RobolectricUi.elements().filter { e ->
            e.node.config.getOrNull(SemanticsActions.OnClick) != null && inside(e.node, panel) && e.bounds.left >= label.bounds.right - 1f &&
                e.bounds.center.y > label.bounds.top - 2f && e.bounds.center.y < label.bounds.bottom + 2f
        }.minByOrNull { it.bounds.left } ?: throw AssertionError("$where: no button beside \"${mode.label}\"")
        button.tap()
        Qa19Ui.settle()
        for (m in LayerBlendMode.entries) {
            if (m.ordinal > LayerBlendMode.SCREEN.ordinal) break // the first rows; the menu scrolls
            assertTrue("$where: the menu shows ${m.label}", Qa19Ui.has(m.label, exact = true))
        }
        Qa19Ui.assertNoTextOverflow("the blend mode menu, $where", cutOnPurpose(session))
        Qa19Ui.click(LayerBlendMode.MULTIPLY.label, exact = true)
        assertEquals(LayerBlendMode.MULTIPLY.ordinal, session.values.choice(mode.key))
        assertEquals("$where: the pick is one step", 2, session.history.depth)
        assertEquals(mode.label, session.undoLabel)
        assertRowApart(s, panel, mode.label, V19Tags.FILTER_RESET_PREFIX + mode.key, where)
        Qa19Ui.assertNoTextOverflow("the Chromatic aberration panel, $where", cutOnPurpose(session))

        // Two fingers on the panel take the pick back; Opacity's step stays.
        twoFingersOn(s, find(panel, "${opacity.label} slider"))
        assertSame(session, c.filterSession)
        assertEquals(LayerBlendMode.NORMAL.ordinal, session.values.choice(mode.key))
        assertEquals(1, session.history.depth)
        assertTrue(session.values.float(opacity.key) < 100f)

        // A label row is as tall with its reset icon as without (A2 review): the fringes' "No
        // color" chip, under their label row, stays where it was when its tap shows the icon and
        // when the icon's tap takes the change back (a step each).
        val fringes = session.filter.params.first { it is FilterParam.Fringes }
        val fringeReset = V19Tags.FILTER_RESET_PREFIX + fringes.key
        // The chip's top in the window, looked up without scrolling (find scrolls into view).
        fun chipTop(): Float = RobolectricUi.elements().lastOrNull { e -> inside(e.node, panel) && labels(e.node).any { it == FringeLabels18.NO_COLOR } }
            ?.let { extent(it.node).top } ?: throw AssertionError("$where: no \"${FringeLabels18.NO_COLOR}\": ${Qa19Ui.shown()}")
        val noColor = find(panel, FringeLabels18.NO_COLOR)
        assertTrue("$where: no fringe reset icon at the default", tagIn(panel, fringeReset) == null)
        val shownAt = chipTop()
        noColor.tap()
        Qa19Ui.settle()
        assertEquals("$where: the chip is one step", 2, session.history.depth)
        assertEquals(fringes.label, session.undoLabel)
        assertTrue("$where: the fringe reset icon off the default", tagIn(panel, fringeReset) != null)
        assertEquals("$where: the chips stay put as the reset icon shows", shownAt, chipTop(), 1f)
        val reset = intoView { tagIn(panel, fringeReset) ?: throw AssertionError("$where: no fringe reset icon: ${Qa19Ui.shown()}") }
        val goneAt = chipTop()
        reset.tap()
        Qa19Ui.settle()
        assertEquals("$where: the reset is one step", 3, session.history.depth)
        assertEquals(String.format(FilterLabels19.RESET_ONE, fringes.label), session.undoLabel)
        assertTrue("$where: the reset hides its icon", tagIn(panel, fringeReset) == null)
        assertEquals("$where: the chips stay put as the reset icon goes", goneAt, chipTop(), 1f)
        session.cancel()
        Qa19Ui.settle()
        Smoke.assertQuiet(c, "the Chromatic aberration panel, $where")
    }

    private fun adjustmentSheet(s: ChromeScreen, where: String) {
        val c = s.c
        Qa19Ui.click("Adjust…", exact = true)
        val before = c.undoManager.undoCount
        val plus = Qa19Ui.find("Increase Exposure", exact = true) ?: throw AssertionError("$where: no + of Exposure: ${Qa19Ui.shown()}")
        plus.tap()
        Qa19Ui.settle()
        val panel = panelOf("Exposure slider")
        assertRowApart(s, panel, "Exposure", V19Tags.FILTER_RESET_PREFIX + "exposure", where)
        Qa19Ui.assertNoTextOverflow("the Adjust sheet, $where", cutOnPurpose(null))
        Qa19Ui.click("Close", exact = true)
        assertEquals("$where: the press is one step", before + 1, c.undoManager.undoCount)
        assertEquals(String.format(FilterLabels19.CHANGE_STEP, "Exposure"), c.undoManager.undoLabel)
        c.liveAdjust.release()
    }

    // ------------------------------------------------------------------ finding and touching

    /**
     * The v1.8 texts that end in "…" on purpose at these widths and font scales, which the overflow
     * check skips by content: the panel's title (the filter's name, beside four buttons) and its
     * "As adjustment layer" note, the Masks strip's chips and the values of the chrome's slider
     * rows. A2's own texts (the labels, values, reset icons, the dropdown and its menu, the sheet's
     * rows) stay checked.
     */
    private fun cutOnPurpose(session: FilterSession?): (String) -> Boolean {
        val rows = RobolectricUi.elements().filter { e ->
            generateSequence(e.node) { it.parent }.any { it.config.getOrNull(SemanticsProperties.TestTag) == ChromeTags.SLIDER_ROWS }
        }.flatMap { labels(it.node) }.toSet()
        val strip = with(MaskStripItems) { setOf(add(LINEAR), add(RADIAL), add(BRUSH), INVERT_LABEL, ADJUST_LABEL) }
        // FilterUi's AsAdjustmentLayerRow: its one-line note.
        val notes = setOf("Stays editable · changes every layer below", "Stays editable · the selection becomes its mask")
        val title = session?.filter?.name
        return { t -> t == title || t in notes || t in strip || t in rows }
    }

    /**
     * The scrolling column that holds the element named [label] (a panel or a sheet). Looked up
     * without [Qa19Ui.find], which opens the strip's More row when it sees no such label (an open
     * row takes the next touch, D25).
     */
    private fun panelOf(label: String): SemanticsNode {
        val e = RobolectricUi.elements().lastOrNull { labels(it.node).any { t -> t == label } }
            ?: throw AssertionError("no \"$label\": ${Qa19Ui.shown()}")
        return generateSequence(e.node.parent) { it.parent }.firstOrNull {
            it.config.getOrNull(SemanticsActions.ScrollBy) != null && it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null
        } ?: e.node.root()
    }

    private fun SemanticsNode.root(): SemanticsNode = generateSequence(this) { it.parent }.last()

    private fun inside(n: SemanticsNode, panel: SemanticsNode): Boolean = generateSequence(n) { it.parent }.any { it.id == panel.id }

    private fun labels(n: SemanticsNode): List<String> =
        n.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty() + n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()

    /** The element of [panel] named [label] (a text or a description), scrolled fully into the panel. */
    private fun find(panel: SemanticsNode, label: String, exact: Boolean = true): RobolectricUi.Element {
        fun now() = RobolectricUi.elements().lastOrNull { e ->
            inside(e.node, panel) && labels(e.node).any { if (exact) it == label else it.contains(label) }
        } ?: throw AssertionError("nothing named \"$label\" in the panel: ${Qa19Ui.shown()}")
        return intoView(::now)
    }

    private fun tagIn(panel: SemanticsNode, tag: String): RobolectricUi.Element? =
        RobolectricUi.elements().lastOrNull { inside(it.node, panel) && it.node.config.getOrNull(SemanticsProperties.TestTag) == tag }

    /**
     * [find]'s element after scrolling its scrolling ancestors until it is fully inside them, as a
     * user would. A scroll animates, and a touch while it runs only stops it (as on a moving list):
     * each one is let finish before the next look.
     */
    private fun intoView(find: () -> RobolectricUi.Element): RobolectricUi.Element {
        repeat(8) {
            val el = find()
            val r = extent(el.node)
            val scrollers = generateSequence(el.node.parent) { it.parent }
                .filter { it.config.getOrNull(SemanticsActions.ScrollBy) != null && it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
                .toList().asReversed()
            for (n in scrollers) {
                val v = extent(n)
                val delta = when { r.bottom > v.bottom -> r.bottom - v.bottom + 8f; r.top < v.top -> r.top - v.top - 8f; else -> 0f }
                if (delta != 0f) {
                    n.config[SemanticsActions.ScrollBy].action!!.invoke(0f, delta)
                    Qa19Ui.settle()
                    return@repeat
                }
            }
            return el
        }
        return find()
    }

    /** [n]'s whole extent in window pixels (not clipped by a scroller). */
    private fun extent(n: SemanticsNode): Rect {
        val p = n.positionInWindow
        return Rect(p.x, p.y, p.x + n.size.width, p.y + n.size.height)
    }

    /** One finger across [slider] from [from] to [to] of its width, slowly (a drag, finished at its UP). */
    private fun dragAcross(s: ChromeScreen, slider: RobolectricUi.Element, from: Float, to: Float) {
        val b = extent(slider.node)
        val y = b.center.y
        s.touch.stroke(b.left + b.width * from to y, b.left + b.width * (from + to) / 2f to y, b.left + b.width * to to y)
        Qa19Ui.settle()
    }

    /** Two fingers tap [on] (the first at a third of it, the second at two thirds): undo. */
    private fun twoFingersOn(s: ChromeScreen, on: RobolectricUi.Element) {
        val b = extent(on.node)
        val y = b.center.y
        s.touch.twoFingerTap(b.left + b.width / 3f to y, b.left + b.width * 2f / 3f to y)
        Smoke.pump(300)
        Qa19Ui.settle()
    }

    /**
     * The row of [label]: the label's text, the value after it and the reset icon (tagged [resetTag])
     * at its far end lie on one line, in that order, apart: the icon at least 8 dp after the value,
     * a whole 40 dp target inside the window.
     */
    private fun assertRowApart(s: ChromeScreen, panel: SemanticsNode, label: String, resetTag: String, where: String) {
        val text = find(panel, label, exact = true)
        val reset = tagIn(panel, resetTag) ?: throw AssertionError("$where: no reset icon for \"$label\": ${Qa19Ui.shown()}")
        val l = extent(text.node)
        val r = extent(reset.node)
        val value = RobolectricUi.elements().filter { e ->
            val b = extent(e.node)
            inside(e.node, panel) && e.node.id != text.node.id && !inside(e.node, reset.node) &&
                e.node.config.getOrNull(SemanticsProperties.Text) != null &&
                b.center.y > r.top && b.center.y < r.bottom && b.left >= l.left && b.right <= r.left + 0.5f
        }.maxByOrNull { extent(it.node).right } ?: throw AssertionError("$where: no value between \"$label\" and its reset icon")
        val v = extent(value.node)
        val d = s.density
        val why = "$where, \"$label\": label $l, value ${labels(value.node)} $v, reset $r"
        assertTrue("one line: $why", l.center.y > r.top && l.center.y < r.bottom && v.center.y > r.top && v.center.y < r.bottom)
        assertTrue("the label before the value: $why", l.right <= v.left + 0.5f)
        assertTrue("the reset icon at least 8 dp after the value: $why", r.left - v.right >= 8f * d - 1f)
        assertTrue("a 40 dp target: $why", r.width >= 40f * d - 1f && r.height >= 40f * d - 1f)
        assertTrue("inside the window: $why", r.right <= s.root.right + 1f)
    }
}
