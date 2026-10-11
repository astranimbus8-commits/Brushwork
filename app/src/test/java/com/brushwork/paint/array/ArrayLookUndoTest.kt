package com.brushwork.paint.array

import android.graphics.Bitmap
import android.graphics.Canvas
import com.brushwork.paint.EditorController
import com.brushwork.paint.array.ArrayLookKit.FADE_AND_TINT
import com.brushwork.paint.array.ArrayLookKit.GRAY
import com.brushwork.paint.array.ArrayLookKit.RED
import com.brushwork.paint.array.ArrayLookKit.RED_TO_BLUE
import com.brushwork.paint.array.ArrayLookKit.pixels
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.model.ArrayEasing
import com.brushwork.paint.model.ArrayLook
import com.brushwork.paint.model.ArrayTint
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.array.ArrayTool
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.common.ArrayLabels18
import com.brushwork.paint.ui.editor.HistoryLabels
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.8 (area B, item 2, design §3.3 a and c): every edit of the look is ONE step "Array look"
 * (`HistoryLabels.ARRAY_LOOK`), undone and redone as one; a slider drag previews and its release
 * records one step; a change of the placement stays "Edit array". The draft rule: above 64
 * copies a dragging preview shows opacity and size without the tint, which returns on release.
 */
@RunWith(RobolectricTestRunner::class)
class ArrayLookUndoTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() = ArrayDraw.clearCaches()

    private fun toolOn(c: EditorController, layer: Layer): ArrayTool {
        val t = c.tools[ToolId.ARRAY] as ArrayTool
        assertTrue("the raster array is the tool's target", t.target === layer)
        return t
    }

    private fun assertStep(c: EditorController, steps: Int, label: String) {
        assertEquals("$label: one step", steps + 1, c.undoManager.undoCount)
        assertEquals(label, c.undoManager.undoLabel)
    }

    @Test
    fun everyLookFieldIsOneStepArrayLook() {
        assertEquals(ArrayLabels18.HISTORY_LOOK, HistoryLabels.ARRAY_LOOK)
        val c = ArrayLookKit.controller(app)
        val layer = ArrayLookKit.pixelArray(c)
        val t = toolOn(c, layer)
        // Four copies: copy 2 sits midway (t = 1/2 Even, 1/4 Speeds up), so the Pace shows.
        t.commit(layer.array!!.spec.copy(count = 4))
        ArrayLookKit.settle(c)
        val base = layer.array!!.spec
        // Each field in turn, as the Look tab sets it (Tint colors with its strength: one step).
        val l1 = ArrayLook(opacityEnd = 0.3f)
        val l2 = l1.copy(opacityStart = 0.9f)
        val l3 = l2.copy(sizeEnd = 1.5f)
        val l4 = l3.copy(sizeStart = 0.8f)
        val l5 = l4.copy(turnEnd = 30f)
        val l6 = l5.copy(turnStart = -10f)
        val l7 = l6.copy(tint = RED_TO_BLUE, tintStrength = 1f)
        val l8 = l7.copy(tintStrength = 0.5f)
        val l9 = l8.copy(easing = ArrayEasing.EASE_IN)
        val looks = listOf(null, l1, l2, l3, l4, l5, l6, l7, l8, l9, null)
        val shown = ArrayList<IntArray>()
        shown += pixels(layer.bitmap)
        for (i in 1 until looks.size) {
            val steps = c.undoManager.undoCount
            t.commit(layer.array!!.spec.copy(look = looks[i]))
            ArrayLookKit.settle(c)
            assertStep(c, steps, ArrayLabels18.HISTORY_LOOK)
            assertEquals("look $i", looks[i], layer.array!!.spec.look)
            assertEquals("the placement kept", base, layer.array!!.spec.copy(look = null))
            shown += pixels(layer.bitmap)
            assertFalse("look $i changes the copies", shown[i].contentEquals(shown[i - 1]))
        }
        // Undo walks the looks back one step at a time, redo forward again.
        for (i in looks.size - 1 downTo 1) {
            assertEquals(ArrayLabels18.HISTORY_LOOK, c.undoManager.undoLabel)
            c.undo()
            ArrayLookKit.settle(c)
            assertEquals("undone to look ${i - 1}", looks[i - 1], layer.array!!.spec.look)
            assertArrayEquals("undone to look ${i - 1}", shown[i - 1], pixels(layer.bitmap))
        }
        for (i in 1 until looks.size) {
            c.redo()
            ArrayLookKit.settle(c)
            assertEquals("redone to look $i", looks[i], layer.array!!.spec.look)
            assertArrayEquals("redone to look $i", shown[i], pixels(layer.bitmap))
        }
    }

    @Test
    fun aDragRecordsOneStepAndThePlacementStaysEditArray() {
        val c = ArrayLookKit.controller(app)
        val layer = ArrayLookKit.pixelArray(c)
        val t = toolOn(c, layer)
        val spec = layer.array!!.spec
        // A slider dragged: previews only, then one step on release.
        var steps = c.undoManager.undoCount
        for (v in listOf(0.9f, 0.7f, 0.5f, 0.3f)) {
            t.preview(spec.copy(look = ArrayLook(opacityEnd = v)))
            assertEquals("a preview records nothing", steps, c.undoManager.undoCount)
        }
        assertNotNull(c.renderOverride)
        t.commitPreview()
        ArrayLookKit.settle(c)
        assertStep(c, steps, ArrayLabels18.HISTORY_LOOK)
        assertEquals(ArrayLook(opacityEnd = 0.3f), layer.array!!.spec.look)
        assertNull(c.renderOverride)
        // The same release twice records nothing more.
        t.commitPreview()
        ArrayLookKit.settle(c)
        assertEquals(steps + 1, c.undoManager.undoCount)
        c.undo()
        ArrayLookKit.settle(c)
        assertNull("one undo: the look before the drag", layer.array!!.spec.look)

        // The placement, alone or with the look: "Edit array".
        steps = c.undoManager.undoCount
        t.commit(spec.copy(count = 4))
        ArrayLookKit.settle(c)
        assertStep(c, steps, ArrayLabels.EDIT)
        steps = c.undoManager.undoCount
        t.commit(layer.array!!.spec.copy(count = 5, look = FADE_AND_TINT))
        ArrayLookKit.settle(c)
        assertStep(c, steps, ArrayLabels.EDIT)
        // "Reset look" and the public edit of the look alone: "Array look".
        steps = c.undoManager.undoCount
        t.commit(layer.array!!.spec.copy(look = null))
        ArrayLookKit.settle(c)
        assertStep(c, steps, ArrayLabels18.HISTORY_LOOK)
        steps = c.undoManager.undoCount
        assertTrue(ArrayOps.edit(c, layer, layer.array!!.spec.copy(look = FADE_AND_TINT)))
        ArrayLookKit.settle(c)
        assertStep(c, steps, ArrayLabels18.HISTORY_LOOK)
        // A look that changes nothing (Pace alone) is no look: no step.
        steps = c.undoManager.undoCount
        t.commit(layer.array!!.spec.copy(look = null))
        ArrayLookKit.settle(c)
        steps = c.undoManager.undoCount
        t.commit(layer.array!!.spec.copy(look = ArrayLook(easing = ArrayEasing.EASE_IN)))
        ArrayLookKit.settle(c)
        assertEquals(steps, c.undoManager.undoCount)
        assertNull(layer.array!!.spec.look)
    }

    @Test
    fun aboveSixtyFourCopiesADragShowsNoTintUntilRelease() {
        val c = ArrayLookKit.controller(app)
        val layer = ArrayLookKit.pixelArray(c)
        val t = toolOn(c, layer)
        val look = FADE_AND_TINT.copy(opacityStart = 0.5f)
        val screen = Bitmap.createBitmap(c.doc.width, c.doc.height, Bitmap.Config.ARGB_8888)
        // Copy 1 (Start: red, half opacity) at (60, 30)..(100, 70).
        fun copyOne(): Int {
            screen.eraseColor(0)
            t.drawOverlay(Canvas(screen), c.viewTransform)
            return screen.getPixel(72, 36)
        }
        val tinted = ArrayLookKit.withAlpha(ArrayTint.apply(GRAY, RED, 1f), 128)
        val plain = ArrayLookKit.withAlpha(GRAY, 128)

        // 64 copies: the preview shows the tint.
        val steps = c.undoManager.undoCount
        t.preview(layer.array!!.spec.copy(count = 64, look = look))
        assertFalse(t.previewIsDraft)
        ArrayLookKit.assertColor("64 copies: tinted while dragging", tinted, copyOne())
        // 65: a draft, opacity without the tint.
        t.preview(layer.array!!.spec.copy(count = 65, look = look))
        assertTrue(t.previewIsDraft)
        ArrayLookKit.assertColor("65 copies: the draft keeps the opacity, not the tint", plain, copyOne())
        // Released: the tint returns (the cache, one step).
        t.commitPreview()
        ArrayLookKit.settle(c)
        assertFalse(t.previewIsDraft)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(65, layer.array!!.spec.count)
        ArrayLookKit.assertColor("released: tinted in the cache", tinted, layer.bitmap.getPixel(72, 36))
        // A field (a discrete commit) is never a draft.
        t.commit(layer.array!!.spec.copy(look = look.copy(tintStrength = 0.5f)))
        assertFalse(t.previewIsDraft)
        screen.recycle()
    }
}
