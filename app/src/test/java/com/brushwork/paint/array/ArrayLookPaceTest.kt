package com.brushwork.paint.array

import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.model.ArrayEasing
import com.brushwork.paint.model.ArrayLook
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.array.ArrayTool
import com.brushwork.paint.ui.common.ArrayLabels18
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.8 (area B review, design §3.3 a): the Look tab's "Pace" when the array has no look. A Pace
 * alone changes no copy, so it is no look (`ArrayLook.isIdentity` ignores the easing) and records
 * no step, yet the chip the user tapped stays lit and the next field that changes the look starts
 * from it; a look edited back to nothing keeps its Pace for the next edit; "Reset look" and
 * another layer bring the Pace back to Even. Driven through the tool's Look-tab entry points
 * ([ArrayTool.previewLook], [ArrayTool.commitLook], [ArrayTool.resetLook]), as the tab calls them.
 */
@RunWith(RobolectricTestRunner::class)
class ArrayLookPaceTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() = ArrayDraw.clearCaches()

    private fun toolOn(c: EditorController, layer: Layer): ArrayTool {
        val t = c.tools[ToolId.ARRAY] as ArrayTool
        if (c.activeToolId != ToolId.ARRAY) c.selectTool(ToolId.ARRAY)
        assertTrue("the array is the tool's target", t.target === layer)
        return t
    }

    @Test
    fun aPaceAloneIsKeptForTheNextEditOfTheLook() {
        val c = ArrayLookKit.controller(app)
        val layer = ArrayLookKit.pixelArray(c)
        val t = toolOn(c, layer)
        t.commit(layer.array!!.spec.copy(count = 4))
        ArrayLookKit.settle(c)
        assertNull(layer.array!!.spec.look)
        assertEquals(ArrayEasing.LINEAR, t.shownPace)

        // "Speeds up" with no look: no step, no look, the chip lit.
        var steps = c.undoManager.undoCount
        t.commitLook { it.copy(easing = ArrayEasing.EASE_IN) }
        ArrayLookKit.settle(c)
        assertEquals("a Pace alone records nothing", steps, c.undoManager.undoCount)
        assertNull("a Pace alone is no look", layer.array!!.spec.look)
        assertEquals("the chip stays lit", ArrayEasing.EASE_IN, t.shownPace)
        assertEquals(ArrayLook.IDENTITY.copy(easing = ArrayEasing.EASE_IN), t.lookBase())

        // End opacity dragged and released: ONE step "Array look", at the Pace chosen first.
        for (v in listOf(0.8f, 0.6f, 0.4f)) t.previewLook { it.copy(opacityEnd = v) }
        assertEquals("a preview records nothing", steps, c.undoManager.undoCount)
        t.commitPreview()
        ArrayLookKit.settle(c)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(ArrayLabels18.HISTORY_LOOK, c.undoManager.undoLabel)
        assertEquals(ArrayLook(opacityEnd = 0.4f, easing = ArrayEasing.EASE_IN), layer.array!!.spec.look)
        val withPace = ArrayLookKit.pixels(layer.bitmap)

        // "Slows down" on a look: one step, the look's own Pace.
        steps = c.undoManager.undoCount
        t.commitLook { it.copy(easing = ArrayEasing.EASE_OUT) }
        ArrayLookKit.settle(c)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(ArrayEasing.EASE_OUT, layer.array!!.spec.look!!.easing)
        assertEquals(ArrayEasing.EASE_OUT, t.shownPace)

        // End opacity back to 100 %: no look any more (one step), the Pace kept for the next edit.
        steps = c.undoManager.undoCount
        t.previewLook { it.copy(opacityEnd = 1f) }
        t.commitPreview()
        ArrayLookKit.settle(c)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertNull("edited back to nothing: no look", layer.array!!.spec.look)
        assertEquals("the Pace is kept", ArrayEasing.EASE_OUT, t.shownPace)
        t.commitLook { it.copy(sizeEnd = 1.5f) }
        ArrayLookKit.settle(c)
        assertEquals(ArrayLook(sizeEnd = 1.5f, easing = ArrayEasing.EASE_OUT), layer.array!!.spec.look)

        // Undo walks back one step at a time; redo comes forward again.
        c.undo()
        ArrayLookKit.settle(c)
        assertNull(layer.array!!.spec.look)
        c.undo()
        ArrayLookKit.settle(c)
        assertEquals(ArrayLook(opacityEnd = 0.4f, easing = ArrayEasing.EASE_OUT), layer.array!!.spec.look)
        c.undo()
        ArrayLookKit.settle(c)
        assertEquals(ArrayLook(opacityEnd = 0.4f, easing = ArrayEasing.EASE_IN), layer.array!!.spec.look)
        assertTrue("undone to the same pixels", withPace.contentEquals(ArrayLookKit.pixels(layer.bitmap)))
        c.redo()
        ArrayLookKit.settle(c)
        assertEquals(ArrayEasing.EASE_OUT, layer.array!!.spec.look!!.easing)

        // "Reset look": no look (one step) and the Pace back to Even.
        steps = c.undoManager.undoCount
        t.resetLook()
        ArrayLookKit.settle(c)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(ArrayLabels18.HISTORY_LOOK, c.undoManager.undoLabel)
        assertNull(layer.array!!.spec.look)
        assertEquals(ArrayEasing.LINEAR, t.plainPace)
        // With no look, the reset only brings the Pace back: no step.
        t.commitLook { it.copy(easing = ArrayEasing.EASE_IN_OUT) }
        assertEquals(ArrayEasing.EASE_IN_OUT, t.shownPace)
        steps = c.undoManager.undoCount
        t.resetLook()
        ArrayLookKit.settle(c)
        assertEquals(steps, c.undoManager.undoCount)
        assertEquals(ArrayEasing.LINEAR, t.shownPace)

        // Another layer: back to Even.
        t.commitLook { it.copy(easing = ArrayEasing.EASE_IN) }
        assertEquals(ArrayEasing.EASE_IN, t.plainPace)
        c.selectLayer(c.doc.layers[0])
        c.selectLayer(layer)
        assertEquals("another layer: Even again", ArrayEasing.LINEAR, t.plainPace)
    }
}
