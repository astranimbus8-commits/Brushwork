package com.brushwork.paint.tools.text.frames

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.tools.text.LetterScaleSpec
import com.brushwork.paint.tools.text.TextBoxSpec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.text.TextThreadSpec
import com.brushwork.paint.tools.text.WrapFixtures
import com.brushwork.paint.tools.text.frames.FrameFixtures.STORY
import com.brushwork.paint.tools.text.frames.FrameFixtures.assertSnapshot
import com.brushwork.paint.tools.text.frames.FrameFixtures.assertWhole
import com.brushwork.paint.tools.text.frames.FrameFixtures.chainOf
import com.brushwork.paint.tools.text.frames.FrameFixtures.itemOf
import com.brushwork.paint.tools.text.frames.FrameFixtures.linkFrame
import com.brushwork.paint.tools.text.frames.FrameFixtures.newFrame
import com.brushwork.paint.tools.text.frames.FrameFixtures.setup
import com.brushwork.paint.tools.text.frames.FrameFixtures.snapshot
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

/**
 * Per-frame wrap around a picture and letter scaling across a chain (v1.6, §3.6a "Limits"):
 * "Wrap around picture" makes ONE frame's lines go around a picture (one step, the chain
 * re-flows; a later edit of the picture re-flows the story); scaled letters run over the whole
 * story. The scaled-frame checks are invariants only (contiguous slices, I1, I9), so they hold
 * before and after area C's letter scaling lands.
 */
@RunWith(RobolectricTestRunner::class)
class FrameWrapAndScaleTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun aFrameWrapsAroundAPictureInOneStep() {
        val s = setup(context, 600, 400)
        WrapFixtures.disc(s.layer2, 340f, 120f, 45f)
        val f1 = newFrame(s, 20f, 20f, 180f, 220f)
        val f2 = linkFrame(s, f1, 230f, 20f, 450f, 220f)
        assertTrue("the picture can be chosen", s.layer2 in s.tool.wrapSources(f2))
        assertFalse(s.tool.wrapSources(f2).any { s.c.textThreads.isFrame(it) })
        val before = snapshot(s.c)
        val steps = s.c.undoManager.undoCount
        val end1 = itemOf(f1).thread.end
        assertTrue(s.tool.setFrameWrap(f2, s.layer2))
        assertEquals(steps + 1, s.c.undoManager.undoCount)
        assertEquals(TextFrameTool.WRAP_LABEL, s.c.undoManager.undoLabel)
        assertTrue(s.tool.wraps(f2))
        assertSame(s.layer2, s.tool.wrapSourceOf(f2))
        assertFalse("only that frame wraps", itemOf(f1).wrapActive)
        assertEquals("frame 1 is unchanged", end1, itemOf(f1).thread.end)
        assertEquals(0.3f * itemOf(f2).spec.sizePx, itemOf(f2).wrap.gapPx, 1e-3f)
        assertWhole(s.c, itemOf(f1).thread.storyId)
        assertTrue("its lines keep away from the disc", WrapFixtures.closestInk(f2.bitmap, 340f, 120f) >= 45f)

        // Painting on the picture re-flows the story inside the paint step.
        val heals = s.c.textThreads.healCount
        s.c.editWholeLayer(s.layer2, "Paint") { b -> Canvas(b).drawCircle(370f, 150f, 50f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2266CC.toInt() }) }
        assertEquals(heals + 1, s.c.textThreads.healCount)
        assertEquals("Paint", s.c.undoManager.undoLabel)
        assertWhole(s.c, itemOf(f1).thread.storyId)

        // Off again; and undo restores everything as it was.
        assertTrue(s.tool.setFrameWrap(f2, null))
        assertFalse(s.tool.wraps(f2))
        assertFalse("nothing to switch off", s.tool.setFrameWrap(f2, null))
        s.c.undo()
        s.c.undo()
        s.c.undo()
        assertSnapshot(s.c, before, "wrap undone")
    }

    @Test
    fun aLockedFrameIsNotWrapped() {
        val s = setup(context)
        WrapFixtures.disc(s.layer2, 300f, 120f, 45f)
        val f1 = newFrame(s, 20f, 20f, 180f, 220f)
        f1.locked = true
        val data = f1.textData
        assertFalse(s.tool.setFrameWrap(f1, s.layer2))
        assertSame(data, f1.textData)
        assertNotNull(s.c.message)
    }

    @Test
    fun scaledLettersRunOverTheWholeChain() {
        val s = setup(context)
        val f1 = newFrame(s, 20f, 20f, 180f, 120f)
        val f2 = linkFrame(s, f1, 220f, 20f, 380f, 120f)
        val f3 = linkFrame(s, f2, 20f, 170f, 380f, 280f)
        assertTrue(s.tool.openStoryEditor(f1))
        s.tool.story.updateSpec { it.copy(letterScale = LetterScaleSpec(smallestPercent = LetterScaleSpec.DEFAULT_ON_PERCENT)) }
        s.tool.story.confirmEditor()
        val id = itemOf(f1).thread.storyId
        for (f in listOf(f1, f2, f3)) assertTrue("${f.name} has the story's letter scaling", itemOf(f).spec.letterScale.isOn)
        assertEquals(listOf(f1, f2, f3), chainOf(s.c, f1))
        assertWhole(s.c, id)

        // An edit at the very end of the story: with scaled letters every letter's size depends on
        // the whole story, so every frame is drawn again (and stays its own rendering).
        val d1 = f1.textData
        assertTrue(s.tool.openStoryEditor(f3))
        s.tool.story.setText(s.tool.story.item!!.text + " The end.")
        s.tool.story.confirmEditor()
        assertNotEquals(d1, f1.textData)
        assertWhole(s.c, id)
    }

    @Test
    fun sameRenderingLooksAtTheWholeStoryOnlyForScaledLetters() {
        val spec = TextSpec(sizePx = 16f, box = TextBoxSpec(width = 200f, minHeight = 60f))
        fun frame(story: String, letters: Boolean) = TextItem(
            spec = if (letters) spec.copy(letterScale = LetterScaleSpec(60f)) else spec,
            cx = 100f, cy = 100f,
            thread = TextThreadSpec(storyId = 5, story = story, start = 0, end = 12),
        ).sanitized()
        val a = "Hello world.\nSecond paragraph."
        val b = "Hello world.\nAnother paragraph, longer."
        assertTrue("the paragraph after the slice doesn't change its lines", StoryWriter.sameRendering(frame(a, false), frame(b, false)))
        assertFalse("scaled letters depend on the whole story", StoryWriter.sameRendering(frame(a, true), frame(b, true)))
        assertTrue(StoryWriter.sameRendering(frame(a, true), frame(a, true)))
        assertFalse("same words, other story text in the same paragraph", StoryWriter.sameRendering(frame("Hello world and more", false), frame("Hello world or more", false)))
    }

    @Test
    fun theMeasureCacheFindsScaledTailsOnlyWithTheSameFactors() {
        val spec = TextSpec(sizePx = 16f, box = TextBoxSpec(width = 200f, minHeight = 60f))
        fun frame(story: String, start: Int, letters: Boolean) = TextItem(
            spec = if (letters) spec.copy(letterScale = LetterScaleSpec(60f)) else spec,
            thread = TextThreadSpec(storyId = 5, story = story, start = start, end = start),
        ).sanitized()
        val cache = StoryMeasureCache()
        cache.frameLayout(frame("One two three four", 4, false))
        assertNotNull("the same tail of another story", cache.get(frame("Six two three four", 4, false)))
        assertNotNull("the same tail at another start", cache.get(frame("Eleven two three four", 7, false)))
        cache.frameLayout(frame("One two three four", 4, true))
        // v1.6 integration (item 8): a scaled tail is found by its text too, but only when its
        // letters have the same factors (the same letter count before it and in its scope).
        assertNotNull("scaled: as many letters before the same tail", cache.get(frame("Six two three four", 4, true)))
        assertNull("scaled: more letters before the same tail are other factors", cache.get(frame("Seven two three four", 6, true)))
        assertNotNull(cache.get(frame("One two three four", 4, true)))
    }
}
