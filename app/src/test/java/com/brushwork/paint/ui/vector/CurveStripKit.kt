package com.brushwork.paint.ui.vector

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.tools.strip.StripHandlesV17
import com.brushwork.paint.ui.tools.strip.StripInventory
import com.brushwork.paint.ui.tools.strip.StripLayout
import com.brushwork.paint.ui.tools.strip.StripRig
import com.brushwork.paint.ui.tools.strip.StripSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.robolectric.RuntimeEnvironment

/**
 * v1.8 E (design §3.9): what the curve strips' UI tests (`CurveStripTest`, `CurvesDropdownUiTest`,
 * `CurvesLabelsTest`) share, after area D's strip kit. Each test runs one UI test (Compose's frame
 * clock serves one test per sandbox) on a [StripRig]:
 * - [atEachWidth]: a fresh rig at 360 dp and at the T606's 392 dp;
 * - [inspect]: the lead's inventory of the state shown ([StripInventory.inspect]);
 * - [split], [row1Handles], [moreHandles], [clippedTexts]: the split and what the screen shows;
 * - [staysOpen]: a sheet or dialog opened from the More row outlives the row;
 * - [tap], [back]: a canvas tap (More row closed first: a canvas touch only closes it) and Back.
 */
internal object CurveStripKit {
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

    /** The state key of [id] on the raster layer, or the vector layer when [vector]. */
    fun key(id: ToolId, vector: Boolean) = id.name + if (vector) "@vector" else ""

    /** The inventory of the state the rig shows as [key]: no problems, converted, the More row left closed. */
    fun inspect(rig: StripRig, key: String, record: Set<String>?): Set<String> {
        rig.settle()
        val (shown, problems) = StripInventory.inspect(rig, key, record)
        assertEquals("$key: the inventory", emptyList<String>(), problems)
        assertFalse("$key: the inspection leaves the More row closed", rig.state.anyOpen)
        return shown
    }

    /** [spec]'s row 1 and More-row item keys on a [screenDp] screen with or without the VECTOR cell. */
    fun split(spec: StripSpec, screenDp: Int, chip: Boolean): Pair<List<String>, List<String>> {
        val available = StripInventory.content(screenDp.toFloat()) - if (chip) StripLayout.VECTOR_CHIP_DP + StripLayout.GAP_DP else 0f
        val s = StripLayout.split(spec, available)
        return s.row1.map { it.key } to s.more.map { it.key }
    }

    private fun SemanticsNode.tag(): String? = config.getOrNull(SemanticsProperties.TestTag)

    private fun under(n: SemanticsNode, tag: String) = generateSequence(n) { it.parent }.any { it.tag() == tag }

    /** The handles of the controls row 1 shows now, left to right. */
    fun row1Handles(): List<String> = RobolectricUi.elements().map { it.node }
        .filter { n -> n.layoutInfo.isPlaced && StripInventory.isControl(n) && n.tag() != V18Tags.STRIP_TOGGLE && under(n, V18Tags.STRIP_ROW1) }
        .sortedBy { it.positionInWindow.x }
        .mapNotNull(StripInventory::handle)

    /** The handles of the controls the More row shows now (it must be open). */
    fun moreHandles(): List<String> = RobolectricUi.elements().map { it.node }
        .filter { n -> n.layoutInfo.isPlaced && StripInventory.isControl(n) && under(n, V18Tags.STRIP_MORE_ROW) }
        .mapNotNull(StripInventory::handle)

    /** The texts on row 1 and the More row that do not fit their fixed-width item (cut, ellipsized or past their lines). */
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
            if (wider || shortened) "$text (${r.lineCount} lines in ${n.size.width} px)" else null
        }

    /** Back on the top window (a sheet, a dialog or a popup closes). */
    fun back(rig: StripRig) {
        val top = RobolectricUi.windowRoots().lastOrNull() ?: return
        if (top === rig.activity.window.decorView) return
        top.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        top.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        rig.settle()
    }

    /**
     * [label] is the handle of a control on the open More row, or the click label of one (an item
     * known by its value, as the line width's).
     */
    fun onTheMoreRow(label: String): Boolean = label in moreHandles() ||
        RobolectricUi.elements().map { it.node }.any { n ->
            n.layoutInfo.isPlaced && under(n, V18Tags.STRIP_MORE_ROW) && n.config.getOrNull(SemanticsActions.OnClick)?.label == label
        }

    /**
     * §3.9 "Overlays": opens the More row, clicks [label] on it, and checks the row closed and
     * [shows] (the sheet or dialog) holds, and still holds once the UI settled again. Leaves the
     * overlay open (the caller closes it).
     */
    fun staysOpen(rig: StripRig, label: String, what: String, shows: () -> Boolean) {
        StripTestKit.openMoreRow()
        assertTrue("$what: the More row is open", StripTestKit.moreRowShown())
        assertTrue("$what: \"$label\" is on the More row: ${moreHandles()}", onTheMoreRow(label))
        SmokeUi.click(label, exact = true)
        rig.settle()
        assertFalse("$what: the More row closed", StripTestKit.moreRowShown())
        assertTrue("$what: open", shows())
        rig.settle()
        assertTrue("$what: still open after the row closed", shows())
    }

    /**
     * §3.9 "Dropdowns": opens the More row, clicks [label] (a popup anchored in its item), checks
     * [shows] and that the row stays open, then dismisses the popup (Back) and checks the row is
     * still open. Leaves the More row open.
     */
    fun popupKeepsTheRow(rig: StripRig, label: String, what: String, shows: () -> Boolean) {
        StripTestKit.openMoreRow()
        assertTrue("$what: \"$label\" is on the More row: ${moreHandles()}", onTheMoreRow(label))
        SmokeUi.click(label, exact = true)
        rig.settle()
        assertTrue("$what: open", shows())
        assertTrue("$what: the More row stays open under the popup", StripTestKit.moreRowShown())
        back(rig)
        assertFalse("$what: dismissed", shows())
        assertTrue("$what: the More row stays open after the popup", StripTestKit.moreRowShown())
    }

    /** A canvas tap at document ([x], [y]) through the controller (the More row closed first). */
    fun tap(rig: StripRig, x: Float, y: Float) {
        StripTestKit.closeMoreRow()
        rig.c.pointerDown(ToolPoint(x, y))
        rig.c.pointerUp(ToolPoint(x, y))
        rig.settle()
    }

    /** The handles of the record that §3.9 removes or renames, as the strip must offer them. */
    fun record(key: String): Set<String> = StripHandlesV17.HANDLES.getValue(key)
}
