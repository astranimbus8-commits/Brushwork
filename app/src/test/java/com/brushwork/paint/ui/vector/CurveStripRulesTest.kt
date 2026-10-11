package com.brushwork.paint.ui.vector

import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveKind
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.controller
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tool
import com.brushwork.paint.ui.common.CurveGenLabels18
import com.brushwork.paint.ui.common.CurveLabels17
import com.brushwork.paint.ui.common.MirrorLabels18
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.tools.strip.StripHandlesV17
import com.brushwork.paint.ui.tools.strip.StripInventory
import com.brushwork.paint.ui.tools.strip.StripLayout
import com.brushwork.paint.ui.tools.strip.StripRow
import com.brushwork.paint.ui.tools.strip.StripSpec
import com.brushwork.paint.vector.CurveGenKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.8 E (design §3.9, rules 1 to 5): the Curve, Polyline and Path strips as specs, pure, over
 * every consistent combination of their facts ([CurveStripFacts]); the UI test of the same strips
 * is `CurveStripTest`.
 * - The lead's rules ([StripInventory.rules]) hold in every state; no spec is legacy.
 * - Row 1 holds the table's controls, in its order.
 * - The AUTO items are the table's own; no measured width made another item AUTO (rule 4 asks to
 *   list such changes here: there are none).
 * - The splits at 360 and 392 dp, with and without the VECTOR cell (rule 3).
 * - The duplicates §3.9 removes never come back; the v1.7 handles keep their names.
 */
@RunWith(RobolectricTestRunner::class)
class CurveStripRulesTest {
    private val c: EditorController by lazy { controller() }

    private fun toolOf(kind: CurveKind): CurveTool = c.tool(
        when (kind) {
            CurveKind.CURVE -> ToolId.CURVE
            CurveKind.POLYLINE -> ToolId.POLYLINE
            CurveKind.PATH -> ToolId.PATH
        },
    )

    private fun spec(f: CurveStripFacts): StripSpec = curveSpec(toolOf(f.kind), f, CurveStripUi())

    /** Every consistent state of a [kind] strip, named. */
    private fun states(kind: CurveKind): List<Pair<String, CurveStripFacts>> = buildList {
        for (s in CurveStroke.entries) add("$kind empty ${s.name}" to CurveStripFacts(kind, stroke = s))
        val gens: List<CurveGenKind?> = if (kind == CurveKind.POLYLINE) listOf(null) else listOf(null, CurveGenKind.ARCHIMEDEAN)
        val bools = listOf(false, true)
        for (points in listOf(1, 2, 5)) for (sel in listOf(0, 1, 2, points).distinct().filter { it <= points }) {
            val single = if (sel == 1) 0 else -1
            val sharps = when {
                kind == CurveKind.POLYLINE || sel == 0 -> listOf(SharpState.NONE)
                sel >= 2 -> listOf(SharpState.ENDS, SharpState.SMOOTH, SharpState.SHARP, SharpState.MIXED)
                else -> listOf(SharpState.ENDS, SharpState.SMOOTH, SharpState.SHARP)
            }
            for (stroke in CurveStroke.entries) for (g in gens) for (copies in bools) for (thick in bools) for (reopened in bools) for (sharp in sharps) {
                val tangents = if (kind == CurveKind.CURVE && single >= 0) bools else listOf(false)
                for (tangent in tangents) {
                    val f = CurveStripFacts(
                        kind = kind, points = points, selected = sel, single = single, allSelected = sel == points, stroke = stroke,
                        generated = g, copies = copies, anyThickness = thick, sharp = sharp, customTangent = tangent,
                        handles = kind == CurveKind.CURVE && points >= 2, reopened = reopened, order = if (kind == CurveKind.PATH) 4 else 4,
                    )
                    add("$kind open $f" to f)
                }
            }
        }
    }

    private val kinds = listOf(CurveKind.CURVE, CurveKind.POLYLINE, CurveKind.PATH)

    @Test
    fun everyStateKeepsTheLeadsRulesAndIsConverted() {
        var n = 0
        for (kind in kinds) for ((name, f) in states(kind)) {
            val spec = spec(f)
            assertEquals(name, emptyList<String>(), StripInventory.rules(spec))
            assertNotNull("$name: the sheets and dialogs are overlays", spec.overlays)
            n++
        }
        assertTrue("states checked: $n", n > 1000)
    }

    private fun row1(f: CurveStripFacts) = spec(f).items.filter { it.row == StripRow.ROW1 }.map { it.key }

    @Test
    fun rowOneHoldsTheTablesControlsInOrder() {
        val k = CurveKeys
        val open = { kind: CurveKind -> CurveStripFacts(kind, points = 3, selected = 1, single = 1, sharp = SharpState.SMOOTH) }
        // Curve: "Curves", "Stroke kind", "Line width" (a plain line); open: the point actions, "Stroke kind", the chip.
        assertEquals(listOf(k.CURVES, k.STROKE_KIND), row1(CurveStripFacts(CurveKind.CURVE)))
        assertEquals(listOf(k.CURVES, k.STROKE_KIND, k.LINE_WIDTH), row1(CurveStripFacts(CurveKind.CURVE, stroke = CurveStroke.PLAIN)))
        assertEquals(listOf(k.ADD, k.DELETE, k.CLOSED, k.STROKE_KIND), row1(open(CurveKind.CURVE)))
        assertEquals(listOf(k.ADD, k.DELETE, k.CLOSED, k.STROKE_KIND, k.GENERATOR), row1(open(CurveKind.CURVE).copy(generated = CurveGenKind.ROSE)))
        // Polyline: no generators.
        assertEquals(listOf(k.STROKE_KIND), row1(CurveStripFacts(CurveKind.POLYLINE)))
        assertEquals(listOf(k.STROKE_KIND, k.LINE_WIDTH), row1(CurveStripFacts(CurveKind.POLYLINE, stroke = CurveStroke.PLAIN)))
        assertEquals(listOf(k.ADD, k.DELETE, k.CLOSED, k.STROKE_KIND), row1(open(CurveKind.POLYLINE).copy(sharp = SharpState.NONE)))
        // Path: "Curves", "Shapes", "Stroke kind"; open: the point actions, "Cyclic", "Order 4", "Stroke kind"; the chip on the More row.
        assertEquals(listOf(k.CURVES, k.SHAPES, k.STROKE_KIND), row1(CurveStripFacts(CurveKind.PATH)))
        assertEquals(listOf(k.ADD, k.DELETE, k.CLOSED, k.ORDER, k.STROKE_KIND), row1(open(CurveKind.PATH)))
        val gen = spec(open(CurveKind.PATH).copy(generated = CurveGenKind.SINE)).items.first { it.key == k.GENERATOR }
        assertEquals(StripRow.MORE, gen.row)
        assertEquals(CurveGenLabels18.CHANGE, gen.label)
        // The two first items' handles.
        val curves = spec(CurveStripFacts(CurveKind.CURVE)).items.first()
        assertEquals(CurveGenLabels18.CURVES_DESC, curves.label)
        assertEquals(CURVE_CURVES_DP.toFloat(), curves.width.value)
        assertEquals("Path shapes", spec(CurveStripFacts(CurveKind.PATH)).items[1].label)
        assertEquals("Order 4", spec(open(CurveKind.PATH)).items.first { it.key == k.ORDER }.label)
        assertEquals("Cyclic", spec(open(CurveKind.PATH)).items.first { it.key == k.CLOSED }.label)
        assertEquals("Closed", spec(open(CurveKind.CURVE)).items.first { it.key == k.CLOSED }.label)
    }

    @Test
    fun theAutoItemsAreTheTablesOwnAndNoWidthAddedOne() {
        val auto = mutableMapOf<CurveKind, MutableSet<String>>()
        for (kind in kinds) for ((_, f) in states(kind)) {
            for (item in spec(f).items) if (item.row == StripRow.ROW1 && item.overflowRank > 0) {
                auto.getOrPut(kind) { sortedSetOf() } += (if (f.points > 0) "open " else "empty ") + item.key + " AUTO " + item.overflowRank
            }
        }
        // §3.9's table: Curve's "Line width" (nothing open) and generator chip (open), Path's open "Stroke kind".
        assertEquals(sortedSetOf("empty ${CurveKeys.LINE_WIDTH} AUTO 1", "open ${CurveKeys.GENERATOR} AUTO 1"), auto[CurveKind.CURVE])
        assertEquals(null, auto[CurveKind.POLYLINE])
        assertEquals(sortedSetOf("open ${CurveKeys.STROKE_KIND} AUTO 1"), auto[CurveKind.PATH])
        // Rule 4's width-driven changes: none. The measured non-AUTO sums stay within 240 dp (each strip has a More row).
        val worst = kinds.associateWith { kind ->
            states(kind).maxOf { (_, f) -> StripLayout.rowWidth(spec(f).items.filter { it.row == StripRow.ROW1 && it.overflowRank <= 0 }) }
        }
        assertEquals(mapOf(CurveKind.CURVE to 188f, CurveKind.POLYLINE to 188f, CurveKind.PATH to 236f), worst)
        assertTrue(worst.values.all { it <= StripInventory.WITH_MORE })
    }

    /** Row 1's keys at [screenDp], with the VECTOR cell or not, and the rest on the More row. */
    private fun split(f: CurveStripFacts, screenDp: Int, chip: Boolean): List<String> {
        val available = StripInventory.content(screenDp.toFloat()) - if (chip) StripLayout.VECTOR_CHIP_DP + StripLayout.GAP_DP else 0f
        val s = StripLayout.split(spec(f), available)
        assertTrue("a toggle: every curve strip has a More row", s.toggle)
        return s.row1.map { it.key }
    }

    @Test
    fun theSplitsAtBothWidthsWithAndWithoutTheVectorCell() {
        val k = CurveKeys
        val configs = listOf(360 to false, 360 to true, 392 to false, 392 to true)
        fun assertRow1(what: String, f: CurveStripFacts, want: (Int, Boolean) -> List<String>) {
            for ((dp, chip) in configs) assertEquals("$what at $dp dp${if (chip) " with the VECTOR cell" else ""}", want(dp, chip), split(f, dp, chip))
        }
        // Nothing open: everything stays everywhere (the worst, 360 dp with the cell, leaves 288 dp).
        assertRow1("Curve, a plain line", CurveStripFacts(CurveKind.CURVE, stroke = CurveStroke.PLAIN)) { _, _ -> listOf(k.CURVES, k.STROKE_KIND, k.LINE_WIDTH) }
        assertRow1("Path", CurveStripFacts(CurveKind.PATH)) { _, _ -> listOf(k.CURVES, k.SHAPES, k.STROKE_KIND) }
        assertRow1("Polyline", CurveStripFacts(CurveKind.POLYLINE, stroke = CurveStroke.PLAIN)) { _, _ -> listOf(k.STROKE_KIND, k.LINE_WIDTH) }
        // A generated Curve: 284 dp; the chip leaves for the More row's front with the VECTOR cell.
        val curve = CurveStripFacts(CurveKind.CURVE, points = 40, generated = CurveGenKind.ARCHIMEDEAN, handles = true)
        assertRow1("a generated Curve", curve) { _, chip ->
            listOf(k.ADD, k.DELETE, k.CLOSED, k.STROKE_KIND) + if (chip) emptyList() else listOf(k.GENERATOR)
        }
        val curveMore = StripLayout.split(spec(curve), StripInventory.content(360f) - StripLayout.VECTOR_CHIP_DP - StripLayout.GAP_DP).more
        assertEquals("the chip leads the More row", k.GENERATOR, curveMore.first().key)
        // An open Path: 256 dp; "Stroke kind" leaves only at 360 dp with the VECTOR cell.
        val path = CurveStripFacts(CurveKind.PATH, points = 4)
        assertRow1("an open Path", path) { dp, chip ->
            listOf(k.ADD, k.DELETE, k.CLOSED, k.ORDER) + if (dp == 360 && chip) emptyList() else listOf(k.STROKE_KIND)
        }
        // An open Polyline: 188 dp, all four stay.
        assertRow1("an open Polyline", CurveStripFacts(CurveKind.POLYLINE, points = 4)) { _, _ -> listOf(k.ADD, k.DELETE, k.CLOSED, k.STROKE_KIND) }
    }

    @Test
    fun theRemovedDuplicatesNeverComeBack() {
        val gone = StripHandlesV17.REMOVED + PointLabels.SELECT_SEVERAL
        for (kind in kinds) for ((name, f) in states(kind)) {
            val labels = spec(f).items.map { it.label } + spec(f).movedInto.keys
            assertEquals("$name: a removed duplicate", emptyList<String>(), labels.filter { it in gone })
            assertTrue("$name: one snap item, the longer name", "Snap" !in labels && labels.count { it == SNAP_LABEL } == 1)
        }
    }

    @Test
    fun theV17HandlesKeepTheirNames() {
        for (kind in kinds) {
            val record = StripHandlesV17.HANDLES.getValue(kind.name) - StripHandlesV17.REMOVED
            val empty = spec(CurveStripFacts(kind))
            val offered = empty.items.map { it.label } + empty.movedInto.keys
            for (h in record) {
                val name = StripHandlesV17.SAME[h] ?: h
                assertTrue("$kind: the v1.7 handle \"$h\" is offered as \"$name\": $offered", name in offered)
            }
        }
        // The paint modes in the "Stroke kind" dropdown, the order arrows in the Order popup.
        val path = spec(CurveStripFacts(CurveKind.PATH, points = 3))
        assertEquals(CurveKeys.STROKE_KIND, path.movedInto[CurveLabels17.STROKE_ONLY])
        assertEquals(CurveKeys.STROKE_KIND, path.movedInto[CurveLabels17.FILL_ONLY])
        assertEquals(CurveKeys.STROKE_KIND, path.movedInto[CurveLabels17.BOTH])
        assertEquals(CurveKeys.ORDER, path.movedInto["Lower order"])
        assertEquals(CurveKeys.ORDER, path.movedInto["Higher order"])
        // The table's More-row controls of an open curve, each under its v1.7 name.
        fun labels(kind: CurveKind) = states(kind).filter { it.second.points > 0 }.flatMap { (_, f) -> spec(f).items.map { it.label } }.toSet()
        val curve = labels(CurveKind.CURVE)
        for (h in listOf(
            "Curve settings", "Sharp corner", "Smooth", "Auto tangent", POINT_THICKNESS_LABEL, "Handles", PointLabels.SELECT_ALL, PointLabels.DESELECT_ALL,
            "Previous point", "Next point", "Use brush size", "Taper ends", "Numbers", MirrorLabels18.REMOVE, CurveGenLabels18.CHANGE,
        )) assertTrue("Curve: \"$h\"", h in curve)
        val polyline = labels(CurveKind.POLYLINE)
        for (h in listOf("Polyline settings", POINT_THICKNESS_LABEL, PointLabels.SELECT_ALL, PointLabels.DESELECT_ALL, "Use brush size", "Numbers", MirrorLabels18.REMOVE)) {
            assertTrue("Polyline: \"$h\"", h in polyline)
        }
        assertTrue("Polyline: no generator", CurveGenLabels18.CHANGE !in polyline && CurveGenLabels18.CURVES_DESC !in polyline)
        val path2 = labels(CurveKind.PATH)
        for (h in listOf(
            "Endpoint", POINT_WEIGHT_LABEL, PointLabels.SELECT_ALL, PointLabels.DESELECT_ALL, POINT_THICKNESS_LABEL, CurveGenLabels18.CHANGE,
            "Path settings", "Numbers", MirrorLabels18.REMOVE,
        )) assertTrue("Path: \"$h\"", h in path2)
    }

    @Test
    fun theCurvesMenuListsTheSixGeneratorsInTheDesignsOrder() {
        assertEquals(
            listOf(CurveGenLabels18.ARCHIMEDEAN, CurveGenLabels18.GOLDEN, CurveGenLabels18.LOGARITHMIC, CurveGenLabels18.FERMAT, CurveGenLabels18.SINE, CurveGenLabels18.ROSE),
            CURVES_MENU.map { it.label },
        )
        assertEquals(CurveGenKind.entries.toSet(), CURVES_MENU.toSet())
    }
}
