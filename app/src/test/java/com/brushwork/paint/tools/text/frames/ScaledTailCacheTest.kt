package com.brushwork.paint.tools.text.frames

import android.content.Context
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.tools.text.LetterScaleScope
import com.brushwork.paint.tools.text.LetterScaleSpec
import com.brushwork.paint.tools.text.TextBoxSpec
import com.brushwork.paint.tools.text.TextFrameLayout
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.text.TextThreadSpec
import com.brushwork.paint.tools.text.frames.FrameFixtures.assertWhole
import com.brushwork.paint.tools.text.frames.FrameFixtures.chainOf
import com.brushwork.paint.tools.text.frames.FrameFixtures.itemOf
import com.brushwork.paint.tools.text.frames.FrameFixtures.linkFrame
import com.brushwork.paint.tools.text.frames.FrameFixtures.newFrame
import com.brushwork.paint.tools.text.frames.FrameFixtures.pixels
import com.brushwork.paint.tools.text.frames.FrameFixtures.render
import com.brushwork.paint.tools.text.frames.FrameFixtures.setup
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Scaled letters across linked frames (v1.6 integration, item 8): a frame's tail of the story is
 * measured with each letter's factor of the story's ramp, which depends on the letters BEFORE the
 * tail (and, for "Whole text", on every letter of the story). The measured tails are reused —
 * by [StoryMeasureCache] and by [TextRenderer.frameLayout] — only when the tail has the same text
 * AND the same factors, so a frame laid out through a cache always equals a fresh layout (I1, I7),
 * also after an edit that keeps the tail's text but changes the letter count before it.
 */
@RunWith(RobolectricTestRunner::class)
class ScaledTailCacheTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val tail = "two three four five six seven eight nine ten eleven twelve"

    private fun spec(scope: LetterScaleScope = LetterScaleScope.WHOLE_TEXT) =
        TextSpec(sizePx = 18f, box = TextBoxSpec(width = 150f, minHeight = 300f), letterScale = LetterScaleSpec(smallestPercent = 40f, scope = scope))

    /** A frame of [story] from [start] (its end not known yet: a probe, as the flow lays one out). */
    private fun probe(story: String, start: Int, spec: TextSpec) =
        TextItem(spec = spec, cx = 110f, cy = 170f, thread = TextThreadSpec(storyId = 9, story = story, start = start, end = start))

    /** [probe] placed: its slice ends where its fresh layout says (I9). */
    private fun placed(p: TextItem): TextItem {
        val end = TextRenderer.frameEnd(p)
        return p.copy(text = p.thread.story.substring(p.thread.start, end), thread = p.thread.copy(end = end))
    }

    private fun assertSameLayout(what: String, fresh: TextFrameLayout, got: TextFrameLayout) {
        assertEquals("$what: lines", fresh.lines, got.lines)
        assertEquals("$what: end", fresh.end, got.end)
        assertEquals("$what: height", fresh.height, got.height, 0f)
        assertEquals("$what: measured text", fresh.measured.text, got.measured.text)
        assertArrayEquals("$what: measured advances", fresh.measured.prefix, got.measured.prefix, 0.0)
    }

    /** Frame [item] drawn through [cache] equals a fresh rendering of it (I1). */
    private fun assertDrawnAsFresh(what: String, cache: StoryMeasureCache, item: TextItem) {
        val viaCache = BitmapUtils.createLayerBitmap(220, 340).also { TextRenderer.drawItem(Canvas(it), item, cache.prepare(item), null) }
        assertArrayEquals("$what: pixels through the cache equal a fresh rendering", pixels(render(item, 220, 340)), pixels(viaCache))
    }

    @Test
    fun theSameTailAfterMoreLettersIsMeasuredAgain() {
        val sa = "One $tail"
        val sb = "One more opening words $tail"
        val a = probe(sa, sa.length - tail.length, spec())
        val b = probe(sb, sb.length - tail.length, spec())
        val freshA = TextRenderer.frameLayout(a)
        val freshB = TextRenderer.frameLayout(b)
        assertEquals(tail, freshA.measured.text)
        assertEquals(tail, freshB.measured.text)
        // The case is discriminating: the same characters measure differently after more letters.
        assertFalse("other factors, other advances", freshA.measured.prefix.contentEquals(freshB.measured.prefix))
        assertNotEquals("other factors, other lines", freshA.lines, freshB.lines)

        // The renderer doesn't take A's measurement for B...
        val handed = TextRenderer.frameLayout(b, freshA.measured)
        assertNotSame("the stale measurement is not used", freshA.measured, handed.measured)
        assertSameLayout("handed A's tail", freshB, handed)
        assertFalse(TextRenderer.canReuseMeasured(b, freshA.measured))
        assertTrue(TextRenderer.canReuseMeasured(b, freshB.measured))

        // ... and neither does the cache.
        val cache = StoryMeasureCache()
        cache.frameLayout(a)
        val misses = cache.misses
        assertNull("other letters before the tail: not found", cache.get(b))
        assertEquals(misses + 1, cache.misses)
        assertSameLayout("through the cache", freshB, cache.frameLayout(b))
        assertDrawnAsFresh("B", cache, placed(b))
    }

    @Test
    fun theSameTailWithTheSameLettersBeforeItIsFoundAgain() {
        val sa = "One $tail"
        val cache = StoryMeasureCache()
        val a = probe(sa, sa.length - tail.length, spec())
        val measuredA = cache.frameLayout(a).measured
        // A letter replaced by another, and a space added: the same letter count before the tail
        // and in the story, so the same factors (whitespace uses up no step).
        for (sb in listOf("Two $tail", "One  $tail", "\tOne $tail")) {
            val b = probe(sb, sb.length - tail.length, spec())
            val hits = cache.hits
            assertSame("$sb: the tail measured for A is found", measuredA, cache.get(b))
            assertEquals(hits + 1, cache.hits)
            val viaCache = cache.frameLayout(b)
            assertSame("$sb: and laid out from", measuredA, viaCache.measured)
            assertSameLayout(sb, TextRenderer.frameLayout(b), viaCache)
            assertDrawnAsFresh(sb, cache, placed(b))
        }
    }

    @Test
    fun eachParagraphFindsALaterParagraphsTailAfterAnEditBeforeIt() {
        val p2 = "Alpha $tail"
        val sa = "First paragraph.\n$p2"
        val sb = "First paragraph, with many more words in it now.\n$p2"
        val each = spec(LetterScaleScope.EACH_PARAGRAPH)
        val cache = StoryMeasureCache()
        val a = probe(sa, sa.length - tail.length, each)
        val measuredA = cache.frameLayout(a).measured
        val b = probe(sb, sb.length - tail.length, each)
        assertSame("each paragraph: an edit in the first paragraph keeps the second one's factors", measuredA, cache.get(b))
        assertSameLayout("each paragraph", TextRenderer.frameLayout(b), cache.frameLayout(b))
        assertDrawnAsFresh("each paragraph", cache, placed(b))

        // The same edit with one ramp over the whole text changes every factor.
        val whole = spec()
        cache.frameLayout(probe(sa, sa.length - tail.length, whole))
        val wb = probe(sb, sb.length - tail.length, whole)
        assertNull("whole text: the letters before count", cache.get(wb))
        assertSameLayout("whole text", TextRenderer.frameLayout(wb), cache.frameLayout(wb))
    }

    @Test
    fun scaledAndUnscaledMeasurementsOfOneTailAreKeptApart() {
        // A one-letter tail scales every factor to 1, but scaled letters are measured without
        // ligatures: a measurement of the same characters unscaled is never taken for it.
        val story = "fi"
        val off = probe(story, 0, spec().copy(letterScale = LetterScaleSpec()))
        val unscaled = TextRenderer.frameLayout(off).measured
        val on = probe(story, 0, spec())
        assertFalse(TextRenderer.canReuseMeasured(on, unscaled))
        assertFalse(TextRenderer.canReuseMeasured(off, TextRenderer.frameLayout(on).measured))
        // A script drawn unscaled (right to left) takes the unscaled rule.
        val rtl = probe("שלום", 0, spec())
        assertTrue(TextRenderer.canReuseMeasured(rtl.copy(spec = spec().copy(letterScale = LetterScaleSpec())), TextRenderer.frameLayout(rtl).measured))
    }

    @Test
    fun editingTheFirstOfThreeScaledFramesRedrawsTheOthersAsFreshLayouts() {
        val s = setup(context)
        val f1 = newFrame(s, 20f, 20f, 180f, 120f)
        val f2 = linkFrame(s, f1, 220f, 20f, 380f, 120f)
        val f3 = linkFrame(s, f2, 20f, 170f, 380f, 280f)
        assertTrue(s.tool.openStoryEditor(f1))
        s.tool.story.updateSpec { it.copy(letterScale = LetterScaleSpec(smallestPercent = 40f)) }
        s.tool.story.confirmEditor()
        val id = itemOf(f1).thread.storyId
        assertEquals(listOf(f1, f2, f3), chainOf(s.c, f1))
        assertWhole(s.c, id)

        fun edit(what: String, change: (String) -> String) {
            val before = listOf(f2, f3).map { it.textData }
            val steps = s.c.undoManager.undoCount
            assertTrue(s.tool.openStoryEditor(f1))
            val story = s.tool.story.item!!.text
            s.tool.story.setText(change(story))
            s.tool.story.confirmEditor()
            assertEquals("$what: one step (I2)", steps + 1, s.c.undoManager.undoCount)
            assertEquals("$what: the story", change(story), itemOf(f1).thread.story)
            // Every frame: I9, its end where a FRESH layout says, its pixels a fresh rendering (I1).
            assertWhole(s.c, id)
            assertNotEquals("$what: frame 2 is written again", before[0], f2.textData)
            assertNotEquals("$what: frame 3 is written again", before[1], f3.textData)
        }
        // More letters in frame 1: the later tails start elsewhere and their letters get smaller.
        edit("letters typed") { "Brand new opening words. $it" }
        // A letter replaced and a space added: the later tails keep their factors (found again).
        edit("a letter replaced") { "X" + it.substring(1) }
        edit("a space added") { it.replaceFirst(" ", "  ") }
        // Letters removed again.
        edit("letters deleted") { it.substring(it.indexOf("words. ") + "words. ".length) }
    }
}
