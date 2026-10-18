package com.brushwork.paint.ui.vector

import com.brushwork.paint.core.Units
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.tools.vector.LineCapStyle
import com.brushwork.paint.tools.vector.ShapeStroke
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.common.MirrorLabels18
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.common.PointLabels19
import com.brushwork.paint.ui.tools.strip.StripInventory
import com.brushwork.paint.ui.tools.strip.StripHandlesV17
import com.brushwork.paint.ui.tools.strip.StripRig
import com.brushwork.paint.ui.vector.ShapeStripItems.POINTS
import com.brushwork.paint.ui.vector.ShapeStripItems.STYLE
import com.brushwork.paint.ui.vector.ShapeStripItems.TYPE
import com.brushwork.paint.ui.vector.ShapeStripItems.WIDTH
import com.brushwork.paint.ui.vector.ShapeStripKit.atEachWidth
import com.brushwork.paint.ui.vector.ShapeStripKit.back
import com.brushwork.paint.ui.vector.ShapeStripKit.clippedTexts
import com.brushwork.paint.ui.vector.ShapeStripKit.fresh
import com.brushwork.paint.ui.vector.ShapeStripKit.inspect
import com.brushwork.paint.ui.vector.ShapeStripKit.key
import com.brushwork.paint.ui.vector.ShapeStripKit.moreHandles
import com.brushwork.paint.ui.vector.ShapeStripKit.open
import com.brushwork.paint.ui.vector.ShapeStripKit.row1Handles
import com.brushwork.paint.ui.vector.ShapeStripKit.split
import com.brushwork.paint.ui.vector.ShapeStripKit.staysOpen
import com.brushwork.paint.ui.vector.ShapeStripKit.withCopies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 F (design §3.9 "Shape"): the Shape tool's converted strip at 360 and 392 dp, on the raster
 * and the vector layer (the VECTOR cell):
 * - row 1 "Shape type" 56, "Shape style" 56, the stroke
 *   width 64 (AUTO 1: it alone leaves row 1, at 360 dp with the VECTOR cell, for the More row's
 *   front) and "Points" 72 (its room kept while no shape is open). No AUTO change was needed:
 *   the non-AUTO items take 192 dp, the whole row 260;
 * - the More row in the design's order: "Editable" (raster layers), "Stroke with", "Center",
 *   "Proportional", "15°", "Snap to objects", "Numbers", "Shape settings"; in Points mode the
 *   point actions; "Remove mirror copies" while the open shape has copies; v1.9 (R18): in Points
 *   mode "Bevel points" last, and "Center" and "Proportional" hidden (they act on the box's
 *   handles and on drawing a new shape; without them the fullest Points mode keeps three lines);
 * - every v1.7 handle reachable with its v1.7 label (the inventory against `StripHandlesV17`;
 *   only "Snap" became "Snap to objects"), the Handles group inside "Handles", no text cut by its
 *   item, the rules (widths rounded to 4 dp and measured, the More row on three lines at most)
 *   in the fullest Points-mode state;
 * - the controls work, and the sheets and dialogs opened from the More row outlive the row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h780dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.vector.shapestripsandbox"])
class ShapeStripTest {

    private fun widthText(rig: StripRig, t: ShapeTool) = Units.format(t.strokeWidth.toDouble(), t.settings.unit, rig.c.doc.dpi.toDouble())

    @Test
    fun theShapeStripKeepsItsControlsAtBothWidths() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        atEachWidth { rig, dp ->
            for (vector in listOf(false, true)) {
                val k = key(vector)
                // The VECTOR cell shows on the vector layer; at 360 dp the width then leaves row 1.
                val narrow = dp == 360 && vector

                // ---- Nothing open: the tool as it opens (the state StripHandlesV17 recorded).
                val t = fresh(rig, vector)
                val width = widthText(rig, t)
                // The record's "60 px" is the width cell's text with the brush the lead's rig had (it
                // follows the brush size): here the default pen's.
                inspect(rig, k, StripHandlesV17.HANDLES.getValue(k).map { if (it == "60 px") width else it }.toSet())
                for (screen in listOf(360, 392)) for (chip in listOf(false, true)) {
                    val want = if (screen == 360 && chip) listOf(TYPE, STYLE, POINTS) else listOf(TYPE, STYLE, WIDTH, POINTS)
                    assertEquals("$k at $screen dp, VECTOR cell $chip: row 1", want, split(rig.spec, screen, chip).first)
                }
                assertEquals("$k at $dp dp: row 1 (\"Points\" keeps its room)", listOf("Shape type", "Shape style") + if (narrow) emptyList() else listOf(width), row1Handles())
                assertEquals("$k at $dp dp: row 1 texts fit", emptyList<String>(), clippedTexts())
                assertTrue(StripTestKit.openMoreRow())
                val more = (if (narrow) listOf(width) else emptyList()) + (if (vector) emptyList() else listOf("Editable")) +
                    listOf("Stroke with", "Center", "Proportional", "15°", "Snap to objects", "Numbers", "Shape settings")
                assertEquals("$k at $dp dp: the More row", more, moreHandles())
                assertEquals("$k at $dp dp: More row texts fit", emptyList<String>(), clippedTexts())
                StripTestKit.closeMoreRow()

                // ---- A shape open: "Points" on row 1.
                open(rig, vector)
                inspect(rig, "$k open", record = null)
                assertEquals("$k at $dp dp: row 1 with a shape open (vector mode ${rig.c.isVectorMode}, ${rig.c.activeLayer.name}, toggle ${StripTestKit.toggle() != null})", listOf("Shape type", "Shape style") + (if (narrow) emptyList() else listOf(width)) + "Points", row1Handles())

                // ---- The fullest Points mode: a copy, a smooth point with handles of its own and a corner selected.
                withCopies(rig, vector)
                val p = ShapeStripKit.tool(rig)
                p.setPointEditing(true)
                p.selectPoints(PointSelection.of(p.pointCount, 0))
                p.setSelectedSmooth(true)
                p.scaleHandles(1.5f)
                p.selectPoints(PointSelection.of(p.pointCount, 0, 1))
                rig.settle()
                assertTrue(p.selectionHasExplicitHandles)
                // The inventory's rules (three More lines at most at every configuration), the
                // measured widths, and the Handles group inside "Handles" (movedInto).
                inspect(rig, "$k Points mode", record = null)
                assertEquals("$k: rules", emptyList<String>(), StripInventory.rules(rig.spec))
                assertTrue(StripTestKit.openMoreRow())
                val pointsMore = moreHandles()
                for (h in listOf(
                    PointLabels.SELECT_ALL, "Smooth", "Auto tangent", "Delete point", "Deselect point", PointLabels.ROUNDNESS,
                    PointLabels.RESET_ROUNDNESS, "Handles", "Reset shape", PointLabels.TO_PATH, MirrorLabels18.REMOVE,
                )) assertTrue("$k at $dp dp, Points mode: \"$h\" on the More row: $pointsMore", h in pointsMore)
                assertTrue("$k at $dp dp, Points mode: the bevel last: $pointsMore", pointsMore.last() == PointLabels19.BEVEL)
                for (h in listOf("Center", "Proportional")) assertFalse("$k at $dp dp, Points mode: no \"$h\": $pointsMore", h in pointsMore)
                assertEquals("$k at $dp dp: Points mode texts fit", emptyList<String>(), clippedTexts())
                assertEquals("$k at $dp dp: one control per label", pointsMore.size, pointsMore.toSet().size)
                StripTestKit.closeMoreRow()
            }

            val c = rig.c

            // ---- "Shape type": every type, "Cog" last; a pick changes the type, the More row follows.
            var t = fresh(rig)
            SmokeUi.click("Shape type", exact = true)
            for (type in ShapeType.entries) assertTrue("the type menu lists \"${type.label}\"", SmokeUi.has(type.label, exact = true))
            SmokeUi.click(ShapeType.ELLIPSE.label, exact = true)
            assertEquals(ShapeType.ELLIPSE, t.settings.type)
            assertTrue(StripTestKit.openMoreRow())
            assertTrue("an ellipse's \"Circle\"", "Circle" in moreHandles())
            assertFalse("Proportional" in moreHandles())
            // A toggle keeps the More row open.
            val snap = c.snapping.enabled
            SmokeUi.click("Snap to objects", exact = true)
            assertNotEquals(snap, c.snapping.enabled)
            assertTrue("a toggle keeps the More row", StripTestKit.moreRowShown())
            SmokeUi.click("Snap to objects", exact = true)
            val center = t.settings.fromCenter
            SmokeUi.click("Center", exact = true)
            assertNotEquals(center, t.settings.fromCenter)
            val editable = t.settings.editable
            SmokeUi.click("Editable", exact = true)
            assertNotEquals(editable, t.settings.editable)
            // "Stroke with": its menu, anchored in the item, keeps the row.
            SmokeUi.click("Stroke with", exact = true)
            SmokeUi.click(ShapeStroke.BRUSH.label, exact = true)
            assertEquals(ShapeStroke.BRUSH, t.settings.strokeWith)
            assertTrue("a pick in a dropdown keeps the More row", StripTestKit.moreRowShown())
            StripTestKit.closeMoreRow()

            // ---- "Line ends" for a plain line (v1.7 option texts), none for a brush-stroked one.
            t = fresh(rig) { it.copy(type = ShapeType.LINE, strokeWith = ShapeStroke.PLAIN) }
            assertEquals(listOf("Shape type", "Line ends", widthText(rig, t)), row1Handles())
            SmokeUi.click("Line ends", exact = true)
            for (cap in LineCapStyle.entries) assertTrue(SmokeUi.has("${cap.label} ends", exact = true))
            SmokeUi.click("${LineCapStyle.ROUND.label} ends", exact = true)
            assertEquals(LineCapStyle.ROUND, t.settings.lineCap)
            t.update { it.copy(strokeWith = ShapeStroke.BRUSH) }
            rig.settle()
            assertEquals("a brush paints its own ends (and its own width)", listOf("Shape type"), row1Handles())

            // ---- The sheets and dialogs outlive the More row (§3.9 "Overlays").
            fresh(rig)
            staysOpen(rig, "Shape settings", "$dp dp, the Shape settings sheet") { SmokeUi.has("Shape", exact = true) }
            back(rig)
            assertFalse(SmokeUi.has("Shape", exact = true))
            t = open(rig)
            staysOpen(rig, "Numbers", "$dp dp, the Numbers sheet") { SmokeUi.has("Rotation", exact = true) }
            back(rig)
            assertFalse(SmokeUi.has("Rotation", exact = true))
            // The width cell opens Shape settings too.
            SmokeUi.click(widthText(rig, t), exact = true)
            assertTrue(SmokeUi.has("Shape", exact = true))
            back(rig)

            // ---- "Points", then "Point roundness" typed from the More row: the dialog outlives the row.
            SmokeUi.click("Points", exact = true)
            assertTrue(t.pointsMode)
            t.selectPoints(PointSelection.of(t.pointCount, 0))
            rig.settle()
            assertTrue(StripTestKit.openMoreRow())
            SmokeUi.click("Type ${PointLabels.ROUNDNESS}", exact = true)
            assertFalse("the More row closed", StripTestKit.moreRowShown())
            SmokeUi.typeAndDone(PointLabels.ROUNDNESS, "20")
            assertEquals(listOf(20f, null, null, null), t.docAnchors()!!.map { it.radius })
            // "Handles": typing the scale closes the popup and the row; the dialog stays.
            t.selectPoints(PointSelection.of(t.pointCount, 0))
            t.setSelectedSmooth(true)
            rig.settle()
            assertTrue(StripTestKit.openMoreRow())
            SmokeUi.click("Handles", exact = true)
            assertTrue(SmokeUi.has("Shorter handles", exact = true))
            SmokeUi.click("Type handle scale", exact = true)
            rig.settle()
            assertFalse("the More row closed", StripTestKit.moreRowShown())
            assertTrue("the Handle scale dialog", SmokeUi.has("Handle scale", exact = true))
            back(rig)

            // ---- "Remove mirror copies": one in-tool step, the row stays open.
            t = withCopies(rig)
            assertTrue(StripTestKit.openMoreRow())
            SmokeUi.click(MirrorLabels18.REMOVE, exact = true)
            assertFalse("the copies are gone", t.hasCopies)
            assertTrue("the More row stays", StripTestKit.moreRowShown())
            assertFalse("its item went with them", MirrorLabels18.REMOVE in moreHandles())
            assertTrue(t.undoStep())
            rig.settle()
            assertTrue("one in-tool step brings them back", t.hasCopies)
            StripTestKit.closeMoreRow()
        }
    }
}
