package com.brushwork.paint.tools.text.frames

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.tools.text.TextBoxSpec
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.frames.FrameFixtures.STORY
import com.brushwork.paint.tools.text.frames.FrameFixtures.assertRendered
import com.brushwork.paint.tools.text.frames.FrameFixtures.assertWhole
import com.brushwork.paint.tools.text.frames.FrameFixtures.chainOf
import com.brushwork.paint.tools.text.frames.FrameFixtures.itemOf
import com.brushwork.paint.tools.text.frames.FrameFixtures.linkFrame
import com.brushwork.paint.tools.text.frames.FrameFixtures.newFrame
import com.brushwork.paint.tools.text.frames.FrameFixtures.pixels
import com.brushwork.paint.tools.text.frames.FrameFixtures.render
import com.brushwork.paint.tools.text.frames.FrameFixtures.setup
import com.brushwork.paint.tools.transform.ContentBounds
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.math.abs

/**
 * A frame's pixels are its slice of the chain (v1.6, §3.6c/d "ThreadFrameEqualsSliceTest", I1,
 * I9): every frame's layer equals a fresh rendering of the item it stores, alone; the frames show
 * consecutive slices of one story; the box a frame draws is where the tool says it is; and a saved
 * and reopened project shows the same pixels and data.
 */
@RunWith(RobolectricTestRunner::class)
class ThreadFrameEqualsSliceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun eachFrameDrawsExactlyItsSliceOfTheChain() {
        val s = setup(context)
        val f1 = newFrame(s, 20f, 20f, 180f, 120f)
        val f2 = linkFrame(s, f1, 220f, 20f, 380f, 140f)
        val f3 = linkFrame(s, f2, 20f, 160f, 380f, 290f)
        val frames = listOf(f1, f2, f3)
        assertEquals(frames, chainOf(s.c, f1))
        assertWhole(s.c, itemOf(f1).thread.storyId)
        // Consecutive slices of the story, in chain order, each drawn alone from its own item.
        val shown = frames.joinToString("") { itemOf(it).text }
        assertTrue("the frames show the story from its start", STORY.startsWith(shown))
        for (f in frames) {
            val item = itemOf(f)
            assertEquals(STORY.substring(item.thread.start, item.thread.end), item.text)
            assertEquals("the stored end is where the frame's layout ends", item.thread.end, TextRenderer.frameEnd(item))
            assertArrayEquals("${f.name}: drawn alone = its pixels", pixels(render(item, f.width, f.height)), pixels(f.bitmap))
        }
    }

    @Test
    fun theBoxAFrameDrawsIsWhereTheToolPutsIt() {
        val s = setup(context)
        val f = newFrame(s, 40f, 30f, 260f, 200f, text = "Boxed story")
        assertTrue(s.tool.openStoryEditor(f))
        // A filled, bordered, padded box (the story's look): the frame keeps its outer box.
        s.tool.story.updateBox { it.copy(fill = true, fillColor = 0xFF336699.toInt(), padding = 6f, borderWidth = 2f) }
        s.tool.story.confirmEditor()
        val item = itemOf(f)
        assertEquals(8f, item.spec.box.inset, 0f)
        val box = FrameGeometry.outerRect(item)
        val ink = ContentBounds.of(f.bitmap)!!
        assertTrue("drawn box $ink vs frame $box", abs(ink.left - box.left) <= 1f && abs(ink.right - box.right) <= 1f)
        assertTrue("drawn box $ink vs frame $box", abs(ink.top - box.top) <= 1f && abs(ink.bottom - box.bottom) <= 1f)
        assertRendered(f)
        assertWhole(s.c, item.thread.storyId)
    }

    @Test
    fun aReopenedProjectShowsTheSameFrames() = runBlocking<Unit> {
        File(context.filesDir, "projects").deleteRecursively()
        val repo = ProjectRepository(context)
        val s = setup(context)
        val f1 = newFrame(s, 20f, 20f, 180f, 120f)
        val f2 = linkFrame(s, f1, 220f, 20f, 380f, 140f)
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
        // The reopened frames are the same story: whole, each one its own rendering.
        assertEquals(listOf(l1, l2), c2.textThreads.framesOf(itemOf(l1).thread.storyId).map { it.layer })
        assertWhole(c2, itemOf(l1).thread.storyId)
        val back = TextCodec.decode(l2.textData)!!
        assertEquals(itemOf(f2), back)
        c2.dispose()
    }

    @Test
    fun aFrameLowerThanALineIsEmptyAndTheNextOneContinues() {
        val s = setup(context)
        val f1 = newFrame(s, 20f, 20f, 180f, 120f)
        val f2 = linkFrame(s, f1, 220f, 20f, 380f, 60f)
        // The second frame is made lower than one line: it takes nothing; a third one continues.
        assertTrue(s.tool.openStoryEditor(f1))
        s.tool.story.setSizePx(48f)
        s.tool.story.confirmEditor()
        assertEquals("", itemOf(f2).text)
        val f3 = linkFrame(s, f2, 20f, 160f, 380f, 290f)
        assertEquals(itemOf(f1).thread.end, itemOf(f3).thread.start)
        assertTrue(itemOf(f3).text.isNotEmpty())
        assertWhole(s.c, itemOf(f1).thread.storyId)
    }

    @Test
    fun aFrameKeepsWholePixelWidths() {
        // Dragged to a fractional size: the text area is whole pixels wide (as it is laid out).
        val s = setup(context)
        val f = newFrame(s, 20.3f, 20.6f, 180.9f, 120.2f, text = "Fractional")
        val item = itemOf(f)
        assertEquals(item.spec.box.width, kotlin.math.round(item.spec.box.width), 0f)
        assertEquals(TextBoxSpec().inset, item.spec.box.inset, 0f)
        assertRendered(f)
    }
}
