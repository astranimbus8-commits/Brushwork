package com.brushwork.paint.v110.cross

import android.graphics.Rect
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.tiles.ChaosResidency
import com.brushwork.paint.engine.tiles.ResidencyState
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.v110.a1.A1Kit.withMaps
import com.brushwork.paint.v110.a8.A8Kit
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.lift.VectorLift
import com.brushwork.paint.vector.select.ObjectTestKit
import com.brushwork.paint.vector.select.vec
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner

/**
 * v1.10 Gate 2 cross test (R1 x A8): the side tap cycle on a vector layer that parks between the
 * taps. An object mirrored 8 ways (the 8 symmetries of the square about the document's centre)
 * lies over a box; tapped at the same spot on one copy, the Transform tool picks that side, then
 * all sides, then the box underneath, then the side again. At FULL the vector layer is parked
 * (`ChaosResidency.park`, the F1 hook: its pixels cut into tiles) before every tap after the first,
 * and the cycle goes on exactly as it does unparked (the same picks and the same lift boxes); the
 * taps record nothing. The side picked by the last tap (on a layer parked before it) then moves
 * by whole pixels: ✓ is ONE step "Transform objects" that changes only that copy's map, and Undo
 * and Redo (each on a layer parked again) are one step each and give back the maps bit for bit
 * and the layer's pixels exactly (read from its store without materializing it, against a fresh
 * render of its content).
 *
 * The app never parks the active layer itself (I29); the hook makes the state on purpose, so the
 * tool's reads of the layer go through the facade. A park while a transform is pending replaces
 * the layer's bitmap, and the Transform tool drops such a session (`isValid`: its target is no
 * longer the layer's), so no park comes between the pick and its ✓. At CLASSIC the same test runs
 * with no park.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class CrossSideOnParkedLayerTest(private val level: EngineLevel) {
    @get:Rule
    val engine = EngineRule(level)

    private val kit = ObjectTestKit()
    private var controller: EditorController? = null

    @After
    fun tearDown() {
        controller?.let { c ->
            c.dispose()
            A8Kit.drain()
            for (l in c.doc.layers) l.recycleBitmaps()
        }
        kit.close()
    }

    /** One pick of the cycle: the selected ids, the side picked alone (id to copy) and the lift's box. */
    private data class Pick(val ids: Set<Long>, val side: Pair<Long, Int>?, val box: Rect?)

    private fun pickOf(c: EditorController): Pick {
        val side = VectorLift.providerOf(c).sidePick?.let { it.id to it.k }
        return Pick(c.vectors.selectedIds, side, VectorLift.activeLift(c)?.sourceRect?.let { Rect(it) })
    }

    private fun EditorController.tap(x: Float, y: Float) {
        pointerDown(ToolPoint(x, y))
        pointerUp(ToolPoint(x, y))
    }

    private fun maps(o: VObject): List<List<Float>> = ObjectCopies.of(o).maps.map { it.toList() }

    private fun settle(c: EditorController) = assertTrue("the layer settles", kit.idleUntil { !c.vectors.isRendering })

    /** At FULL, parks the vector layer (the F1 hook) unless it still is, and checks it is parked now; nothing at CLASSIC. */
    private fun parkIfFull(c: EditorController, what: String) {
        if (level == EngineLevel.CLASSIC) return
        if (c.vec.residency != ResidencyState.PARKED) assertTrue("$what: the layer parks", ChaosResidency().park(c.vec))
        assertEquals("$what: parked", ResidencyState.PARKED, c.vec.residency)
    }

    /** The layer's pixels, read from its store (no materialization). */
    private fun pixels(c: EditorController): IntArray = A8Kit.pixelsOf(c.vec).first

    /** The Transform tool chosen again with nothing selected (a new tap cycle), settled. */
    private fun fresh(c: EditorController): TransformTool {
        c.selectTool(ToolId.BRUSH)
        c.vectors.setSelection(c.vec, emptySet())
        val tool = kit.transform(c)
        settle(c)
        return tool
    }

    /** Taps [spot] once per entry of [expected] (parking before every tap but the first when [park]), waiting for each pick. */
    private fun cycle(c: EditorController, spot: Pair<Float, Float>, expected: List<Pick>, park: Boolean): List<Pick> =
        expected.mapIndexed { i, want ->
            if (park && i > 0) parkIfFull(c, "tap ${i + 1}")
            c.tap(spot.first, spot.second)
            kit.idleUntil { pickOf(c).let { it.ids == want.ids && it.side == want.side && it.box != null } }
            settle(c)
            pickOf(c)
        }

    @Test
    fun theSideCycleGoesOnAcrossParksAndTheSideEditIsOneExactStep() {
        val c = kit.controller().also { controller = it }
        val shape = kit.rectangle(346f, 132f, 30f, 16f)
        val ids = c.vectors.addObjects(c.vec, listOf(kit.box(142f, 119f, 184f, 152f), withMaps(shape, d4())), "Add")
        val (box, mirrored) = ids
        settle(c)
        val steps = c.undoManager.undoCount
        // Copy 4 (the mirror across x = 256) of the rectangle lies on the box: (151..181, 124..140).
        val spot = 166f to 132f
        val expected = listOf(
            Pick(setOf(mirrored), mirrored to 4, null),
            Pick(setOf(mirrored), null, null),
            Pick(setOf(box), null, null),
            Pick(setOf(mirrored), mirrored to 4, null),
        )
        fun List<Pick>.withoutBoxes() = map { it.copy(box = null) }

        // The cycle unparked, then again from the start with a park before every tap but the first.
        fresh(c)
        val unparked = cycle(c, spot, expected, park = false)
        assertEquals("side, all sides, the box underneath, the side again", expected, unparked.withoutBoxes())
        fresh(c)
        val parked = cycle(c, spot, expected, park = true)
        assertEquals("the cycle across parks is the cycle unparked, boxes and all", unparked, parked)
        assertEquals("taps record nothing", steps, c.undoManager.undoCount)

        // The side picked on the parked layer moved by whole pixels, then ✓: one step.
        val tool = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        val before = maps(c.vec.vector!!.byId(mirrored)!!)
        val beforePixels = pixels(c)
        val beforeBox = c.vec.vector!!.byId(box)
        tool.moveBy(12f, 8f)
        tool.commit()
        settle(c)
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertEquals(TransformTool.TRANSFORM_OBJECTS_LABEL, c.undoManager.undoLabel)
        val after = maps(c.vec.vector!!.byId(mirrored)!!)
        assertEquals("still 8 sides", 8, after.size)
        for (k in 0 until 8) if (k != 4) assertEquals("side $k stays", before[k], after[k])
        assertNotEquals("side 4 moved", before[4], after[4])
        assertEquals("side 4 moved by (12, 8)", listOf(before[4][2] + 12f, before[4][5] + 8f), listOf(after[4][2], after[4][5]))
        assertEquals("the box underneath stays", beforeBox, c.vec.vector!!.byId(box))
        val afterPixels = pixels(c)
        assertArrayEquals("the layer is its content, drawn afresh", kit.render(c.vec.vector!!), afterPixels)
        assertNotEquals("the pixels changed", beforePixels.toList(), afterPixels.toList())

        // Undo, then Redo, each on a parked layer: one step each, maps and pixels exact.
        parkIfFull(c, "undo")
        c.undo()
        settle(c)
        assertEquals("undo: one step", steps, c.undoManager.undoCount)
        assertEquals("undo: the maps bit for bit", before, maps(c.vec.vector!!.byId(mirrored)!!))
        assertArrayEquals("undo: the pixels as before", beforePixels, pixels(c))
        parkIfFull(c, "redo")
        c.redo()
        settle(c)
        assertEquals("redo: one step", steps + 1, c.undoManager.undoCount)
        assertEquals("redo: the maps bit for bit", after, maps(c.vec.vector!!.byId(mirrored)!!))
        assertArrayEquals("redo: the pixels as after", afterPixels, pixels(c))
        assertArrayEquals("redo: the layer is its content", kit.render(c.vec.vector!!), pixels(c))
    }

    companion object {
        private const val CX = 256f
        private const val CY = 192f

        /** The map about the document's centre with linear part (a b; c d) (exact: entries 0 and ±1). */
        private fun about(a: Float, b: Float, c: Float, d: Float) =
            floatArrayOf(a, b, CX - a * CX - b * CY, c, d, CY - c * CX - d * CY, 0f, 0f, 1f)

        /** The 8 symmetries of the square about the centre: identity, 3 turns, 4 mirrors (copy 4 across x = 256). */
        fun d4(): List<FloatArray> = listOf(
            about(1f, 0f, 0f, 1f), about(0f, -1f, 1f, 0f), about(-1f, 0f, 0f, -1f), about(0f, 1f, -1f, 0f),
            about(-1f, 0f, 0f, 1f), about(1f, 0f, 0f, -1f), about(0f, 1f, 1f, 0f), about(0f, -1f, -1f, 0f),
        )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun levels(): List<Array<Any>> = listOf(arrayOf(EngineLevel.CLASSIC), arrayOf(EngineLevel.FULL))
    }
}
