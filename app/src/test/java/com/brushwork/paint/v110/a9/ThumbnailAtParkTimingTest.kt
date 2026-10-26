package com.brushwork.paint.v110.a9

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.budget.Budgets
import com.brushwork.paint.engine.tiles.ChaosResidency
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeMemoryProbe
import com.brushwork.paint.testing.FakeSwapStore
import com.brushwork.paint.testing.MemoryPreset
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
import com.brushwork.paint.ui.layers.LayerThumbnails
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.Random
import java.util.concurrent.Executor

/**
 * v1.10 A9 step 6 (design 2.13, 2.17 engine proxy `ThumbnailAtParkTimingTest`): the thumbnail of
 * one parked 512 x 512 layer whose every tile is SWAPPED, made from its tiles: the request, the
 * reads (here inline: the worker runs on this thread, so the time is the whole work, off main on
 * the phone) and the picture. Budget: the PC median (15 runs after 3 warm-ups) x 6, then
 * [PerfBudget.ms] on CI.
 */
@Category(Timing::class)
@RunWith(RobolectricTestRunner::class)
class ThumbnailAtParkTimingTest {
    private val swap = FakeSwapStore()

    @get:Rule
    val engine = EngineRule(
        EngineLevel.FULL,
        memory = FakeMemoryProbe(MemoryPreset.T606),
        budgets = Budgets(javaMax = 512L shl 20, nativeBudget = 256L shl 20, known = true, stepDown = 0),
        residency = ChaosResidency(),
        swap = swap,
    )

    private val made = mutableListOf<Bitmap>()

    @After
    fun tearDown() {
        A9Docs.disposeTracked()
        made.forEach { if (!it.isRecycled) it.recycle() }
    }

    @Test
    fun aParkedLayersThumbnailIsQuick() {
        val r = Random(43L)
        val d = A9Docs.track(Document("thumb-timing", "thumb-timing", SIDE, SIDE))
        val b = BitmapUtils.createLayerBitmap(SIDE, SIDE).apply { setPixels(IntArray(SIDE * SIDE) { r.nextInt() }, 0, SIDE, 0, 0, SIDE, SIDE) }
        made += b
        val l = Layer(d.newLayerId(), "Painted", b).also { d.layers += it }
        assertTrue(ChaosResidency().park(l))
        val t = l.pixels as TiledPixels
        for (row in 0 until t.rows) for (col in 0 until t.cols) t.tileAt(col, row)?.let { if (it.solidColor == null) A9Docs.swapOut(swap, it) }
        val inline = Executor { it.run() }
        val main = Handler(Looper.getMainLooper())
        val make = {
            val thumbs = LayerThumbnails(SIZE, inline, main)
            thumbs.content(l)
            shadowOf(Looper.getMainLooper()).idle()
            check(thumbs.madeFromTiles == 1)
        }
        make()
        val ms = PerfBudget.measuredWithin(PerfBudget.ms(BUDGET_MS), what = "a parked 512² layer's thumbnail from swapped tiles") {
            repeat(3) { make() }
            val times = DoubleArray(15) { PerfBudget.timeMs(make) }
            times.sort()
            times[times.size / 2]
        }
        println("ThumbnailAtParkTimingTest: median $ms ms (budget ${PerfBudget.ms(BUDGET_MS)} ms)")
        assertEquals("still parked", true, l.pixels is TiledPixels)
        assertEquals(0, l.pins)
    }

    private companion object {
        const val SIDE = 512
        const val SIZE = 96

        /** PC median 4.29 ms x 6 (Measurements line of the A9 progress file). */
        const val BUDGET_MS = 26.0
    }
}
