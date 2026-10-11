package com.brushwork.paint.ui.symmetry

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.brush.MirrorsStripKit
import com.brushwork.paint.ui.brush.MirrorsStripKit.assertSplits
import com.brushwork.paint.ui.brush.MirrorsStripKit.back
import com.brushwork.paint.ui.brush.MirrorsStripKit.clippedTexts
import com.brushwork.paint.ui.brush.MirrorsStripKit.inspect
import com.brushwork.paint.ui.brush.MirrorsStripKit.key
import com.brushwork.paint.ui.brush.MirrorsStripKit.moreHandles
import com.brushwork.paint.ui.brush.MirrorsStripKit.row1Handles
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.SymmetryLabels
import com.brushwork.paint.ui.tools.strip.StripInventory
import com.brushwork.paint.ui.tools.strip.StripRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 D (design §3.9 "Symmetry"): the symmetry tool's converted strip at 360 and 392 dp, on the
 * raster and the vector layer (the VECTOR cell), for each of the six types:
 * - row 1: the type dropdown (described by the current type's label; its entries are the six v1.7
 *   type texts, declared in `movedInto`), the type's values ("Divisions" and "Angle" AUTO 1;
 *   "Angle" AUTO 1; "Spacing X" and "Spacing Y" AUTO 1; nothing), "Done";
 * - the More row: "Angle" for the Array, "Reset symmetry";
 * - the AUTO item leaves row 1 only at 360 dp with the VECTOR cell and four items (rules 1 to 3), every v1.7
 *   handle is reachable (the inventory, Mirror being the type the tool picks), no value text is cut;
 * - the dropdown changes the type, a value control opens its field, Reset and Done work.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h780dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.symmetry.symmetrystripsandbox"])
class SymmetryStripTest {

    /** Row 1 in full (keys) and its AUTO item, by type. */
    private fun row1(type: SymmetryType): Pair<List<String>, String?> = when (type) {
        SymmetryType.KALEIDOSCOPE, SymmetryType.ROTATION ->
            listOf(SymmetryStripItems.TYPE, SymmetryStripItems.DIVISIONS, SymmetryStripItems.ANGLE, SymmetryStripItems.DONE) to SymmetryStripItems.ANGLE
        SymmetryType.MIRROR -> listOf(SymmetryStripItems.TYPE, SymmetryStripItems.ANGLE, SymmetryStripItems.DONE) to SymmetryStripItems.ANGLE
        SymmetryType.ARRAY ->
            listOf(SymmetryStripItems.TYPE, SymmetryStripItems.SPACING_X, SymmetryStripItems.SPACING_Y, SymmetryStripItems.DONE) to SymmetryStripItems.SPACING_Y
        SymmetryType.OFF, SymmetryType.PERSPECTIVE_ARRAY -> listOf(SymmetryStripItems.TYPE, SymmetryStripItems.DONE) to null
    }

    /** The semantics nodes, every window, whose text or description is [label]. */
    private fun labelled(label: String): Int = RobolectricUi.elements().count { e ->
        val n = e.node
        label in n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() ||
            n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label }
    }

    @Test
    fun theSymmetryStripFollowsItsTypeAtBothWidths() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        MirrorsStripKit.atEachWidth { rig, dp ->
            for (vector in listOf(false, true)) {
                val k = key(ToolId.SYMMETRY, vector)
                rig.select(ToolId.SYMMETRY, vector)
                val c = rig.c
                assertEquals("$k: picking the tool picks the mirror", SymmetryType.MIRROR, c.symmetry.type)
                inspect(rig, k)
                for (type in SymmetryType.entries) {
                    SymmetryUi.update(c) { it.copy(type = type) }
                    rig.settle()
                    val s = c.symmetry.sanitized()
                    val spec = rig.spec
                    val where = "$k, $type at $dp dp"
                    assertEquals("$where: the rules", emptyList<String>(), StripInventory.rules(spec))
                    val (full, auto) = row1(type)
                    // The AUTO item leaves only where the full row (268 dp) does not fit: 360 dp with the VECTOR
                    // cell (240); the mirror's (200 dp) always fits.
                    val leaves = auto != null && full.size == 4
                    assertSplits("$k, $type", spec) { w, chip -> if (w == 360 && chip && leaves) full - auto!! else full }
                    // On the screen, with the v1.7 texts.
                    val handle = mapOf(
                        SymmetryStripItems.TYPE to type.label,
                        SymmetryStripItems.DIVISIONS to "Divisions ${s.divisions}",
                        SymmetryStripItems.ANGLE to "Angle 0°",
                        SymmetryStripItems.SPACING_X to "Spacing X 300 px",
                        SymmetryStripItems.SPACING_Y to "Spacing Y 300 px",
                        SymmetryStripItems.DONE to "Done",
                        SymmetryStripItems.RESET to SymmetryLabels.RESET,
                    )
                    val shown1 = if (dp == 360 && vector && leaves) full - auto!! else full
                    assertEquals("$where: row 1", shown1.map { handle.getValue(it) }, row1Handles())
                    assertEquals("$where: row 1 texts fit", emptyList<String>(), clippedTexts())
                    val more = spec.items.filter { it.row == StripRow.MORE }.map { it.key }
                    assertEquals("$where: the More row's items", (if (type == SymmetryType.ARRAY) listOf(SymmetryStripItems.ANGLE) else emptyList()) + SymmetryStripItems.RESET, more)
                    assertTrue(StripTestKit.openMoreRow())
                    assertEquals("$where: the More row", ((full - shown1) + more).map { handle.getValue(it) }, moreHandles())
                    assertEquals("$where: More row texts fit", emptyList<String>(), clippedTexts())
                    StripTestKit.closeMoreRow()
                }
                SymmetryUi.update(c) { it.copy(type = SymmetryType.MIRROR) }
                rig.settle()
            }

            rig.select(ToolId.SYMMETRY)
            val c = rig.c
            // The type dropdown: its six entries, the v1.7 texts; an entry picks the type.
            SmokeUi.click(SymmetryType.MIRROR.label, exact = true)
            rig.settle()
            for (type in SymmetryType.entries) assertTrue("$dp dp: the entry \"${type.label}\"", SmokeUi.has(type.label, exact = true))
            // Open, the button leaves the semantics: the current type's label is its entry's alone.
            assertEquals("open: one \"Mirror ruler\"", 1, labelled(SymmetryType.MIRROR.label))
            assertFalse(row1Handles().contains(SymmetryType.MIRROR.label))
            SmokeUi.click(SymmetryType.KALEIDOSCOPE.label, exact = true)
            rig.settle()
            assertEquals(SymmetryType.KALEIDOSCOPE, c.symmetry.type)
            assertTrue(SmokeUi.has(SymmetryType.KALEIDOSCOPE.label, exact = true))
            assertFalse("the dropdown closed", SmokeUi.has(SymmetryType.ROTATION.label, exact = true))
            assertEquals("closed: the button", SymmetryType.KALEIDOSCOPE.label, row1Handles().first())
            // The current type picked again: nothing changes, the dropdown closes.
            SmokeUi.click(SymmetryType.KALEIDOSCOPE.label, exact = true)
            rig.settle()
            assertEquals(1, labelled(SymmetryType.KALEIDOSCOPE.label))
            SmokeUi.click(SymmetryType.KALEIDOSCOPE.label, exact = true)
            rig.settle()
            assertEquals(SymmetryType.KALEIDOSCOPE, c.symmetry.type)
            assertFalse("the dropdown closed", SmokeUi.has(SymmetryType.ROTATION.label, exact = true))
            assertEquals(1, labelled(SymmetryType.KALEIDOSCOPE.label))

            // A value control opens its field (with its slider) in a dropdown.
            SmokeUi.click("Divisions 6", exact = true)
            rig.settle()
            assertTrue("$dp dp: the Divisions field: ${SmokeUi.shown()}", SmokeUi.has("Increase Divisions", exact = true))
            SmokeUi.click("Increase Divisions", exact = true)
            rig.settle()
            assertEquals(7, c.symmetry.divisions)
            back(rig)
            assertTrue(SmokeUi.has("Divisions 7", exact = true))

            // "Reset symmetry" on the More row: the defaults, the type kept; the row stays open.
            SymmetryUi.update(c) { it.copy(angleDeg = SymmetryUi.storedAngle(30f)) }
            rig.settle()
            assertTrue(SmokeUi.has("Angle 30°", exact = true) || run { StripTestKit.openMoreRow(); SmokeUi.has("Angle 30°", exact = true) })
            SmokeUi.click(SymmetryLabels.RESET, exact = true)
            rig.settle()
            assertEquals(SymmetryType.KALEIDOSCOPE, c.symmetry.type)
            assertEquals(6, c.symmetry.divisions)
            assertEquals(0f, SymmetryUi.shownAngle(c.symmetry.sanitized()), 1e-3f)
            assertTrue("Reset keeps the More row", StripTestKit.moreRowShown())
            StripTestKit.closeMoreRow()

            // "Done": back to the last painting tool.
            val paint = c.lastPaintTool
            SmokeUi.click("Done", exact = true)
            rig.settle()
            assertEquals(paint, c.activeToolId)
            rig.select(ToolId.SYMMETRY)
            SymmetryUi.update(c) { it.copy(type = SymmetryType.MIRROR) }
            rig.select(ToolId.BRUSH)
        }
    }
}
