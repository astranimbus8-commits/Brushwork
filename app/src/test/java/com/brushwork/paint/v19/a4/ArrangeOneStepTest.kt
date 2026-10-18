package com.brushwork.paint.v19.a4

import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Layer
import com.brushwork.paint.v19.a4.A4Kit.Companion.assertClose
import com.brushwork.paint.vector.select.ObjectActions
import com.brushwork.paint.vector.select.ObjectEdits
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 (R19, A4): the object bar's "Bring forward" and "Send backward" (`ObjectActions.arrange`)
 * move each selected object past the next object that overlaps it, or one plain step when none
 * does (`ArrangeOrder.step`); "Already in front" / "Already at the back" only at the top or the
 * bottom. Each move is ONE step whose undo and redo restore data and pixels together.
 */
@RunWith(RobolectricTestRunner::class)
class ArrangeOneStepTest {
    private val kit = A4Kit()

    @After
    fun tearDown() = kit.close()

    /** Bottom first: a, b apart from a, c over a, d apart from all. */
    private fun setup(): Pair<EditorController, Layer> {
        val c = kit.controller()
        val layer = kit.vectorLayer(
            c,
            listOf(
                kit.box(10f, 10f, 50f, 50f),
                kit.box(130f, 10f, 170f, 50f, 0xFF20A040.toInt()),
                kit.box(30f, 30f, 70f, 70f, 0xFF2040E0.toInt()),
                kit.box(180f, 120f, 220f, 160f, 0xFFE0C020.toInt()),
            ),
            "Objects",
        )
        assertSame(layer, c.activeLayer)
        return c to layer
    }

    private fun order(layer: Layer): List<Long> = layer.vector!!.objects.map { it.id }

    /** Selects the object at [index] and runs [how] as one step, checking undo and redo. */
    private fun step(c: EditorController, layer: Layer, index: Int, how: ObjectEdits.Arrange): List<Long> {
        val before = layer.vector!!
        val pixels = kit.pixels(layer.bitmap)
        c.vectors.setSelection(layer, setOf(before.objects[index].id))
        val steps = c.undoManager.undoCount
        assertTrue(how.label, ObjectActions.arrange(c, how))
        kit.settle(c)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(how.label, c.undoManager.undoLabel)
        val after = layer.vector!!
        assertClose("${how.label}: pixels and data", kit.render(after), kit.pixels(layer.bitmap))
        assertEquals("the selection stays", setOf(before.objects[index].id), c.vectors.selectedIds)
        c.undo()
        kit.settle(c)
        assertSame(before, layer.vector)
        assertClose("${how.label} undone", pixels, kit.pixels(layer.bitmap))
        c.redo()
        kit.settle(c)
        assertSame(after, layer.vector)
        return order(layer)
    }

    @Test
    fun forwardPassesTheNextOverlappingObjectOrTakesOnePlainStep() {
        val (c, layer) = setup()
        val (a, b, cc, d) = order(layer)
        // a: b is apart, c overlaps it: right above c.
        assertEquals(listOf(b, cc, a, d), step(c, layer, 0, ObjectEdits.Arrange.FORWARD))
        c.undo()
        kit.settle(c)
        // b: nothing above overlaps it: one plain step (v1.8 said "Already in front").
        assertEquals(listOf(a, cc, b, d), step(c, layer, 1, ObjectEdits.Arrange.FORWARD))
    }

    @Test
    fun backwardMirrorsForward() {
        val (c, layer) = setup()
        val (a, b, cc, d) = order(layer)
        // c: b is apart, a overlaps it: right below a.
        assertEquals(listOf(cc, a, b, d), step(c, layer, 2, ObjectEdits.Arrange.BACKWARD))
        c.undo()
        kit.settle(c)
        // d: nothing below overlaps it: one plain step.
        assertEquals(listOf(a, b, d, cc), step(c, layer, 3, ObjectEdits.Arrange.BACKWARD))
    }

    @Test
    fun aPlainStepLeavesThePictureAsItWas() {
        val (c, layer) = setup()
        val picture = kit.picture(c)
        step(c, layer, 1, ObjectEdits.Arrange.FORWARD)
        assertClose("b passed c, which it does not meet", picture, kit.picture(c), tol = 0)
    }

    @Test
    fun onlyTheTopAndTheBottomSayAlready() {
        val (c, layer) = setup()
        val ids = order(layer)
        c.vectors.setSelection(layer, setOf(ids.last()))
        val steps = c.undoManager.undoCount
        assertFalse(ObjectActions.arrange(c, ObjectEdits.Arrange.FORWARD))
        assertEquals("Already in front", c.message)
        c.vectors.setSelection(layer, setOf(ids.first()))
        assertFalse(ObjectActions.arrange(c, ObjectEdits.Arrange.BACKWARD))
        assertEquals("Already at the back", c.message)
        assertEquals("no step", steps, c.undoManager.undoCount)
        assertEquals(ids, order(layer))
    }
}
