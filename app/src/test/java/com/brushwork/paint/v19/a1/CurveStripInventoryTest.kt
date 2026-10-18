package com.brushwork.paint.v19.a1

import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveKind
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.common.CurveLabels17
import com.brushwork.paint.ui.common.EraseLabels19
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.tools.strip.StripHandlesV17
import com.brushwork.paint.ui.tools.strip.StripInventory
import com.brushwork.paint.ui.tools.strip.StripKeysV19
import com.brushwork.paint.ui.tools.strip.StripLayout
import com.brushwork.paint.ui.tools.strip.StripRow
import com.brushwork.paint.ui.tools.strip.StripSpec
import com.brushwork.paint.ui.vector.CurveKeys
import com.brushwork.paint.ui.vector.CurveStripFacts
import com.brushwork.paint.ui.vector.CurveStripUi
import com.brushwork.paint.ui.vector.STROKE_KINDS
import com.brushwork.paint.ui.vector.SharpState
import com.brushwork.paint.ui.vector.curveSpec
import com.brushwork.paint.v19.a1.A1Tools.curveTool
import com.brushwork.paint.vector.CurveGenKind
import com.brushwork.paint.vector.draw.VectorEraseMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 A1 step 9 (design 3.2 A1, R11 (a), §3.9 rules 1 to 5, I10): the Curve, Polyline and Path
 * strips with the v1.9 items, pure (no composition: A1's four Robolectric UI classes are used),
 * at 392 and 360 dp, with and without the VECTOR cell.
 * - Every state, also while the tool erases a vector or shape layer (the row-1 mode cell,
 *   StripKeysV19.ERASE_MODE_CELL, in each of the Eraser's three modes), keeps the lead's rules
 *   ([StripInventory.rules]).
 * - The mode cell is in every erasing state's spec and in row 1 at all four widths.
 * - What makes room for the cell (rule 4 asks to list such changes here): "Stroke kind" becomes
 *   AUTO (rank 1) on an open Curve and Polyline and on Path; Curve's generator chip and an open
 *   Path's "Order N" (after the weight) are More items; the "Use brush size" / "Taper ends"
 *   shortcut leaves the strip (both stay in the settings sheet). The worst cases at 360 dp with the
 *   VECTOR cell fill the More row's three lines to within 4 dp.
 * - Label scan: no label repeats on a strip (row 1, the More row, the items' menus by
 *   `movedInto`), nor between it and the menus A1 opens from it ("Stroke kind" with "Erase instead
 *   of paint" last, the mode cell's) or the bottom bar's slot 1 ("Erase with curve" and its
 *   "Erasing with ..." twin); v1.8's removed duplicates stay removed. Rule L (no label retyped as a
 *   literal) is `LabelsV19Test`'s, over every main source.
 * The rig's active layer is a vector layer: on a pixel layer the cell is null and nothing would be
 * checked, so each erasing state asserts the cell is there.
 */
@RunWith(RobolectricTestRunner::class)
class CurveStripInventoryTest {
    private val c: EditorController by lazy { A1Tools.controller(vector = true) }

    private fun toolOf(kind: CurveKind): CurveTool = c.curveTool(
        when (kind) {
            CurveKind.CURVE -> ToolId.CURVE
            CurveKind.POLYLINE -> ToolId.POLYLINE
            CurveKind.PATH -> ToolId.PATH
        },
    )

    private fun spec(f: CurveStripFacts): StripSpec = curveSpec(toolOf(f.kind), f, CurveStripUi())

    private val kinds = listOf(CurveKind.CURVE, CurveKind.POLYLINE, CurveKind.PATH)
    private val cells: List<VectorEraseMode?> = listOf(null) + VectorEraseMode.entries

    /** Every consistent state of a [kind] strip (as `CurveStripRulesTest` builds them), in each erase cell. */
    private fun states(kind: CurveKind): List<Pair<String, CurveStripFacts>> = buildList {
        for (cell in cells) {
            for (s in CurveStroke.entries) add("$kind empty ${s.name} cell=$cell" to CurveStripFacts(kind, stroke = s, eraseCell = cell))
            val gens: List<CurveGenKind?> = if (kind == CurveKind.POLYLINE) listOf(null) else listOf(null, CurveGenKind.ARCHIMEDEAN)
            val bools = listOf(false, true)
            for (points in listOf(1, 2, 5)) for (sel in listOf(0, 1, 2, points).distinct().filter { it <= points }) {
                val single = if (sel == 1) 0 else -1
                val sharps = when {
                    kind == CurveKind.POLYLINE || sel == 0 -> listOf(SharpState.NONE)
                    sel >= 2 -> listOf(SharpState.ENDS, SharpState.SMOOTH, SharpState.SHARP, SharpState.MIXED)
                    else -> listOf(SharpState.ENDS, SharpState.SMOOTH, SharpState.SHARP)
                }
                // (A reopened path is never erased with: erase commits it first, a reopen turns erase off.)
                val reopens = if (cell == null) bools else listOf(false)
                for (stroke in CurveStroke.entries) for (g in gens) for (copies in bools) for (thick in bools) for (reopened in reopens) for (sharp in sharps) {
                    val tangents = if (kind == CurveKind.CURVE && single >= 0) bools else listOf(false)
                    val captions = if (kind == CurveKind.PATH && single >= 0) bools else listOf(false)
                    for (tangent in tangents) for (caption in captions) {
                        val f = CurveStripFacts(
                            kind = kind, points = points, selected = sel, single = single, allSelected = sel == points, stroke = stroke,
                            generated = g, copies = copies, anyThickness = thick, sharp = sharp, customTangent = tangent,
                            handles = kind == CurveKind.CURVE && points >= 2, reopened = reopened, caption = caption, eraseCell = cell,
                        )
                        add("$kind open $f" to f)
                    }
                }
            }
        }
    }

    private val configs = listOf(360 to false, 360 to true, 392 to false, 392 to true)

    private fun available(screenDp: Int, chip: Boolean) =
        StripInventory.content(screenDp.toFloat()) - if (chip) StripLayout.VECTOR_CHIP_DP + StripLayout.GAP_DP else 0f

    @Test
    fun everyStateWithAndWithoutTheModeCellKeepsTheLeadsRules() {
        var n = 0
        var erasing = 0
        val problems = mutableListOf<String>()
        for (kind in kinds) for ((name, f) in states(kind)) {
            val spec = spec(f)
            StripInventory.rules(spec).forEach { problems += "$name: $it" }
            assertNotNull("$name: the sheets and dialogs are overlays", spec.overlays)
            n++
            if (f.eraseCell != null) erasing++
        }
        assertTrue("the rules:\n" + problems.take(40).joinToString("\n"), problems.isEmpty())
        assertTrue("states checked: $n, erasing $erasing", n > 3000 && erasing > 2000)
    }

    @Test
    fun theModeCellStaysInRowOneAtBothWidthsWithAndWithoutTheVectorCell() {
        val key = StripKeysV19.ERASE_MODE_CELL
        for (kind in kinds) for ((name, f) in states(kind)) {
            val cell = spec(f).items.firstOrNull { it.key == key.key }
            val mode = f.eraseCell
            if (mode == null) {
                assertEquals("$name: no cell while painting", null, cell)
                continue
            }
            assertNotNull("$name: the mode cell is on the strip", cell)
            cell!!
            assertEquals(name, StripRow.ROW1, cell.row)
            assertEquals(name, 0, cell.overflowRank)
            assertEquals(name, key.widthDp.toFloat(), cell.width.value)
            assertEquals(name, EraseLabels19.MODE_CELL.format(mode.label), cell.label)
            for ((dp, chip) in configs) {
                val s = StripLayout.split(spec(f), available(dp, chip))
                assertTrue("$name: the cell in row 1 at $dp dp${if (chip) " with the VECTOR cell" else ""}", s.row1.any { it.key == key.key })
            }
        }
    }

    @Test
    fun theItemsThatMakeRoomForTheCellAndTheWidthsInBudget() {
        val auto = mutableMapOf<CurveKind, MutableSet<String>>()
        val moved = mutableMapOf<CurveKind, MutableSet<String>>()
        val shortcuts = sortedSetOf<String>()
        for (kind in kinds) for ((_, f) in states(kind)) {
            if (f.eraseCell == null) continue
            val open = if (f.points > 0) "open " else "empty "
            for (item in spec(f).items) {
                if (item.row == StripRow.ROW1 && item.overflowRank > 0) auto.getOrPut(kind) { sortedSetOf() } += open + item.key + " AUTO " + item.overflowRank
                if ((item.key == CurveKeys.GENERATOR || item.key == CurveKeys.ORDER) && item.row == StripRow.MORE) moved.getOrPut(kind) { sortedSetOf() } += open + item.key
                if (item.key == CurveKeys.BRUSH_SIZE || item.key == CurveKeys.TAPER) shortcuts += "$kind $open" + item.key
            }
        }
        val k = CurveKeys
        assertEquals(sortedSetOf("empty ${k.LINE_WIDTH} AUTO 1", "open ${k.STROKE_KIND} AUTO 1"), auto[CurveKind.CURVE])
        assertEquals(sortedSetOf("open ${k.STROKE_KIND} AUTO 1"), auto[CurveKind.POLYLINE])
        assertEquals(sortedSetOf("empty ${k.STROKE_KIND} AUTO 1", "open ${k.STROKE_KIND} AUTO 1"), auto[CurveKind.PATH])
        // More items while the cell shows: Curve's open generator chip (Path's is one already), and an open
        // Path's "Order N" (after the weight; nothing open, it is one already).
        assertEquals(sortedSetOf("open ${k.GENERATOR}"), moved[CurveKind.CURVE])
        assertEquals(sortedSetOf("empty ${k.ORDER}", "open ${k.GENERATOR}", "open ${k.ORDER}"), moved[CurveKind.PATH])
        // The "Use brush size" / "Taper ends" shortcut leaves the strip while the cell shows (both stay in the settings sheet).
        assertEquals(emptySet<String>(), shortcuts)
        // The non-AUTO sums of row 1 while erasing, each within rule 1's 240 dp (every curve strip has a More row).
        val worst = kinds.associateWith { kind ->
            states(kind).filter { it.second.eraseCell != null }
                .maxOf { (_, f) -> StripLayout.rowWidth(spec(f).items.filter { it.row == StripRow.ROW1 && it.overflowRank <= 0 }) }
        }
        assertEquals(mapOf(CurveKind.CURVE to 212f, CurveKind.POLYLINE to 188f, CurveKind.PATH to 236f), worst)
        assertTrue(worst.values.all { it <= StripInventory.WITH_MORE })
    }

    @Test
    fun theWorstCasesKeepTheMoreRowWithinItsLinesAt360() {
        // The most the More row holds while erasing: a generated path with copies and varying thickness,
        // one point selected (Curve: its tangent dragged) or two (Path: the weight too), each stroke kind.
        for (kind in kinds) for (mode in VectorEraseMode.entries) for (stroke in CurveStroke.entries) for (sel in listOf(1, 2)) {
            val f = CurveStripFacts(
                kind = kind, points = 5, selected = sel, single = if (sel == 1) 0 else -1, stroke = stroke,
                generated = if (kind == CurveKind.POLYLINE) null else CurveGenKind.ARCHIMEDEAN, copies = true, anyThickness = true,
                sharp = if (kind == CurveKind.POLYLINE) SharpState.NONE else if (sel == 1) SharpState.SMOOTH else SharpState.MIXED,
                customTangent = kind == CurveKind.CURVE && sel == 1, handles = kind == CurveKind.CURVE, eraseCell = mode,
            )
            val spec = spec(f)
            for ((dp, chip) in configs) {
                val s = StripLayout.split(spec, available(dp, chip))
                val lines = StripLayout.moreLines(s, spec.hint, StripInventory.content(dp.toFloat()), 1)
                assertTrue("$kind $mode $stroke $sel at $dp dp chip=$chip: ${lines.size} More lines", lines.size <= StripLayout.MAX_MORE_LINES)
                for (line in lines) assertTrue("$kind $mode $stroke $sel at $dp dp chip=$chip: a line of ${StripLayout.rowWidth(line)} dp", StripLayout.rowWidth(line) <= StripInventory.content(dp.toFloat()))
            }
        }
    }

    /** The texts of the menus A1 opens from its strips, and of slot 1, that share the screen with the strip. */
    private fun menuTexts(kind: CurveKind): Map<String, List<String>> = mapOf(
        "Stroke kind" to listOf(CurveLabels17.STROKE_ONLY, CurveLabels17.FILL_ONLY, CurveLabels17.BOTH) + STROKE_KINDS.map { it.label } + EraseLabels19.INSTEAD_OF_PAINT,
        "mode cell" to VectorEraseMode.entries.map { it.label } + EraseLabels19.TURN_INTO_PIXELS,
        "slot 1" to when (kind) {
            CurveKind.CURVE -> listOf(EraseLabels19.WITH_CURVE, EraseLabels19.ERASING_CURVE)
            CurveKind.POLYLINE -> listOf(EraseLabels19.WITH_POLYLINE, EraseLabels19.ERASING_POLYLINE)
            CurveKind.PATH -> listOf(EraseLabels19.WITH_PATH, EraseLabels19.ERASING_PATH)
        },
    )

    @Test
    fun labelScanNoLabelRepeatsOnAStripItsMenusOrSlotOne() {
        val gone = StripHandlesV17.REMOVED + PointLabels.SELECT_SEVERAL
        val problems = linkedSetOf<String>()
        for (kind in kinds) {
            val menus = menuTexts(kind)
            for ((menu, texts) in menus) {
                val d = texts.groupBy { it }.filter { it.value.size > 1 }.keys
                if (d.isNotEmpty()) problems += "$kind: the $menu menu repeats $d"
            }
            for ((name, f) in states(kind)) {
                val spec = spec(f)
                for ((dp, chip) in configs) {
                    val s = StripLayout.split(spec, available(dp, chip))
                    val items = (s.row1 + s.more).map { it.label }
                    val shown = items + spec.movedInto.keys
                    val d = shown.groupBy { it }.filter { it.value.size > 1 }.keys
                    if (d.isNotEmpty()) problems += "$name at $dp dp chip=$chip: repeats $d"
                    for ((menu, texts) in menus) {
                        // (The paint modes are the "Stroke kind" menu's own rows, its movedInto handles.)
                        val both = texts.filter { it in items || (it in spec.movedInto && spec.movedInto[it] != CurveKeys.STROKE_KIND) }
                        if (both.isNotEmpty()) problems += "$name: $both on the strip and in the $menu menu"
                    }
                    val back = shown.filter { it in gone }
                    if (back.isNotEmpty()) problems += "$name: a removed duplicate $back"
                    if (shown.any { it.isBlank() }) problems += "$name: a blank label"
                }
            }
        }
        // The three modes' cells read differently.
        assertEquals(3, VectorEraseMode.entries.map { EraseLabels19.MODE_CELL.format(it.label) }.toSet().size)
        assertTrue("the label scan:\n" + problems.take(40).joinToString("\n"), problems.isEmpty())
    }
}
