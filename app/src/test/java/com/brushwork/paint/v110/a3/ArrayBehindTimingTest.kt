package com.brushwork.paint.v110.a3

import android.graphics.Color
import com.brushwork.paint.EditorController
import com.brushwork.paint.array.ArrayBehind
import com.brushwork.paint.array.ArrayLookKit
import com.brushwork.paint.array.ArrayOps
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.common.ArrayLabels110
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.10 (R4 (c), A3 step 7): making a 64-copy pixel array behind its layer costs at most 1.1 x
 * what v1.9's "Array" from the same selection costs ([ArrayOps.fromSelection], which also cuts the
 * source and records its undo tiles). One run is the user's two steps, timed together: the array
 * made from the selection ([ArrayBehind.fromSelectionBelow], one step
 * [ArrayLabels110.ARRAY_BEHIND_STEP], or v1.9's one step [ArrayLabels.BUTTON]), then "Edit array"
 * to 64 copies along a diagonal, drawn within the run (`ArrayRenders` draws on the calling thread
 * in tests).
 * Both steps are undone between runs, untimed. Each side is the median of 15 runs after 3
 * warm-ups, the two sides interleaved run by run on two editors so a slower stretch of the JVM
 * weighs on both; `PerfBudget` retries a ratio over the limit. On a 512 x 512 document
 * everywhere, and on the phone's 1080 x 2408 locally only (a T606-size document).
 */
@RunWith(RobolectricTestRunner::class)
@Category(Timing::class)
class ArrayBehindTimingTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() = ArrayDraw.clearCaches()

    private fun median(v: DoubleArray): Double = v.sorted().let { it[it.size / 2] }

    /** A [w] x [h] editor whose layer 1 holds a [size] px semi-transparent ramp at ([at], [at]), and that square's selection. */
    private class Bench(app: android.content.Context, w: Int, h: Int, val at: Int, val size: Int) {
        val c: EditorController = ArrayLookKit.controller(app, w, h)
        val src: Layer = c.doc.layers[1]
        val sel: Selection

        init {
            src.bitmap.setPixels(IntArray(size * size) { i ->
                val x = i % size
                val y = i / size
                Color.argb(110 + (x + 2 * y) % 146, (x * 3) and 0xFF, (y * 5) and 0xFF, 200 - (x + y) % 120)
            }, 0, size, at, at, size, size)
            sel = ArrayLookKit.rectSelection(c, at.toFloat(), at.toFloat(), (at + size).toFloat(), (at + size).toFloat())
        }
    }

    /**
     * One run on [b]: [make] from the selection, then "Edit array" to 64 copies [dx], [dy] apart;
     * the wall time of both (ms). Untimed: the Brush picked, the source and its selection set
     * before; both steps undone after.
     */
    private fun run(b: Bench, dx: Float, dy: Float, label: String, make: (EditorController) -> Boolean): Double {
        val c = b.c
        c.selectTool(ToolId.BRUSH)
        c.selectLayer(b.src)
        c.setSelection(b.sel, recordUndo = false)
        ArrayLookKit.settle(c)
        val layers = c.doc.layers.size
        val steps = c.undoManager.undoCount
        val t0 = System.nanoTime()
        assertTrue("$label: made", make(c))
        val layer = c.activeLayer
        val spec = layer.array!!.spec.copy(count = 64, relativeX = 0f, relativeY = 0f, constantX = dx, constantY = dy)
        assertTrue("$label: 64 copies", ArrayOps.edit(c, layer, spec))
        val ms = (System.nanoTime() - t0) / 1e6
        assertFalse("$label: drawn within the timed run", c.arrayRenders.isPending)
        ArrayLookKit.settle(c)
        assertEquals("$label: two steps", steps + 2, c.undoManager.undoCount)
        assertEquals("$label: 64 copies", 64, layer.array!!.spec.count)
        c.undo()
        c.undo()
        ArrayLookKit.settle(c)
        assertEquals("$label: undone", layers, c.doc.layers.size)
        return ms
    }

    /**
     * The medians (behind, v1.9) of [count] interleaved runs after [warm] warm-ups on two
     * [w] x [h] editors, the [size] px source at ([at], [at]) and its copies [dx], [dy] apart.
     */
    private fun medians(w: Int, h: Int, at: Int, size: Int, dx: Float, dy: Float, count: Int = 15, warm: Int = 3): Pair<Double, Double> {
        val behind = Bench(app, w, h, at, size)
        val v19 = Bench(app, w, h, at, size)
        val a = DoubleArray(count)
        val b = DoubleArray(count)
        for (k in 0 until warm + count) {
            val ta = run(behind, dx, dy, ArrayLabels110.ARRAY_BEHIND_STEP) { ArrayBehind.fromSelectionBelow(it) }
            val tb = run(v19, dx, dy, ArrayLabels.BUTTON) { it.arrayFromSelection() }
            if (k >= warm) {
                a[k - warm] = ta
                b[k - warm] = tb
            }
        }
        return median(a) to median(b)
    }

    private fun report(what: String, p: Pair<Double, Double>) {
        println(
            "v110 A3 ArrayBehindTimingTest: %s: behind median %.3f ms, v1.9 Array median %.3f ms of 15 after 3 warm-ups; ratio %.3f (budget 1.1); x 6 = %.1f / %.1f ms"
                .format(what, p.first, p.second, p.first / p.second, p.first * 6, p.second * 6),
        )
    }

    @Test
    fun a64CopyArrayBehindOnA512DocumentCostsAtMostV19sArray() {
        val what = "64 copies of a 96 px source, 512 x 512"
        val p = PerfBudget.measuredRatioWithin(LIMIT, what = what) { medians(512, 512, at = 24, size = 96, dx = 5f, dy = 5f) }
        report(what, p)
    }

    @Test
    fun a64CopyArrayBehindOnThePhonesDocumentCostsAtMostV19sArray() {
        assumeTrue("a T606-size document: locally only", System.getenv("CI") == null)
        val what = "64 copies of a 320 px source, 1080 x 2408"
        val p = PerfBudget.measuredRatioWithin(LIMIT, what = what) { medians(1080, 2408, at = 40, size = 320, dx = 11f, dy = 30f) }
        report(what, p)
    }

    private companion object {
        /** R4 (c): within 1.1 x of v1.9's `fromSelection`. */
        const val LIMIT = 1.1
    }
}
