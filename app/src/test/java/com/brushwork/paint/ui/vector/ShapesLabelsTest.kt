package com.brushwork.paint.ui.vector

import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.tools.vector.ShapeStroke
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.common.ArrayLabels18
import com.brushwork.paint.ui.common.CogLabels18
import com.brushwork.paint.ui.common.ColorSelectLabels18
import com.brushwork.paint.ui.common.CurveGenLabels18
import com.brushwork.paint.ui.common.FringeLabels18
import com.brushwork.paint.ui.common.MagneticLabels18
import com.brushwork.paint.ui.common.MenuLabels18
import com.brushwork.paint.ui.common.MirrorLabels18
import com.brushwork.paint.ui.common.StripLabels18
import com.brushwork.paint.ui.common.TransformLabels18
import com.brushwork.paint.ui.tools.strip.StripRig
import com.brushwork.paint.ui.vector.ShapeStripKit.atEachWidth
import com.brushwork.paint.ui.vector.ShapeStripKit.back
import com.brushwork.paint.ui.vector.ShapeStripKit.fresh
import com.brushwork.paint.ui.vector.ShapeStripKit.withCopies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.lang.reflect.Modifier

/**
 * v1.8 F (design §4.8 rule 4, "`<Area>LabelsTest`"): on the Shape tool's real screens at 360 and
 * 392 dp, no handle or visible text is a substring of another where one of the two is a v1.8 text
 * (every `LabelsV18` constant, and "Cog"): `SmokeUi` matches by a case-sensitive `contains`.
 * - the Shape settings sheet for a cog, a star and a polygon;
 * - the Shape strip with the More row open on a shape with mirror copies (raster and vector
 *   layer, a plain and a brush stroke);
 * - the Shape strip in Points mode (the fullest: copies, a smooth point with handles and a
 *   corner selected), with the "Handles" popup open too; no "Undo point edit" / "Redo point edit".
 *
 * A control's own derived handles name the same control and are allowed: "Decrease X",
 * "Increase X", "Type X", "Type a value for X", "Reset X" and (v1.8 polish) "X slider" for a control labelled X (tests reach
 * each by its exact handle). The same text twice (a slider's label over its field's) is one text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.vector.shapeslabelssandbox"])
class ShapesLabelsTest {

    private val v18: Set<String> = listOf(
        MirrorLabels18, ArrayLabels18, MagneticLabels18, ColorSelectLabels18, FringeLabels18,
        CurveGenLabels18, CogLabels18, TransformLabels18, StripLabels18, MenuLabels18,
    ).flatMap { o ->
        o.javaClass.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { f -> f.isAccessible = true; f.get(null) as String }
    }.toSet() + ShapeType.COG.label

    private val derived = listOf("Decrease ", "Increase ", "Type ", "Type a value for ", "Reset ")

    /**
     * v1.8 polish (accessibility): the shared slider (`LabeledSlider`) is now named "X slider" for a
     * screen reader, the same control as its label X (as "Type a value for X"); a derived handle.
     */
    private fun sliderOf(label: String) = "$label slider"

    /** Pairs (a, b) of what the screen shows now, a inside b, one of them a v1.8 text. */
    private fun clashes(): List<Pair<String, String>> {
        val shown = SmokeUi.shown().filter { it.isNotBlank() }.distinct()
        val out = mutableListOf<Pair<String, String>>()
        for (a in shown) for (b in shown) {
            if (a == b || !b.contains(a)) continue
            if (a !in v18 && b !in v18) continue
            if (derived.any { b == it + a } || b == sliderOf(a)) continue
            out += a to b
        }
        return out
    }

    private fun assertClean(screen: String, mustShow: List<String>) {
        val shown = SmokeUi.shown()
        for (s in mustShow) assertTrue("$screen: \"$s\" shows: ${shown.take(120)}", s in shown)
        assertEquals("$screen: a v1.8 text inside another (SmokeUi would match the wrong one); shown: ${shown.take(120)}", emptyList<Pair<String, String>>(), clashes())
    }

    @Test
    fun theShapeScreensKeepTheirV18TextsApart() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        atEachWidth { rig: StripRig, dp: Int ->
            // ---- The Shape settings sheet for a cog (with a hole), a star and a polygon (section headers show in capitals).
            for ((type, show) in listOf(
                ShapeType.COG to listOf("COG", CogLabels18.TEETH, CogLabels18.TOOTH_DEPTH, CogLabels18.TOOTH_WIDTH, CogLabels18.TOOTH_TAPER, CogLabels18.HOLE_SIZE, "CORNERS"),
                ShapeType.STAR to listOf("STAR", "Number of points", "Inner radius", "CORNERS"),
                ShapeType.POLYGON to listOf("POLYGON", "Number of sides", "CORNERS"),
            )) {
                fresh(rig) { it.copy(type = type, cogHole = 0.3f) }
                SmokeUi.click("Shape settings", exact = true)
                assertFalse("the More row closed", StripTestKit.moreRowShown())
                assertClean("$dp dp, Shape settings for a ${type.label}", show)
                back(rig)
            }

            // ---- The More row on a shape with copies: raster and vector layer, plain and brush.
            for (vector in listOf(false, true)) for (stroke in listOf(ShapeStroke.PLAIN, ShapeStroke.BRUSH)) {
                withCopies(rig, vector) { it.copy(strokeWith = stroke) }
                assertTrue(StripTestKit.openMoreRow())
                assertClean(
                    "$dp dp, More row with copies (vector $vector, $stroke)",
                    listOf(MirrorLabels18.REMOVE, StripLabels18.MORE_TOGGLE, "Shape settings", "Numbers"),
                )
                StripTestKit.closeMoreRow()
            }

            // ---- Points mode, the fullest: copies, a smooth point with handles and a corner selected.
            for (vector in listOf(false, true)) {
                withCopies(rig, vector)
                val t = ShapeStripKit.tool(rig)
                t.setPointEditing(true)
                t.selectPoints(PointSelection.of(t.pointCount, 0))
                t.setSelectedSmooth(true)
                t.scaleHandles(1.5f)
                t.selectPoints(PointSelection.of(t.pointCount, 0, 1))
                rig.settle()
                assertTrue(StripTestKit.openMoreRow())
                val screen = "$dp dp, Points mode (vector $vector)"
                assertClean(screen, listOf(MirrorLabels18.REMOVE, StripLabels18.MORE_TOGGLE, "Handles", "Delete point"))
                for (gone in listOf("Undo point edit", "Redo point edit")) {
                    assertTrue("$screen: no \"$gone\" (§3.2)", SmokeUi.shown().none { it.contains(gone) })
                }
                SmokeUi.click("Handles", exact = true)
                assertClean("$screen, the Handles popup", listOf("Shorter handles", "Longer handles", "In and out"))
                back(rig)
                StripTestKit.closeMoreRow()
            }
        }
    }
}
