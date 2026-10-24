package com.brushwork.paint.v110.a3

import android.graphics.Color
import com.brushwork.paint.array.ArrayBehind
import com.brushwork.paint.array.ArrayLookKit
import com.brushwork.paint.array.ArrayLookKit.GRAY
import com.brushwork.paint.array.ArrayOps
import com.brushwork.paint.array.ArraySources
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.masks.AdjustmentEffects
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerTree
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextBoxSpec
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.text.TextThreadSpec
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.common.ArrayLabels110
import com.brushwork.paint.v110.a3.BehindKit.oneStep
import com.brushwork.paint.v110.a3.BehindKit.redo
import com.brushwork.paint.v110.a3.BehindKit.refused
import com.brushwork.paint.v110.a3.BehindKit.undo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.10 (R4, A3 step 2): "array behind" is ONE step [ArrayLabels110.ARRAY_BEHIND_STEP] that puts a
 * new copies-only layer "Array N" directly below the source (same folder) and opens the Array
 * tool on it; undo removes it and selects the source again, redo brings it back. For a raster
 * selection, selected objects, and a whole text, shape or vector layer (also from a row that is
 * not the active one). Folders, adjustment layers and linked text frames are refused.
 */
@RunWith(RobolectricTestRunner::class)
class ArrayBehindStepTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() = ArrayDraw.clearCaches()

    /** [made] is a copies-only layer directly below [src], at its level, and the Array tool is on it. */
    private fun assertBelow(c: com.brushwork.paint.EditorController, src: Layer, made: Layer) {
        assertNotSame(src, made)
        assertEquals("directly below the source", c.doc.indexOf(src) - 1, c.doc.indexOf(made))
        assertEquals("in the source's folder", src.parentId, made.parentId)
        assertTrue("copies only", ArrayBehind.isCopiesOnly(made))
        assertSame("the new layer is active", made, c.activeLayer)
        assertEquals(ToolId.ARRAY, c.activeToolId)
        assertNull(LayerTree.check(c.doc.layers))
    }

    /** Undo removes [made] and selects [src]; redo puts [made] back below it, active. */
    private fun assertUndoRedo(c: com.brushwork.paint.EditorController, src: Layer, made: Layer) {
        val layers = c.doc.layers.size
        undo(c)
        assertEquals(layers - 1, c.doc.layers.size)
        assertEquals("undo removes the layer", -1, c.doc.indexOf(made))
        assertSame("undo selects the source again", src, c.activeLayer)
        redo(c)
        assertEquals(layers, c.doc.layers.size)
        assertEquals("redo puts it back below", c.doc.indexOf(src) - 1, c.doc.indexOf(made))
        assertSame(made, c.activeLayer)
        assertTrue(ArrayBehind.isCopiesOnly(made))
    }

    @Test
    fun selectedPixelsGoBehindInOneStep() {
        val c = ArrayLookKit.controller(app)
        val src = BehindKit.selectedSquare(c)
        val layers = c.doc.layers.size
        oneStep(c, ArrayLabels110.ARRAY_BEHIND_STEP) { assertTrue(ArrayBehind.fromSelectionBelow(c)) }
        val made = c.activeLayer
        assertEquals(layers + 1, c.doc.layers.size)
        assertBelow(c, src, made)
        assertEquals("Array 1", made.name)
        assertNotNull("the selected pixels are the source", made.array!!.pixels)
        // count 3, relative X 100 %: copies at x 60..100 and 100..140; the source place stays empty.
        assertEquals(0, Color.alpha(made.bitmap.getPixel(40, 50)))
        assertEquals(GRAY, made.bitmap.getPixel(80, 50))
        assertEquals(GRAY, made.bitmap.getPixel(120, 50))
        assertUndoRedo(c, src, made)
    }

    @Test
    fun selectedObjectsGoBehindWithTheirIds() {
        val c = ArrayLookKit.controller(app)
        val v = BehindKit.twoBoxes(c)
        val first = v.vector!!.objects[0]
        c.vectors.setSelection(v, setOf(first.id))
        assertTrue(ArrayBehind.available(c))
        oneStep(c, ArrayLabels110.ARRAY_BEHIND_STEP) { assertTrue(ArrayBehind.run(c)) }
        val made = c.activeLayer
        assertBelow(c, v, made)
        assertEquals("the picked object, its id kept", listOf(first), made.vector!!.objects)
        assertTrue("the object selection is cleared, as Array clears it", c.vectors.selectedIds.isEmpty())
        assertUndoRedo(c, v, made)
    }

    @Test
    fun aWholeLayerGoesBehindFromAnyRow() {
        val c = ArrayLookKit.controller(app)
        val text = BehindKit.textLayer(c)
        val v = BehindKit.twoBoxes(c)
        assertSame(v, c.activeLayer)
        // The layer menu on the text row while the vector layer is active.
        oneStep(c, ArrayLabels110.ARRAY_BEHIND_STEP) { assertTrue(ArrayBehind.fromLayerBelow(c, text)) }
        val madeText = c.activeLayer
        assertBelow(c, text, madeText)
        assertEquals(text.textData, madeText.textData)
        assertUndoRedo(c, text, madeText)
        // A vector layer without a selection goes whole from the bar.
        c.selectLayer(v)
        oneStep(c, ArrayLabels110.ARRAY_BEHIND_STEP) { assertTrue(ArrayBehind.fromSelectionBelow(c)) }
        val madeVector = c.activeLayer
        assertBelow(c, v, madeVector)
        assertEquals(v.vector!!.objects, madeVector.vector!!.objects)
        assertEquals("Array 2", madeVector.name)
    }

    @Test
    fun refusedSourcesAndLayersWithAnArray() {
        val c = ArrayLookKit.controller(app)
        val raster = c.doc.layers[1]
        c.selectLayer(raster)
        c.setSelection(null, recordUndo = false)
        assertFalse("a raster layer without a selection", ArrayBehind.available(c))
        refused(c, ArrayLabels.PLAIN_REFUSAL) { ArrayBehind.fromSelectionBelow(c) }
        refused(c, ArrayLabels.PLAIN_REFUSAL) { ArrayBehind.fromLayerBelow(c, raster) }
        c.setSelection(ArrayLookKit.rectSelection(c, 300f, 200f, 340f, 240f), recordUndo = false)
        assertTrue(ArrayBehind.available(c))
        refused(c, ArrayOps.EMPTY_SELECTION) { ArrayBehind.fromSelectionBelow(c) }
        c.setSelection(null, recordUndo = false)
        val folder = c.addFolder()!!
        assertFalse(ArrayBehind.available(c))
        refused(c, ArrayLabels.FOLDER_REFUSAL) { ArrayBehind.fromLayerBelow(c, folder) }
        // A layer that has an array is not offered; asked anyway, its array opens (no step).
        val arrayed = ArrayLookKit.pixelArray(c)
        c.selectTool(ToolId.BRUSH)
        assertFalse(ArrayBehind.available(c))
        val steps = c.undoManager.undoCount
        assertTrue(ArrayBehind.fromLayerBelow(c, arrayed))
        assertEquals(steps, c.undoManager.undoCount)
        assertEquals(ToolId.ARRAY, c.activeToolId)
        assertSame(arrayed, c.activeLayer)
    }

    /**
     * R4 (a): a frame of linked text stays refused ([ArrayLabels.LINKED_REFUSAL]) from the layer menu
     * and the bar, and is not offered, also after its text changes; an adjustment layer likewise
     * ([ArrayOps.ADJUSTMENT_REFUSAL]). Nothing is recorded and no layer is added.
     */
    @Test
    fun linkedTextAndAdjustmentLayersAreRefused() {
        val c = ArrayLookKit.controller(app)
        val frame = TextItem(
            "Hello",
            // A frame is a box: a thread without one is dropped (`TextItem.sanitized`).
            spec = TextSpec(sizePx = 40f, box = TextBoxSpec(width = 160f, minHeight = 60f)),
            cx = 100f,
            cy = 60f,
            thread =TextThreadSpec(storyId = 7, index = 0, story = "Hello world", start = 0, end = 5, overset = true, rev = 1),
        )
        val linked = c.addLayerWithContent("Text", "Add text", textData = TextCodec.encode(frame), draw = ArrayLookKit.textDraw(frame))!!
        assertTrue("the fixture is a linked frame", ArraySources.isLinkedText(linked))
        assertSame(linked, c.activeLayer)
        assertFalse("a linked frame is not offered", ArrayBehind.available(c))
        refused(c, ArrayLabels.LINKED_REFUSAL) { ArrayBehind.fromLayerBelow(c, linked) }
        refused(c, ArrayLabels.LINKED_REFUSAL) { ArrayBehind.fromSelectionBelow(c) }
        // The same layer unlinked (its data replaced): offered again, the kept answer follows the data.
        val plain = BehindKit.textLayer(c)
        linked.textData = plain.textData
        c.selectLayer(linked)
        assertFalse(ArraySources.isLinkedText(linked))
        assertTrue("a plain text layer is offered", ArrayBehind.available(c))
        linked.textData = TextCodec.encode(frame)
        assertTrue(ArraySources.isLinkedText(linked))
        assertFalse(ArraySources.isLinkedText(plain))
        assertTrue(ArraySources.isLinkedText(linked))

        val adjustment = c.addAdjustmentLayer(AdjustmentEffects.defaultSpec(), null)!!
        c.selectLayer(adjustment)
        assertFalse("an adjustment layer is not offered", ArrayBehind.available(c))
        refused(c, ArrayOps.ADJUSTMENT_REFUSAL) { ArrayBehind.fromLayerBelow(c, adjustment) }
        refused(c, ArrayOps.ADJUSTMENT_REFUSAL) { ArrayBehind.fromSelectionBelow(c) }
    }
}
