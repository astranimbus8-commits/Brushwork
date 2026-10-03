package com.brushwork.paint.tools.text.frames

import com.brushwork.paint.tools.text.TextBoxSpec
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.text.TextThreadSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [TextThreadFlow] (v1.6, §3.6c, §3.6d "ThreadFlowTest"): slices are contiguous from 0 and cover
 * the story or end overset; frames past the end are empty; a paragraph break and an emoji at a
 * frame boundary; chain order; pinned (locked) frames keep their slice; the measured-tails cache
 * gives exactly the uncached flow (Robolectric: real text measurement).
 */
@RunWith(RobolectricTestRunner::class)
class ThreadFlowTest {

    private val spec = TextSpec(sizePx = 20f)

    private fun frame(w: Float, h: Float, cx: Float = 200f, cy: Float = 100f) =
        FlowFrame(null, TextItem(spec = spec.copy(box = TextBoxSpec(width = w, minHeight = h)), cx = cx, cy = cy))

    private fun assertContiguous(story: String, items: List<TextItem>) {
        var start = 0
        for ((k, it) in items.withIndex()) {
            val th = it.thread
            assertEquals("frame $k starts where the one before ends", start, th.start)
            assertEquals("I9", story.substring(th.start, th.end), it.text)
            assertEquals("frame $k index", k, th.index)
            assertEquals("frame $k ends where its own layout says", th.end, TextRenderer.frameEnd(it))
            assertEquals("overset only on the last frame", k == items.lastIndex && th.end < story.length, th.overset)
            assertTrue(it.threaded)
            start = th.end
        }
    }

    @Test
    fun slicesAreContiguousAndCoverTheStoryOrEndOverset() {
        val story = FrameFixtures.STORY
        val small = TextThreadFlow.flow(story, spec, List(3) { frame(240f, 70f, cy = 60f + 90f * it) }, 7L, 3L)
        assertContiguous(story, small)
        assertTrue("three small frames can't hold the story", small.last().thread.overset)
        assertTrue(small.all { it.thread.end > it.thread.start })
        assertTrue(small.all { it.thread.storyId == 7L && it.thread.rev == 3L && it.thread.story == story })

        val big = TextThreadFlow.flow(story, spec, List(3) { frame(360f, 2000f) }, 7L, 0L)
        assertContiguous(story, big)
        assertEquals("the first frame holds it all", story.length, big[0].thread.end)
        assertFalse(big.last().thread.overset)
        assertEquals("frames past the end are empty", "", big[1].text)
        assertEquals(story.length, big[2].thread.start)
        assertEquals(story.length, big[2].thread.end)
        // Encoded and decoded, a frame is the same item.
        for (it in small + big) assertEquals(it, TextCodec.decode(TextCodec.encode(it)))
    }

    @Test
    fun anEmptyStoryAndAFrameWithNoRoom() {
        val empty = TextThreadFlow.flow("", spec, listOf(frame(200f, 100f), frame(200f, 100f)), 3L, 0L)
        assertContiguous("", empty)
        assertTrue(empty.all { it.text.isEmpty() && !it.thread.overset })
        // A frame lower than one line takes nothing; the next one continues from the same place.
        val story = "One two three four five six seven eight nine ten."
        val items = TextThreadFlow.flow(story, spec, listOf(frame(300f, 5f), frame(300f, 200f)), 3L, 0L)
        assertContiguous(story, items)
        assertEquals("", items[0].text)
        assertEquals(story, items[1].text)
    }

    @Test
    fun aParagraphBreakAtAFrameBoundary() {
        // Each frame holds exactly two one-word lines: the break after the second word ends it.
        val story = "Alpha\nBravo\nCharlie\nDelta\nEcho\nFoxtrot"
        val lineH = TextRenderer.frameLayout(TextItem(spec = spec.copy(box = TextBoxSpec(width = 300f, minHeight = 1000f)), thread = TextThreadSpec(storyId = 1, story = "X", end = 1))).let { it.height }
        val twoLines = lineH * 2.1f * spec.lineSpacing
        val items = TextThreadFlow.flow(story, spec, List(3) { frame(300f, twoLines) }, 5L, 0L)
        assertContiguous(story, items)
        assertEquals("Alpha\nBravo\n", items[0].text)
        assertEquals("Charlie\nDelta\n", items[1].text)
        assertEquals("Echo\nFoxtrot", items[2].text)
        assertFalse(items[2].thread.overset)
    }

    @Test
    fun anEmojiAtABoundaryIsNeverSplit() {
        // A run of emoji too wide for a narrow frame is broken between clusters, across frames.
        val story = "😀😁😂🤣😃😄😅😆😉😊😋😎😍😘🥰😗😙😚🙂🤗🤩🤔🤨😐😑😶🙄😏😣😥"
        val items = TextThreadFlow.flow(story, spec, List(5) { frame(70f, 26f) }, 9L, 0L)
        assertContiguous(story, items)
        for (it in items) {
            val th = it.thread
            if (th.start in 1 until story.length) assertFalse("frame starts inside a surrogate pair", Character.isLowSurrogate(story[th.start]))
            if (th.end in 1 until story.length) assertFalse("frame ends inside a surrogate pair", Character.isHighSurrogate(story[th.end - 1]))
        }
        assertTrue("the run spans several frames", items.count { it.text.isNotEmpty() } >= 2)
    }

    @Test
    fun chainOrderIsTheGivenOrderAndPinnedFramesKeepTheirSlice() {
        val story = FrameFixtures.STORY
        val frames = listOf(frame(200f, 60f, cy = 250f), frame(200f, 60f, cy = 50f), frame(200f, 60f, cy = 150f))
        val items = TextThreadFlow.flow(story, spec, frames, 4L, 0L)
        assertContiguous(story, items)
        assertEquals("the first frame of the chain is the first given, wherever it is", 250f, items[0].cy, 0f)
        // Frame 1 pinned with a slice of its own: frame 2 continues from its end.
        val pinned = items[1].copy(thread = items[1].thread.copy(start = 5, end = 40)).sanitized()
        val again = TextThreadFlow.flow(story, spec, listOf(FlowFrame(null, items[0].template()), FlowFrame(null, pinned, pinned = true), FlowFrame(null, items[2].template())), 4L, 1L)
        assertEquals(pinned, again[1])
        assertEquals(40, again[2].thread.start)
        assertEquals(story.substring(40, again[2].thread.end), again[2].text)
        assertEquals(2, again[2].thread.index)
    }

    @Test
    fun theMeasureCacheGivesExactlyTheUncachedFlow() {
        val story = FrameFixtures.STORY
        val frames = List(4) { frame(220f + 20f * it, 70f) }
        val cache = StoryMeasureCache()
        val plain = TextThreadFlow.flow(story, spec, frames, 2L, 0L)
        val cached1 = TextThreadFlow.flow(story, spec, frames, 2L, 0L, cache)
        val cached2 = TextThreadFlow.flow(story, spec, frames, 2L, 0L, cache)
        assertEquals(plain, cached1)
        assertEquals(plain, cached2)
        assertTrue("the second flow measured nothing again", cache.hits >= frames.size)
        // Drawing through the cache gives the renderer's own layout.
        for (it in plain) {
            val a = cache.prepare(it).block!!
            val b = TextRenderer.prepare(it).block!!
            assertEquals(b.width, a.width, 0f)
            assertEquals(b.height, a.height, 0f)
            assertArrayEqualsPixels(it)
        }
    }

    private fun assertArrayEqualsPixels(item: TextItem) {
        val cache = StoryMeasureCache()
        cache.frameLayout(item)
        val viaCache = android.graphics.Bitmap.createBitmap(400, 300, android.graphics.Bitmap.Config.ARGB_8888)
        TextRenderer.drawItem(android.graphics.Canvas(viaCache), item, cache.prepare(item), null)
        org.junit.Assert.assertArrayEquals(FrameFixtures.pixels(FrameFixtures.render(item, 400, 300)), FrameFixtures.pixels(viaCache))
    }

    @Test
    fun storiesAreCappedWithoutSplittingAPair() {
        assertEquals("abc", TextThreadFlow.cap("abc"))
        val huge = "a".repeat(TextThreadSpec.MAX_STORY - 1) + "😀tail"
        assertEquals(TextThreadSpec.MAX_STORY - 1, TextThreadFlow.cap(huge).length)
        val story = "Short story"
        val items = TextThreadFlow.flow(story, spec, listOf(frame(300f, 100f)), 1L, 0L)
        assertEquals(0, TextThreadFlow.oversetCount(items[0]))
        val tiny = TextThreadFlow.flow("😀😀 ab", spec, listOf(frame(300f, 5f)), 1L, 0L)
        assertEquals("code points beyond the frame", 5, TextThreadFlow.oversetCount(tiny[0]))
    }

    @Test
    fun threadedDataIsRecognizedCheaply() {
        val frame = TextThreadFlow.flow("Hello", spec, listOf(frame(300f, 100f)), 123L, 0L)[0]
        assertTrue(TextThreads.maybeThreaded(TextCodec.encode(frame)))
        assertFalse(TextThreads.maybeThreaded(TextCodec.encode(TextItem("plain \"storyId\":5 text"))))
        assertFalse("v1.5 data has no thread", TextThreads.maybeThreaded("""{"version":3,"item":{"text":"x"}}"""))
        assertTrue(TextThreads.maybeThreaded("""{"item":{"thread":{"storyId":10}}}"""))
        assertFalse(TextThreads.maybeThreaded("""{"item":{"thread":{"storyId":0}}}"""))
    }

    /** The place, size and wrap of this frame, as a flow template. */
    private fun TextItem.template(): TextItem = this
}
