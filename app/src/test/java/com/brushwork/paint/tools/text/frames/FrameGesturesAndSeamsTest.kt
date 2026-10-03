package com.brushwork.paint.tools.text.frames

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.model.GridType
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.PlaceholderAmount
import com.brushwork.paint.tools.text.PlaceholderKind
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.text.WrapFixtures
import com.brushwork.paint.tools.text.frames.FrameFixtures.STORY
import com.brushwork.paint.tools.text.frames.FrameFixtures.assertWhole
import com.brushwork.paint.tools.text.frames.FrameFixtures.boxOf
import com.brushwork.paint.tools.text.frames.FrameFixtures.chainOf
import com.brushwork.paint.tools.text.frames.FrameFixtures.drag
import com.brushwork.paint.tools.text.frames.FrameFixtures.itemOf
import com.brushwork.paint.tools.text.frames.FrameFixtures.linkFrame
import com.brushwork.paint.tools.text.frames.FrameFixtures.newFrame
import com.brushwork.paint.tools.text.frames.FrameFixtures.setup
import com.brushwork.paint.tools.text.frames.FrameFixtures.tap
import com.brushwork.paint.tools.text.frames.FrameFixtures.tapOutPort
import com.brushwork.paint.ui.layers.LayerOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The Text frames tool as fingers use it, and its seams with the rest of the editor (v1.6, §3.6a;
 * review of area D):
 * - the Text tool tapping a frame, and the layer window's "Edit text", hand the frame to the Text
 *   frames tool (it is never edited as a plain text, which would lose the story edit on ✓);
 * - in link mode a drag anywhere draws the next frame, also when it starts over another frame or
 *   on an out-port; link mode ends when undo takes the loaded frame away;
 * - a resize handle dragged past the opposite edge holds the frame at its least size (with
 *   increments off the edge follows the finger exactly, I8);
 * - the middle of a small frame still moves it;
 * - a story editor opened and left untouched doesn't swallow an undo;
 * - "Fill the box" fills the whole chain, also after the text ("Add after") when the first frame
 *   is already full; with no room at all it says so once.
 */
@RunWith(RobolectricTestRunner::class)
class FrameGesturesAndSeamsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    // ------------------------------------------------------------------ the Text tool and the layer window

    @Test
    fun theTextToolTappingAFrameSwitchesToTextFramesWithTheFrameSelected() {
        val s = setup(context)
        val f = newFrame(s, 20f, 20f, 180f, 120f)
        s.c.selectTool(ToolId.TEXT)
        val text = s.c.tools.getValue(ToolId.TEXT) as TextTool
        val steps = s.c.undoManager.undoCount
        val data = f.textData
        // On the frame's first line.
        tap(s.c, 40f, 30f)
        idle()
        assertEquals("the frame went to the Text frames tool", ToolId.TEXT_FRAMES, s.c.activeToolId)
        assertSame(f, s.tool.selected)
        assertSame(f, s.c.activeLayer)
        assertFalse("the Text tool holds no pending text of the frame", text.hasPendingWork)
        assertFalse("a tap only selects", s.tool.story.isOpen)
        assertEquals("a tap records nothing", steps, s.c.undoManager.undoCount)
        assertSame(data, f.textData)
        assertNull("no preview left behind", s.c.renderOverride)
    }

    @Test
    fun editTextOnAFrameOpensItsStoryAndOkKeepsTheWholeEditedStory() {
        val s = setup(context)
        val f1 = newFrame(s, 20f, 20f, 180f, 120f)
        val f2 = linkFrame(s, f1, 220f, 20f, 380f, 280f)
        s.c.selectTool(ToolId.BRUSH)
        // The layer window's "Edit text": the Text tool's editLayer, then the frames seam.
        assertTrue(LayerOps.editText(s.c, f2))
        assertEquals(ToolId.TEXT_FRAMES, s.c.activeToolId)
        assertTrue("the story editor opened", s.tool.story.isOpen)
        assertFalse(s.tool.story.editingNew)
        assertSame(f2, s.tool.storyTarget)
        assertEquals("the editor shows the WHOLE story, not the frame's slice", STORY, s.tool.story.item!!.text)
        val text = s.c.tools.getValue(ToolId.TEXT) as TextTool
        assertFalse(text.hasPendingWork)
        val steps = s.c.undoManager.undoCount
        val edited = "A new first sentence. $STORY"
        s.tool.story.setText(edited)
        s.tool.story.confirmEditor()
        idle()
        assertEquals("one step", steps + 1, s.c.undoManager.undoCount)
        assertEquals(TextFrameTool.EDIT_LABEL, s.c.undoManager.undoLabel)
        assertEquals("the edit is kept in every frame", edited, itemOf(f1).thread.story)
        assertEquals(edited, itemOf(f2).thread.story)
        assertWhole(s.c, itemOf(f1).thread.storyId)

        // editLayer straight from the Text tool (its options strip's "Edit text"): the same.
        s.c.selectTool(ToolId.TEXT)
        assertTrue(text.editLayer(f1, openEditor = true))
        assertEquals(ToolId.TEXT_FRAMES, s.c.activeToolId)
        assertSame(f1, s.tool.storyTarget)
        assertFalse(text.hasPendingWork)
        s.tool.story.cancelEditor()
        assertEquals(steps + 1, s.c.undoManager.undoCount)
    }

    // ------------------------------------------------------------------ link mode

    @Test
    fun inLinkModeADragOverAnotherFrameDrawsTheNextFrame() {
        val s = setup(context)
        val f1 = newFrame(s, 20f, 20f, 180f, 120f)
        val other = newFrame(s, 220f, 150f, 380f, 280f, text = "Another story")
        val otherData = other.textData
        tapOutPort(s, f1)
        assertSame(f1, s.tool.linkFrom)
        val steps = s.c.undoManager.undoCount
        // The drag starts inside the other frame: it draws, it doesn't move that frame.
        drag(s.c, 240f, 170f, 380f, 290f)
        idle()
        assertEquals(steps + 1, s.c.undoManager.undoCount)
        assertEquals(TextFrameTool.LINK_LABEL, s.c.undoManager.undoLabel)
        assertSame("the other frame stayed as it was", otherData, other.textData)
        val chain = chainOf(s.c, f1)
        assertEquals(2, chain.size)
        assertEquals(240f, boxOf(chain[1]).left, 0.51f)
        assertEquals(170f, boxOf(chain[1]).top, 0.01f)
        assertNull("link mode ended", s.tool.linkFrom)
        assertWhole(s.c, itemOf(f1).thread.storyId)
    }

    @Test
    fun aFingerTravellingFromAnOutPortDrawsAFrame() {
        val s = setup(context)
        val f1 = newFrame(s, 20f, 20f, 180f, 120f)
        val port = FramePorts.outPort(s.c.viewTransform, boxOf(f1))
        // Not in link mode: a new frame of its own (the story editor opens for it).
        drag(s.c, port.x, port.y, 330f, 260f)
        assertTrue(s.tool.story.isOpen)
        assertTrue(s.tool.story.editingNew)
        s.tool.story.cancelEditor()
        assertNull(s.tool.linkFrom)
        // In link mode: the next frame of the story.
        tapOutPort(s, f1)
        assertSame(f1, s.tool.linkFrom)
        drag(s.c, port.x, port.y, 330f, 260f)
        idle()
        assertEquals(TextFrameTool.LINK_LABEL, s.c.undoManager.undoLabel)
        assertEquals(2, chainOf(s.c, f1).size)
    }

    @Test
    fun linkModeEndsWhenUndoTakesTheLoadedFrameAway() {
        val s = setup(context)
        val f1 = newFrame(s, 20f, 20f, 180f, 120f)
        val f2 = linkFrame(s, f1, 220f, 20f, 380f, 120f)
        tapOutPort(s, f2)
        assertSame(f2, s.tool.linkFrom)
        s.c.undo()
        assertEquals(-1, s.doc.indexOf(f2))
        assertNull("the loaded frame is gone: no link mode", s.tool.linkFrom)
        // A drag now draws a frame of its own (no silent failure).
        drag(s.c, 220f, 150f, 380f, 280f)
        assertTrue(s.tool.story.isOpen && s.tool.story.editingNew)
        s.tool.story.cancelEditor()
    }

    // ------------------------------------------------------------------ resizing and moving

    @Test
    fun aHandleDraggedPastTheOppositeEdgeHoldsTheLeastSize() {
        val s = setup(context)
        assertFalse(s.c.increments.enabled)
        val f = newFrame(s, 100f, 100f, 300f, 200f)
        val before = boxOf(f)
        // The right handle, dragged far to the left of the frame's left edge.
        drag(s.c, before.right, before.centerY(), 20f, before.centerY(), steps = 8)
        idle()
        val after = boxOf(f)
        assertEquals("the left edge stays", before.left, after.left, 0.51f)
        assertEquals("the frame is at its least width, not mirrored", before.left + TextFrameTool.MIN_FRAME_DP, after.right, 1.01f)
        assertEquals(before.top, after.top, 0.01f)
        assertEquals(before.bottom, after.bottom, 0.01f)
        assertWhole(s.c, itemOf(f).thread.storyId)
        // The bottom handle dragged above the top edge: the same.
        val b2 = boxOf(f)
        drag(s.c, b2.centerX(), b2.bottom, b2.centerX(), 10f, steps = 8)
        idle()
        val a2 = boxOf(f)
        assertEquals(b2.top, a2.top, 0.01f)
        assertEquals(b2.top + TextFrameTool.MIN_FRAME_DP, a2.bottom, 0.51f)
    }

    @Test
    fun theMiddleOfASmallFrameMovesIt() {
        val s = setup(context)
        val f = newFrame(s, 100f, 100f, 140f, 140f, text = "Hi")
        assertSame(f, s.tool.selected)
        val before = boxOf(f)
        // 20 px from the middle handles: inside a 40 px frame that is the frame, not a handle.
        drag(s.c, 120f, 120f, 160f, 170f)
        idle()
        assertEquals(TextFrameTool.MOVE_LABEL, s.c.undoManager.undoLabel)
        val after = boxOf(f)
        assertEquals(before.width(), after.width(), 0.01f)
        assertEquals(before.height(), after.height(), 0.01f)
        assertEquals(before.left + 40f, after.left, 0.01f)
        assertEquals(before.top + 50f, after.top, 0.01f)
        // Near a corner (inside), or just outside an edge, it is still a handle.
        drag(s.c, after.right - 3f, after.bottom - 3f, after.right + 37f, after.bottom + 27f)
        idle()
        assertEquals(TextFrameTool.RESIZE_LABEL, s.c.undoManager.undoLabel)
        assertEquals("the corner follows the finger", after.width() + 37f, boxOf(f).width(), 1.01f)
        val big = boxOf(f)
        drag(s.c, big.centerX(), big.top - 12f, big.centerX(), big.top - 32f)
        idle()
        assertEquals(TextFrameTool.RESIZE_LABEL, s.c.undoManager.undoLabel)
        assertEquals(big.top - 32f, boxOf(f).top, 0.51f)
    }

    @Test
    fun aMoveAndAResizeFollowTheGridBeforeTheIncrement() {
        val s = setup(context)
        val f = newFrame(s, 20f, 20f, 180f, 120f, text = "On the grid")
        s.c.updateGrid(s.c.grid.copy(enabled = true, snap = true, type = GridType.SQUARE, spacingPx = 50f))
        s.c.increments.update { it.copy(enabled = true, lengthPx = 10f) }
        // The top-left corner (20, 20) travels (37, 41) to (57, 61): the grid corner (50, 50)
        // wins over the 40 px steps (§3.4: guide, then grid, then increment).
        drag(s.c, 100f, 70f, 137f, 111f)
        idle()
        var b = boxOf(f)
        assertEquals(50f, b.left, 0.01f)
        assertEquals(50f, b.top, 0.01f)
        assertEquals(160f, b.width(), 0.51f)
        // The right edge dragged to x = 263: the grid line at 250 (steps from the left edge would give 260).
        drag(s.c, b.right, b.centerY(), 263f, b.centerY())
        idle()
        b = boxOf(f)
        assertEquals(250f, b.right, 0.51f)
        assertEquals(50f, b.left, 0.01f)
        assertWhole(s.c, itemOf(f).thread.storyId)
        // Grid snapping off again: the step applies.
        s.c.updateGrid(s.c.grid.copy(snap = false))
        drag(s.c, b.right, b.centerY(), 263f, b.centerY())
        idle()
        assertEquals(260f, boxOf(f).right, 0.51f)
    }

    // ------------------------------------------------------------------ the overlay

    /** True when the tool's overlay has a red ("+", overset) pixel within [r] px of ([x], [y]). */
    private fun redNear(s: FrameFixtures.Setup, x: Float, y: Float, r: Int = 8): Boolean {
        val bmp = Bitmap.createBitmap(s.doc.width, s.doc.height, Bitmap.Config.ARGB_8888)
        s.tool.drawOverlay(Canvas(bmp), s.c.viewTransform)
        for (py in (y.toInt() - r)..(y.toInt() + r)) for (px in (x.toInt() - r)..(x.toInt() + r)) {
            if (px !in 0 until bmp.width || py !in 0 until bmp.height) continue
            val c = bmp.getPixel(px, py)
            if (Color.alpha(c) > 200 && Color.red(c) > 180 && Color.green(c) < 100 && Color.blue(c) < 100) return true
        }
        return false
    }

    @Test
    fun anOverflowingFramesOutPortShowsARedPlusUntilTheStoryFits() {
        val s = setup(context)
        val f1 = newFrame(s, 20f, 20f, 180f, 120f, text = FrameFixtures.LOREM.take(300))
        assertTrue(itemOf(f1).thread.overset)
        val t = s.c.viewTransform
        val port1 = FramePorts.outPort(t, boxOf(f1))
        assertTrue("the red + on the out-port", redNear(s, port1.x, port1.y))
        val f2 = linkFrame(s, f1, 20f, 140f, 370f, 280f)
        assertFalse("the story fits the two frames", itemOf(f2).thread.overset)
        assertFalse("no red + once linked", redNear(s, port1.x, port1.y))
        val port2 = FramePorts.outPort(t, boxOf(f2))
        assertFalse("nor on the last frame, which holds the rest", redNear(s, port2.x, port2.y))
    }

    // ------------------------------------------------------------------ undo while the story editor is open

    @Test
    fun anUntouchedStoryEditorDoesNotSwallowUndo() {
        val s = setup(context)
        val f = newFrame(s, 20f, 20f, 180f, 120f)
        drag(s.c, 100f, 70f, 140f, 90f)
        idle()
        assertEquals(TextFrameTool.MOVE_LABEL, s.c.undoManager.undoLabel)
        val steps = s.c.undoManager.undoCount
        assertTrue(s.tool.openStoryEditor(f))
        assertTrue(s.tool.hasPendingWork)
        assertFalse("nothing changed yet", s.tool.hasUserChanges)
        s.c.undo()
        assertFalse("the editor closed", s.tool.story.isOpen)
        assertEquals("and the move was undone", steps - 1, s.c.undoManager.undoCount)
        assertEquals(20f, boxOf(f).left, 0.51f)

        // A changed story: undo takes back the change (the editor closes, history untouched).
        assertTrue(s.tool.openStoryEditor(f))
        s.tool.story.setText("Changed")
        assertTrue(s.tool.hasUserChanges)
        s.c.undo()
        assertFalse(s.tool.story.isOpen)
        assertEquals(steps - 1, s.c.undoManager.undoCount)
        assertEquals(STORY, itemOf(f).thread.story)

        // A new frame being typed is the user's work too.
        drag(s.c, 220f, 150f, 380f, 280f)
        assertTrue(s.tool.story.editingNew)
        assertTrue(s.tool.hasUserChanges)
        s.c.undo()
        assertFalse(s.tool.story.isOpen)
        assertEquals(steps - 1, s.c.undoManager.undoCount)
    }

    @Test
    fun editingTheStoryFromAWrappedFrameKeepsItsWrap() {
        val s = setup(context, 600, 400)
        WrapFixtures.disc(s.layer2, 340f, 120f, 45f)
        val f1 = newFrame(s, 20f, 20f, 180f, 220f)
        val f2 = linkFrame(s, f1, 230f, 20f, 450f, 220f)
        assertTrue(s.tool.setFrameWrap(f2, s.layer2))
        val wrap = itemOf(f2).wrap
        // Tapping the selected (wrapped) frame again opens its story.
        tap(s.c, 440f, 200f)
        assertTrue(s.tool.story.isOpen)
        assertSame(f2, s.tool.storyTarget)
        val steps = s.c.undoManager.undoCount
        s.tool.story.setText("New words up front. $STORY")
        s.tool.story.confirmEditor()
        idle()
        assertEquals(steps + 1, s.c.undoManager.undoCount)
        assertEquals(TextFrameTool.EDIT_LABEL, s.c.undoManager.undoLabel)
        assertTrue("the frame still wraps around the picture", s.tool.wraps(f2))
        assertEquals(wrap, itemOf(f2).wrap)
        assertTrue("its lines keep away from the disc", WrapFixtures.closestInk(f2.bitmap, 340f, 120f) >= 45f)
        assertFalse("the other frame doesn't wrap", itemOf(f1).wrapActive)
        assertWhole(s.c, itemOf(f1).thread.storyId)
    }

    // ------------------------------------------------------------------ "Fill the box" over a chain

    @Test
    fun fillTheBoxAfterTheTextFillsTheLaterFramesWhenTheFirstIsFull() {
        val s = setup(context)
        val words = "The quick brown fox jumps over the lazy dog. ".repeat(3).trim()
        val f1 = newFrame(s, 20f, 20f, 180f, 80f, text = words)
        assertTrue("the first frame is full", itemOf(f1).thread.overset)
        val f2 = linkFrame(s, f1, 20f, 100f, 380f, 290f)
        assertFalse("the second one has room", itemOf(f2).thread.overset)
        assertTrue(s.tool.openStoryEditor(f1))
        val host = s.tool.story
        host.placeholderKind = PlaceholderKind.LOREM
        host.placeholderAmount = PlaceholderAmount.FILL
        host.placeholderReplace = false
        val req = need(host.placeholderRequest())
        val edit = req.edit()
        assertNotNull("the stand-in request always has an answer", edit)
        assertTrue(host.applyPlaceholder(req.item, edit))
        val filled = host.item!!.text
        assertTrue("the text is kept in front", filled.startsWith(words))
        assertTrue("placeholder text was added", filled.length > words.length + 40)
        host.confirmEditor()
        idle()
        assertEquals(TextFrameTool.EDIT_LABEL, s.c.undoManager.undoLabel)
        assertFalse("the chain is filled exactly: nothing overflows", itemOf(f2).thread.overset)
        assertEquals(filled, itemOf(f2).thread.story)
        assertWhole(s.c, itemOf(f1).thread.storyId)
    }

    @Test
    fun fillTheBoxWithNoRoomLeftSaysSoAndKeepsTheStory() {
        val s = setup(context)
        val f = newFrame(s, 20f, 20f, 180f, 120f)
        assertTrue(itemOf(f).thread.overset)
        assertTrue(s.tool.openStoryEditor(f))
        val host = s.tool.story
        host.placeholderAmount = PlaceholderAmount.FILL
        host.placeholderReplace = false
        val req = need(host.placeholderRequest())
        // Handled (the toast says there is no room): not "the text changed meanwhile".
        assertTrue(host.applyPlaceholder(req.item, req.edit()))
        assertEquals(STORY, host.item!!.text)
        assertFalse("nothing to record", s.tool.hasUserChanges)
        // A story changed after the request: that one is refused.
        val stale = host.placeholderRequest()!!
        host.setText("Something else")
        assertFalse(host.applyPlaceholder(stale.item, stale.edit()))
        host.cancelEditor()
    }

    private fun <T> need(v: T?): T {
        assertNotNull(v)
        return v!!
    }
}
