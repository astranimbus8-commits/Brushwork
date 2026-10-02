package com.brushwork.paint.qa3

import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.WrapFixtures
import com.brushwork.paint.tools.text.WrapFixtures.pixels
import com.brushwork.paint.tools.text.WrapFixtures.setup
import com.brushwork.paint.tools.text.WrapFixtures.wrappedText
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.select.ObjectActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Final QA (v1.5 §4.1 × §4.9): text wrapped around a VECTOR layer follows every kind of vector
 * edit of it, each inside that edit's own undo step: a new brush stroke drawn in vector mode, the
 * Object bar's Delete, and the vector eraser; one undo restores picture and text together, and
 * the layer stays a vector layer.
 */
@RunWith(RobolectricTestRunner::class)
class Qa3WrapVectorPictureRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val context get() = RuntimeEnvironment.getApplication()

    private fun itemOf(layer: Layer): TextItem = TextCodec.decode(layer.textData)!!

    private fun assertRendered(text: Layer, c: EditorController) {
        val fresh = WrapFixtures.render(itemOf(text), c.doc.width, c.doc.height)
        assertArrayEquals("the text layer is its item's rendering (I1)", pixels(fresh), pixels(text.bitmap))
    }

    private fun settle(c: EditorController) {
        Smoke.pumpUntil(10_000) { c.settleVectorWork(); true }
        c.settleVectorWork()
        Smoke.pump(100)
    }

    @Test
    fun vectorEditsOfThePictureReflowInTheirOwnStep() {
        val s = setup(context, scope = scope)
        val vector = s.c.addVectorLayer()!!
        val square = VPath(
            0,
            subpaths = listOf(VSubpath(listOf(VAnchor(70f, 110f, sharp = true), VAnchor(140f, 110f, sharp = true), VAnchor(140f, 190f, sharp = true), VAnchor(70f, 190f, sharp = true)), closed = true)),
            polyline = true,
            fill = VPaint.Solid(WrapFixtures.BLACK),
        )
        s.c.vectors.addObjects(vector, listOf(square), "Add square")
        settle(s.c)
        s.c.selectTool(ToolId.TEXT)
        val text = wrappedText(s, source = vector)
        val t0 = itemOf(text)

        // 1. A brush stroke in vector mode on the picture's layer.
        s.c.selectLayer(vector)
        assertTrue(s.c.isVectorMode)
        s.c.selectTool(ToolId.BRUSH)
        s.c.brush = s.c.brush.copy(size = 20f)
        var steps = s.c.undoManager.undoCount
        s.c.pointerDown(ToolPoint(260f, 60f))
        for (i in 1..20) s.c.pointerMove(ToolPoint(260f + 3f * i, 60f + 5f * i))
        s.c.pointerUp(ToolPoint(320f, 160f))
        settle(s.c)
        assertEquals("one step", steps + 1, s.c.undoManager.undoCount)
        assertEquals("a vector object", 2, vector.vector!!.objects.size)
        val t1 = itemOf(text)
        assertNotEquals("the stroke re-flowed the text", t0, t1)
        assertRendered(text, s.c)

        // 2. The Object bar's Delete of the stroke.
        val strokeId = vector.vector!!.objects.last().id
        s.c.vectors.setSelection(vector, setOf(strokeId))
        steps = s.c.undoManager.undoCount
        assertTrue(ObjectActions.delete(s.c))
        settle(s.c)
        assertEquals("one step", steps + 1, s.c.undoManager.undoCount)
        assertEquals(1, vector.vector!!.objects.size)
        val t2 = itemOf(text)
        assertEquals("back around the square alone", t0.wrap.polygons, t2.wrap.polygons)
        assertRendered(text, s.c)

        // 3. The vector eraser (Object mode) on the square.
        s.c.selectTool(ToolId.ERASER)
        s.c.eraser = s.c.eraser.copy(size = 20f)
        steps = s.c.undoManager.undoCount
        s.c.pointerDown(ToolPoint(100f, 150f))
        for (i in 1..10) s.c.pointerMove(ToolPoint(100f + 2f * i, 150f))
        s.c.pointerUp(ToolPoint(120f, 150f))
        settle(s.c)
        assertEquals("one step", steps + 1, s.c.undoManager.undoCount)
        assertTrue("still a vector layer", vector.isVectorLayer)
        assertEquals("the square is gone", 0, vector.vector!!.objects.size)
        val t3 = itemOf(text)
        assertTrue("nothing to wrap around now", t3.wrap.polygons.isEmpty())
        assertRendered(text, s.c)

        // One undo each, picture and text together.
        s.c.undo(); assertEquals(t2, itemOf(text)); assertEquals(1, vector.vector!!.objects.size)
        s.c.undo(); assertEquals(t1, itemOf(text)); assertEquals(2, vector.vector!!.objects.size)
        s.c.undo(); assertEquals(t0, itemOf(text)); assertEquals(1, vector.vector!!.objects.size)
        assertRendered(text, s.c)
        s.c.dispose()
    }
}
