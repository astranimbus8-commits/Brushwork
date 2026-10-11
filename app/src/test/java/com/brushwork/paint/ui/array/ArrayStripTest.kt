package com.brushwork.paint.ui.array

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.activity.compose.setContent
import com.brushwork.paint.array.ArrayLookKit
import com.brushwork.paint.array.ArrayOps
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.array.ArrayTool
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.common.V17Tags
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.tools.strip.StripHandlesV17
import com.brushwork.paint.ui.tools.strip.StripInventory
import com.brushwork.paint.ui.tools.strip.StripLayout
import com.brushwork.paint.ui.tools.strip.StripRig
import com.brushwork.paint.ui.tools.strip.StripRow
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorLayers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 (area B, design §3.9 Array row, §4.9): the Array tool's converted strip at 360 and 392 dp,
 * on the raster layer and on the vector layer (the VECTOR cell), in each of its states: no array
 * (the v1.7 hint, 236 dp), an array (the chip, spoken "Array settings", 120 dp, and the room of
 * "Rendering array…", 112 dp), a raster source being edited ("Finish source edit" in the chip's
 * place), a render past 300 ms (the note shows, the chip does not move) and a vector array.
 * Every state is converted (`legacyContent == null`), all on row 1 with no toggle and no More row
 * in every configuration, keeps the inventory's rules (each item's own width measured no wider
 * than declared) and the v1.7 handles; every shown item lies wholly inside row 1. The chip opens
 * the Array sheet (an overlay), "Finish source edit" finishes in ONE step.
 * One UI test, own sandbox; the 392 dp phone is a second rig after a change of qualifiers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h780dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.array.arraystripsandbox"])
class ArrayStripTest {

    @Test
    fun theArrayStripAt360And392Dp() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 120_000)
        try {
            for (w in listOf(360, 392)) {
                if (w != 360) RuntimeEnvironment.setQualifiers("w${w}dp-h873dp-xxhdpi")
                val rig = StripRig(panel = false).show()
                try {
                    states(rig, w)
                } finally {
                    // The next phone's rig is the only strip on screen.
                    rig.activity.setContent {}
                    rig.settle()
                    rig.close()
                }
            }
        } finally {
            dog.interrupt()
            ArrayDraw.clearCaches()
        }
    }

    private fun states(rig: StripRig, w: Int) {
        val c = rig.c
        assertEquals("the phone is $w dp wide", w, rig.activity.resources.configuration.screenWidthDp)
        val raster = rig.doc.layers[0]
        val vector = rig.doc.layers[1]
        val tool = c.tools.getValue(ToolId.ARRAY) as ArrayTool

        // No array: the v1.7 hint, alone on row 1.
        rig.select(ToolId.ARRAY, vector = false)
        check(rig, "$w dp, no array", listOf(KEY_HINT), StripHandlesV17.HANDLES.getValue("ARRAY"))
        assertTrue(SmokeUi.has(NO_ARRAY_HINT, exact = true))
        rig.select(ToolId.ARRAY, vector = true)
        assertTrue(c.isVectorMode)
        check(rig, "$w dp, no array, VECTOR", listOf(KEY_HINT), StripHandlesV17.HANDLES.getValue("ARRAY@vector"))
        assertTrue("the VECTOR cell", SmokeUi.has(VECTOR_MODE_ON, exact = true))
        assertTrue(SmokeUi.has(NO_ARRAY_HINT, exact = true))

        // A raster array: the chip, and the rendering note's room.
        rig.select(ToolId.BRUSH, vector = false)
        Canvas(raster.bitmap).drawRect(20f, 30f, 60f, 70f, Paint().apply { color = ArrayLookKit.GRAY })
        raster.markChanged()
        c.setSelection(ArrayLookKit.rectSelection(c, 20f, 30f, 60f, 70f), recordUndo = false)
        assertTrue(c.arrayFromSelection())
        c.setSelection(null, recordUndo = false)
        ArrayLookKit.settle(c)
        val layer = c.activeLayer
        assertNotNull(layer.array)
        if (c.activeToolId != ToolId.ARRAY) c.selectTool(ToolId.ARRAY)
        if (tool.sheetOpen) tool.closeSheet()
        rig.settle()
        val arrayKeys = listOf(KEY_SETTINGS, KEY_RENDERING)
        check(rig, "$w dp, Line × 3", arrayKeys, setOf(SETTINGS))
        assertFalse("not rendering: the note's room is kept, empty", rig.spec.items.single { it.key == KEY_RENDERING }.shown)
        assertFalse(SmokeUi.has(ArrayLabels.RENDERING))

        // The chip opens the Array sheet, an overlay of the strip.
        SmokeUi.click(SETTINGS, exact = true)
        assertTrue(tool.sheetOpen)
        assertNotNull("the Array sheet", StripTestKit.tagged(V17Tags.ARRAY_SHEET))
        tool.closeSheet()
        rig.settle()
        assertNull(StripTestKit.tagged(V17Tags.ARRAY_SHEET))

        // The source being edited (an undone "Finish source edit" with the Array tool current).
        assertTrue(ArrayOps.editSource(c, layer))
        rig.settle()
        assertEquals(ToolId.ARRAY, c.activeToolId)
        check(rig, "$w dp, editing the source", arrayKeys, setOf(ArrayLabels.FINISH_SOURCE))
        assertFalse(SmokeUi.has(SETTINGS, exact = true))
        val steps = c.undoManager.undoCount
        SmokeUi.click(ArrayLabels.FINISH_SOURCE, exact = true)
        ArrayLookKit.settle(c)
        assertFalse(layer.array!!.spec.editingSource)
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertEquals(ArrayLabels.FINISH_SOURCE, c.undoManager.undoLabel)
        rig.settle()
        assertTrue(SmokeUi.has(SETTINGS, exact = true))

        // A render past 300 ms: the note shows beside the chip, which stays where it was.
        val chipBefore = bounds(SETTINGS)
        c.arrayRenders.policy = VectorLayers.Policy.ASYNC
        val gate = CountDownLatch(1)
        c.arrayRenders.workerHook = { gate.await(20, TimeUnit.SECONDS) }
        try {
            tool.commit(layer.array!!.spec.copy(count = 4))
            assertTrue("rendering in the background", c.arrayRenders.isPending)
            rig.settle()
            assertTrue("\"${ArrayLabels.RENDERING}\" past 300 ms", tool.rendering && SmokeUi.has(ArrayLabels.RENDERING, exact = true))
            assertTrue(rig.spec.items.single { it.key == KEY_RENDERING }.shown)
            check(rig, "$w dp, rendering", arrayKeys, setOf(SETTINGS))
            assertEquals("the chip does not move", chipBefore, bounds(SETTINGS))
        } finally {
            gate.countDown()
        }
        assertTrue(Smoke.pumpUntil { !c.arrayRenders.isPending })
        c.arrayRenders.workerHook = null
        c.arrayRenders.policy = VectorLayers.Policy.SYNC
        rig.settle()
        assertFalse("gone once it landed", SmokeUi.has(ArrayLabels.RENDERING))
        assertEquals(4, layer.array!!.spec.count)

        // A vector array: the chip beside the VECTOR cell.
        c.vectors.update(vector, VectorContent.EMPTY.plus(listOf(ArrayLookKit.box(200f, 100f, 240f, 140f))).first, "Add")
        ArrayLookKit.settle(c)
        assertTrue(c.arrayWholeLayer(vector))
        ArrayLookKit.settle(c)
        c.selectLayer(vector)
        if (c.activeToolId != ToolId.ARRAY) c.selectTool(ToolId.ARRAY)
        if (tool.sheetOpen) tool.closeSheet()
        rig.settle()
        assertTrue(c.isVectorMode)
        assertNotNull(vector.array)
        check(rig, "$w dp, vector array", arrayKeys, setOf(SETTINGS))
        assertTrue("the VECTOR cell", SmokeUi.has(VECTOR_MODE_ON, exact = true))
        SmokeUi.click(SETTINGS, exact = true)
        assertNotNull("the Array sheet", StripTestKit.tagged(V17Tags.ARRAY_SHEET))
        tool.closeSheet()
        rig.settle()
        Smoke.assertQuiet(c, "the Array strip at $w dp")
    }

    /**
     * The strip of [what] now: converted, [keys] all on row 1 (236 dp) with no toggle at 360 and
     * 392 dp with and without the VECTOR cell, the inventory's rules and [record] kept (each item's
     * own width measured), and each shown item wholly inside row 1 on screen.
     */
    private fun check(rig: StripRig, what: String, keys: List<String>, record: Set<String>) {
        val spec = rig.spec
        assertNull("$what: converted", spec.legacyContent)
        assertNull("$what: no hint line (it would add the toggle)", spec.hint)
        assertTrue("$what: nothing moved into a popup", spec.movedInto.isEmpty())
        assertEquals("$what: the items", keys, spec.items.map { it.key })
        assertTrue("$what: row 1 only, never AUTO", spec.items.all { it.row == StripRow.ROW1 && it.overflowRank == 0 })
        assertEquals("$what: row 1's width", 236f, StripLayout.rowWidth(spec.items), 0.01f)
        for (screen in listOf(360f, 392f)) for (cell in listOf(false, true)) {
            val available = StripInventory.content(screen) - if (cell) StripLayout.VECTOR_CHIP_DP + StripLayout.GAP_DP else 0f
            val split = StripLayout.split(spec, available)
            val where = "$what at $screen dp${if (cell) " with the VECTOR cell" else ""}"
            assertFalse("$where: no toggle", split.toggle)
            assertEquals("$where: all on row 1", keys, split.row1.map { it.key })
        }
        val (_, problems) = StripInventory.inspect(rig, what, record)
        assertEquals("$what: the inventory", emptyList<String>(), problems)
        assertNull("$what: no toggle on screen", StripTestKit.toggle())
        assertFalse(StripTestKit.moreRowShown())
        val row = requireNotNull(StripTestKit.tagged(V18Tags.STRIP_ROW1)) { "$what: no row 1" }.boundsInWindow
        for (item in spec.items) {
            if (!item.shown) continue
            val b = bounds(item.label)
            assertTrue("$what: \"${item.label}\" $b wholly inside row 1 $row", b.left >= row.left - 0.5f && b.right <= row.right + 0.5f && b.width() > 0f)
        }
    }

    /** The window bounds of the shown node [label] (its description or its text). */
    private fun bounds(label: String): RectF {
        val b = requireNotNull(SmokeUi.find(label, exact = true)) { "\"$label\" is not shown; shown: ${SmokeUi.shown().take(40)}" }.node.boundsInWindow
        return RectF(b.left, b.top, b.right, b.bottom)
    }

    private companion object {
        /** The converted row's VECTOR cell (StripHost). */
        const val VECTOR_MODE_ON = "Vector mode is on"
    }
}
