package com.brushwork.paint.tools.text.frames

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.text.frames.FrameFixtures.assertRendered
import com.brushwork.paint.tools.text.frames.FrameFixtures.assertWhole
import com.brushwork.paint.tools.text.frames.FrameFixtures.itemOf
import com.brushwork.paint.tools.text.frames.FrameFixtures.linkFrame
import com.brushwork.paint.tools.text.frames.FrameFixtures.newFrame
import com.brushwork.paint.tools.text.frames.FrameFixtures.pixels
import com.brushwork.paint.tools.text.frames.FrameFixtures.setup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * v1.7 history taps over the Text frames tool (item 10, §3.10; area D): what a tap's first
 * finger changed in the open story editor (a slider, the Kerning field) or with the X / Y pill is
 * taken back by [com.brushwork.paint.EditorController.restoreUiMark], so the tap's one undo
 * undoes one document step instead of the whole story edit:
 * - the story goes back to what the editor showed when the finger landed (the editor stays open);
 * - an untouched editor is then let go by undo, which undoes exactly one step;
 * - a pill move begun after the mark is dropped, one begun before it goes back to where it was.
 */
@RunWith(RobolectricTestRunner::class)
class TextFrameHistoryTapRobolectricTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun aHistoryTapTakesBackWhatItsFirstFingerChangedInTheStoryEditor() {
        val s = setup(context)
        val c = s.c
        val f1 = newFrame(s, 20f, 20f, 180f, 120f)
        val f2 = linkFrame(s, f1, 220f, 20f, 380f, 280f)
        idle()
        val storyId = itemOf(f1).thread.storyId
        val data1 = f1.textData
        val data2 = f2.textData
        val steps = c.undoManager.undoCount
        assertNull("nothing pending: no mark", s.tool.historyMark())

        // The editor opened on frame 2, untouched: the same mark twice.
        assertTrue(s.tool.openFrame(f2, openEditor = true))
        val opened = s.tool.story.opened
        assertNotNull(opened)
        assertEquals(s.tool.historyMark(), s.tool.historyMark())

        // The first finger moved the size slider and the Kerning field before the second landed.
        val tap = c.uiMark()
        s.tool.story.setSizePx(24f)
        s.tool.story.setKerns(3..3, 200)
        idle()
        assertTrue(s.tool.hasUserChanges)
        assertTrue(c.restoreUiMark(tap))
        assertTrue("the editor stays open", s.tool.story.isOpen)
        assertEquals(opened, s.tool.story.item)
        assertFalse(s.tool.hasUserChanges)
        // Nothing changed since: nothing to take back.
        val again = c.uiMark()
        assertFalse(c.restoreUiMark(again))
        c.releaseUiMark(again)
        // Marks of another tool (or none) never touch the story.
        s.tool.story.setSizePx(28f)
        val sized = s.tool.story.item
        s.tool.rollbackHistory("not a mark")
        s.tool.rollbackHistory(null)
        assertEquals(sized, s.tool.story.item)
        s.tool.story.setSizePx(opened!!.spec.sizePx)
        assertEquals(opened, s.tool.story.item)

        // The tap's ONE undo: the untouched editor is let go and one document step is undone.
        c.undo()
        c.releaseUiMark(tap)
        idle()
        assertFalse(s.tool.story.isOpen)
        assertEquals("exactly one document step undone", steps - 1, c.undoManager.undoCount)
        assertEquals("the link step went", -1, c.doc.indexOf(f2))
        c.redo()
        idle()
        assertEquals(data1, f1.textData)
        assertEquals(data2, f2.textData)
        assertWhole(c, storyId)
    }

    @Test
    fun aTapAfterTypingTakesBackOnlyTheFingersChange() {
        val s = setup(context)
        val c = s.c
        val f1 = newFrame(s, 20f, 20f, 300f, 200f, text = "Short story")
        val steps = c.undoManager.undoCount
        assertTrue(s.tool.openFrame(f1, openEditor = true))
        s.tool.story.setText("Short story, typed on")
        val typed = s.tool.story.item

        val tap = c.uiMark()
        s.tool.story.setSizePx(30f)
        assertTrue(c.restoreUiMark(tap))
        assertEquals("the typing stays", typed, s.tool.story.item)
        // The tap's undo takes back the pending story edit (what v1.6's undo does), no step.
        c.undo()
        c.releaseUiMark(tap)
        assertFalse(s.tool.story.isOpen)
        assertEquals(steps, c.undoManager.undoCount)
        assertEquals("Short story", itemOf(f1).text)
        assertRendered(f1)
    }

    @Test
    fun aHistoryTapTakesBackAPillMove() {
        val s = setup(context)
        val c = s.c
        val f1 = newFrame(s, 20f, 20f, 200f, 160f)
        idle()
        val data = f1.textData
        val ink = pixels(f1.bitmap)
        val steps = c.undoManager.undoCount
        assertTrue(s.tool.openFrame(f1, openEditor = false))
        val pos = s.tool.objectPosition
        val at = pos.position!!

        // Nothing pending at the mark: a pill drag begun by the first finger is dropped.
        val tap = c.uiMark()
        pos.beginPositionEdit()
        pos.setPosition(at.x + 60f, null)
        idle()
        assertTrue(s.tool.hasPendingWork)
        assertTrue(c.restoreUiMark(tap))
        c.releaseUiMark(tap)
        assertFalse("the move is dropped", s.tool.hasPendingWork)
        assertEquals(at, pos.position)
        assertEquals(data, f1.textData)
        assertTrue("the frame's pixels as they were", ink.contentEquals(pixels(f1.bitmap)))
        assertEquals(steps, c.undoManager.undoCount)

        // A drag under way at the mark goes back to where it was, still pending; ✓ writes one step.
        pos.beginPositionEdit()
        pos.setPosition(at.x + 30f, null)
        val tap2 = c.uiMark()
        pos.setPosition(at.x + 90f, at.y + 10f)
        assertTrue(c.restoreUiMark(tap2))
        c.releaseUiMark(tap2)
        assertTrue(s.tool.hasPendingWork)
        assertEquals(Vec2(at.x + 30f, at.y), pos.position)
        pos.endPositionEdit()
        idle()
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertEquals(at.x + 30f, itemOf(f1).cx, 1e-3f)
        assertWhole(c, itemOf(f1).thread.storyId)
        c.undo()
        idle()
        assertEquals(data, f1.textData)
        assertTrue(ink.contentEquals(pixels(f1.bitmap)))
    }
}
