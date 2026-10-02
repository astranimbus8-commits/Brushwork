package com.brushwork.paint.qa3

import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.text.WrapFixtures
import com.brushwork.paint.tools.text.WrapFixtures.disc
import com.brushwork.paint.tools.text.WrapFixtures.pixels
import com.brushwork.paint.tools.text.WrapFixtures.setup
import com.brushwork.paint.tools.text.WrapFixtures.wrappedText
import com.brushwork.paint.tools.transform.TransformTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Final QA (v1.5 §4.1, §7 checklist 3): undo and redo NEVER re-flow a wrapped text (the
 * listener's own counter stays put through every undo / redo of brush, Transform and delete
 * steps, and the text always equals its stored item's rendering); the undo of a deleted picture
 * brings back the layer the text follows; a locked text is left alone and catches up when it is
 * opened (one "Edit text" step on ✓); a hidden text follows its picture.
 */
@RunWith(RobolectricTestRunner::class)
class Qa3WrapUndoRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val context get() = RuntimeEnvironment.getApplication()

    private fun itemOf(layer: Layer): TextItem = TextCodec.decode(layer.textData)!!

    private fun assertRendered(text: Layer, c: EditorController) {
        val fresh = WrapFixtures.render(itemOf(text), c.doc.width, c.doc.height)
        assertArrayEquals("the text layer is its item's rendering (I1)", pixels(fresh), pixels(text.bitmap))
    }

    private fun stroke(c: EditorController, x0: Float, y0: Float, x1: Float, y1: Float) {
        c.pointerDown(ToolPoint(x0, y0))
        for (i in 1..20) c.pointerMove(ToolPoint(x0 + (x1 - x0) * i / 20f, y0 + (y1 - y0) * i / 20f))
        c.pointerUp(ToolPoint(x1, y1))
    }

    @Test
    fun undoAndRedoNeverReflow() {
        val s = setup(context, scope = scope)
        disc(s.picture, 110f, 150f, 35f)
        val text = wrappedText(s)
        val t0 = itemOf(text)
        val reflow = s.c.textWrap
        // 1. A brush stroke on the picture.
        s.c.selectLayer(s.picture)
        s.c.selectTool(ToolId.BRUSH)
        s.c.brush = s.c.brush.copy(size = 24f)
        val n0 = reflow.reflowCount
        stroke(s.c, 250f, 60f, 320f, 140f)
        assertEquals("the stroke re-flowed the text once", n0 + 1, reflow.reflowCount)
        val t1 = itemOf(text)
        assertNotEquals(t0, t1)
        // 2. Transform: move the picture.
        s.c.selectTool(ToolId.TRANSFORM)
        WrapFixtures.idle()
        val tt = s.c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        assertTrue(Smoke.pumpUntil(10_000) { tt.transformState != null })
        tt.moveBy(-30f, 20f)
        tt.commit()
        assertEquals(n0 + 2, reflow.reflowCount)
        val t2 = itemOf(text)
        assertNotEquals(t1, t2)
        // 3. Delete the picture: no re-flow, the outline stays.
        s.c.selectTool(ToolId.BRUSH)
        s.c.deleteLayer(s.picture)
        assertEquals("deleting is no edit of the picture", n0 + 2, reflow.reflowCount)
        assertEquals(t2, itemOf(text))

        // Undo everything, redo everything, twice: never a re-flow; the text follows its steps.
        val n = reflow.reflowCount
        repeat(2) {
            s.c.undo(); assertEquals(t2, itemOf(text)); assertRendered(text, s.c)
            s.c.undo(); assertEquals(t1, itemOf(text)); assertRendered(text, s.c)
            s.c.undo(); assertEquals(t0, itemOf(text)); assertRendered(text, s.c)
            s.c.redo(); assertEquals(t1, itemOf(text))
            s.c.redo(); assertEquals(t2, itemOf(text))
            s.c.redo(); assertEquals(t2, itemOf(text)); assertRendered(text, s.c)
        }
        assertEquals("undo / redo never re-flow", n, reflow.reflowCount)

        // Undo of the delete: the picture is back (its id) and the text follows it again.
        s.c.undo()
        val back = s.c.doc.layerById(t2.wrap.sourceLayerId)!!
        assertEquals("Picture", back.name)
        s.c.selectLayer(back)
        s.c.selectTool(ToolId.ERASER)
        s.c.eraser = s.c.eraser.copy(size = 60f)
        stroke(s.c, 60f, 120f, 160f, 200f)
        assertEquals("the restored picture re-flows the text", n + 1, reflow.reflowCount)
        assertNotEquals(t2, itemOf(text))
        assertRendered(text, s.c)
        s.c.dispose()
    }

    @Test
    fun aLockedTextCatchesUpWhenOpenedAndAHiddenOneFollows() {
        val s = setup(context, scope = scope)
        disc(s.picture, 110f, 150f, 35f)
        val text = wrappedText(s)
        val copy = s.c.duplicateLayer(text)!!
        val t0 = itemOf(text)
        s.c.toggleLock(text)
        s.c.toggleVisibility(copy)
        s.c.selectLayer(s.picture)
        s.c.selectTool(ToolId.BRUSH)
        s.c.brush = s.c.brush.copy(size = 24f)
        val steps = s.c.undoManager.undoCount
        stroke(s.c, 250f, 60f, 320f, 140f)
        assertEquals("one step", steps + 1, s.c.undoManager.undoCount)
        assertEquals("the locked text is left as it is", t0, itemOf(text))
        assertNotEquals("the hidden text follows its picture", t0, itemOf(copy))
        assertRendered(copy, s.c)
        val followed = itemOf(copy)
        // Unlocked and opened: it shows the picture as it is now; ✓ records it as one step.
        s.c.toggleLock(text)
        s.c.selectTool(ToolId.TEXT)
        val tool = s.c.tools.getValue(ToolId.TEXT) as TextTool
        assertTrue(tool.editLayer(text))
        assertTrue("the text needs to catch up", tool.hasUserChanges)
        assertEquals(followed.wrap.polygons, tool.item!!.wrap.polygons)
        val before = s.c.undoManager.undoCount
        tool.commit()
        assertEquals("✓ is one step", before + 1, s.c.undoManager.undoCount)
        assertEquals(followed.wrap.polygons, itemOf(text).wrap.polygons)
        assertRendered(text, s.c)
        s.c.undo()
        assertEquals(t0, itemOf(text))
        assertFalse(text.locked)
        s.c.dispose()
    }
}
