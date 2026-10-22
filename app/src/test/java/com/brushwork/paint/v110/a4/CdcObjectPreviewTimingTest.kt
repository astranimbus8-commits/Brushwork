package com.brushwork.paint.v110.a4

import android.os.Looper
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.engine.PerfTrace
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.filters.FilterSession
import com.brushwork.paint.model.ArrayMode
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.LayerArray
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.select.ObjectTestKit
import com.brushwork.paint.vector.select.vec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.Locale

/**
 * v1.10 (R6, A4; design (c), contracts C12): object-mode preview frames of Change Drawing Color
 * on a 512 x 512 vector layer of 200 objects (paths with fill and line, shapes), each the median
 * of 15 frames after 3 warm-ups as `PerfTrace.FILTER_PREVIEW` records them.
 * - The whole layer with an array of 8 copies each (1,800 objects drawn): the exact pixel preview
 *   C12 keeps. Budget 5.5 ms on the PC (33 ms on the x6 device proxy).
 * - The 200 objects selected (rule 1): the layer's data mapped and re-rendered. Budget 42 ms on
 *   the PC (the x6 proxy of 250 ms on the device).
 */
@Category(Timing::class)
@RunWith(RobolectricTestRunner::class)
class CdcObjectPreviewTimingTest {
    private val kit = ObjectTestKit(512, 512)

    @Before
    fun setUp() { PerfTrace.clear(); PerfTrace.enabled = true }

    @After
    fun tearDown() { PerfTrace.enabled = false; PerfTrace.clear(); kit.close() }

    private fun layer(): EditorController {
        val c = kit.controller()
        val objects = ArrayList<VObject>(200)
        for (k in 0 until 200) {
            val x = 12f + (k % 20) * 24f
            val y = 12f + (k / 20) * 24f
            objects += if (k % 2 == 0) kit.box(x, y, x + 16f, y + 16f) else kit.ellipse(x + 8f, y + 8f, 18f, 14f)
        }
        c.vectors.addObjects(c.vec, objects, "Add")
        return c
    }

    private fun session(c: EditorController): FilterSession {
        c.startFilter(FilterRegistry.byId("adjust.change_drawing_color")!!)
        val s = c.filterSession!!
        s.debounceMs = 0L
        assertTrue(s.objectMode)
        assertTrue(kit.idleUntil { s.hasPreview && !s.isRendering })
        return s
    }

    /**
     * Runs the main looper without pausing until [done] (at most 5 s): the frame's return to the
     * main thread is then not delayed by a poll interval, which would count in its time.
     */
    private fun spinUntil(done: () -> Boolean): Boolean {
        val end = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return true
            Thread.onSpinWait()
        }
        return done()
    }

    /** The median frame of [s] over 15 color changes after 3 warm-ups, within [budgetMs] on the PC. */
    private fun medianFrame(s: FilterSession, budgetMs: Double, what: String): Double {
        var step = 0
        fun frame(): Double {
            step++
            s.update("color", if (step % 2 == 0) 0xFF20A060.toInt() else 0xFFC03080.toInt())
            assertTrue(spinUntil { !s.isRendering && !s.previewStale })
            return PerfTrace.lastMs(PerfTrace.FILTER_PREVIEW)!!.toDouble()
        }
        val median = PerfBudget.measuredWithin(PerfBudget.ms(budgetMs), what = what) {
            repeat(3) { frame() }
            val runs = DoubleArray(15) { frame() }.sorted()
            runs[runs.size / 2]
        }
        println(String.format(Locale.ROOT, "CdcObjectPreviewTimingTest (%s): median %.2f ms of 15 runs after 3 warm-ups; x6 = %.1f ms; budget %.1f ms", what, median, median * 6, budgetMs))
        return median
    }

    @Test
    fun aWholeLayerFrameOf200ObjectsWith8CopiesStaysWithinBudget() {
        val c = layer()
        c.vectors.updateArray(c.vec, LayerArray(ArraySpec(mode = ArrayMode.LINE, count = 9, relativeX = 0f, constantX = 5f, constantY = 4f)), "Array")
        assertEquals("200 objects, 8 copies each", 1800, ArrayDraw.effectiveVector(c.vec.dataSnapshot())!!.objects.size)
        val s = session(c)
        assertTrue("C12: the exact pixel preview", s.holdsPixelCopy)
        medianFrame(s, 5.5, "whole layer, 8 copies")
        s.cancel()
    }

    @Test
    fun aRenderedFrameOf200SelectedObjectsStaysWithinBudget() {
        val c = layer()
        c.vectors.setSelection(c.vec, c.vec.vector!!.objects.mapTo(HashSet()) { it.id })
        val s = session(c)
        assertFalse("re-rendered", s.holdsPixelCopy)
        medianFrame(s, 42.0, "200 selected objects")
        s.cancel()
    }
}
