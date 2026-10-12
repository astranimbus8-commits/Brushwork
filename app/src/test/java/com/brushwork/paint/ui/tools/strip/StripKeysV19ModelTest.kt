package com.brushwork.paint.ui.tools.strip

import androidx.compose.ui.unit.dp
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.vector.VectorEraserStrip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.9 F1a (design 2.3.5): the strip keys are as designed and keep the strip's rules (unique
 * keys, widths in 4 dp steps, 40 dp at least when in the strip, width 0 exactly for rank 99, tools
 * named as `ToolId`), and [StripKeysV19Kit] places them as 2.3.5 says. JVM; the walk over the
 * real strips is [StripKeysV19Test].
 */
class StripKeysV19ModelTest {
    @Test
    fun theKeysAreAsDesigned() {
        val k = StripKeysV19
        assertEquals(
            listOf(k.BEVEL, k.ERASE_MODES, k.ERASE_PIXELS, k.ERASE_MODE_CELL, k.ERASE_KIND, k.OWN_LAYER, k.NEW_LAYER_EACH_TIME_TEXT, k.NEW_LAYER_EACH_TIME_SHAPE, k.DUPLICATE, k.FORWARD, k.BACKWARD),
            k.ALL,
        )
        val curves = setOf("curve", "polyline", "path")
        assertEquals(
            listOf(
                StripKeyV19("points.bevel", 40, 60, setOf("polyline", "shape")),
                StripKeyV19("eraser.modes", 120, 0, setOf("eraser")),
                StripKeyV19("eraser.pixels", 132, 90, setOf("eraser")),
                StripKeyV19("erase.modeCell", 56, 0, curves + "shape"),
                StripKeyV19("stroke.erase", 0, 99, curves + "shape"),
                StripKeyV19("curve.ownLayer", 0, 99, curves),
                StripKeyV19("text.newLayerEachTime", 132, 80, setOf("text")),
                StripKeyV19("shape.newLayerEachTime", 0, 99, setOf("shape")),
                StripKeyV19("object.duplicate", 40, 50, setOf("shape", "text")),
                StripKeyV19("object.forward", 40, 51, setOf("shape", "text")),
                StripKeyV19("object.backward", 40, 52, setOf("shape", "text")),
            ),
            k.ALL,
        )
    }

    @Test
    fun theKeysKeepTheStripRules() {
        val keys = StripKeysV19.ALL.map { it.key }
        assertEquals("unique keys", keys.size, keys.toSet().size)
        val tools = ToolId.entries.map { it.name.lowercase() }.toSet()
        for (k in StripKeysV19.ALL) {
            assertTrue("${k.key}: rank ${k.rank} in 0..99", k.rank in 0..99)
            assertEquals("${k.key}: width 0 exactly for rank 99", k.widthDp == 0, k.rank == 99)
            assertEquals("${k.key}: rounded to 4 dp (inventory rule 4)", 0, k.widthDp % 4)
            if (k.widthDp > 0) assertTrue("${k.key}: 40 dp at least when in the strip", k.widthDp >= 40)
            assertTrue("${k.key}: tools named as ToolId: ${k.tools - tools}", k.tools.isNotEmpty() && tools.containsAll(k.tools))
        }
        assertEquals("v1.8's chooser stays one item", VectorEraserStrip.KEY, StripKeysV19.ERASE_MODES.key)
        assertEquals(VectorEraserStrip.WIDTH_DP, StripKeysV19.ERASE_MODES.widthDp.toFloat())
    }

    private fun item(key: String, width: Int, row: StripRow = StripRow.ROW1, rank: Int = 0) = StripItem(key, key, width.dp, row, rank) {}

    @Test
    fun theKitPlacesTheKeysAsDesigned() {
        val spec = StripSpec(
            listOf(item("a", 80), item("m1", 100, StripRow.MORE), item("b", 72, rank = 1), item("m2", 60, StripRow.MORE)),
            hint = "A hint",
        )
        val objects = StripKeysV19Kit.Situation(onObjects = true)
        val shape = StripKeysV19Kit.augmented(spec, ToolId.SHAPE, objects)
        assertEquals(listOf("a", "b", "erase.modeCell", "m1", "m2", "object.duplicate", "object.forward", "object.backward"), shape.items.map { it.key })
        val cell = shape.items.single { it.key == "erase.modeCell" }
        assertEquals(StripRow.ROW1, cell.row)
        assertEquals(0, cell.overflowRank)
        assertEquals(56f, cell.width.value)
        assertTrue(shape.items.filter { it.key.startsWith("object.") }.all { it.row == StripRow.MORE })
        assertEquals("A hint", shape.hint)
        // Shape: Bevel in Points mode only, the object items never in it; no erase cell on a raster layer.
        assertEquals(
            listOf("a", "b", "erase.modeCell", "m1", "m2", "points.bevel"),
            StripKeysV19Kit.augmented(spec, ToolId.SHAPE, objects.copy(pointsMode = true)).items.map { it.key },
        )
        assertEquals(
            listOf("a", "b", "m1", "m2", "object.duplicate", "object.forward", "object.backward"),
            StripKeysV19Kit.augmented(spec, ToolId.SHAPE, StripKeysV19Kit.Situation(onObjects = false)).items.map { it.key },
        )
        assertEquals(listOf("points.bevel", "erase.modeCell"), StripKeysV19Kit.shownKeys(ToolId.POLYLINE, objects).map { it.key })
        assertEquals(listOf("points.bevel"), StripKeysV19Kit.shownKeys(ToolId.POLYLINE, StripKeysV19Kit.Situation(onObjects = false)).map { it.key })
        // Text: the object items with an open text, "New layer each time" without one.
        assertEquals(listOf("object.duplicate", "object.forward", "object.backward"), StripKeysV19Kit.shownKeys(ToolId.TEXT, objects.copy(textOpen = true)).map { it.key })
        assertEquals(listOf("text.newLayerEachTime"), StripKeysV19Kit.shownKeys(ToolId.TEXT, objects).map { it.key })
        // Keys of width 0 are rows of menus or sheets; a key the strip has already is not added again.
        val eraser = StripKeysV19Kit.augmented(StripSpec(listOf(item("eraser.modes", 120), item("eraser.size", 76))), ToolId.ERASER, objects)
        assertEquals(listOf("eraser.modes", "eraser.size", "eraser.pixels"), eraser.items.map { it.key })
        val rasterEraser = StripSpec(listOf(item("eraser.size", 76)))
        assertTrue("no erase keys on a raster layer", StripKeysV19Kit.augmented(rasterEraser, ToolId.ERASER, StripKeysV19Kit.Situation(onObjects = false)) === rasterEraser)
        assertEquals(listOf("text.newLayerEachTime", "object.duplicate", "object.forward", "object.backward").sorted(), StripKeysV19Kit.stripKeys(ToolId.TEXT).map { it.key }.sorted())
        assertTrue("a tool without keys keeps its spec", StripKeysV19Kit.augmented(spec, ToolId.BRUSH, objects) === spec)
        // Rank 1-49 would join row 1 as AUTO with that rank.
        val auto = StripKeysV19Kit.item(StripKeyV19("x.auto", 48, 7, setOf("brush")))
        assertEquals(StripRow.ROW1, auto.row)
        assertEquals(7, auto.overflowRank)
    }

    @Test
    fun theRulesCatchAMoreRowOverThreeLines() {
        // The walk's check is StripInventory.rules' rule 5: four 300 dp More items and a hint need 5 lines at 360 dp.
        val wide = StripSpec(listOf(item("a", 80)) + (1..4).map { item("m$it", 300, StripRow.MORE) }, hint = "A hint")
        assertTrue(StripInventory.rules(wide).any { it.startsWith("rule 5:") })
        val fits = StripSpec(listOf(item("a", 80), item("m", 300, StripRow.MORE)), hint = "A hint")
        assertTrue(StripInventory.rules(fits).none { it.startsWith("rule 5:") })
    }
}

/**
 * v1.9 F1a: a strip spec with the v1.9 keys a tool shows in a [Situation] (design 2.3.5), as
 * stand-in items of their declared widths: rank 0 and 1-49 at row 1's end (overflow rank =
 * rank), rank 50-98 after the More row's own items, in rank order. Keys of width 0 (rows of menus
 * and sheets) and keys the spec already has are left out.
 */
internal object StripKeysV19Kit {
    /**
     * What decides which keys show (2.3.5's notes): [onObjects], a vector or shape layer is active
     * (the eraser's chooser and "Turn into pixels", the erase mode cell); [pointsMode], Shape is in
     * Points mode (Bevel there, the object items not); [textOpen], a text is open (the object
     * items; "New layer each time" only while none is). Forward and Backward are counted whenever
     * Duplicate is (their "two or more texts" is the worst case).
     */
    data class Situation(val onObjects: Boolean, val pointsMode: Boolean = false, val textOpen: Boolean = false)

    /** The keys of [tool] that take room in the strip. */
    fun stripKeys(tool: ToolId): List<StripKeyV19> = StripKeysV19.ALL.filter { it.widthDp > 0 && tool.name.lowercase() in it.tools }

    /** The keys of [tool] the strip shows in [s]. */
    fun shownKeys(tool: ToolId, s: Situation): List<StripKeyV19> {
        val k = StripKeysV19
        return stripKeys(tool).filter { key ->
            when (key) {
                k.ERASE_MODES, k.ERASE_PIXELS, k.ERASE_MODE_CELL -> s.onObjects
                k.BEVEL -> tool != ToolId.SHAPE || s.pointsMode
                k.DUPLICATE, k.FORWARD, k.BACKWARD -> if (tool == ToolId.SHAPE) !s.pointsMode else s.textOpen
                k.NEW_LAYER_EACH_TIME_TEXT -> !s.textOpen
                else -> true
            }
        }
    }

    fun item(k: StripKeyV19): StripItem = when (k.rank) {
        in 1..49 -> StripItem(k.key, k.key, k.widthDp.dp, StripRow.ROW1, k.rank) {}
        in 50..98 -> StripItem(k.key, k.key, k.widthDp.dp, StripRow.MORE) {}
        else -> StripItem(k.key, k.key, k.widthDp.dp, StripRow.ROW1, 0) {}
    }

    fun augmented(spec: StripSpec, tool: ToolId, s: Situation): StripSpec {
        val have = spec.items.map { it.key }.toSet()
        val add = shownKeys(tool, s).filter { it.key !in have }
        if (add.isEmpty()) return spec
        val row1 = spec.items.filter { it.row == StripRow.ROW1 } + add.filter { it.rank < 50 }.map(::item)
        val more = spec.items.filter { it.row == StripRow.MORE } + add.filter { it.rank >= 50 }.sortedBy { it.rank }.map(::item)
        return StripSpec(row1 + more, spec.hint, spec.overlays, spec.movedInto)
    }
}
