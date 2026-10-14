package com.brushwork.paint.v19.a6

import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.engine.live.StrokeBelowCache
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.testing.Timing
import com.brushwork.paint.tools.ToolId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * v1.9 A6 steps 9 and 10 (P3, R11a): what the stroke's below-cache saves, on the P0 bench's boards
 * (PHONE 20 layers painting layer 10, PRINT 3 layers painting layer 1, [A6Bench]) at the app's fit,
 * with the P0 bench's tools (Hard round 40 px, the soft Eraser 90 px) and its 30 cm serpentine,
 * two touch events per frame. Per frame it times the display tiles' composite (`tiles.update`,
 * the part P3 and R11a change: the JVM's FRAME span is mostly the software blit of the screen,
 * which a phone does on its GPU) and the whole frame (that composite plus the view's FRAME span,
 * which then finds nothing left to composite). A value is the median over [RUNS] strokes (after
 * [WARM_UPS]) of each stroke's median; the cache on and off alternate stroke by stroke on one
 * editor, so the machine's load weighs on both alike. Both switches are turned on here, shipped
 * or not. Also a board of 40 layers painting the top one (the most below). Local runs only
 * (T606-size canvases); prints `[a6-below] ...` lines, asserts only that the cached strokes drew
 * from the cache.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h803dp-xxhdpi")
@Category(Timing::class)
class StrokeBelowBench {
    private val strokesWereOn = StrokeBelowCache.strokesOn
    private val eraserWasOn = StrokeBelowCache.eraserOn

    @Before
    fun setUp() {
        assumeTrue("T606-size canvases: local runs only", System.getenv("CI") == null)
        // What P3 and R11a would save, whether or not they ship.
        StrokeBelowCache.strokesOn = true
        StrokeBelowCache.eraserOn = true
    }

    @After
    fun tearDown() {
        StrokeBelowCache.strokesOn = strokesWereOn
        StrokeBelowCache.eraserOn = eraserWasOn
    }

    private fun f(v: Float) = String.format(Locale.ROOT, "%.2f", v)

    /** One stroke through [pts], a frame after every second event; the medians of its composites and frames. */
    private fun run(b: A6Bench, pts: List<PointF>, visible: Rect): Pair<Float, Float> {
        val composites = ArrayList<Float>(pts.size / 2 + 1)
        val frames = ArrayList<Float>(pts.size / 2 + 1)
        b.c.pointerDown(b.point(pts[0]))
        var i = 1
        while (i < pts.size - 1) {
            b.c.pointerMove(b.point(pts[i++]))
            if (i < pts.size - 1) b.c.pointerMove(b.point(pts[i++]))
            val t0 = System.nanoTime()
            b.c.tiles.update(b.c.compositor, visible)
            val composite = (System.nanoTime() - t0) / 1_000_000f
            composites += composite
            // The view finds nothing left to composite: its FRAME span is the rest of the frame.
            frames += composite + b.frame()
        }
        b.c.pointerUp(b.point(pts.last()))
        b.settle()
        val steps = b.c.undoManager.undoCount
        b.c.undo()
        Smoke.pump(40)
        assertEquals("the stroke was undone", steps - 1, b.c.undoManager.undoCount)
        b.settle()
        return A6Bench.median(composites) to A6Bench.median(frames)
    }

    private fun bench(name: String, w: Int, h: Int, layers: Int, active: Int) {
        A6Bench(w, h, layers, active).use { b ->
            val cache = b.c.liveAdjust.strokeBelow
            for (tool in listOf(ToolId.BRUSH, ToolId.ERASER)) {
                b.c.selectTool(ToolId.BRUSH)
                b.c.brush = BrushLibrary.byId("hardround")!!.copy(size = 40f)
                if (tool == ToolId.ERASER) {
                    b.c.toggleEraser()
                    assertEquals(ToolId.ERASER, b.c.activeToolId)
                    b.c.eraser = BrushLibrary.byId("softeraser")!!.copy(size = 90f)
                }
                val zoom = b.fit()
                val pts = A6Bench.serpentine(b.visibleArea(), STROKE_LENGTH)
                val visible = RectF(0f, 0f, b.screenW.toFloat(), b.screenH.toFloat())
                b.c.viewTransform.inverse.mapRect(visible)
                val vis = Rect().also { visible.roundOut(it) }
                val cached = ArrayList<Pair<Float, Float>>()
                val full = ArrayList<Pair<Float, Float>>()
                try {
                    for (r in 0 until WARM_UPS + RUNS) {
                        for (on in if (r % 2 == 0) listOf(true, false) else listOf(false, true)) {
                            cache.enabled = on
                            val hits = cache.hits
                            val m = run(b, pts, vis)
                            if (on) assertTrue("$name $tool drew from the cache", cache.hits > hits)
                            else assertEquals("$name $tool: the cache was off", hits, cache.hits)
                            if (r < WARM_UPS) continue
                            if (on) cached += m else full += m
                        }
                    }
                } finally {
                    cache.enabled = true
                }
                val kind = if (tool == ToolId.BRUSH) "brush" else "eraser"
                val cc = A6Bench.median(cached.map { it.first })
                val fc = A6Bench.median(full.map { it.first })
                val cf = A6Bench.median(cached.map { it.second })
                val ff = A6Bench.median(full.map { it.second })
                println("[a6-below-runs] $name $kind composite cached: ${cached.joinToString(" ") { f(it.first) }}")
                println("[a6-below-runs] $name $kind composite full: ${full.joinToString(" ") { f(it.first) }}")
                println("[a6-below] composite.$kind $name ${f(zoom)} cached ${f(cc)} full ${f(fc)} ratio ${f(cc / fc)} jvm")
                println("[a6-below] frame.$kind $name ${f(zoom)} cached ${f(cf)} full ${f(ff)} ratio ${f(cf / ff)} jvm")
                assertTrue(cc > 0f && fc > 0f)
            }
        }
    }

    @Test
    fun phone() = bench("PHONE", 1080, 2408, layers = 20, active = 10)

    @Test
    fun print() = bench("PRINT", 3000, 4000, layers = 3, active = 1)

    /** The most below: 40 layers on PHONE, painting the top one. */
    @Test
    fun phoneTopOf40() = bench("PHONE40", 1080, 2408, layers = 40, active = 39)

    private companion object {
        const val WARM_UPS = 2
        const val RUNS = 9
        /** The bench's 30 cm stroke at 350 dpi. */
        const val STROKE_LENGTH = 4134f
    }
}
