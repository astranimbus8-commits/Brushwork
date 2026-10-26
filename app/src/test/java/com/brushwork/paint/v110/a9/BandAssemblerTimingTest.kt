package com.brushwork.paint.v110.a9

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.tiles.BandAssembler
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Random

/**
 * v1.10 A9 step 1 (design 2.17, engine proxy): one parked 512 x 512 layer, every tile painted,
 * drawn through `BandAssembler` at the thumbnail scale 0.37 (a layer or folder thumbnail, a
 * zoomed-out proxy). Budget: the PC median (15 runs after 3 warm-ups) x 6, then [PerfBudget.ms]
 * on CI. Also checks the bands draw what the flat bitmap draws.
 */
@Category(Timing::class)
@RunWith(RobolectricTestRunner::class)
class BandAssemblerTimingTest {
    private val made = mutableListOf<Bitmap>()

    @After
    fun tearDown() {
        A9Docs.disposeTracked()
        made.forEach { if (!it.isRecycled) it.recycle() }
    }

    private fun keep(b: Bitmap) = b.also { made += it }

    @Test
    fun aThumbnailOfAParkedLayerIsQuick() {
        val r = Random(41L)
        val flat = keep(BitmapUtils.createLayerBitmap(SIDE, SIDE).apply { setPixels(IntArray(SIDE * SIDE) { r.nextInt() }, 0, SIDE, 0, 0, SIDE, SIDE) })
        val tiled = TiledPixels.fromBitmap(flat)
        val side = Math.ceil(SIDE * SCALE.toDouble()).toInt()
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val expected = keep(BitmapUtils.createLayerBitmap(side, side))
        Canvas(expected).apply { scale(SCALE, SCALE) }.drawBitmap(flat, 0f, 0f, paint)
        val out = keep(BitmapUtils.createLayerBitmap(side, side))
        val c = Canvas(out).apply { scale(SCALE, SCALE) }
        val draw = {
            out.eraseColor(0)
            BandAssembler.draw(c, tiled, paint)
        }
        draw()
        A9Docs.assertBitEqual("bands against the flat bitmap", expected, out)
        val ms = PerfBudget.measuredWithin(PerfBudget.ms(BUDGET_MS), what = "BandAssembler 512² at 0.37") {
            repeat(3) { draw() }
            val times = DoubleArray(15) { PerfBudget.timeMs(draw) }
            times.sort()
            times[times.size / 2]
        }
        println("BandAssemblerTimingTest: median $ms ms (budget ${PerfBudget.ms(BUDGET_MS)} ms)")
        assertTrue(ms >= 0.0)
    }

    private companion object {
        const val SIDE = 512
        const val SCALE = 0.37f

        /** PC median 0.565 ms x 6 (see the class docs; Measurements line of the A9 progress file). */
        const val BUDGET_MS = 3.4
    }
}
