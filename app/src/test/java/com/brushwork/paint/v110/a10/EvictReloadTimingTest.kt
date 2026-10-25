package com.brushwork.paint.v110.a10

import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.NativeWatch
import com.brushwork.paint.engine.tiles.ResidencyState
import com.brushwork.paint.engine.tiles.TileOps
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.storage.LayerCodec
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
import com.brushwork.paint.v110.lead.F1Pixels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * v1.10 A10 (design 2.0 EVICTED row and P3 order 1, step 4): a phone layer (1080 x 2400) evicted
 * to its saved file comes back as a PARKED store ([NativeWatch.reload]: the saved file read and
 * cut into tiles). The design's phone figure ("40-60 ms") is an estimate until the device checks,
 * and it is bound by inflating the file: this PC inflates only a few times faster than a phone, so
 * the x 6 proxy of 60 ms (10 ms) lies under the time the codec alone takes here. The guard is
 * therefore the reload against the codec's own read of the same file (the floor no reload can
 * beat): at most [LIMIT] times it plus [SLACK_MS], so the cut adds at most half the read. Each is
 * the median of [RUNS] runs after [WARM] warm-ups, each store released after it; the pixels come
 * back bit for bit. The medians and the x 6 figure are printed for the measurements.
 */
@Category(Timing::class)
@RunWith(RobolectricTestRunner::class)
class EvictReloadTimingTest {
    @get:Rule
    val engine = EngineRule(level = EngineLevel.TILES)

    private fun median(v: List<Double>): Double = v.sorted()[v.size / 2]

    private fun ms(block: () -> Unit): Double {
        val t0 = System.nanoTime()
        block()
        return (System.nanoTime() - t0) / 1e6
    }

    private fun oneReload(evicted: TiledPixels): Double {
        var back: TiledPixels? = null
        val ms = ms { back = NativeWatch.reload(evicted) }
        back?.let { TileOps.releaseStore(it) }
        return ms
    }

    /** The floor: the codec's read of [file] into a new array, as the reload starts. */
    private fun oneRead(file: File): Double = ms { LayerCodec.read(file, W, H, ByteArray(LayerCodec.byteLength(W, H))) }

    @Test
    fun anEvictedPhoneLayerReloadsAtMostHalfAReadOverTheCodec() {
        val dir = File(RuntimeEnvironment.getApplication().filesDir, "projects/reload").apply { mkdirs() }
        val src = F1Pixels.mixed(W, H, 9)
        val (layer, ref) = EvictKit.savedParkedLayer(dir, 1, src)
        assertTrue(NativeWatch.evict(layer, ref, 100L) > 0)
        assertEquals(ResidencyState.EVICTED, layer.residency)
        val evicted = layer.pixels as TiledPixels
        val file = File(ref.projectDir, ref.file)

        val (reload, read) = PerfBudget.measuredRatioWithin(LIMIT, tries = 3, slackMs = PerfBudget.ms(SLACK_MS), what = "reload of an evicted phone layer against the codec's read") {
            repeat(WARM) { oneReload(evicted); oneRead(file) }
            val reloads = ArrayList<Double>(RUNS)
            val reads = ArrayList<Double>(RUNS)
            repeat(RUNS) {
                reloads += oneReload(evicted)
                reads += oneRead(file)
            }
            median(reloads) to median(reads)
        }
        val back = NativeWatch.reload(evicted)
        assertNull(back.evictedTo)
        val pixels = back.materialize()
        F1Pixels.assertBitEqual("the saved file's pixels", src, pixels)
        pixels.recycle()
        TileOps.releaseStore(back)
        println(
            "v110 A10 EvictReloadTimingTest: reload of an evicted %dx%d layer (%d KiB file): median %.2f ms of %d after %d warm-ups (x 6 = %.1f ms; device estimate 60 ms); the codec's read alone %.2f ms (ratio %.2f, limit %.1f)"
                .format(W, H, file.length() shr 10, reload, RUNS, WARM, reload * 6, read, reload / read, LIMIT),
        )
        src.recycle()
    }

    private companion object {
        const val W = 1080
        const val H = 2400

        /** The reload is at most this many times the codec's read of the same file... */
        const val LIMIT = 1.5

        /** ...plus this (desktop ms, scaled on CI). */
        const val SLACK_MS = 2.0
        const val RUNS = 15
        const val WARM = 3
    }
}
