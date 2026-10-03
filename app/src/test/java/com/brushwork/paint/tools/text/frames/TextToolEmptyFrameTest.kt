package com.brushwork.paint.tools.text.frames

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.text.frames.FrameFixtures.boxOf
import com.brushwork.paint.tools.text.frames.FrameFixtures.itemOf
import com.brushwork.paint.tools.text.frames.FrameFixtures.linkFrame
import com.brushwork.paint.tools.text.frames.FrameFixtures.newFrame
import com.brushwork.paint.tools.text.frames.FrameFixtures.setup
import com.brushwork.paint.tools.text.frames.FrameFixtures.tap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The Text tool tapping a frame that shows no text (v1.6 integration, item 9): an empty frame
 * left by "Unlink here", or a frame past its story's end, is still a frame. A tap on it hands it
 * to the Text frames tool with the frame selected (the editLayer seam), as a tap on a frame with
 * text does; it never starts a new text on top of it.
 */
@RunWith(RobolectricTestRunner::class)
class TextToolEmptyFrameTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Taps the middle of [frame] with the Text tool and checks it went to the Text frames tool, selected, recording nothing. */
    private fun assertTapSelects(s: FrameFixtures.Setup, frame: Layer) {
        s.c.selectTool(ToolId.TEXT)
        val text = s.c.tools.getValue(ToolId.TEXT) as TextTool
        val steps = s.c.undoManager.undoCount
        val layers = s.doc.layers.size
        val data = frame.textData
        val box = boxOf(frame)
        tap(s.c, box.centerX(), box.centerY())
        idle()
        assertEquals("the frame went to the Text frames tool", ToolId.TEXT_FRAMES, s.c.activeToolId)
        assertSame(frame, s.tool.selected)
        assertSame(frame, s.c.activeLayer)
        assertFalse("no new text was started on the frame", text.hasPendingWork)
        assertFalse("a tap only selects", s.tool.story.isOpen)
        assertEquals("a tap records nothing", steps, s.c.undoManager.undoCount)
        assertEquals("no layer was added", layers, s.doc.layers.size)
        assertSame(data, frame.textData)
        assertNull("no preview left behind", s.c.renderOverride)
    }

    @Test
    fun anEmptyFrameLeftByUnlinkHereIsSelectedNotWrittenOver() {
        val s = setup(context)
        val f1 = newFrame(s, 20f, 20f, 180f, 120f)
        val f2 = linkFrame(s, f1, 220f, 20f, 380f, 120f)
        val f3 = linkFrame(s, f2, 20f, 170f, 380f, 280f)
        assertTrue(s.tool.unlinkAfter(f1))
        for (f in listOf(f2, f3)) {
            assertTrue("${f.name} is still a frame", s.c.textThreads.isFrame(f))
            assertEquals("${f.name} is empty", "", itemOf(f).text)
        }
        assertTapSelects(s, f2)
        assertTapSelects(s, f3)
        // A second tap on the selected frame (now in the Text frames tool) opens its story.
        val box = boxOf(f3)
        tap(s.c, box.centerX(), box.centerY())
        assertTrue(s.tool.story.isOpen)
        assertSame(f3, s.tool.storyTarget)
        s.tool.story.cancelEditor()
    }

    @Test
    fun aFramePastItsStorysEndIsSelected() {
        val s = setup(context)
        val f1 = newFrame(s, 20f, 20f, 180f, 120f, text = "Short")
        val f2 = linkFrame(s, f1, 220f, 20f, 380f, 280f)
        assertEquals("the story fits the first frame", "", itemOf(f2).text)
        assertTapSelects(s, f2)
    }

    @Test
    fun aHiddenOrLockedEmptyFrameIsNotTaken() {
        val s = setup(context)
        val f1 = newFrame(s, 20f, 20f, 180f, 120f, text = "Short")
        val f2 = linkFrame(s, f1, 220f, 20f, 380f, 280f)
        s.c.selectTool(ToolId.TEXT)
        val text = s.c.tools.getValue(ToolId.TEXT) as TextTool
        val box = boxOf(f2)
        f2.visible = false
        // Not a frame under the finger any more: the tap starts a new text, as on empty canvas.
        assertNull(text.textLayerAt(Vec2(box.centerX(), box.centerY())))
        f2.visible = true
        assertSame(f2, text.textLayerAt(Vec2(box.centerX(), box.centerY())))
        f2.locked = true
        assertNull(text.textLayerAt(Vec2(box.centerX(), box.centerY())))
    }
}
