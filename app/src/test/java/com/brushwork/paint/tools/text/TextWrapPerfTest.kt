package com.brushwork.paint.tools.text

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.tools.text.WrapFixtures.LOREM
import com.brushwork.paint.tools.text.WrapFixtures.setup
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.cos
import kotlin.math.sin

/**
 * Cost guards of text wrap (v1.5 §4.1c; relative, JVM + Robolectric): the T606 budgets are a
 * 2000-character layout ≤ 4 ms (live drags at 60 fps), an outline ≤ 25 ms per picture version,
 * a re-flow ≤ 60 ms. A desktop JVM is several times faster than the phone, so these bounds only
 * catch regressions in kind (a quadratic loop, a full re-measure per frame), not milliseconds.
 */
@RunWith(RobolectricTestRunner::class)
class TextWrapPerfTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun median(runs: Int, block: () -> Unit): Double {
        val times = DoubleArray(runs) {
            val t0 = System.nanoTime()
            block()
            (System.nanoTime() - t0) / 1e6
        }
        times.sort()
        return times[runs / 2]
    }

    @Test
    fun aDragRelaysOut2000CharactersQuickly() {
        val text = buildString { while (length < 2000) append(LOREM).append(' ') }.take(2000)
        // A 600-point outline (a wobbly blob) in the middle of the text.
        val n = 600
        val blob = WrapPolygon(
            List(n) { i -> val a = 2 * Math.PI * i / n; (500 + (180 + 25 * sin(9 * a)) * cos(a)).toFloat() },
            List(n) { i -> val a = 2 * Math.PI * i / n; (700 + (180 + 25 * sin(9 * a)) * sin(a)).toFloat() },
        )
        val base = TextItem(text, TextSpec(sizePx = 16f, box = TextBoxSpec(width = 900f)), 500f, 700f, wrap = TextWrapSpec(sourceLayerId = 1, polygons = listOf(blob), gapPx = 6f))
        var prepared = TextRenderer.prepare(base)
        assertTrue("${prepared.block!!.wrapLines!!.size} lines", prepared.block!!.wrapLines!!.size > 15)
        var dy = 0f
        val drag = median(31) {
            dy += 1f
            prepared = TextRenderer.prepare(base.copy(cy = 700f + dy), prepared)
        }
        val cold = median(11) { TextRenderer.prepare(base.copy(cx = 501f + dy++)) }
        println("[perf] wrap layout of 2000 chars: drag ${"%.2f".format(drag)} ms, cold ${"%.2f".format(cold)} ms")
        assertTrue("drag re-layout $drag ms", drag < PerfBudget.ms(40.0))
    }

    @Test
    fun anOutlineAndAReflowStayCheap() {
        val s = setup(context, 1024, 1024)
        Canvas(s.picture.bitmap).drawCircle(400f, 500f, 260f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WrapFixtures.BLACK })
        s.picture.markChanged()
        val contours = WrapContours()
        val trace = median(7) {
            s.picture.markChanged()
            contours.outline(s.picture)
        }
        val text = WrapFixtures.wrappedText(s, cx = 512f, cy = 500f, width = 900f, size = 18f)
        var grow = 0f
        val reflows = s.c.textWrap.reflowCount
        val reflow = median(5) {
            // The picture grows a little every time: its outline changes, the text re-flows.
            grow += 4f
            s.c.editWholeLayer(s.picture, "Grow") { b -> Canvas(b).drawCircle(400f, 500f, 260f + grow, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WrapFixtures.BLACK }) }
        }
        println("[perf] outline of a 1024² layer ${"%.2f".format(trace)} ms; picture edit + re-flow ${"%.2f".format(reflow)} ms")
        assertTrue(text.isTextLayer)
        assertTrue(s.c.textWrap.reflowCount >= reflows + 5)
        assertTrue("outline $trace ms", trace < PerfBudget.ms(250.0))
        assertTrue("edit + re-flow $reflow ms", reflow < PerfBudget.ms(600.0))
    }
}
