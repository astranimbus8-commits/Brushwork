package com.brushwork.paint.ui.vector

import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.common.CurveGenLabels18
import com.brushwork.paint.ui.common.PaintMode
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.tools.strip.StripHandlesV17
import com.brushwork.paint.ui.tools.strip.StripInventory
import com.brushwork.paint.ui.tools.strip.StripRig
import com.brushwork.paint.ui.tools.strip.StripRow
import com.brushwork.paint.ui.vector.CurveStripKit.atEachWidth
import com.brushwork.paint.ui.vector.CurveStripKit.back
import com.brushwork.paint.ui.vector.CurveStripKit.inspect
import com.brushwork.paint.ui.vector.CurveStripKit.key
import com.brushwork.paint.ui.vector.CurveStripKit.moreHandles
import com.brushwork.paint.ui.vector.CurveStripKit.popupKeepsTheRow
import com.brushwork.paint.ui.vector.CurveStripKit.record
import com.brushwork.paint.ui.vector.CurveStripKit.row1Handles
import com.brushwork.paint.ui.vector.CurveStripKit.split
import com.brushwork.paint.ui.vector.CurveStripKit.staysOpen
import com.brushwork.paint.ui.vector.CurveStripKit.tap
import com.brushwork.paint.vector.CurveGenKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 E (design §3.9 "Curve", "Polyline", "Path"): the three converted curve strips on the screen,
 * at 360 and 392 dp, on the raster and the vector layer (the VECTOR cell); the same specs, pure,
 * over every state: `CurveStripRulesTest`.
 * - Every state (nothing open with a brush or a plain line; open with the last point, none, one or
 *   every point selected; a generated curve) passes the lead's inventory (rules 1 to 5, measured
 *   widths, the v1.7 handles of the record with nothing open) and is converted.
 * - Row 1 shows what the split says, left to right, and no text is cut on row 1 or the More row.
 * - The removed duplicates (point Undo / Redo, "Select several") show nowhere, More row closed or open.
 * - The settings sheets (by the settings item and by Path's line width), the Numbers sheet, the
 *   typing dialogs (thickness; handle scale and point weight from their popups) and the generator
 *   sheet, opened from the More row, outlive the row; the Handles, Order and Point weight popups
 *   and the "Snap to objects" toggle keep the row open, during and after them.
 * The AUTO items, and that no width adds one, are checked over every state in `CurveStripRulesTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h780dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.vector.curvestripsandbox"])
class CurveStripTest {

    private val gone = StripHandlesV17.REMOVED + PointLabels.SELECT_SEVERAL

    /**
     * A curve tool's settings sheet shows: its paint mode's Fill segment, the stroke-less mode
     * (v1.10 Gate 2: the sheet's stroke kinds no longer offer "No stroke", the probe until then).
     */
    private fun settingsSheetShown() = StripTestKit.tagged("${PaintModeTags.CURVE}.${PaintMode.FILL.name}") != null

    /** Row 1 on the screen is the split's row 1 ([CurveKeys.LINE_WIDTH] shows its value, not a label). */
    private fun assertRow1(rig: StripRig, dp: Int, vector: Boolean, what: String) {
        val (keys, _) = split(rig.spec, dp, vector)
        val shown = row1Handles()
        assertEquals("$what at $dp dp: row 1 $shown", keys.size, shown.size)
        keys.forEachIndexed { i, k ->
            if (k != CurveKeys.LINE_WIDTH) assertEquals("$what at $dp dp: row 1 item ${i + 1}", rig.spec.items.first { it.key == k }.label, shown[i])
        }
        assertEquals("$what at $dp dp: row 1 texts fit", emptyList<String>(), CurveStripKit.clippedTexts())
    }

    /** No removed duplicate on the strip, the More row closed and open; leaves it closed. */
    private fun assertNoneGone(what: String) {
        StripTestKit.closeMoreRow()
        assertEquals("$what: removed duplicates on row 1", emptySet<String>(), StripInventory.handlesShown().intersect(gone))
        if (StripTestKit.openMoreRow()) {
            assertEquals("$what: removed duplicates on the More row", emptySet<String>(), StripInventory.handlesShown().intersect(gone))
            assertEquals("$what: More row texts fit", emptyList<String>(), CurveStripKit.clippedTexts())
            StripTestKit.closeMoreRow()
        }
    }

    /**
     * The popup of [item] (on the More row) types its value: [type] there closes the popup and the
     * row and opens the typing dialog (it shows [range]), which holds once the UI settled; Back
     * closes it.
     */
    private fun typedFromPopup(rig: StripRig, item: String, type: String, range: String, what: String) {
        StripTestKit.openMoreRow()
        SmokeUi.click(item, exact = true)
        rig.settle()
        SmokeUi.click(type, exact = true)
        rig.settle()
        assertFalse("$what: the More row closed", StripTestKit.moreRowShown())
        assertTrue("$what: open", SmokeUi.has(range))
        rig.settle()
        assertTrue("$what: still open", SmokeUi.has(range))
        back(rig)
        assertFalse("$what: closed", SmokeUi.has(range))
    }

    @Test
    fun theCurveStripsKeepTheirControlsAtBothWidths() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        atEachWidth { rig, dp ->
            for (vector in listOf(false, true)) for (id in listOf(ToolId.CURVE, ToolId.POLYLINE, ToolId.PATH)) {
                val k = key(id, vector)
                rig.select(id, vector)
                val t = rig.c.tools.getValue(id) as CurveTool
                // Nothing open: the record of v1.7's handles, by the stroke kind.
                for (stroke in listOf(CurveStroke.BRUSH, CurveStroke.PLAIN)) {
                    t.update { it.copy(stroke = stroke) }
                    val what = "$k at $dp dp, ${stroke.name}"
                    inspect(rig, k, record(k))
                    assertRow1(rig, dp, vector, what)
                    assertNoneGone(what)
                }
                // Open: three points, then the selections.
                tap(rig, 100f, 100f)
                tap(rig, 200f, 150f)
                tap(rig, 300f, 100f)
                assertEquals(3, t.pointCount)
                val states = listOf<Pair<String, () -> Unit>>(
                    "the last point" to {},
                    "no point" to { t.deselect() },
                    "one point" to { t.select(1) },
                    "every point" to { t.toggleSelectAll() },
                )
                for ((name, set) in states) {
                    set()
                    rig.settle()
                    val what = "$k at $dp dp, open, $name selected"
                    inspect(rig, what, null)
                    assertRow1(rig, dp, vector, what)
                    assertNoneGone(what)
                }
                t.discard()
                rig.settle()
                if (id != ToolId.POLYLINE) {
                    assertTrue(t.startGenerator(CurveGenKind.ROSE, null))
                    rig.settle()
                    val what = "$k at $dp dp, a generated rose"
                    inspect(rig, what, null)
                    assertRow1(rig, dp, vector, what)
                    assertNoneGone(what)
                    val chip = rig.spec.items.first { it.key == CurveKeys.GENERATOR }
                    assertEquals(CurveGenLabels18.CHANGE, chip.label)
                    val onRow1 = CurveGenLabels18.CHANGE in row1Handles()
                    // Curve: row 1 (AUTO 1: the More row's front with the VECTOR cell); Path: the More row.
                    assertEquals("$what: the chip on row 1", id == ToolId.CURVE && !vector, onRow1)
                    assertEquals(if (id == ToolId.CURVE) StripRow.ROW1 else StripRow.MORE, chip.row)
                    if (!onRow1) {
                        assertTrue(StripTestKit.openMoreRow())
                        val more = moreHandles()
                        assertTrue("$what: the chip on the More row: $more", CurveGenLabels18.CHANGE in more)
                        if (id == ToolId.CURVE) assertEquals("$what: the chip leads the More row", CurveGenLabels18.CHANGE, more.first())
                        StripTestKit.closeMoreRow()
                    }
                    // Its visible text is the generator's name.
                    assertTrue(StripTestKit.reach { if (SmokeUi.has(CurveGenLabels18.ROSE, exact = true)) true else null } == true)
                    StripTestKit.closeMoreRow()
                    t.discard()
                    rig.settle()
                }
            }

            // The overlays (§3.9): sheets and dialogs opened from the More row outlive it.
            rig.select(ToolId.CURVE)
            val curve = rig.c.tools.getValue(ToolId.CURVE) as CurveTool
            staysOpen(rig, "Curve settings", "$dp dp, the Curve settings sheet") { settingsSheetShown() }
            assertFalse("$dp dp, the Curve settings sheet: no \"${CurveStroke.NONE.label}\" chip", SmokeUi.has(CurveStroke.NONE.label, exact = true))
            back(rig)
            assertFalse(settingsSheetShown())
            staysOpen(rig, "Numbers", "$dp dp, the Numbers sheet") { SmokeUi.has("No points yet") }
            back(rig)
            assertFalse(SmokeUi.has("No points yet"))
            // A toggle on the More row leaves it open.
            val snapWas = rig.c.snapping.enabled
            for (round in 1..2) {
                StripTestKit.openMoreRow()
                SmokeUi.click(SNAP_LABEL, exact = true)
                rig.settle()
                assertEquals("$dp dp, $SNAP_LABEL, tap $round", if (round == 1) !snapWas else snapWas, rig.c.snapping.enabled)
                assertTrue("$dp dp, $SNAP_LABEL, tap $round: the More row stays open", StripTestKit.moreRowShown())
            }
            StripTestKit.closeMoreRow()
            tap(rig, 100f, 100f)
            tap(rig, 200f, 150f)
            tap(rig, 300f, 100f)
            curve.select(1)
            rig.settle()
            // The thickness item's value opens its typing dialog.
            StripTestKit.openMoreRow()
            val value = SmokeUi.find("Type the point thickness")
            assertNotNull("$dp dp: the thickness value on the More row", value)
            SmokeUi.click("Type the point thickness")
            rig.settle()
            assertFalse("$dp dp, the thickness dialog: the More row closed", StripTestKit.moreRowShown())
            assertTrue("$dp dp, the thickness dialog: open", SmokeUi.has("0 – 300 %"))
            rig.settle()
            assertTrue("$dp dp, the thickness dialog: still open", SmokeUi.has("0 – 300 %"))
            back(rig)
            assertFalse(SmokeUi.has("0 – 300 %"))
            // The Handles popup keeps the More row open, during and after it.
            popupKeepsTheRow(rig, "Handles", "$dp dp, the Handles popup") { SmokeUi.has("Handle scale") }
            // Its typing dialog outlives the popup and the row.
            typedFromPopup(rig, "Handles", "Type handle scale", "1 – 10000 % of the lengths now", "$dp dp, the handle scale dialog")
            curve.discard()
            rig.settle()

            // Polyline: its settings sheet.
            rig.select(ToolId.POLYLINE)
            staysOpen(rig, "${ToolId.POLYLINE.label} settings", "$dp dp, the Polyline settings sheet") { settingsSheetShown() }
            back(rig)
            assertFalse(settingsSheetShown())

            // Path, nothing open: the line width (known by its value) opens the settings sheet; the
            // Order popup keeps the row; the settings sheet.
            rig.select(ToolId.PATH)
            val path = rig.c.tools.getValue(ToolId.PATH) as CurveTool
            path.update { it.copy(stroke = CurveStroke.PLAIN) }
            rig.settle()
            staysOpen(rig, "Line width", "$dp dp, the line width") { SmokeUi.has("Use brush size", exact = true) }
            back(rig)
            assertFalse(SmokeUi.has("Use brush size", exact = true))
            popupKeepsTheRow(rig, "Order ${path.pathOrder}", "$dp dp, the Order popup") { SmokeUi.has("Lower order", exact = true) }
            StripTestKit.closeMoreRow()
            staysOpen(rig, "${ToolId.PATH.label} settings", "$dp dp, the Path settings sheet") { settingsSheetShown() }
            back(rig)
            assertFalse(settingsSheetShown())
            // Open: the Point weight popup keeps the row; its typing dialog outlives both.
            tap(rig, 100f, 100f)
            tap(rig, 200f, 150f)
            tap(rig, 300f, 100f)
            path.select(1)
            rig.settle()
            popupKeepsTheRow(rig, POINT_WEIGHT_LABEL, "$dp dp, the Point weight popup") { SmokeUi.has("Point weight slider") }
            typedFromPopup(rig, POINT_WEIGHT_LABEL, "Type the point weight", "0.1 – 10", "$dp dp, the point weight dialog")
            path.discard()
            rig.settle()
            assertTrue(path.startGenerator(CurveGenKind.SINE, null))
            rig.settle()
            staysOpen(rig, CurveGenLabels18.CHANGE, "$dp dp, the generator sheet") { SmokeUi.has(CurveGenLabels18.WAVE_HEIGHT, exact = true) }
            assertTrue("the sheet is titled by the menu it came from", SmokeUi.has(CurveGenLabels18.CURVES, exact = true))
            back(rig)
            assertFalse(SmokeUi.has(CurveGenLabels18.WAVE_HEIGHT, exact = true))
            assertNotNull("the curve stays generated", path.generator)
            path.discard()
            rig.settle()
            assertNull(path.generator)
            rig.select(ToolId.BRUSH)
        }
    }
}
