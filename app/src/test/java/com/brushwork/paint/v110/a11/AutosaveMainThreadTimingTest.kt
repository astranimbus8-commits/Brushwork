package com.brushwork.paint.v110.a11

import android.os.Looper
import com.brushwork.paint.BrushworkApp
import com.brushwork.paint.EditorSession
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.budget.LedgerCategory
import com.brushwork.paint.engine.tiles.FlatPixels
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.model.GridSettings
import com.brushwork.paint.storage.SaveThumbnail
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeSwapStore
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/**
 * v1.10 (A11, design 2.17, finding 54): the main-thread part of an autosave of a FULL artwork of
 * 500 swapped layers stays within PC 2.7 ms (the T606's 16 ms over the x 6 proxy; x 4 on CI): the
 * median of 15 saves after 3 warm-ups of the editor's `save()` with the active (FLAT) layer
 * changed, counting only the time main runs (the snapshots and pins of the thumbnail, the layer
 * entries, `project.json`'s fields and the release), never its waits on the save writer, which
 * renders the thumbnail and encodes. The proxy is 512 x 512 (four tiles a layer; new test
 * documents stay at most 512 x 512); the swapped tiles are swapped out by the test, as A10 would.
 * Thread CPU time is not used: Windows counts it in 15.6 ms ticks.
 */
@RunWith(RobolectricTestRunner::class)
@Category(Timing::class)
class AutosaveMainThreadTimingTest {
    @get:Rule
    val engine = EngineRule(EngineLevel.FULL)

    private val side = 512

    private fun median(v: DoubleArray): Double = v.sorted().let { it[it.size / 2] }

    /**
     * Runs [block] to its end on this (main) thread, every resumption included, and returns the
     * wall time this thread spent running it (ms): its waits on other dispatchers are not counted.
     */
    private fun mainThreadMs(block: suspend () -> Unit): Double {
        val queue = LinkedBlockingQueue<Runnable>()
        val here = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                queue.put(block)
            }
        }
        var failure: Throwable? = null
        val job = CoroutineScope(here + SupervisorJob()).launch {
            try {
                block()
            } catch (e: Throwable) {
                failure = e
            }
        }
        var busy = 0L
        while (true) {
            val r = queue.poll(2, TimeUnit.MILLISECONDS)
            if (r == null) {
                if (job.isCompleted) break
                continue
            }
            val t0 = System.nanoTime()
            r.run()
            busy += System.nanoTime() - t0
        }
        failure?.let { throw it }
        return busy / 1e6
    }

    @Test
    fun autosave500SwappedLayersMainThreadPart() {
        // 500 painted layers (eight patterns, copied), the active one at the top.
        A11Kit.freshRepo()
        val templates = File(RuntimeEnvironment.getApplication().cacheDir, "autosave-templates").apply { mkdirs() }
        val patterns = List(8) { k -> File(templates, "p$k.bin").also { A11Kit.writeRows(it, side, side, A11Kit.PaintedRows(side, side, seed = 700L + k)::fill) } }
        A11Kit.writeProject(ID, side, side, List(LAYERS) { i -> { f: File -> patterns[i % patterns.size].copyTo(f, overwrite = true) } }, active = LAYERS - 1)
        templates.deleteRecursively()

        val app = RuntimeEnvironment.getApplication() as BrushworkApp
        val session = EditorSession(app, ID)
        pumpUntil("the editor") { session.state is EditorSession.State.Ready }
        val c = (session.state as EditorSession.State.Ready).controller
        val doc = c.doc
        val active = doc.layers[LAYERS - 1]
        assertTrue("the active layer is FLAT", active.pixels is FlatPixels)
        assertTrue("the others are tiles", doc.layers.count { it.pixels is TiledPixels } == LAYERS - 1)

        val swap = FakeSwapStore(capacityBytes = 4L shl 30)
        EngineConfig.swapStore = swap
        val ledger = EngineConfig.budget.ledger
        for (l in doc.layers) {
            val s = l.pixels as? TiledPixels ?: continue
            for (t in s.tiles) {
                if (t == null || t.solidColor != null) continue
                val b = t.bitmap
                swap.swapOut(t)
                b?.recycle()
                ledger.sub(LedgerCategory.TILES, t.bytes.toLong())
            }
        }

        val main = Thread.currentThread()
        var grid = false
        fun saves(count: Int = 15, warm: Int = 3): Double {
            val ms = DoubleArray(count)
            for (k in 0 until warm + count) {
                active.markChanged()
                grid = !grid
                c.updateGrid(GridSettings(enabled = grid))
                SaveThumbnail.renderThread = null
                val t = mainThreadMs { session.save() }
                assertTrue("saved", active.savedVersion == active.contentVersion)
                assertNotSame("the thumbnail was rendered off main", main, SaveThumbnail.renderThread ?: main.also { error("no snapshot thumbnail") })
                if (k >= warm) ms[k - warm] = t
            }
            return median(ms)
        }
        try {
            val ms = PerfBudget.measuredWithin(PerfBudget.ms(BUDGET_MS), what = "autosave, 500 swapped layers, main-thread part") { saves() }
            println("v110 A11 AutosaveMainThreadTimingTest: autosave of 500 swapped 512 x 512 layers, main-thread part: median %.3f ms of 15 after 3 warm-ups (x 6 = %.1f ms; device budget 16 ms)".format(ms, ms * 6))
        } finally {
            var closed = false
            session.close { closed = true }
            pumpUntil("the session to close") { closed }
        }
    }

    private fun pumpUntil(what: String, done: () -> Boolean) {
        var n = 0
        while (!done() && n++ < 6000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            Thread.sleep(2)
        }
        assertTrue("timed out waiting for $what", done())
    }

    private companion object {
        const val ID = "autosave500"
        const val LAYERS = 500
        const val BUDGET_MS = 2.7
    }
}
