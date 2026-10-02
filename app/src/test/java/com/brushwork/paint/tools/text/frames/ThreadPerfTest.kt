package com.brushwork.paint.tools.text.frames

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.model.Layer
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.tools.text.TextBoxSpec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.text.frames.FrameFixtures.LOREM
import com.brushwork.paint.tools.text.frames.FrameFixtures.assertWhole
import com.brushwork.paint.tools.text.frames.FrameFixtures.itemOf
import com.brushwork.paint.tools.text.frames.FrameFixtures.linkFrame
import com.brushwork.paint.tools.text.frames.FrameFixtures.newFrame
import com.brushwork.paint.tools.text.frames.FrameFixtures.setup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * Cost guards of linked frames (v1.6, §3.6c budgets; relative, JVM + Robolectric). On the T606 a
 * re-flow of 5,000 characters over 6 frames takes ≤ 40 ms of layout and a story edit ≤ 60 ms, and
 * typing previews the re-flow at most every 100 ms (trailing edge). A desktop JVM is several times
 * faster than the phone, so these bounds catch regressions in kind (a tail measured twice, a
 * quadratic loop, a preview per keystroke), not milliseconds.
 */
@RunWith(RobolectricTestRunner::class)
class ThreadPerfTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun median(runs: Int, block: (Int) -> Unit): Double {
        val times = DoubleArray(runs) { k ->
            val t0 = System.nanoTime()
            block(k)
            (System.nanoTime() - t0) / 1e6
        }
        times.sort()
        return times[runs / 2]
    }

    private val story5k = buildString {
        var p = 0
        while (length < 5000) {
            append(LOREM)
            append(if (++p % 3 == 0) "\n" else " ")
        }
    }.take(5000)

    private val spec = TextSpec(sizePx = 16f)

    private val sixFrames = List(6) { k ->
        FlowFrame(null, TextItem(spec = spec.copy(box = TextBoxSpec(width = 300f, minHeight = 260f)), cx = 200f + k, cy = 150f))
    }

    @Test
    fun aStoryOf5000CharactersFlowsThroughSixFramesQuickly() {
        // Warm up the text measurement.
        repeat(2) { TextThreadFlow.flow(story5k, spec, sixFrames, 1L, 0L, StoryMeasureCache()) }
        // A story edit: every tail is new (a fresh cache each time).
        val cold = median(7) { k -> TextThreadFlow.flow(story5k.replaceRange(k, k + 1, "x"), spec, sixFrames, 1L, 0L, StoryMeasureCache()) }
        // Drawing right after a flow measures nothing again.
        val cache = StoryMeasureCache()
        val items = TextThreadFlow.flow(story5k, spec, sixFrames, 1L, 0L, cache)
        val misses = cache.misses
        val draw = median(5) { items.forEach { cache.prepare(it) } }
        assertEquals("drawing the flowed frames measures nothing again", misses, cache.misses)
        println("[perf] flow 5000 chars / 6 frames: cold ${"%.2f".format(cold)} ms; layout for drawing ${"%.2f".format(draw)} ms")
        assertTrue("cold flow $cold ms", cold < PerfBudget.ms(120.0))
        assertTrue("layout for drawing $draw ms", draw < PerfBudget.ms(60.0))
    }

    @Test
    fun typingInTheFirstFrameDoesNotMeasureTheLaterTailsAgain() {
        val cache = StoryMeasureCache()
        TextThreadFlow.flow(story5k, spec, sixFrames, 1L, 0L, cache)
        val hits = cache.hits
        // One more letter in the first frame's first word: the later frames' tails are the same characters.
        val typed = story5k.substring(0, 3) + "e" + story5k.substring(3)
        val items = TextThreadFlow.flow(typed, spec, sixFrames, 1L, 0L, cache)
        assertTrue("later tails found again (${cache.hits - hits} hits)", cache.hits - hits >= 4)
        assertEquals("the same flow as without the cache", TextThreadFlow.flow(typed, spec, sixFrames, 1L, 0L), items)
        assertTrue("bounded", cache.size <= StoryMeasureCache.DEFAULT_MAX_CHARS)
    }

    @Test
    fun aStoryEditThroughTheToolStaysCheapAndTypingPreviewsAtMostEvery100Ms() {
        val s = setup(context, 1000, 1000)
        val frames = ArrayList<Layer>()
        frames += newFrame(s, 20f, 20f, 320f, 280f, text = story5k)
        for (k in 1 until 6) {
            val x = 20f + 330f * (k % 3)
            val y = 20f + 320f * (k / 3)
            frames += linkFrame(s, frames.last(), x, y, x + 300f, y + 260f)
        }
        val id = itemOf(frames[0]).thread.storyId
        assertWhole(s.c, id)
        var n = 0
        val edit = median(5) {
            assertTrue(s.tool.openStoryEditor(frames[0]))
            s.tool.story.setText("Edit ${n++} " + story5k)
            s.tool.story.confirmEditor()
        }
        println("[perf] story edit (5000 chars, 6 frames, preview + write): ${"%.2f".format(edit)} ms")
        assertWhole(s.c, id)
        assertTrue("story edit $edit ms", edit < PerfBudget.ms(400.0))

        // Typing: the first change previews at once, the next ones wait for the 100 ms throttle.
        s.tool.storyPreviewMs = 100L
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertTrue(s.tool.openStoryEditor(frames[0]))
        val runs = s.tool.previewRuns
        for (k in 1..8) s.tool.story.setText("Typing $k " + story5k)
        assertEquals("one preview right away, the rest waits", runs + 1, s.tool.previewRuns)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertEquals("then one more, with the last text (trailing edge)", runs + 2, s.tool.previewRuns)
        val preview = s.c.renderOverride as ThreadPreview
        val shown = preview.itemFor(frames[0])
        assertNotNull(shown)
        assertTrue(shown!!.text.startsWith("Typing 8 "))
        s.tool.story.cancelEditor()
        assertEquals(null, s.c.renderOverride)
    }
}
