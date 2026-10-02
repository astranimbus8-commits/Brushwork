package com.brushwork.paint.tools.text

import android.graphics.Bitmap
import android.graphics.Canvas
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.6 foundation (§4.4, §4.9, I1 / I9): a frame of a linked story is laid out from the story at
 * its start, in its fixed box; it draws exactly its own slice, [TextRenderer.frameEnd] is where
 * the next frame starts, and a reloaded frame draws the same pixels. Unthreaded text keeps the
 * v1.5 path (Robolectric, real Skia).
 */
@RunWith(RobolectricTestRunner::class)
class ThreadedRenderTest {

    private val story = "Linked frames flow a story from one box into the next, just like InDesign. " +
        "When the first frame is full, the words that do not fit continue in the second frame.\n\n" +
        "A new paragraph starts here and keeps going until every frame is used or the story ends."

    private val spec = TextSpec(sizePx = 24f, box = TextBoxSpec(width = 260f, minHeight = 90f))

    /** The frames of [story] in boxes of [spec], flowed the way area D's TextThreadFlow does. */
    private fun flow(frames: Int): List<TextItem> {
        val out = ArrayList<TextItem>()
        var start = 0
        for (k in 0 until frames) {
            val probe = TextItem(spec = spec, cx = 200f, cy = 100f + 150f * k, thread = TextThreadSpec(storyId = 42, index = k, story = story, start = start, end = start))
            val end = TextRenderer.frameEnd(probe)
            val last = k == frames - 1
            out += probe.copy(thread = probe.thread.copy(end = end, overset = last && end < story.length)).sanitized()
            start = end
        }
        return out
    }

    private fun render(item: TextItem, w: Int = 400, h: Int = 600): IntArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        TextRenderer.drawItem(Canvas(bmp), item, TextRenderer.prepare(item), null)
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        return px
    }

    @Test
    fun framesTakeContiguousSlicesAndFrameEndAgrees() {
        val frames = flow(3)
        assertEquals(0, frames[0].thread.start)
        for (k in 1 until frames.size) assertEquals(frames[k - 1].thread.end, frames[k].thread.start)
        for (f in frames) {
            assertEquals("I9", story.substring(f.thread.start, f.thread.end), f.text)
            assertEquals("frameEnd of the stored frame is its end", f.thread.end, TextRenderer.frameEnd(f))
            assertTrue("each frame shows something", f.thread.end > f.thread.start)
        }
        val all = frames.joinToString("") { it.text }
        assertTrue("the frames cover a prefix of the story", story.startsWith(all))
        val layout = TextRenderer.frameLayout(frames[1])
        assertEquals(frames[1].thread.start, layout.start)
        assertEquals(frames[1].thread.end, layout.end)
        assertEquals(260f, layout.width, 0f)
        assertEquals(90f, layout.contentHeight, 0f)
        assertTrue(layout.height <= 90f)
    }

    @Test
    fun aFrameDrawsExactlyItsSlice() {
        for (f in flow(3)) {
            val prep = TextRenderer.prepare(f)
            val block = prep.block
            assertNotNull(block)
            val lines = block!!.wrapLines!!
            assertTrue(lines.isNotEmpty())
            // Every visible character of the slice is on exactly one line; no line reaches past it.
            val covered = BooleanArray(f.text.length)
            for (l in lines) {
                assertTrue(l.end <= f.text.length)
                for (i in l.start until l.end) {
                    assertTrue("character $i drawn twice", !covered[i])
                    covered[i] = true
                }
            }
            for (i in f.text.indices) if (!f.text[i].isWhitespace()) assertTrue("'${f.text[i]}' at $i of \"${f.text}\" not drawn", covered[i])
            // The box is the frame's fixed box.
            assertEquals(260f, block.contentWidth, 0f)
            assertEquals(90f, block.contentHeight, 0f)
            // Export sees the frame's own lines.
            val runs = TextExport.lines(f)!!
            assertEquals(lines.count { it.end > it.start }, runs.size)
        }
    }

    @Test
    fun aReloadedFrameDrawsTheSamePixels() {
        for (f in flow(2)) {
            val back = TextCodec.decode(TextCodec.encode(f))!!
            assertEquals(f, back)
            assertArrayEquals(render(f), render(back))
        }
    }

    @Test
    fun theLayoutCacheFollowsTheThread() {
        val f = flow(2)[1]
        val prep = TextRenderer.prepare(f)
        assertTrue(prep.matches(f))
        // A frame moved (no wrap): the same layout is reused.
        val moved = f.copy(cx = f.cx + 30f)
        assertSame(prep, TextRenderer.prepare(moved, prep))
        // Another story copy (a re-flow bumped rev): laid out again.
        val bumped = f.copy(thread = f.thread.copy(rev = f.thread.rev + 1))
        assertTrue(!prep.matches(bumped))
        // The same text unthreaded is the v1.5 StaticLayout path, never the frame's block.
        val plain = f.copy(thread = TextThreadSpec())
        val plainPrep = TextRenderer.prepare(plain, prep)
        assertTrue(plainPrep !== prep)
        assertNull("unthreaded text keeps the v1.5 layout", plainPrep.block!!.wrapLines)
        assertNotNull(plainPrep.block!!.staticLayout)
    }

    @Test
    fun aFrameWithNoRoomTakesNothing() {
        val tiny = TextItem(
            spec = spec.copy(box = TextBoxSpec(width = 260f, minHeight = 4f)),
            thread = TextThreadSpec(storyId = 9, story = story, start = 10, end = 10),
        ).sanitized()
        assertEquals(10, TextRenderer.frameEnd(tiny))
        assertTrue(TextRenderer.prepare(tiny).isEmpty)
    }
}
