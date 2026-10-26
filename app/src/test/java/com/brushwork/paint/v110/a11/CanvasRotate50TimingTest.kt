package com.brushwork.paint.v110.a11

import android.os.Looper
import com.brushwork.paint.BrushworkApp
import com.brushwork.paint.EditorSession
import com.brushwork.paint.engine.CanvasOps
import com.brushwork.paint.engine.CanvasRotation
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
import org.junit.Assert.assertEquals
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

/**
 * v1.10 (A11, 4.4 A11 step 4): a 90° canvas rotation of a FULL artwork of 50 painted 512 x 512
 * layers runs layer by layer (materialize, rotate, park) to its end within the budget: the median
 * of 15 rotations after 3 warm-ups, wall time from the tap to the commit, each followed by an
 * unmeasured undo. Design 2.17 has no row for it; the budget is chosen from the PC measurement
 * (about twice the median), so a regression to whole-artwork work on main shows.
 */
@RunWith(RobolectricTestRunner::class)
@Category(Timing::class)
class CanvasRotate50TimingTest {
    @get:Rule
    val engine = EngineRule(EngineLevel.FULL)

    private val side = 512

    private fun median(v: DoubleArray): Double = v.sorted().let { it[it.size / 2] }

    @Test
    fun rotate50LayersLayerByLayer() {
        A11Kit.freshRepo()
        val templates = File(RuntimeEnvironment.getApplication().cacheDir, "rotate-templates").apply { mkdirs() }
        val patterns = List(8) { k -> File(templates, "p$k.bin").also { A11Kit.writeRows(it, side, side, A11Kit.PaintedRows(side, side, seed = 900L + k)::fill) } }
        A11Kit.writeProject(ID, side, side, List(LAYERS) { i -> { f: File -> patterns[i % patterns.size].copyTo(f, overwrite = true) } }, active = LAYERS - 1)
        templates.deleteRecursively()

        val app = RuntimeEnvironment.getApplication() as BrushworkApp
        val session = EditorSession(app, ID)
        pumpUntil("the editor") { session.state is EditorSession.State.Ready }
        val c = (session.state as EditorSession.State.Ready).controller
        val doc = c.doc
        assertEquals("the others are tiles", LAYERS - 1, doc.layers.count { it.pixels is TiledPixels })

        fun rotations(count: Int = 15, warm: Int = 3): Double {
            val ms = DoubleArray(count)
            for (k in 0 until warm + count) {
                val t0 = System.nanoTime()
                assertTrue("started", CanvasOps.applyRotate(c, CanvasRotation.CW_90))
                pumpUntil("the rotation") { c.busyMessage == null }
                val t = (System.nanoTime() - t0) / 1e6
                assertEquals("rotated", side, doc.width)
                assertEquals("parked", LAYERS - 1, doc.layers.count { it.pixels is TiledPixels })
                c.undo()
                if (k >= warm) ms[k - warm] = t
            }
            return median(ms)
        }
        try {
            val ms = PerfBudget.measuredWithin(PerfBudget.ms(BUDGET_MS), what = "canvas rotation, 50 layers, layer by layer") { rotations() }
            println("v110 A11 CanvasRotate50TimingTest: 90 degree rotation of 50 painted 512 x 512 layers (FULL): median %.1f ms of 15 after 3 warm-ups (x 6 = %.0f ms; budget %.0f ms, chosen)".format(ms, ms * 6, BUDGET_MS))
        } finally {
            var closed = false
            session.close { closed = true }
            pumpUntil("the session to close") { closed }
        }
    }

    private fun pumpUntil(what: String, done: () -> Boolean) {
        var n = 0
        while (!done() && n++ < 6000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
            if (!done()) Thread.sleep(1)
        }
        assertTrue("timed out waiting for $what", done())
    }

    private companion object {
        const val ID = "rotate50"
        const val LAYERS = 50
        const val BUDGET_MS = 2000.0
    }
}
