package com.brushwork.paint.tools.text.frames

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextBoxSpec
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextPathSpec
import com.brushwork.paint.tools.text.TextPathType
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.text.frames.FrameFixtures.BLACK
import com.brushwork.paint.tools.text.frames.FrameFixtures.STORY
import com.brushwork.paint.tools.text.frames.FrameFixtures.Setup
import com.brushwork.paint.tools.text.frames.FrameFixtures.assertRendered
import com.brushwork.paint.tools.text.frames.FrameFixtures.assertSnapshot
import com.brushwork.paint.tools.text.frames.FrameFixtures.assertWhole
import com.brushwork.paint.tools.text.frames.FrameFixtures.chainOf
import com.brushwork.paint.tools.text.frames.FrameFixtures.drag
import com.brushwork.paint.tools.text.frames.FrameFixtures.itemOf
import com.brushwork.paint.tools.text.frames.FrameFixtures.linkFrame
import com.brushwork.paint.tools.text.frames.FrameFixtures.newFrame
import com.brushwork.paint.tools.text.frames.FrameFixtures.setup
import com.brushwork.paint.tools.text.frames.FrameFixtures.snapshot
import com.brushwork.paint.tools.text.frames.FrameFixtures.tap
import com.brushwork.paint.tools.text.frames.FrameFixtures.tapOutPort
import com.brushwork.paint.tools.transform.TransformTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Linked text frames and undo (v1.6, §3.6a/d "ThreadUndoTest", I1, I2, I9): create, story edit,
 * link (a new frame, a plain text box), unlink, move, resize, delete the first / middle / last
 * frame's layer, duplicate a frame, rasterize one (a pixel edit, the Transform tool), merge one
 * down (as the upper and as the lower layer). Each is ONE step, the story stays whole (or heals
 * inside that step), and one undo restores every layer's pixels and data; redo brings the result
 * back; neither re-flows.
 */
@RunWith(RobolectricTestRunner::class)
class ThreadUndoTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /**
     * Runs [block] as one user action: exactly one new step (named [label] when given); undo
     * restores the document as it was before, redo as it was after, and neither re-flows.
     */
    private fun oneStep(s: Setup, label: String?, block: () -> Unit) {
        val c = s.c
        val before = snapshot(c)
        val steps = c.undoManager.undoCount
        block()
        idle()
        assertEquals("$label: one user action, one step", steps + 1, c.undoManager.undoCount)
        if (label != null) assertEquals(label, c.undoManager.undoLabel)
        val after = snapshot(c)
        val heals = c.textThreads.healCount
        c.undo()
        assertSnapshot(c, before, "$label undone")
        c.redo()
        assertSnapshot(c, after, "$label redone")
        assertEquals("undo / redo never re-flow", heals, c.textThreads.healCount)
        assertNull("no preview is left installed", c.renderOverride)
    }

    /** Three linked frames holding [STORY] (it doesn't fit: the last one is overset). */
    private fun chain3(s: Setup): List<Layer> {
        val f1 = newFrame(s, 20f, 20f, 180f, 120f)
        val f2 = linkFrame(s, f1, 220f, 20f, 380f, 120f)
        val f3 = linkFrame(s, f2, 20f, 170f, 180f, 270f)
        val chain = chainOf(s.c, f1)
        assertEquals(listOf(f1, f2, f3), chain)
        assertWhole(s.c, storyOf(f1))
        assertTrue("the story doesn't fit the three frames", itemOf(f3).thread.overset)
        return chain
    }

    private fun storyOf(l: Layer): Long = itemOf(l).thread.storyId

    /** The whole story [layer]'s frames hold. */
    private fun textOf(s: Setup, layer: Layer): String = s.c.textThreads.story(storyOf(layer))!!.text

    // ------------------------------------------------------------------ create, edit, link

    @Test
    fun drawingAFrameAndConfirmingItsStoryIsOneStep() {
        val s = setup(context)
        lateinit var f: Layer
        oneStep(s, TextFrameTool.ADD_LABEL) { f = newFrame(s, 20f, 20f, 180f, 120f) }
        assertTrue(s.c.textThreads.isFrame(f))
        assertTrue("named after its first words", f.name.startsWith("Frame 1: Lorem"))
        assertSame("the new frame is selected", f, s.tool.selected)
        val item = itemOf(f)
        assertEquals(STORY, item.thread.story)
        assertTrue("a small frame overflows", item.thread.overset)
        assertTrue(TextThreadFlow.oversetCount(item) > 0)
        assertWhole(s.c, item.thread.storyId)
        // The frame is the box drawn.
        val box = FrameGeometry.outerRect(item)
        assertEquals(20f, box.left, 0.51f)
        assertEquals(20f, box.top, 0.01f)
        assertEquals(180f, box.right, 0.51f)
        assertEquals(120f, box.bottom, 0.01f)
    }

    @Test
    fun cancellingOrLeavingTheNewStoryEmptyCreatesNothing() {
        val s = setup(context)
        val layers = s.doc.layers.toList()
        val steps = s.c.undoManager.undoCount
        drag(s.c, 20f, 20f, 180f, 120f)
        assertTrue(s.tool.story.isOpen)
        s.tool.story.setText("Some words")
        assertNotNull("the new frame previews in the overlay", s.tool.story.item)
        s.tool.story.cancelEditor()
        assertFalse(s.tool.story.isOpen)
        drag(s.c, 20f, 20f, 180f, 120f)
        s.tool.story.confirmEditor()
        assertEquals(layers, s.doc.layers.toList())
        assertEquals(steps, s.c.undoManager.undoCount)
        assertNull(s.c.renderOverride)
    }

    @Test
    fun aTooSmallRectangleIsNoFrame() {
        val s = setup(context)
        drag(s.c, 20f, 20f, 40f, 300f)
        assertFalse(s.tool.story.isOpen)
        assertEquals(TextFrameTool.TOO_SMALL, s.c.message)
    }

    @Test
    fun editingTheStoryIsOneStepAndReflowsEveryFrame() {
        val s = setup(context)
        val (f1, f2, f3) = chain3(s)
        val revBefore = itemOf(f1).thread.rev
        oneStep(s, TextFrameTool.EDIT_LABEL) {
            assertTrue(s.tool.openStoryEditor(f2))
            s.tool.story.setText("A short story now.\n" + STORY.take(120))
            s.tool.story.confirmEditor()
        }
        assertWhole(s.c, storyOf(f1))
        assertTrue("every frame got the new revision", listOf(f1, f2, f3).all { itemOf(it).thread.rev == revBefore + 1 })
        assertFalse("it all fits now", itemOf(f3).thread.overset)
        assertTrue(itemOf(f1).text.startsWith("A short story now."))
    }

    @Test
    fun anUnchangedStoryEditRecordsNothing() {
        val s = setup(context)
        val f = newFrame(s, 20f, 20f, 180f, 120f)
        val steps = s.c.undoManager.undoCount
        val data = f.textData
        assertTrue(s.tool.openStoryEditor(f))
        s.tool.story.confirmEditor()
        assertEquals(steps, s.c.undoManager.undoCount)
        assertSame(data, f.textData)
    }

    @Test
    fun linkingANewFrameIsOneStepAndTheOverflowContinuesInIt() {
        val s = setup(context)
        val f1 = newFrame(s, 20f, 20f, 180f, 120f)
        val end1 = itemOf(f1).thread.end
        lateinit var f2: Layer
        oneStep(s, TextFrameTool.LINK_LABEL) { f2 = linkFrame(s, f1, 220f, 20f, 380f, 120f) }
        assertEquals(listOf(f1, f2), chainOf(s.c, f1))
        assertEquals("the new frame continues where the first one ends", end1, itemOf(f2).thread.start)
        assertFalse(itemOf(f1).thread.overset)
        assertEquals("the new layer sits right above the frame it continues", s.doc.indexOf(f1) + 1, s.doc.indexOf(f2))
        assertNull("link mode ends", s.tool.linkFrom)
        assertWhole(s.c, storyOf(f1))
    }

    @Test
    fun tappingTheOutPortTwiceOrEmptyCanvasCancelsLinkMode() {
        val s = setup(context)
        val f1 = newFrame(s, 20f, 20f, 180f, 120f)
        tapOutPort(s, f1)
        assertSame(f1, s.tool.linkFrom)
        tapOutPort(s, f1)
        assertNull(s.tool.linkFrom)
        tapOutPort(s, f1)
        tap(s.c, 300f, 250f)
        assertNull("a tap on empty canvas cancels", s.tool.linkFrom)
        assertEquals(1, s.c.textThreads.framesOf(storyOf(f1)).size)
    }

    @Test
    fun joiningAPlainTextBoxAppendsItsTextInTheStoryLook() {
        val s = setup(context)
        val f1 = newFrame(s, 20f, 20f, 180f, 120f, text = "First frame words.", size = 16f)
        val plain = plainText(s, "Joined words", 300f, 200f, width = 120f, size = 30f)
        val plainBox = TextRenderer.prepare(itemOf(plain)).block!!
        oneStep(s, TextFrameTool.LINK_LABEL) {
            tapOutPort(s, f1)
            tap(s.c, 300f, 200f)
        }
        val joined = itemOf(plain)
        assertTrue("the text box became the next frame", joined.threaded)
        assertEquals(listOf(f1, plain), chainOf(s.c, f1))
        assertEquals("its text follows the story after a paragraph break", "First frame words.\nJoined words", joined.thread.story)
        assertEquals("in the story's look", 16f, joined.spec.sizePx, 0f)
        assertEquals("keeping its box width", 120f, joined.spec.box.width, 0f)
        assertEquals("and its height", plainBox.contentHeight, joined.spec.box.minHeight, 0.01f)
        assertEquals(300f, joined.cx, 0f)
        assertEquals(200f, joined.cy, 0f)
        assertWhole(s.c, storyOf(f1))
    }

    @Test
    fun onlyHorizontalTextBoxesCanBeJoined() {
        val s = setup(context)
        val f1 = newFrame(s, 20f, 20f, 180f, 120f, text = "Words.")
        val onPath = plainText(s, "Around", 300f, 200f, width = 0f, path = TextPathSpec(type = TextPathType.CIRCLE))
        val vertical = plainText(s, "縦書き", 300f, 60f, width = 0f, vertical = true)
        val datas = listOf(onPath.textData, vertical.textData)
        val steps = s.c.undoManager.undoCount
        for (target in listOf(onPath, vertical)) {
            s.c.message = null
            assertFalse(s.tool.join(f1, target))
            assertEquals(TextFrameTool.HORIZONTAL_ONLY, s.c.message)
        }
        assertEquals(datas, listOf(onPath.textData, vertical.textData))
        assertEquals(steps, s.c.undoManager.undoCount)
        // Another frame of the same story is refused too.
        val f2 = linkFrame(s, f1, 220f, 20f, 380f, 120f)
        assertFalse(s.tool.join(f1, f2))
        assertEquals(TextFrameTool.SAME_STORY, s.c.message)
    }

    // ------------------------------------------------------------------ unlink

    @Test
    fun unlinkHereEmptiesTheFramesAfterAndMakesThemStandalone() {
        val s = setup(context)
        val (f1, f2, f3) = chain3(s)
        val story = textOf(s, f1)
        oneStep(s, TextFrameTool.UNLINK_LABEL) { assertTrue(s.tool.unlinkAfter(f1)) }
        assertEquals(listOf(f1), chainOf(s.c, f1))
        assertTrue("the rest of the story is overset in frame 1", itemOf(f1).thread.overset)
        assertEquals(story, textOf(s, f1))
        val ids = HashSet<Long>()
        for (f in listOf(f2, f3)) {
            val it = itemOf(f)
            assertTrue("still a frame, of a story of its own", it.threaded)
            assertEquals("", it.text)
            assertEquals("", it.thread.story)
            assertNotEquals(storyOf(f1), it.thread.storyId)
            assertTrue("each its own story", ids.add(it.thread.storyId))
            assertRendered(f)
        }
        assertWhole(s.c, storyOf(f1))
        // The last frame has nothing to unlink.
        assertFalse(s.tool.unlinkAfter(f1))
        assertEquals(TextFrameTool.NOTHING_TO_UNLINK, s.c.message)
    }

    // ------------------------------------------------------------------ move, resize

    @Test
    fun movingAFrameIsOneStepAndOnlyThatFrameChanges() {
        val s = setup(context)
        val (f1, f2, f3) = chain3(s)
        val d1 = f1.textData
        val d3 = f3.textData
        val before = itemOf(f2)
        oneStep(s, TextFrameTool.MOVE_LABEL) { drag(s.c, 300f, 70f, 315f, 80f) }
        val after = itemOf(f2)
        assertEquals(before.cx + 15f, after.cx, 0f)
        assertEquals(before.cy + 10f, after.cy, 0f)
        assertEquals("the same slice", before.thread, after.thread)
        assertSame("the other frames are untouched", d1, f1.textData)
        assertSame(d3, f3.textData)
        assertWhole(s.c, storyOf(f1))
    }

    @Test
    fun resizingAFrameIsOneStepAndTheChainReflows() {
        val s = setup(context)
        val (f1, _, f3) = chain3(s)
        s.tool.select(f3)
        val box = FrameFixtures.boxOf(f3)
        val end = itemOf(f3).thread.end
        oneStep(s, TextFrameTool.RESIZE_LABEL) { drag(s.c, box.right, box.bottom, box.right + 30f, box.bottom + 25f) }
        val now = FrameFixtures.boxOf(f3)
        assertEquals(box.left, now.left, 0.01f)
        assertEquals(box.top, now.top, 0.01f)
        assertEquals(box.right + 30f, now.right, 0.51f)
        assertEquals(box.bottom + 25f, now.bottom, 0.01f)
        assertTrue("a bigger last frame holds more", itemOf(f3).thread.end > end)
        assertWhole(s.c, storyOf(f1))
    }

    @Test
    fun aLockedFrameCanNotBeMovedOrItsStoryEdited() {
        val s = setup(context)
        val (f1, f2, _) = chain3(s)
        f2.locked = true
        val steps = s.c.undoManager.undoCount
        val data = f2.textData
        drag(s.c, 300f, 70f, 330f, 90f)
        assertSame(data, f2.textData)
        assertFalse(s.tool.openStoryEditor(f1))
        assertEquals("Unlock frame 2 to edit this story", s.c.message)
        assertEquals(steps, s.c.undoManager.undoCount)
        assertNull(s.c.renderOverride)
    }

    // ------------------------------------------------------------------ delete

    @Test
    fun deletingTheFirstFramesLayerHealsTheStoryInTheSameStep() {
        val s = setup(context)
        val (f1, f2, f3) = chain3(s)
        val story = textOf(s, f1)
        oneStep(s, "Delete layer") { s.c.deleteLayer(f1) }
        assertEquals(listOf(f2, f3), chainOf(s.c, f2))
        assertEquals("text is never lost", story, textOf(s, f2))
        assertEquals("the second frame now starts the story", 0, itemOf(f2).thread.start)
        assertEquals(0, itemOf(f2).thread.index)
        assertWhole(s.c, storyOf(f2))
    }

    @Test
    fun deletingTheMiddleFramesLayerHealsTheStory() {
        val s = setup(context)
        val (f1, f2, f3) = chain3(s)
        oneStep(s, "Delete layer") { s.c.deleteLayer(f2) }
        assertEquals(listOf(f1, f3), chainOf(s.c, f1))
        assertEquals(itemOf(f1).thread.end, itemOf(f3).thread.start)
        assertWhole(s.c, storyOf(f1))
    }

    @Test
    fun deletingTheLastFrameLeavesTheRestOverset() {
        val s = setup(context)
        val (f1, f2, f3) = chain3(s)
        val slice2 = itemOf(f2).text
        oneStep(s, "Delete layer") { s.tool.select(f3); s.tool.deleteFrame() }
        assertEquals(-1, s.doc.indexOf(f3))
        assertEquals(listOf(f1, f2), chainOf(s.c, f1))
        assertTrue(itemOf(f2).thread.overset)
        assertEquals("its lines stay as they were", slice2, itemOf(f2).text)
        assertWhole(s.c, storyOf(f1))
    }

    @Test
    fun aHealFlowsAroundALockedFrame() {
        val s = setup(context)
        val (f1, f2, f3) = chain3(s)
        f2.locked = true
        val slice2 = itemOf(f2).thread
        oneStep(s, "Delete layer") { s.c.deleteLayer(f1) }
        assertEquals("the locked frame keeps its slice", slice2, itemOf(f2).thread)
        assertEquals("the frame after it continues from its end", slice2.end, itemOf(f3).thread.start)
        assertRendered(f3)
    }

    // ------------------------------------------------------------------ duplicate, rasterize, merge

    @Test
    fun aDuplicatedFrameIsAnUnlinkedTextBox() {
        val s = setup(context)
        val (f1, f2, f3) = chain3(s)
        val datas = listOf(f1, f2, f3).map { it.textData }
        lateinit var copy: Layer
        oneStep(s, "Duplicate layer") { copy = s.c.duplicateLayer(f2)!! }
        val item = TextCodec.decode(copy.textData)!!
        assertFalse("the copy is not linked", item.threaded)
        assertEquals("it keeps the frame's slice", itemOf(f2).text, item.text)
        assertEquals("in a box of the frame's width", itemOf(f2).spec.box.width, item.spec.box.width, 0f)
        assertRendered(copy)
        assertEquals("the story's frames are untouched", datas, listOf(f1, f2, f3).map { it.textData })
        assertEquals(listOf(f1, f2, f3), chainOf(s.c, f1))
    }

    @Test
    fun paintingOnAFrameRasterizesItAndTheStoryHeals() {
        val s = setup(context)
        val (f1, f2, f3) = chain3(s)
        val story = textOf(s, f1)
        oneStep(s, "Scribble") {
            s.c.editWholeLayer(f2, "Scribble") { b -> Canvas(b).drawRect(230f, 30f, 260f, 60f, Paint().apply { color = BLACK }) }
        }
        assertNull("the painted frame is plain pixels now", f2.textData)
        assertEquals(listOf(f1, f3), chainOf(s.c, f1))
        assertEquals(story, textOf(s, f1))
        assertWhole(s.c, storyOf(f1))
    }

    @Test
    fun transformingAFrameRasterizesItAndTheStoryHealsInTheSameStep() {
        val s = setup(context)
        val (f1, f2, f3) = chain3(s)
        s.c.selectLayer(f2)
        s.c.selectTool(ToolId.TRANSFORM)
        idle()
        val tt = s.c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        oneStep(s, null) {
            tt.moveBy(12f, 0f)
            tt.commit()
        }
        assertNull(f2.textData)
        assertEquals(listOf(f1, f3), chainOf(s.c, f1))
        assertWhole(s.c, storyOf(f1))
    }

    @Test
    fun mergingAFrameDownHealsTheStory() {
        val s = setup(context)
        val (f1, f2, f3) = chain3(s)
        // Frame 1 sits right above "Layer 2" (a raster layer): merged into it.
        assertSame(s.layer2, s.doc.layers[s.doc.indexOf(f1) - 1])
        oneStep(s, "Merge down") { s.c.mergeDown(f1) }
        assertEquals(-1, s.doc.indexOf(f1))
        assertEquals(listOf(f2, f3), chainOf(s.c, f2))
        assertEquals(0, itemOf(f2).thread.start)
        assertWhole(s.c, storyOf(f2))
    }

    @Test
    fun mergingALayerIntoAFrameHealsTheStory() {
        val s = setup(context)
        val (f1, f2, f3) = chain3(s)
        s.c.selectLayer(f3)
        val above = s.c.addLayer("Above")!!
        assertSame(f3, s.doc.layers[s.doc.indexOf(above) - 1])
        oneStep(s, "Merge down") { s.c.mergeDown(above) }
        assertNull("the frame merged into is plain pixels", f3.textData)
        assertEquals(listOf(f1, f2), chainOf(s.c, f1))
        assertTrue(itemOf(f2).thread.overset)
        assertWhole(s.c, storyOf(f1))
    }

    @Test
    fun theLastFrameLeftHoldsTheWholeStory() {
        val s = setup(context)
        val (f1, f2, f3) = chain3(s)
        val story = textOf(s, f1)
        s.c.deleteLayer(f1)
        s.c.deleteLayer(f3)
        assertEquals(listOf(f2), chainOf(s.c, f2))
        assertEquals(story, textOf(s, f2))
        assertEquals(0, itemOf(f2).thread.start)
        assertTrue(itemOf(f2).thread.overset)
        assertWhole(s.c, storyOf(f2))
        // Undo twice: the three frames are back as they were.
        s.c.undo()
        s.c.undo()
        assertEquals(listOf(f1, f2, f3), chainOf(s.c, f1))
        assertWhole(s.c, storyOf(f1))
    }

    // ------------------------------------------------------------------ helpers

    /** Adds a plain (unthreaded) text layer: [text] in a box [width] px wide (0 = fits its text). */
    private fun plainText(
        s: Setup,
        text: String,
        cx: Float,
        cy: Float,
        width: Float = 140f,
        size: Float = 16f,
        path: TextPathSpec = TextPathSpec(),
        vertical: Boolean = false,
    ): Layer {
        val item = TextItem(text, TextSpec(sizePx = size, color = BLACK, vertical = vertical, box = TextBoxSpec(width = width)), cx, cy, path = path)
        val prep = TextRenderer.prepare(item)
        val layer = s.c.addLayerWithContent("Text", "Add text", textData = TextCodec.encode(item)) { cv -> TextRenderer.drawItem(cv, item, prep, null) }
        return requireNotNull(layer)
    }
}
