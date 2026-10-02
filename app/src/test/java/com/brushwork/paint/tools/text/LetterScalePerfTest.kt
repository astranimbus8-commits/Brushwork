package com.brushwork.paint.tools.text

import android.graphics.Canvas
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.tools.text.WrapFixtures.LOREM
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Cost guards of letter scaling (v1.6 §3.5c; relative, JVM + Robolectric): the T606 budget is a
 * 2,000-character scaled text laid out in ≤ 4 ms and drawn in ≤ 6 ms (§6.3: ≤ 10 ms together).
 * A desktop JVM under Robolectric is not the phone, so these bounds only catch regressions in
 * kind (a quadratic loop, a re-measure per letter, a ramp per drawn line), not milliseconds.
 */
@RunWith(RobolectricTestRunner::class)
class LetterScalePerfTest {

    private fun median(runs: Int, block: () -> Unit): Double {
        val times = DoubleArray(runs) {
            val t0 = System.nanoTime()
            block()
            (System.nanoTime() - t0) / 1e6
        }
        times.sort()
        return times[runs / 2]
    }

    private val text = buildString { while (length < 2000) append(LOREM).append(' ') }.take(2000)

    @Test
    fun twoThousandScaledLettersLayOutAndDrawQuickly() {
        val base = TextItem(text, TextSpec(sizePx = 14f, box = TextBoxSpec(width = 900f), letterScale = LetterScaleSpec(smallestPercent = 40f)), 500f, 500f)
        var k = 0
        // The slider moving: a new scale every time (the ramp and the measurement are new).
        val layout = median(21) {
            k++
            TextRenderer.prepare(base.copy(spec = base.spec.copy(letterScale = base.spec.letterScale.copy(smallestPercent = 40f + (k % 50)))))
        }
        val unscaled = median(21) {
            k++
            TextRenderer.prepare(base.copy(spec = base.spec.copy(sizePx = 14f + (k % 7) * 0.01f, letterScale = LetterScaleSpec())))
        }
        val prep = TextRenderer.prepare(base)
        val bmp = BitmapUtils.createLayerBitmap(1000, 1000)
        val canvas = Canvas(bmp)
        val draw = median(21) {
            bmp.eraseColor(0)
            TextRenderer.drawItem(canvas, base, prep, null)
        }
        // Moving the text re-lays out nothing.
        val move = median(21) { k++; TextRenderer.prepare(base.copy(cx = 500f + k), prep) }
        println("[perf] 2000 scaled letters: layout ${"%.2f".format(layout)} ms (unscaled ${"%.2f".format(unscaled)} ms), draw ${"%.2f".format(draw)} ms, move ${"%.3f".format(move)} ms")
        assertTrue("layout $layout ms", layout < PerfBudget.ms(60.0))
        assertTrue("draw $draw ms", draw < PerfBudget.ms(120.0))
        assertTrue("move $move ms", move < PerfBudget.ms(1.0))
    }

    @Test
    fun aLongScaledStoryFlowsIntoFramesQuickly() {
        val story = buildString { while (length < 5000) append(LOREM).append(' ') }.take(5000)
        val spec = TextSpec(sizePx = 16f, box = TextBoxSpec(width = 500f, minHeight = 300f), letterScale = LetterScaleSpec(smallestPercent = 50f))
        var n = 0
        val flow = median(7) {
            n++
            // Six frames, each laid out from the story at its start (area D's flow).
            var start = 0
            val s = story.substring(0, story.length - (n % 3))
            for (f in 0 until 6) {
                start = TextRenderer.frameEnd(TextItem(spec = spec, thread = TextThreadSpec(storyId = 1, story = s, start = start, end = start)))
            }
        }
        println("[perf] 5000-character scaled story over 6 frames: ${"%.2f".format(flow)} ms")
        assertTrue("flow $flow ms", flow < PerfBudget.ms(400.0))
    }
}
