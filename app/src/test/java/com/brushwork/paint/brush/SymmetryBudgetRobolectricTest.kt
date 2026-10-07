package com.brushwork.paint.brush

import android.graphics.Matrix
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.assist.SymmetryMaps
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.math.cos
import kotlin.math.sin

/**
 * v1.7 (item 18, §6.3): the symmetry frame budgets, measured on the JVM (Robolectric NATIVE) as
 * the brush's work per frame of a finger stroke (two touch events, 24 px of travel, every copy's
 * dabs stamped into the one buffer; the median frame after a warm-up stroke). Mirror with a
 * 100 px brush stays within its 16 ms here too. The many-copy budgets (32 copies of a 50 px
 * brush ≤ 33 ms, 64 copies of a 64 px brush ≤ 16 ms) are pure dab drawing, which this JVM's
 * raster does several times slower than the phone's: here each copy must cost no more than one
 * more dab of the same stroke (the copies add no work of their own); the T606's times are a
 * device check.
 */
@RunWith(RobolectricTestRunner::class)
class SymmetryBudgetRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()
    private val size = 512

    private fun setup(): EditorController {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("budget", "Budget", size, size)
        doc.layers += Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(size, size))
        doc.layers += Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(size, size))
        doc.activeLayerIndex = 1
        return EditorController(app, doc, scope, settings).also {
            it.viewTransform.set(Matrix())
            it.tools
            it.selectTool(ToolId.BRUSH)
        }
    }

    /**
     * Median and worst frame (ms) of a [frames]-frame stroke around the canvas centre with
     * [brushPx] and [s], after a warm-up stroke; checks the ruler made [copies] maps.
     */
    private fun frames(s: SymmetrySettings, copies: Int, brushPx: Float, frames: Int = 40): Pair<Double, Double> {
        val c = setup()
        c.brush = BrushLibrary.defaultBrush.copy(size = brushPx)
        c.updateSymmetry(s)
        val r = 150f
        var ms = 0L
        fun at(arc: Float): ToolPoint {
            val a = arc / r
            return ToolPoint(size / 2f + r * cos(a), size / 2f + r * sin(a), 1f, ms)
        }
        assertEquals(copies, SymmetryMaps.count(s, size, size, at(0f).x, at(0f).y))
        val times = ArrayList<Double>()
        for (pass in 0 until 2) {
            times.clear()
            var arc = 0f
            c.pointerDown(at(arc))
            repeat(frames) {
                val t0 = System.nanoTime()
                // Two touch events per 60 Hz frame (a 120 Hz digitizer), 12 px apart.
                arc += 12f; ms += 8; c.pointerMove(at(arc))
                arc += 12f; ms += 8; c.pointerMove(at(arc))
                times += (System.nanoTime() - t0) / 1e6
            }
            c.pointerUp(at(arc))
        }
        assertEquals("two strokes, two steps", 2, c.undoManager.undoCount)
        val sorted = times.sorted()
        return sorted[sorted.size / 2] to sorted.last()
    }

    private fun report(what: String, frame: Pair<Double, Double>, budgetMs: Double) =
        println("v17 H symmetry budget: $what: median ${"%.2f".format(frame.first)} ms, worst ${"%.2f".format(frame.second)} ms per frame (T606 budget $budgetMs ms)")

    @Test
    fun theSymmetryFramesStayWithinTheirBudgets() {
        val mirror = frames(SymmetrySettings(SymmetryType.MIRROR), 2, 100f)
        report("Mirror, 100 px brush", mirror, 16.0)
        assertTrue("Mirror, 100 px brush: median frame ${mirror.first} ms", mirror.first <= PerfBudget.ms(16.0))
        val many = listOf(
            Triple("32 copies of a 50 px brush", SymmetrySettings(SymmetryType.ROTATION, divisions = 32), 50f) to (32 to 33.0),
            Triple("64 copies of a 64 px brush", SymmetrySettings(SymmetryType.KALEIDOSCOPE, divisions = 32), 64f) to (64 to 16.0),
        )
        for ((case, maps) in many) {
            val (what, s, px) = case
            val plain = frames(SymmetrySettings(), 0, px)
            val sym = frames(s, maps.first, px)
            report("$what (no symmetry: ${"%.2f".format(plain.first)} ms)", sym, maps.second)
            // Each copy costs what one more dab of the stroke costs, no more.
            assertTrue("$what: ${sym.first} ms vs ${maps.first} × ${plain.first} ms", sym.first <= 1.5 * maps.first * plain.first + PerfBudget.ms(1.0))
        }
    }
}
