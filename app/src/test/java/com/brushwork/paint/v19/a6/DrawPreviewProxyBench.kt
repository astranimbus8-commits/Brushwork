package com.brushwork.paint.v19.a6

import com.brushwork.paint.AppSettings
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.engine.live.LiveAdjust
import com.brushwork.paint.engine.live.StrokePreview
import com.brushwork.paint.testing.Timing
import com.brushwork.paint.tools.ToolId
import org.junit.After
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
 * v1.9 A6 step 8 (P4, LA2): "Drawing preview: Faster" against "Exact pixels", per frame while
 * drawing, the way the P0 bench measures `frame` (`qa19/P0ProxyBench`, [A6Bench]): Hard round
 * 40 px, a 30 cm serpentine over the visible canvas, two touch events per frame, on A4 (5 layers)
 * and PRINT (3 layers) at the app's fit and at design 2.7's fits (0.47, 0.41). A value is the
 * median over [RUNS] strokes (after [WARM_UPS]) of each stroke's median frame; the two modes
 * alternate stroke by stroke on one editor (the live policy opted into, real clock), so the
 * machine's load weighs on both alike. LA2: "Faster" ships when it is at least 20 % cheaper on A4
 * or PRINT. Local runs only (T606-size canvases); prints `[p4] ...` lines, asserts only that
 * the "Faster" strokes ran from proxies.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h803dp-xxhdpi")
@Category(Timing::class)
class DrawPreviewProxyBench {
    private val wasAvailable = StrokePreview.available

    @Before
    fun setUp() {
        assumeTrue("T606-size canvases: local runs only", System.getenv("CI") == null)
        StrokePreview.available = true
    }

    @After
    fun tearDown() {
        StrokePreview.available = wasAvailable
    }

    private fun f(v: Float) = String.format(Locale.ROOT, "%.2f", v)

    private fun bench(name: String, w: Int, h: Int, layers: Int, active: Int, designZoom: Float) {
        A6Bench(w, h, layers, active).use { b ->
            b.c.selectTool(ToolId.BRUSH)
            b.c.brush = BrushLibrary.byId("hardround")!!.copy(size = 40f)
            b.c.liveAdjust.policy = LiveAdjust.Policy.LIVE
            for (zoom in listOf(b.fit(), designZoom)) {
                if (zoom != b.c.viewTransform.zoom) b.zoomTo(zoom)
                val pts = A6Bench.serpentine(b.visibleArea(), STROKE_LENGTH)
                val faster = ArrayList<Float>()
                val exact = ArrayList<Float>()
                var proxied = 0
                for (run in 0 until WARM_UPS + RUNS) {
                    for (mode in if (run % 2 == 0) MODES else MODES.reversed()) {
                        b.c.settings.drawPreview = mode
                        var sawSession = false
                        val frame = b.strokeRun(pts) { if (b.c.liveAdjust.isActive) sawSession = true }.first
                        if (mode == AppSettings.DRAW_PREVIEW_FASTER && sawSession) proxied++
                        if (run < WARM_UPS) continue
                        if (mode == AppSettings.DRAW_PREVIEW_FASTER) faster += frame else exact += frame
                    }
                }
                assertTrue("$name: the Faster strokes ran from proxies ($proxied)", proxied == WARM_UPS + RUNS)
                val fm = A6Bench.median(faster)
                val em = A6Bench.median(exact)
                println("[p4-runs] $name ${f(zoom)} faster: ${faster.joinToString(" ") { f(it) }}")
                println("[p4-runs] $name ${f(zoom)} exact: ${exact.joinToString(" ") { f(it) }}")
                println("[p4] frame.draw $name ${f(zoom)} faster ${f(fm)} exact ${f(em)} ratio ${f(fm / em)} jvm")
            }
        }
    }

    @Test
    fun a4() = bench("A4", 2480, 3508, layers = 5, active = 2, designZoom = 0.47f)

    @Test
    fun print() = bench("PRINT", 3000, 4000, layers = 3, active = 1, designZoom = 0.41f)

    private companion object {
        const val WARM_UPS = 3
        const val RUNS = 15
        /** The bench's 30 cm stroke at 350 dpi. */
        const val STROKE_LENGTH = 4134f
        val MODES = listOf(AppSettings.DRAW_PREVIEW_FASTER, AppSettings.DRAW_PREVIEW_EXACT)
    }
}
