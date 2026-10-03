package com.brushwork.paint.tools.text.frames

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.EditEvent
import com.brushwork.paint.EditListener
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextWrapReflow
import com.brushwork.paint.tools.text.WrapFixtures
import com.brushwork.paint.tools.text.frames.FrameFixtures.assertSnapshot
import com.brushwork.paint.tools.text.frames.FrameFixtures.assertWhole
import com.brushwork.paint.tools.text.frames.FrameFixtures.chainOf
import com.brushwork.paint.tools.text.frames.FrameFixtures.itemOf
import com.brushwork.paint.tools.text.frames.FrameFixtures.newFrame
import com.brushwork.paint.tools.text.frames.FrameFixtures.snapshot
import com.brushwork.paint.tools.text.frames.FrameFixtures.tap
import com.brushwork.paint.tools.text.frames.FrameFixtures.tapOutPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Text wrapped around a picture, linked frames and deletes together (v1.6, §3.6c/d
 * "ThreadReflowConvergenceTest"): a frame keeps wrapping around its picture; editing the picture
 * re-flows the WHOLE story once, inside the picture's step (TextWrapReflow hands the frame to
 * `TextThreads.reflowStory`), even when several frames wrap around it; the listeners settle (the
 * re-flow's own edits are ignored, a second re-flow finds nothing to do); deleting the picture
 * changes nothing, deleting a frame heals the story around the wrap.
 */
@RunWith(RobolectricTestRunner::class)
class ThreadReflowConvergenceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private class Rig(val s: FrameFixtures.Setup, val picture: Layer, val text: WrapFixtures.Setup)

    /** A 600 x 600 canvas: a disc on "Picture", the Text tool ready. */
    private fun rig(): Rig {
        val w = WrapFixtures.setup(context, 600, 600)
        WrapFixtures.disc(w.picture, 300f, 330f, 50f)
        w.c.selectTool(ToolId.TEXT_FRAMES)
        val tool = w.c.tools.getValue(ToolId.TEXT_FRAMES) as TextFrameTool
        tool.storyPreviewMs = 0L
        tool.dragPreviewMs = 0L
        return Rig(FrameFixtures.Setup(w.c, w.background, w.picture, tool), w.picture, w)
    }

    /** A plain text wrapped around the picture, centred at ([cx], [cy]) in a [width] px box. */
    private fun wrapped(r: Rig, text: String, cx: Float, cy: Float, width: Float): Layer {
        r.s.c.selectTool(ToolId.TEXT)
        val l = WrapFixtures.wrappedText(r.text, text = text, cx = cx, cy = cy, width = width)
        r.s.c.selectTool(ToolId.TEXT_FRAMES)
        return l
    }

    /** Joins text layer [target] after frame [from] with the tool's gestures (out-port, then a tap on the text). */
    private fun join(r: Rig, from: Layer, target: Layer) {
        tapOutPort(r.s, from)
        val item = WrapFixtures.itemOf(target)
        val b = com.brushwork.paint.tools.text.TextRenderer.prepare(item).docBounds(item)
        // A corner of the text's box that no frame covers.
        val frames = r.s.c.textThreads.allFrames().map { FrameGeometry.outerRect(it.item).apply { inset(-40f, -40f) } }
        val p = listOf(b.left + 4f to b.top + 4f, b.left + 4f to b.bottom - 4f, b.right - 4f to b.top + 4f, b.right - 4f to b.bottom - 4f)
            .first { (x, y) -> frames.none { it.contains(x, y) } }
        tap(r.s.c, p.first, p.second)
        assertTrue("${target.name} joined the story", itemOf(target).threaded)
    }

    private fun paintOnPicture(r: Rig) {
        r.s.c.editWholeLayer(r.picture, "Paint") { b -> Canvas(b).drawCircle(240f, 330f, 45f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2266CC.toInt() }) }
    }

    @Test
    fun aJoinedWrappedTextKeepsWrappingAroundItsPicture() {
        val r = rig()
        val f1 = newFrame(r.s, 20f, 20f, 220f, 140f)
        val w = wrapped(r, "Words that go around the round picture in the middle of the page.", 300f, 330f, 420f)
        val wrap = WrapFixtures.itemOf(w).wrap
        join(r, f1, w)
        val frame = itemOf(w)
        assertEquals("the frame wraps as the text did", wrap, frame.wrap)
        assertTrue(frame.wrapActive)
        assertEquals(listOf(f1, w), chainOf(r.s.c, f1))
        assertWhole(r.s.c, frame.thread.storyId)
        // No line of the frame crosses the disc (its ink keeps the gap around it).
        val ink = WrapFixtures.closestInk(w.bitmap, 300f, 330f)
        assertTrue("ink ${ink}px from the disc's centre", ink >= 50f)
    }

    @Test
    fun paintingThePictureReflowsTheWholeStoryOnceInsideThatStep() {
        val r = rig()
        val c = r.s.c
        val f1 = newFrame(r.s, 20f, 20f, 220f, 140f)
        val w1 = wrapped(r, "Upper words go around the picture on the left and on the right side.", 300f, 270f, 420f)
        join(r, f1, w1)
        val w2 = wrapped(r, "Lower words go around the picture too, under the upper ones.", 300f, 410f, 420f)
        join(r, w1, w2)
        val id = itemOf(f1).thread.storyId
        assertEquals(listOf(f1, w1, w2), chainOf(c, f1))
        assertWhole(c, id)

        // Count the events of one picture edit: the heal's own edits come back labelled and are ignored.
        var reflowEvents = 0
        val counter = object : EditListener {
            override fun onEdited(e: EditEvent) { if (e.label == TextWrapReflow.REFLOW_LABEL) reflowEvents++ }
        }
        c.addEditListener(counter)
        val before = snapshot(c)
        val heals = c.textThreads.healCount
        val steps = c.undoManager.undoCount
        val polys = itemOf(w1).wrap.polygons
        paintOnPicture(r)
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertEquals("Paint", c.undoManager.undoLabel)
        assertEquals("the story re-flowed once, though two frames wrap around the picture", heals + 1, c.textThreads.healCount)
        assertTrue("its writes were reported (${reflowEvents})", reflowEvents in 1..3)
        assertNotEquals("the frames have the picture's new outline", polys, itemOf(w1).wrap.polygons)
        assertEquals(itemOf(w1).wrap.polygons, itemOf(w2).wrap.polygons)
        assertEquals(c.textWrap.contours.polygons(r.picture, itemOf(w1).wrap.contour), itemOf(w1).wrap.polygons)
        assertWhole(c, id)
        // Settled: another re-flow finds nothing to do.
        assertFalse(c.textThreads.reflowStory(id))
        assertEquals(heals + 1, c.textThreads.healCount)
        c.removeEditListener(counter)
        // One undo restores the picture and every frame; redo brings them back without re-flowing.
        val after = snapshot(c)
        c.undo()
        assertSnapshot(c, before, "paint undone")
        c.redo()
        assertSnapshot(c, after, "paint redone")
        assertEquals(heals + 1, c.textThreads.healCount)
    }

    @Test
    fun deletingThePictureChangesNothingAndDeletingAFrameHealsAroundTheWrap() {
        val r = rig()
        val c = r.s.c
        val f1 = newFrame(r.s, 20f, 20f, 220f, 140f)
        val w = wrapped(r, "Words that go around the round picture in the middle of the page.", 300f, 330f, 420f)
        join(r, f1, w)
        val id = itemOf(f1).thread.storyId
        val data = listOf(f1.textData, w.textData)
        val heals = c.textThreads.healCount

        c.deleteLayer(r.picture)
        assertEquals("a deleted picture is no edit: the frames keep their outline", data, listOf(f1.textData, w.textData))
        assertEquals(heals, c.textThreads.healCount)
        c.undo()

        val steps = c.undoManager.undoCount
        c.deleteLayer(f1)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(listOf(w), chainOf(c, w))
        assertEquals("the wrapped frame now starts the story", 0, itemOf(w).thread.start)
        assertTrue(itemOf(w).wrapActive)
        assertWhole(c, id)
        assertFalse(c.textThreads.reflowStory(id))
    }
}
