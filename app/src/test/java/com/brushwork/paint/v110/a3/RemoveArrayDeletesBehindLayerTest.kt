package com.brushwork.paint.v110.a3

import com.brushwork.paint.array.ArrayBehind
import com.brushwork.paint.array.ArrayLookKit
import com.brushwork.paint.array.ArrayOps
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.array.ArrayTool
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.common.ArrayLabels110
import com.brushwork.paint.v110.a3.BehindKit.oneStep
import com.brushwork.paint.v110.a3.BehindKit.redo
import com.brushwork.paint.v110.a3.BehindKit.undo
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.10 (R4, UQ11, A3 step 2): on a copies-only layer the remove item reads
 * [ArrayLabels110.REMOVE_WITH_LAYER] and deletes the whole layer as ONE step with that name (undo
 * brings it back with its array and cache); the source is untouched, and the layer offers no
 * source edit. A v1.9 array keeps "Remove array", which keeps its layer.
 */
@RunWith(RobolectricTestRunner::class)
class RemoveArrayDeletesBehindLayerTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() = ArrayDraw.clearCaches()

    @Test
    fun removingOnACopiesOnlyLayerDeletesItInOneStep() {
        val c = ArrayLookKit.controller(app)
        val src = BehindKit.selectedSquare(c)
        assertTrue(ArrayBehind.fromSelectionBelow(c))
        ArrayLookKit.settle(c)
        val made = c.activeLayer
        val index = c.doc.indexOf(made)
        val cache = ArrayLookKit.pixels(made.bitmap)
        val srcPixels = ArrayLookKit.pixels(src.bitmap)
        assertEquals(ArrayLabels110.REMOVE_WITH_LAYER, ArrayBehind.removeLabel(made))
        val tool = c.tools[ToolId.ARRAY] as ArrayTool
        assertTrue(tool.sheetOpen)
        oneStep(c, ArrayLabels110.REMOVE_WITH_LAYER) { tool.removeArray() }
        assertEquals("the layer is gone", -1, c.doc.indexOf(made))
        assertFalse("the sheet goes with it", tool.sheetOpen)
        assertArrayEquals(srcPixels, ArrayLookKit.pixels(src.bitmap))
        undo(c)
        assertEquals("undo brings it back in place", index, c.doc.indexOf(made))
        assertTrue(ArrayBehind.isCopiesOnly(made))
        assertNotNull(made.array!!.pixels)
        assertArrayEquals(cache, ArrayLookKit.pixels(made.bitmap))
        redo(c)
        assertEquals(-1, c.doc.indexOf(made))
        // Straight through ArrayOps too (the layer menu's remove item).
        undo(c)
        oneStep(c, ArrayLabels110.REMOVE_WITH_LAYER) { assertTrue(ArrayOps.remove(c, made)) }
        assertEquals(-1, c.doc.indexOf(made))
    }

    @Test
    fun aCopiesOnlyLayerOffersNoSourceEdit() {
        val c = ArrayLookKit.controller(app)
        BehindKit.selectedSquare(c)
        assertTrue(ArrayBehind.fromSelectionBelow(c))
        ArrayLookKit.settle(c)
        val made = c.activeLayer
        val steps = c.undoManager.undoCount
        assertFalse(ArrayOps.editSource(c, made))
        assertEquals(steps, c.undoManager.undoCount)
        assertFalse(made.array!!.spec.editingSource)
    }

    @Test
    fun aV19ArrayKeepsRemoveArrayAndItsLayer() {
        val c = ArrayLookKit.controller(app)
        val arrayed = ArrayLookKit.pixelArray(c)
        assertFalse(ArrayBehind.isCopiesOnly(arrayed))
        assertEquals(ArrayLabels.REMOVE, ArrayBehind.removeLabel(arrayed))
        oneStep(c, ArrayLabels.REMOVE) { assertTrue(ArrayOps.remove(c, arrayed)) }
        assertSame("the layer stays", arrayed, c.doc.layers[c.doc.indexOf(arrayed)])
        assertEquals(null, arrayed.array)
    }
}
