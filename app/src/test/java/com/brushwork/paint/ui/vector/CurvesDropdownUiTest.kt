package com.brushwork.paint.ui.vector

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveKind
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.gen.CurveGenerators
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.CurveGenLabels18
import com.brushwork.paint.ui.tools.strip.StripRig
import com.brushwork.paint.ui.vector.CurveStripKit.back
import com.brushwork.paint.ui.vector.CurveStripKit.row1Handles
import com.brushwork.paint.vector.CurveGenKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.math.min

/**
 * v1.8 E (design §3.7): the [CurveGenLabels18.CURVES] dropdown of the Curve and Path strips at
 * 360 dp, reached by its description ([CurveGenLabels18.CURVES_DESC], R15):
 * - while nothing is open it is row 1's first item, its six entries in the design's order;
 * - each entry places its curve at the centre of the canvas seen (the whole canvas here: the rig
 *   has no canvas view), 0.3 × its short side (a wave twice as long), and opens it;
 * - the generator chip shows the generator's name and is known as [CurveGenLabels18.CHANGE] (row 1
 *   on Curve, the More row on Path); it opens the generator sheet with that kind's settings;
 * - a slider drag in the sheet is ONE in-tool step (one undo takes it all back).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h780dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.vector.curvesdropdownsandbox"])
class CurvesDropdownUiTest {

    /** Every setting any generator sheet shows. */
    private val allSettings = CurveGenKind.entries.flatMap(::generatorSettings).distinct()

    /** The dropdown's entries shown now, top to bottom (the top window: the menu). */
    private fun entries(): List<String> {
        val top = RobolectricUi.windowRoots().last()
        val names = CURVES_MENU.map { it.label }.toSet()
        return RobolectricUi.elements().filter { it.window === top && it.node.layoutInfo.isPlaced }
            .mapNotNull { e -> e.node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }?.firstOrNull { it in names }?.let { it to e.bounds.top } }
            .sortedBy { it.second }.map { it.first }.distinct()
    }

    /** The sheet's sliders now (the top window), top to bottom. */
    private fun sliders(): List<RobolectricUi.Element> {
        val top = RobolectricUi.windowRoots().last()
        return RobolectricUi.elements().filter { it.window === top && it.node.layoutInfo.isPlaced && it.node.config.getOrNull(SemanticsActions.SetProgress) != null }
            .sortedBy { it.bounds.top }
    }

    @Test
    fun theCurvesDropdownStartsEachGeneratorAndItsChipOpensItsSheet() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val rig = StripRig(panel = false).show()
        try {
            for (id in listOf(ToolId.CURVE, ToolId.PATH)) {
                rig.select(id)
                val t = rig.c.tools.getValue(id) as CurveTool
                val what = id.name
                assertEquals("$what: the dropdown leads row 1", CurveGenLabels18.CURVES_DESC, row1Handles().first())
                assertTrue("$what: its visible text", SmokeUi.has(CurveGenLabels18.CURVES, exact = true))
                for ((i, kind) in CURVES_MENU.withIndex()) {
                    assertEquals(0, t.pointCount)
                    SmokeUi.click(CurveGenLabels18.CURVES_DESC, exact = true)
                    rig.settle()
                    if (i == 0) assertEquals("$what: the entries in order", CURVES_MENU.map { it.label }, entries())
                    SmokeUi.click(kind.label, exact = true)
                    rig.settle()
                    val g = t.generator
                    assertNotNull("$what: ${kind.label} starts a generated curve", g)
                    g!!
                    assertEquals(kind, g.kind)
                    assertTrue("$what: the curve is open", t.pointCount > 2)
                    assertFalse("$what: the dropdown left with nothing open", SmokeUi.has(CurveGenLabels18.CURVES, exact = true))
                    // The whole canvas (400 × 300): its centre, 0.3 × 300 px (a wave twice that).
                    val doc = rig.doc
                    val reach = CurveTool.GENERATOR_VIEW_FRACTION * min(doc.width, doc.height)
                    assertEquals(doc.width / 2f, g.cx, 0.01f)
                    assertEquals(doc.height / 2f, g.cy, 0.01f)
                    assertEquals(if (kind == CurveGenKind.SINE) 2 * reach else reach, g.size, 0.01f)
                    assertNotNull("$what: the canvas handles", t.generatorHandles)

                    // The chip: the name shown, known as CHANGE; row 1 on Curve, the More row on Path.
                    assertEquals("$what: the chip on row 1", id == ToolId.CURVE, CurveGenLabels18.CHANGE in row1Handles())
                    val chip = StripTestKit.reach { SmokeUi.find(CurveGenLabels18.CHANGE, exact = true) }
                    assertNotNull("$what: the chip", chip)
                    assertTrue("$what: the chip shows \"${kind.label}\"", SmokeUi.has(kind.label, exact = true))
                    SmokeUi.click(CurveGenLabels18.CHANGE, exact = true)
                    rig.settle()
                    assertFalse("$what: the sheet closed the More row", StripTestKit.moreRowShown())
                    for (s in allSettings) {
                        assertEquals("$what, ${kind.label}: \"$s\" in the sheet", s in generatorSettings(kind), SmokeUi.has(s, exact = true))
                    }
                    assertTrue(
                        "$what, ${kind.label}: points per turn ${densityShown(g, t.kind)}",
                        SmokeUi.has(densityShown(g, t.kind).toString(), exact = true),
                    )
                    back(rig)
                    assertFalse(SmokeUi.has(CurveGenLabels18.POINTS_PER_TURN, exact = true))
                    t.discard()
                    rig.settle()
                    assertNull(t.generator)
                }
            }
            assertEquals(CurveGenerators.DEFAULT_PATH_DENSITY, densityShown(com.brushwork.paint.vector.CurveGenerator(), CurveKind.PATH))

            // A slider drag in the sheet is ONE in-tool step.
            rig.select(ToolId.CURVE)
            val t = rig.c.tools.getValue(ToolId.CURVE) as CurveTool
            SmokeUi.click(CurveGenLabels18.CURVES_DESC, exact = true)
            SmokeUi.click(CurveGenLabels18.ARCHIMEDEAN, exact = true)
            rig.settle()
            val before = t.generator!!
            val anchorsBefore = t.anchors
            SmokeUi.click(CurveGenLabels18.CHANGE, exact = true)
            rig.settle()
            val turns = sliders().first()
            val b = turns.bounds
            val y = b.center.y
            RobolectricUi.drag(turns.window, b.left + b.width * 0.1f to y, b.left + b.width * 0.4f to y, b.left + b.width * 0.8f to y)
            rig.settle()
            val after = t.generator!!
            assertNotEquals("the drag changed the turns", before.turns, after.turns, 0.001f)
            assertEquals(before.copy(turns = after.turns), after)
            back(rig)
            assertTrue(t.undoStep())
            rig.settle()
            assertEquals("one undo takes the whole drag back", before, t.generator)
            assertEquals(anchorsBefore, t.anchors)
            assertTrue("the next undo takes the curve back", t.undoStep())
            rig.settle()
            assertEquals(0, t.pointCount)
            assertNull(t.generator)
            t.discard()
            rig.select(ToolId.BRUSH)
        } finally {
            StripTestKit.closeMoreRow()
            rig.close()
        }
    }
}
