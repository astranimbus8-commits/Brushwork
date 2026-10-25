package com.brushwork.paint.v110.a3

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshots.Snapshot
import com.brushwork.paint.array.ArrayBehind
import com.brushwork.paint.array.ArrayLookKit
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.masks.AdjustmentEffects
import com.brushwork.paint.ui.common.ArrayLabels110
import com.brushwork.paint.v110.a3.BehindKit.oneStep
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.10 (R4, A3 step 3): the controller's entry points (region `v110-A3 arrayBehind`):
 * `arrayBehindAvailable` lights the bars' item for a raster selection, selected objects and a
 * text, shape or vector layer, never for a folder, an adjustment layer or a layer with an array,
 * and follows those changes inside a `derivedStateOf` (as the editor screen reads it);
 * `arrayBehind()` takes the selected objects, else the selection.
 */
@RunWith(RobolectricTestRunner::class)
class ArrayBehindEntryPointsTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() = ArrayDraw.clearCaches()

    @Test
    fun availableFollowsTheSourceAndTheSelection() {
        val c = ArrayLookKit.controller(app)
        val shown = derivedStateOf { c.arrayBehindAvailable }
        fun now(): Boolean { Snapshot.sendApplyNotifications(); return shown.value }
        val raster = c.doc.layers[1]
        c.selectLayer(raster)
        c.setSelection(null, recordUndo = false)
        assertFalse("a raster layer without a selection", now())
        BehindKit.selectedSquare(c)
        assertTrue("a raster selection", now())
        val text = BehindKit.textLayer(c)
        c.setSelection(null, recordUndo = false)
        assertTrue("a text layer", now())
        c.addFolder()!!
        assertFalse("a folder", now())
        c.addAdjustmentLayer(AdjustmentEffects.defaultSpec(), null)!!
        assertFalse("an adjustment layer", now())
        c.selectLayer(text)
        assertTrue(now())
        // The copies-only layer it makes has an array: not offered on it.
        oneStep(c, ArrayLabels110.ARRAY_BEHIND_STEP) { c.arrayBehind() }
        assertTrue(ArrayBehind.isCopiesOnly(c.activeLayer))
        assertFalse("a layer with an array", now())
        c.undo()
        ArrayLookKit.settle(c)
        assertTrue("undone: the text layer again", now())
    }

    @Test
    fun arrayBehindTakesTheSelectedObjectsFirst() {
        val c = ArrayLookKit.controller(app)
        val v = BehindKit.twoBoxes(c)
        val second = v.vector!!.objects[1]
        // A raster selection over the first box, and the second box picked on the object bar.
        c.setSelection(ArrayLookKit.rectSelection(c, 10f, 20f, 70f, 80f), recordUndo = false)
        c.vectors.setSelection(v, setOf(second.id))
        assertTrue(c.arrayBehindAvailable)
        oneStep(c, ArrayLabels110.ARRAY_BEHIND_STEP) { c.arrayBehind() }
        assertEquals("the picked object, not the selected area's", listOf(second), c.activeLayer.vector!!.objects)
        assertEquals(c.doc.indexOf(v) - 1, c.doc.indexOf(c.activeLayer))
    }
}
