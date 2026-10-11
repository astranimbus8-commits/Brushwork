package com.brushwork.paint.ui.placement

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.tools.strip.StripHandlesV17
import com.brushwork.paint.ui.tools.strip.StripInventory
import com.brushwork.paint.ui.tools.strip.StripLayout
import com.brushwork.paint.ui.tools.strip.StripRig
import com.brushwork.paint.ui.tools.strip.StripSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.robolectric.RuntimeEnvironment

/**
 * v1.8 G (design §3.9, §4.9): what area G's strip tests (`PlacementStripTest`,
 * `TextFrameStripTest`, `MaskStripTest`, `RemoveStripTest`, `PathfinderStripTest`) share, as D's
 * strip tests do. Each runs one UI test (Compose's frame clock serves one test per sandbox) over
 * the two phone widths, a [StripRig] per width:
 * - [inspect]: the lead's inventory ([StripInventory.inspect]: rules 1 to 5, the measured widths,
 *   the v1.7 handles of [StripHandlesV17] on row 1, the More row or inside an item) and
 *   `legacyContent == null`;
 * - [assertSplits]: rule 3, row 1 and the More row of the spec at each of the four
 *   configurations (360 or 392 dp, with the VECTOR cell or not), pure;
 * - [row1Handles] / [moreHandles]: what the rows show on the screen now;
 * - [clippedTexts]: no label cut by its item's declared width;
 * - [staysOpen]: a sheet or dialog opened from the More row outlives the row; [popupKeepsRow]: a
 *   dropdown or popup opened from the More row keeps it open.
 */
internal object PlacementStripKit {
    /** The two phones of §3.9: 360 dp and the T606's 392 dp. */
    val WIDTHS = listOf(360 to "w360dp-h780dp-xxhdpi", 392 to "w392dp-h873dp-xxhdpi")

    /** Runs [block] on a fresh strip rig at each of [WIDTHS] (the screen's width in dp). */
    fun atEachWidth(block: (rig: StripRig, screenDp: Int) -> Unit) {
        for ((dp, qualifiers) in WIDTHS) {
            RuntimeEnvironment.setQualifiers(qualifiers)
            val rig = StripRig(panel = false).show()
            try {
                block(rig, dp)
            } finally {
                StripTestKit.closeMoreRow()
                rig.close()
                // The next width's rig is a new activity: this one's window leaves the screen.
                runCatching { rig.activity.windowManager.removeViewImmediate(rig.activity.window.decorView) }
                RobolectricUi.settle(2, 50)
            }
        }
    }

    /** The inventory of the state the rig shows as [key] ("REMOVE", "MASK@vector", ...): no problems, converted. */
    fun inspect(rig: StripRig, key: String, record: Set<String>? = StripHandlesV17.HANDLES[key]): Set<String> {
        assertNull("$key: converted (no legacy content)", rig.spec.legacyContent)
        val (shown, problems) = StripInventory.inspect(rig, key, record)
        assertEquals("$key: the inventory", emptyList<String>(), problems)
        assertFalse("$key: the inspection leaves the More row closed", rig.state.anyOpen)
        return shown
    }

    /** [spec]'s row 1 and More-row item keys, and the toggle, on a [screenDp] screen with or without the VECTOR cell. */
    fun split(spec: StripSpec, screenDp: Int, chip: Boolean): Triple<List<String>, List<String>, Boolean> {
        val available = StripInventory.content(screenDp.toFloat()) - if (chip) StripLayout.VECTOR_CHIP_DP + StripLayout.GAP_DP else 0f
        val s = StripLayout.split(spec, available)
        return Triple(s.row1.map { it.key }, s.more.map { it.key }, s.toggle)
    }

    /** Rule 3 at all four configurations: row 1 as [row1] says for each, the rest on the More row. */
    fun assertSplits(what: String, spec: StripSpec, row1: (screenDp: Int, chip: Boolean) -> List<String>) {
        val all = spec.items.map { it.key }
        for (dp in listOf(360, 392)) for (chip in listOf(false, true)) {
            val (r1, more, toggle) = split(spec, dp, chip)
            val want = row1(dp, chip)
            val where = "$what at $dp dp${if (chip) " with the VECTOR cell" else ""}"
            assertEquals("$where: row 1", want, r1)
            assertEquals("$where: the More row", all - want.toSet(), more)
            assertEquals("$where: the toggle", more.isNotEmpty() || spec.hint != null, toggle)
        }
    }

    private fun SemanticsNode.tag(): String? = config.getOrNull(SemanticsProperties.TestTag)

    private fun under(n: SemanticsNode, tag: String) = generateSequence(n) { it.parent }.any { it.tag() == tag }

    /** The handles of the controls row 1 shows now, left to right. */
    fun row1Handles(): List<String> = RobolectricUi.elements().map { it.node }
        .filter { n -> n.layoutInfo.isPlaced && StripInventory.isControl(n) && n.tag() != V18Tags.STRIP_TOGGLE && under(n, V18Tags.STRIP_ROW1) }
        .sortedBy { it.positionInWindow.x }
        .mapNotNull(StripInventory::handle)

    /** The handles of the controls the More row shows now (it must be open), line by line, left to right. */
    fun moreHandles(): List<String> = RobolectricUi.elements().map { it.node }
        .filter { n -> n.layoutInfo.isPlaced && StripInventory.isControl(n) && under(n, V18Tags.STRIP_MORE_ROW) }
        .sortedWith(compareBy({ it.positionInWindow.y.toInt() / 8 }, { it.positionInWindow.x }))
        .mapNotNull(StripInventory::handle)

    /** The texts on the More row now that are not in controls (the hint). */
    fun moreTexts(): List<String> = RobolectricUi.elements().map { it.node }
        .filter { n -> n.layoutInfo.isPlaced && under(n, V18Tags.STRIP_MORE_ROW) && generateSequence(n) { it.parent }.none(StripInventory::isControl) }
        .flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    /** The texts on row 1 now that are not controls (a status text). */
    fun row1Texts(): List<String> = RobolectricUi.elements().map { it.node }
        .filter { n -> n.layoutInfo.isPlaced && under(n, V18Tags.STRIP_ROW1) && generateSequence(n) { it.parent }.none(StripInventory::isControl) }
        .flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    /**
     * The texts on row 1 and the More row that do not fit their fixed-width item (cut, ellipsized
     * or past their lines): the declared widths are measured, so a label must fit inside them.
     */
    fun clippedTexts(): List<String> = RobolectricUi.elements().map { it.node }
        .filter { n -> n.layoutInfo.isPlaced && (under(n, V18Tags.STRIP_ROW1) || under(n, V18Tags.STRIP_MORE_ROW)) }
        .mapNotNull { n ->
            val get = n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: return@mapNotNull null
            val results = mutableListOf<TextLayoutResult>()
            if (get(results) != true) return@mapNotNull null
            val r = results.firstOrNull() ?: return@mapNotNull null
            val text = r.layoutInput.text.text
            val lines = 0 until r.lineCount
            val wider = lines.any { r.getLineRight(it) - r.getLineLeft(it) > n.size.width + 1f }
            val shortened = r.lineCount == 0 || r.getLineEnd(r.lineCount - 1) < text.length || lines.any { r.isLineEllipsized(it) }
            if (wider || shortened) "$text (lines ${lines.map { (r.getLineRight(it) - r.getLineLeft(it)).toInt() }} px in ${n.size.width} px, ${if (r.lineCount > 0) r.getLineEnd(r.lineCount - 1) else 0} of ${text.length} chars)" else null
        }

    /** Back on the top window (a sheet, a dialog or a popup closes). */
    fun back(rig: StripRig) {
        val top = RobolectricUi.windowRoots().lastOrNull() ?: return
        if (top === rig.activity.window.decorView) return
        top.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        top.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        rig.settle()
    }

    /** Whether a window (a popup, a dialog, a sheet) shows over the rig's activity. */
    fun overlayWindowShown(rig: StripRig): Boolean = RobolectricUi.windowRoots().lastOrNull().let { it != null && it !== rig.activity.window.decorView }

    /**
     * Whether the sheet titled [title] shows: hosted (the editor's sheet host), or, as in the rig
     * (no host), a modal window of its own with the title.
     */
    fun sheetShown(rig: StripRig, title: String): Boolean =
        title in SmokeUi.sheetTitles() || (overlayWindowShown(rig) && SmokeUi.has(title, exact = true))

    /**
     * §3.9 "Overlays": opens the More row, clicks [label] on it, and checks the row closed and
     * [shows] (the sheet or dialog) holds, and still holds once the UI settled again. Leaves the
     * overlay open (the caller closes it).
     */
    fun staysOpen(rig: StripRig, label: String, what: String, shows: () -> Boolean) {
        assertTrue("$what: the More row opens", StripTestKit.openMoreRow())
        assertTrue("$what: \"$label\" is on the More row: ${moreHandles()}", label in moreHandles())
        SmokeUi.click(label, exact = true)
        rig.settle()
        assertFalse("$what: the More row closed", StripTestKit.moreRowShown())
        assertTrue("$what: open", shows())
        rig.settle()
        assertTrue("$what: still open after the row closed", shows())
    }

    /**
     * §3.9 "Dropdowns": opens the More row, clicks [label] on it, and checks [shows] (the dropdown
     * or popup) holds with the row still open, and the row stays open once it is dismissed (Back).
     */
    fun popupKeepsRow(rig: StripRig, label: String, what: String, shows: () -> Boolean) {
        assertTrue("$what: the More row opens", StripTestKit.openMoreRow())
        assertTrue("$what: \"$label\" is on the More row: ${moreHandles()}", label in moreHandles())
        SmokeUi.click(label, exact = true)
        rig.settle()
        assertTrue("$what: open", shows())
        assertTrue("$what: the More row stays open", StripTestKit.moreRowShown())
        back(rig)
        assertFalse("$what: dismissed", shows())
        assertTrue("$what: the More row stays open after it", StripTestKit.moreRowShown())
        StripTestKit.closeMoreRow()
    }

    /** The state key of [id] on the raster layer, or the vector layer when [vector]. */
    fun key(id: ToolId, vector: Boolean) = id.name + if (vector) "@vector" else ""
}
