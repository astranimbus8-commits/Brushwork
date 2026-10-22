package com.brushwork.paint.v110.a3

import com.brushwork.paint.array.ArrayBehind
import com.brushwork.paint.array.ArrayLookKit
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.ui.common.ArrayLabels110
import com.brushwork.paint.v110.a3.BehindKit.oneStep
import com.brushwork.paint.v110.a3.BehindKit.redo
import com.brushwork.paint.v110.a3.BehindKit.undo
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.10 (R4, A3 step 2): "array behind" COPIES: the main layer's pixels and data stay
 * byte-identical (after the step, its undo and its redo), the selection stays on it, and an
 * alpha-locked source is allowed (nothing is cut). The new layer takes the source's opacity and
 * blend mode.
 */
@RunWith(RobolectricTestRunner::class)
class MainLayerUntouchedTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() = ArrayDraw.clearCaches()

    @Test
    fun aRasterSourceKeepsItsPixelsDataAndSelection() {
        val c = ArrayLookKit.controller(app)
        val src = BehindKit.selectedSquare(c)
        src.alphaLocked = true
        src.opacity = 0.5f
        src.blendMode = LayerBlendMode.MULTIPLY
        val pixels = ArrayLookKit.pixels(src.bitmap)
        val data = src.dataSnapshot()
        val sel = c.selection
        oneStep(c, ArrayLabels110.ARRAY_BEHIND_STEP) { assertTrue("alpha lock does not refuse a copy", ArrayBehind.fromSelectionBelow(c)) }
        val made = c.activeLayer
        assertTrue(ArrayBehind.isCopiesOnly(made))
        assertEquals(0.5f, made.opacity)
        assertEquals(LayerBlendMode.MULTIPLY, made.blendMode)
        for (step in listOf("made", "undone", "redone")) {
            when (step) {
                "undone" -> undo(c)
                "redone" -> redo(c)
            }
            assertArrayEquals("$step: the source pixels", pixels, ArrayLookKit.pixels(src.bitmap))
            assertEquals("$step: the source data", data, src.dataSnapshot())
        }
        assertSame("the selection stays on the main layer", sel, c.selection)
        assertTrue(src.alphaLocked)
    }

    @Test
    fun aVectorSourceIsNotReRendered() {
        val c = ArrayLookKit.controller(app)
        val v = BehindKit.twoBoxes(c)
        val content = v.vector
        val pixels = ArrayLookKit.pixels(v.bitmap)
        // A selection over the first box: the objects it touches go behind.
        c.setSelection(ArrayLookKit.rectSelection(c, 10f, 20f, 70f, 80f), recordUndo = false)
        val sel = c.selection
        oneStep(c, ArrayLabels110.ARRAY_BEHIND_STEP) { assertTrue(ArrayBehind.fromSelectionBelow(c)) }
        val made = c.activeLayer
        assertEquals(listOf(content!!.objects[0]), made.vector!!.objects)
        assertSame("the source content is the same object", content, v.vector)
        assertArrayEquals("the source cache", pixels, ArrayLookKit.pixels(v.bitmap))
        assertSame(sel, c.selection)
    }

    @Test
    fun aTextSourceKeepsItsDataAndPixels() {
        val c = ArrayLookKit.controller(app)
        val text = BehindKit.textLayer(c)
        val pixels = ArrayLookKit.pixels(text.bitmap)
        val data = text.dataSnapshot()
        oneStep(c, ArrayLabels110.ARRAY_BEHIND_STEP) { assertTrue(ArrayBehind.fromLayerBelow(c, text)) }
        assertEquals(data, text.dataSnapshot())
        assertArrayEquals(pixels, ArrayLookKit.pixels(text.bitmap))
        assertEquals("the copy holds the same text", data.text, c.activeLayer.textData)
    }
}
