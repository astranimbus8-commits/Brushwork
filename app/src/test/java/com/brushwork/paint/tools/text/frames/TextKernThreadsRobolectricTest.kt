package com.brushwork.paint.tools.text.frames

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.text.KerningEditor
import com.brushwork.paint.tools.text.TextKern
import com.brushwork.paint.tools.text.TextKerns
import com.brushwork.paint.tools.text.frames.FrameFixtures.assertWhole
import com.brushwork.paint.tools.text.frames.FrameFixtures.itemOf
import com.brushwork.paint.tools.text.frames.FrameFixtures.linkFrame
import com.brushwork.paint.tools.text.frames.FrameFixtures.newFrame
import com.brushwork.paint.tools.text.frames.FrameFixtures.pixels
import com.brushwork.paint.tools.text.frames.FrameFixtures.render
import com.brushwork.paint.tools.text.frames.FrameFixtures.setup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * v1.7 manual kerning across linked frames (item 17, area D accept test): a story's kerns index
 * the STORY and every frame stores them, so text flowing from one frame into the next never
 * moves a kern off its letters:
 * - a kern set in frame 2 is written to both frames in one step, and frame 2 draws it;
 * - typing in frame 1 (text flows across the frame boundary) keeps it between the same letters;
 * - deleting in frame 1 (text flows back) too, and undo restores it;
 * - a frame whose lines and kerns are unchanged keeps its pixels (data-only update), a frame
 *   whose kerns change is drawn again (I1 in every state);
 * - a duplicated frame keeps the kerns of its slice as a plain text.
 */
@RunWith(RobolectricTestRunner::class)
class TextKernThreadsRobolectricTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private class Chain(val s: FrameFixtures.Setup, val f1: Layer, val f2: Layer)

    private fun chain(story: String = FrameFixtures.STORY): Chain {
        val s = setup(context)
        val f1 = newFrame(s, 20f, 20f, 180f, 120f, story)
        val f2 = linkFrame(s, f1, 220f, 20f, 380f, 280f)
        idle()
        return Chain(s, f1, f2)
    }

    /** A gap a few letters into [layer]'s slice (from [from] on) between two Latin letters. */
    private fun letterGapIn(layer: Layer, from: Int = -1): Int {
        val th = itemOf(layer).thread
        for (g in maxOf(th.start + 4, from) until th.end - 1) {
            if (th.story[g].isLetter() && th.story[g + 1].isLetter() && th.story[g].code < 0x80 && th.story[g + 1].code < 0x80) return g
        }
        error("no letter gap in ${layer.name}")
    }

    /** Opens the story editor on [layer], applies [edit] to it, OK. */
    private fun editStory(ch: Chain, layer: Layer, edit: (KerningEditor) -> Unit) {
        assertTrue(ch.s.tool.openFrame(layer, openEditor = true))
        edit(ch.s.tool.story)
        ch.s.tool.story.confirmEditor()
        idle()
    }

    @Test
    fun aKernInFrameTwoStaysBetweenItsLettersWhileFrameOneIsEdited() {
        val ch = chain()
        val c = ch.s.c
        val story0 = itemOf(ch.f1).thread.story
        val g = letterGapIn(ch.f2)
        val pair = story0.substring(g, g + 2)
        val unkerned2 = pixels(ch.f2.bitmap)

        // Set: one step, both frames hold the story kern, frame 2 draws it.
        val steps = c.undoManager.undoCount
        editStory(ch, ch.f2) { it.setKerns(g..g, 400) }
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertEquals(TextFrameTool.EDIT_LABEL, c.undoManager.undoLabel)
        assertEquals(listOf(TextKern(g, 400)), itemOf(ch.f1).kerns)
        assertEquals(listOf(TextKern(g, 400)), itemOf(ch.f2).kerns)
        assertWhole(c, itemOf(ch.f1).thread.storyId)
        assertFalse("frame 2 draws the kern", unkerned2.contentEquals(pixels(ch.f2.bitmap)))

        // Typing in frame 1 pushes text into frame 2: the kern moves with its letters.
        val start2 = itemOf(ch.f2).thread.start
        val added = "Some new words typed at the very start push the story on. "
        editStory(ch, ch.f1) { it.setText(added + story0, added.length) }
        val story1 = itemOf(ch.f1).thread.story
        assertEquals(added + story0, story1)
        assertTrue("the text flowed across the frame boundary", itemOf(ch.f2).thread.start != start2)
        val g1 = itemOf(ch.f2).kerns.single().index
        assertEquals("the same letters around the kern", pair, story1.substring(g1, g1 + 2))
        assertEquals(g + added.length, g1)
        assertEquals("every frame holds the story kerns", itemOf(ch.f1).kerns, itemOf(ch.f2).kerns)
        assertWhole(c, itemOf(ch.f1).thread.storyId)

        // Deleting in frame 1 flows text back: still the same letters.
        val cut = story1.indexOf(' ', added.length + 3) + 1
        editStory(ch, ch.f1) { it.setText(story1.substring(0, 6) + story1.substring(cut), 6) }
        val story2 = itemOf(ch.f1).thread.story
        val g2 = itemOf(ch.f2).kerns.single().index
        assertEquals(pair, story2.substring(g2, g2 + 2))
        assertWhole(c, itemOf(ch.f1).thread.storyId)

        // Undo goes back through the states, the kern included.
        c.undo()
        idle()
        assertEquals(listOf(TextKern(g1, 400)), itemOf(ch.f2).kerns)
        c.undo()
        idle()
        assertEquals(listOf(TextKern(g, 400)), itemOf(ch.f2).kerns)
        c.undo()
        idle()
        assertTrue(itemOf(ch.f1).kerns.isEmpty() && itemOf(ch.f2).kerns.isEmpty())
        assertTrue("unkerned again", unkerned2.contentEquals(pixels(ch.f2.bitmap)))
    }

    @Test
    fun aFrameIsDrawnAgainOnlyWhenItsOwnKernsChange() {
        // Frame 1 ends inside paragraph 1; paragraph 2 lies in frame 2 only.
        val ch = chain(FrameFixtures.LOREM.take(200) + "\nAnother paragraph whose letters only frame two shows.")
        val a = itemOf(ch.f1)
        val story = a.thread.story
        val p2 = story.indexOf('\n') + 1
        assertTrue("paragraph 2 is in frame 2", p2 >= itemOf(ch.f2).thread.start)
        val g2 = letterGapIn(ch.f2, p2)
        val g1 = letterGapIn(ch.f1)
        // A kern of frame 2's own paragraph doesn't change frame 1's pixels, and does change frame 2's.
        val k2 = TextKerns.withValue(emptyList(), g2..g2, 300, story.length)
        assertTrue(StoryWriter.sameRendering(a, a.copy(kerns = k2)))
        assertFalse(StoryWriter.sameRendering(itemOf(ch.f2), itemOf(ch.f2).copy(kerns = k2)))
        val k1 = TextKerns.withValue(emptyList(), g1..g1, 300, story.length)
        assertFalse(StoryWriter.sameRendering(a, a.copy(kerns = k1)))
        // Writing frame 2's kern: frame 1 gets the kerns as data only (the same pixels).
        val px1 = pixels(ch.f1.bitmap)
        editStory(ch, ch.f2) { it.setKerns(g2..g2, 300) }
        assertTrue(px1.contentEquals(pixels(ch.f1.bitmap)))
        assertEquals(k2, itemOf(ch.f1).kerns)
        assertEquals(k2, itemOf(ch.f2).kerns)
        FrameFixtures.assertRendered(ch.f1)
        FrameFixtures.assertRendered(ch.f2)
    }

    @Test
    fun aDuplicatedFrameKeepsTheKernsOfItsSlice() {
        val ch = chain()
        val g2 = letterGapIn(ch.f2)
        val g1 = letterGapIn(ch.f1)
        editStory(ch, ch.f2) { it.setKerns(g2..g2, -150) }
        editStory(ch, ch.f1) { it.setKerns(g1..g1, 250) }
        val th2 = itemOf(ch.f2).thread
        val copy = ch.s.c.duplicateLayer(ch.f2)!!
        idle()
        val plain = itemOf(copy)
        assertFalse(plain.thread.isOn)
        assertEquals(th2.story.substring(th2.start, th2.end), plain.text)
        assertEquals("only its own slice's kern, re-indexed", listOf(TextKern(g2 - th2.start, -150)), plain.kerns)
        FrameFixtures.assertRendered(copy)
        val unkerned = render(plain.copy(kerns = emptyList()), copy.width, copy.height)
        assertNotEquals(pixels(unkerned).toList(), pixels(copy.bitmap).toList())
    }
}
