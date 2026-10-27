package com.brushwork.paint.v110.cross

import android.graphics.Color
import com.brushwork.paint.EditorController
import com.brushwork.paint.array.ArrayBehind
import com.brushwork.paint.array.ArrayLookKit
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.tiles.ResidencyState
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.model.ArrayLook
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.common.ArrayLabels110
import com.brushwork.paint.v110.a3.BehindKit
import com.brushwork.paint.v110.a8.A8Kit
import com.brushwork.paint.vector.ObjectColorSlots
import com.brushwork.paint.vector.VStop
import com.brushwork.paint.vector.select.ObjectTestKit
import com.brushwork.paint.vector.select.vec
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner

/**
 * v1.10 Gate 2 cross test (R6 x R4): Change Drawing Color on a vector array made with "Array
 * behind". A whole vector layer goes behind ([ArrayLabels110.ARRAY_BEHIND_STEP]): the new layer
 * "Array 1" holds its objects and a copies-only array (`hideSource`); selected again after the
 * Array tool is left (which selects the source, R4), it gets a tint. Change
 * Drawing Color on it is ONE step: its objects take the mapped colors
 * (`ObjectColorSlots.map` with the filter's color mapper) while the array, its tint and
 * `hideSource` included, is kept the same instance; the layer shows exactly its new data drawn
 * with that array (copy 0 still hidden: the source's place stays empty), and the source layer is
 * untouched. Undo gives back the objects (the same instance) and the pixels exactly, Redo the
 * new ones. CLASSIC and FULL (where the source layer is parked once inactive: the test level's
 * `ChaosResidency` parks every inactive layer).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class CrossCdcArrayBehindTest(private val level: EngineLevel) {
    @get:Rule
    val engine = EngineRule(level)

    private val kit = ObjectTestKit()
    private var controller: EditorController? = null

    @After
    fun tearDown() {
        ArrayDraw.clearCaches()
        controller?.let { c ->
            c.dispose()
            A8Kit.drain()
            for (l in c.doc.layers) l.recycleBitmaps()
        }
        kit.close()
    }

    private val cdc get() = FilterRegistry.byId("adjust.change_drawing_color")!!

    private val tint = ArrayLook(tint = listOf(VStop(0f, 0xFFFF0000.toInt()), VStop(1f, 0xFF0000FF.toInt())), tintStrength = 0.6f)

    /** Change Drawing Color applied to the active layer as the sheet does it; its color mapper. */
    private fun changeDrawingColor(c: EditorController): (Int) -> Int {
        c.startFilter(cdc)
        val s = c.filterSession!!
        s.debounceMs = 0L
        s.update("color", 0xFF00A040.toInt())
        s.update("amount", 80f)
        assertTrue(kit.idleUntil { s.hasPreview && !s.isRendering && !s.previewStale })
        val f = cdc.colorMapper(s.values)!!
        s.apply()
        assertTrue(kit.idleUntil { s.isClosed })
        ArrayLookKit.settle(c)
        return f
    }

    private fun pixels(c: EditorController) = A8Kit.pixelsOf(c.activeLayer).first

    @Test
    fun cdcOnABehindArrayKeepsCopyZeroHiddenAndItsTintInOneStep() {
        val c = kit.controller().also { controller = it }
        val source = c.vec
        c.vectors.addObjects(source, listOf(kit.box(30f, 40f, 90f, 100f), kit.ellipse(60f, 200f)), "Add")
        ArrayLookKit.settle(c)
        val sourceContent = source.vector!!

        // The whole layer behind, then a tint on its array.
        BehindKit.oneStep(c, ArrayLabels110.ARRAY_BEHIND_STEP) { assertTrue(ArrayBehind.fromSelectionBelow(c)) }
        val made = c.activeLayer
        assertTrue("copies only", ArrayBehind.isCopiesOnly(made))
        // Leaving the Array tool selects the source again (R4); the array layer is selected back.
        c.selectTool(ToolId.BRUSH)
        ArrayLookKit.settle(c)
        assertSame(source, c.activeLayer)
        c.selectLayer(made)
        ArrayLookKit.edit(c, made, made.array!!.spec.copy(look = tint))
        assertSame(made, c.activeLayer)
        if (level == EngineLevel.FULL) assertEquals("the source layer parked once inactive", ResidencyState.PARKED, source.residency)
        val arr = made.array!!
        assertTrue("hideSource kept with the tint", arr.spec.hideSource)
        val before = made.vector!!
        val beforePixels = pixels(c)
        assertEquals("copy 0 hidden: the box's place is empty", 0, Color.alpha(beforePixels[70 * kit.w + 60]))

        // Change Drawing Color: one step, colors mapped, the array (tint, hideSource) kept.
        val steps = c.undoManager.undoCount
        val f = changeDrawingColor(c)
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        val expected = ObjectColorSlots.map(before, null, f)
        assertNotEquals("the colors changed", before, expected)
        assertEquals(expected, made.vector)
        assertSame("the array, its tint and hideSource kept", arr, made.array)
        val afterPixels = pixels(c)
        val drawn = ArrayDraw.effectiveVector(LayerData(vector = expected, array = arr))!!
        assertArrayEquals("the new data drawn with the array", kit.render(drawn), afterPixels)
        assertEquals("copy 0 still hidden", 0, Color.alpha(afterPixels[70 * kit.w + 60]))
        assertSame("the source layer is untouched", sourceContent, source.vector)

        // Undo and Redo: one step each, data and pixels exact.
        BehindKit.undo(c)
        assertEquals("undo: one step", steps, c.undoManager.undoCount)
        assertSame("undo: the objects as before", before, made.vector)
        assertSame(arr, made.array)
        assertArrayEquals("undo: the pixels as before", beforePixels, pixels(c))
        BehindKit.redo(c)
        assertEquals("redo: one step", steps + 1, c.undoManager.undoCount)
        assertEquals(expected, made.vector)
        assertSame(arr, made.array)
        assertArrayEquals("redo: the pixels as after", afterPixels, pixels(c))
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun levels(): List<Array<Any>> = listOf(arrayOf(EngineLevel.CLASSIC), arrayOf(EngineLevel.FULL))
    }
}
