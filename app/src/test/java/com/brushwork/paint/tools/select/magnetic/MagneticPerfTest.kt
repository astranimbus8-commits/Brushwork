package com.brushwork.paint.tools.select.magnetic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
import com.brushwork.paint.tools.select.LassoCommit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * v1.8 (C, design §3.4 budgets, §6.3 Q7): the magnetic lasso's costs on a 2048 px proxy (the
 * largest; a 4000 × 5000 canvas maps to 1638 × 2048). The edge map builds within 400 ms on the
 * worker; a segment of about 300 proxy px traces within 30 ms (an anchor tap); the two segments
 * of a dragged anchor (150 px each) trace again within 16 ms (one frame); the closed outline
 * rasterizes at 4000 × 5000 within 300 ms; the map and the search hold at most 12 MB.
 * The T606's budgets, checked on the desktop JVM (×4 on CI).
 */
@RunWith(RobolectricTestRunner::class)
@Category(Timing::class)
class MagneticPerfTest {

    private val n = EdgeMap.MAX_SIDE
    private val c = 1024f
    private val r = 950f

    /** The rim point at [arc] px along the rim from its leftmost point (clockwise, upwards). */
    private fun rim(arc: Float): Pair<Int, Int> {
        val a = PI + arc / r
        return (c + r * cos(a)).roundToInt() to (c + r * sin(a)).roundToInt()
    }

    private val lum by lazy { MagneticTestPictures.disc(n, n, c, c, r) }
    private val map by lazy { requireNotNull(EdgeMap.fromLuminance(lum.copyOf(), n, n, 1f)) }

    @Test
    fun theEdgeMapOfA2048ProxyWithin400Ms() {
        PerfBudget.within(PerfBudget.ms(400.0), what = "edge map 2048² (luminance)") {
            requireNotNull(EdgeMap.fromLuminance(lum.copyOf(), n, n, 1f))
        }
        // From the pixels (the luminance pass included).
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(-1)
        Canvas(bmp).drawCircle(c, c, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() })
        PerfBudget.within(PerfBudget.ms(400.0), what = "edge map 2048² (pixels)") {
            requireNotNull(EdgeMap.build(bmp, n))
        }
        bmp.recycle()
    }

    @Test
    fun aSegmentOf300PxWithin30Ms() {
        val wire = LiveWire()
        val (ax, ay) = rim(0f)
        val (bx, by) = rim(300f)
        map.half // built once with the map, not per segment
        PerfBudget.within(PerfBudget.ms(30.0), what = "segment 300 px") {
            requireNotNull(wire.path(map, ax, ay, bx, by, floor = 25))
        }
        val path = requireNotNull(wire.path(map, ax, ay, bx, by, floor = 25))
        assertTrue(MagneticTestPictures.offRim(path, c, c, r) <= 1.5f)
    }

    @Test
    fun aDraggedAnchorsTwoSegmentsWithin16Ms() {
        val wire = LiveWire()
        val (ax, ay) = rim(0f)
        val (mx, my) = rim(150f)
        val (bx, by) = rim(300f)
        PerfBudget.within(PerfBudget.ms(16.0), what = "retrace 2 × 150 px") {
            requireNotNull(wire.path(map, ax, ay, mx, my, floor = 25))
            requireNotNull(wire.path(map, mx, my, bx, by, floor = 25))
        }
    }

    @Test
    fun theCommitAt4000x5000Within300Ms() {
        // A traced outline: 12 anchors, segments of pixel steps (about 11 000 points).
        val path = Path()
        val k = 11_000
        for (i in 0 until k) {
            val a = 2 * PI * i / k
            val x = (2000 + 1800 * cos(a)).roundToInt().toFloat()
            val y = (2500 + 2300 * sin(a)).roundToInt().toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        PerfBudget.within(PerfBudget.ms(300.0), tries = 3, what = "commit 4000 × 5000") {
            LassoCommit.rasterize(path, 4000, 5000, antiAlias = true)
        }
        val s = LassoCommit.rasterize(path, 4000, 5000, antiAlias = true)
        assertEquals(255, s.alphaAt(2000, 2500))
        assertEquals(0, s.alphaAt(10, 10))
    }

    @Test
    fun theMapAndTheSearchHoldAtMost12Mb() {
        val wire = LiveWire()
        // The largest search: over the node cap (the half-resolution copy is built too).
        requireNotNull(wire.path(map, 74, 1024, 1024, 74, floor = 25))
        requireNotNull(wire.path(map, 74, 1024, 120, 730, floor = 25))
        val bytes = map.grad.size + map.half.grad.size + wire.bytes
        assertTrue("$bytes bytes", bytes <= 12L * 1024 * 1024)
    }
}
