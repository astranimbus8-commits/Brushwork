package com.brushwork.paint.tools.text.frames

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Layer
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextThreadSpec
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.text.TextTransforms
import com.brushwork.paint.tools.text.frames.FrameFixtures.assertSnapshot
import com.brushwork.paint.tools.text.frames.FrameFixtures.assertWhole
import com.brushwork.paint.tools.text.frames.FrameFixtures.boxOf
import com.brushwork.paint.tools.text.frames.FrameFixtures.chainOf
import com.brushwork.paint.tools.text.frames.FrameFixtures.itemOf
import com.brushwork.paint.tools.text.frames.FrameFixtures.linkFrame
import com.brushwork.paint.tools.text.frames.FrameFixtures.newFrame
import com.brushwork.paint.tools.text.frames.FrameFixtures.pixels
import com.brushwork.paint.tools.text.frames.FrameFixtures.setup
import com.brushwork.paint.tools.text.frames.FrameFixtures.snapshot
import com.brushwork.paint.tools.transform.TransformTool
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * v1.7 (§3.11, area D follow-up): a frame of a linked story scaled by the Transform tool gives its
 * look to the whole story. Driven as the Transform tool commits a text layer:
 * [TextTransforms.mapped] on the layer's data, then `EditorController.updateTextLayer` with the
 * re-rendered item; `TextThreads` sees the edit and re-flows every frame in the scaled look
 * (each keeping its own box) inside the same "Transform" step. One undo restores every frame,
 * data and pixels; redo gives the same pixels; a move re-flows nothing; a saved and reopened
 * project keeps it.
 */
@RunWith(RobolectricTestRunner::class)
class TextFrameScaleRobolectricTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Two linked frames of the story at 16 px; the Brush tool is selected (the Transform commit is driven by hand). */
    private fun story(): Triple<FrameFixtures.Setup, Layer, Layer> {
        val s = setup(context, 512, 512)
        val f1 = newFrame(s, 16f, 16f, 196f, 136f)
        val f2 = linkFrame(s, f1, 216f, 16f, 316f, 116f)
        ShadowLooper.idleMainLooper()
        s.c.selectTool(ToolId.BRUSH)
        assertEquals(listOf(f1, f2), chainOf(s.c, f1))
        assertEquals(16f, itemOf(f1).spec.sizePx, 0f)
        assertEquals(16f, itemOf(f2).spec.sizePx, 0f)
        assertTrue("the story runs into frame 2", itemOf(f2).text.isNotEmpty())
        assertWhole(s.c, itemOf(f1).thread.storyId)
        return Triple(s, f1, f2)
    }

    /** What the Transform tool commits for a text layer mapped by [m]: the mapped data, re-rendered, one step. */
    private fun transform(c: EditorController, layer: Layer, m: FloatArray) {
        val mapped = TextTransforms.mapped(layer.textData!!, m) ?: throw AssertionError("the frame can't be mapped")
        val item = TextCodec.decode(mapped)!!
        assertTrue(c.updateTextLayer(layer, mapped, TransformTool.TRANSFORM_LABEL, null) { cv -> TextRenderer.drawItem(cv, item, TextRenderer.prepare(item), null) })
    }

    /** A scale by [k] about ([px], [py]), row-major. */
    private fun scale(k: Float, px: Float, py: Float) = floatArrayOf(k, 0f, px - k * px, 0f, k, py - k * py, 0f, 0f, 1f)

    private fun move(dx: Float, dy: Float) = floatArrayOf(1f, 0f, dx, 0f, 1f, dy, 0f, 0f, 1f)

    @Test
    fun aScaledFrameGivesItsLookToTheWholeStoryInOneStep() {
        val (s, f1, f2) = story()
        val c = s.c
        val id = itemOf(f1).thread.storyId
        val before = snapshot(c)
        val box1 = boxOf(f1)
        val box2 = boxOf(f2)
        val end1 = itemOf(f1).thread.end
        val len2 = itemOf(f2).text.length
        val rev = itemOf(f1).thread.rev
        val steps = c.undoManager.undoCount
        val heals = c.textThreads.healCount

        // Frame 2 scaled by 200 %: the whole story is 32 px, frame 1 in its own box holds less.
        transform(c, f2, scale(2f, 216f, 16f))
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertEquals(TransformTool.TRANSFORM_LABEL, c.undoManager.undoLabel)
        assertEquals("the story re-flowed inside the Transform step", heals + 1, c.textThreads.healCount)
        assertEquals(32f, itemOf(f1).spec.sizePx, 0f)
        assertEquals(32f, itemOf(f2).spec.sizePx, 0f)
        assertEquals("frame 1 keeps its box", box1, boxOf(f1))
        val big = boxOf(f2)
        assertEquals(2f * box2.width(), big.width(), 0.01f)
        assertEquals(2f * box2.height(), big.height(), 0.01f)
        assertEquals(box2.left, big.left, 0.01f)
        assertEquals(box2.top, big.top, 0.01f)
        assertTrue("frame 1 holds less at 32 px", itemOf(f1).thread.end < end1)
        assertEquals("frame 2 continues where frame 1 ends now", itemOf(f1).thread.end, itemOf(f2).thread.start)
        assertTrue("one newer copy of the story", itemOf(f1).thread.rev > rev)
        assertWhole(c, id)
        val after = snapshot(c)

        // One undo restores every frame bit for bit; redo gives the same pixels.
        c.undo()
        assertSnapshot(c, before, "undo of the 200 % scale")
        assertEquals(steps, c.undoManager.undoCount)
        c.redo()
        assertSnapshot(c, after, "redo of the 200 % scale")
        assertWhole(c, id)
        c.undo()
        assertSnapshot(c, before, "undo again")

        // Frame 1 scaled by 50 %: the whole story is 8 px, frame 2 in its own box holds more.
        transform(c, f1, scale(0.5f, 16f, 16f))
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertEquals(TransformTool.TRANSFORM_LABEL, c.undoManager.undoLabel)
        assertEquals(8f, itemOf(f1).spec.sizePx, 0f)
        assertEquals(8f, itemOf(f2).spec.sizePx, 0f)
        assertEquals("frame 2 keeps its box", box2, boxOf(f2))
        assertEquals(0.5f * box1.width(), boxOf(f1).width(), 0.01f)
        assertTrue("frame 2 holds more at 8 px", itemOf(f2).text.length > len2)
        assertWhole(c, id)
        c.undo()
        assertSnapshot(c, before, "undo of the 50 % scale")
        assertWhole(c, id)
    }

    @Test
    fun aMovedFrameKeepsTheLookAndReflowsNothing() {
        val (s, f1, f2) = story()
        val c = s.c
        val id = itemOf(f1).thread.storyId
        val before = snapshot(c)
        val data1 = f1.textData
        val px1 = pixels(f1.bitmap)
        val item2 = itemOf(f2)
        val steps = c.undoManager.undoCount
        val heals = c.textThreads.healCount

        transform(c, f2, move(30f, 40f))
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertEquals("nothing re-flowed", heals, c.textThreads.healCount)
        assertSame("frame 1 untouched", data1, f1.textData)
        assertArrayEquals(px1, pixels(f1.bitmap))
        val moved = itemOf(f2)
        assertEquals(item2.thread, moved.thread)
        assertEquals(item2.spec, moved.spec)
        assertEquals(item2.cx + 30f, moved.cx, 1e-3f)
        assertEquals(item2.cy + 40f, moved.cy, 1e-3f)
        assertWhole(c, id)
        c.undo()
        assertSnapshot(c, before, "undo of the move")
    }

    @Test
    fun aPlainTextScalesAloneAsBefore() {
        val (s, f1, _) = story()
        val c = s.c
        // A plain (unlinked) text layer: its scale changes nothing else and starts no re-flow.
        val frameData = f1.textData
        c.selectTool(ToolId.TEXT)
        val text = c.tools.getValue(ToolId.TEXT) as TextTool
        text.startTextAt(400f, 400f)
        text.setText("Plain")
        assertTrue(text.commitItem())
        c.selectTool(ToolId.BRUSH)
        val layer = c.doc.layers.first { it.textData != null && !c.textThreads.isFrame(it) }
        val heals = c.textThreads.healCount
        val size = itemOf(layer).spec.sizePx
        transform(c, layer, scale(2f, 400f, 400f))
        assertEquals(2f * size, itemOf(layer).spec.sizePx, 1e-3f)
        assertEquals("a plain text has no thread", TextThreadSpec(), itemOf(layer).thread)
        assertEquals(heals, c.textThreads.healCount)
        assertSame(frameData, f1.textData)
    }

    @Test
    fun aReopenedProjectKeepsTheScaledLook() = runBlocking<Unit> {
        File(context.filesDir, "projects").deleteRecursively()
        val repo = ProjectRepository(context)
        val (s, f1, f2) = story()
        transform(s.c, f2, scale(2f, 216f, 16f))
        val id = itemOf(f1).thread.storyId
        assertWhole(s.c, id)
        val data = listOf(f1, f2).map { it.textData }
        val px = listOf(f1, f2).map { pixels(it.bitmap) }
        repo.save(s.c.doc, null)
        val doc = repo.load(s.c.doc.id)
        val c2 = EditorController(context, doc, s.c.scope, AppSettings(context))
        val l1 = doc.layers.first { it.id == f1.id }
        val l2 = doc.layers.first { it.id == f2.id }
        assertEquals(data, listOf(l1, l2).map { it.textData })
        assertArrayEquals(px[0], pixels(l1.bitmap))
        assertArrayEquals(px[1], pixels(l2.bitmap))
        assertEquals(32f, itemOf(l1).spec.sizePx, 0f)
        assertEquals(32f, itemOf(l2).spec.sizePx, 0f)
        assertNotEquals("the boxes stay each frame's own", boxOf(l1).width(), boxOf(l2).width())
        assertEquals(listOf(l1, l2), chainOf(c2, l1))
        assertWhole(c2, id)
        assertFalse("nothing to heal on reopening", c2.textThreads.healCount > 0)
        c2.dispose()
    }
}
