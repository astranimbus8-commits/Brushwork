package com.brushwork.paint.tools.clone

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import com.brushwork.paint.EditorController
import com.brushwork.paint.assist.SymmetryGuides
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.DisplayTiles
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.vector.ObjectCopies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.cos
import kotlin.math.sin

/**
 * v1.8 D (design §6.3 Q2–Q4, §5.1 D): the clone stamp's symmetry frame budgets on the JVM
 * (Robolectric NATIVE). A frame is two touch events (24 px of travel) and the render of the
 * display tiles they dirtied, which is where the copies cost: one coverage buffer, drawn once per
 * map. The canvas is 512 px with 128 px display tiles (the T606's 2048 px canvas has 512 px
 * tiles: the same tiles per canvas). The median frame after a warm-up stroke is asserted:
 * Rotation 8 with a 64 px brush ≤ 16 ms (Q2); an Array 4 × 8 lattice (32 copies) with a 50 px
 * brush ≤ 33 ms and its commit ≤ 300 ms (Q3); each copy no dearer than 1.5 × the plain stroke's
 * frame; the worst overlap (a Rotation 32 circle) ≤ 66 ms; the guides overlay within an order
 * of magnitude of its desktop time. The T606's own times are device checks.
 */
@RunWith(RobolectricTestRunner::class)
@Category(Timing::class)
class SymmetryClonePerfTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val size = 512
    private var display: DisplayTiles? = null

    private fun setup(brushPx: Float, ruler: SymmetrySettings): EditorController {
        val c = CloneMirrorKit.setup(scope, size, size, brushPx)
        // A busy picture on the bottom layer, painted over the top one (This layer samples it).
        val bg = c.doc.layers[0].bitmap
        val px = IntArray(size * size) { i -> (0xFF shl 24) or (((i % size) / 2) shl 16) or (((i / size) / 2) shl 8) or 0x40 }
        bg.setPixels(px, 0, size, 0, 0, size, size)
        c.activeLayer.bitmap.setPixels(px, 0, size, 0, 0, size, size)
        // The display tiles the frames render: 128 px, told about every invalidation.
        val screen = DisplayTiles(size, size, tileSize = 128)
        c.tiles.invalidationHook = { r -> screen.invalidate(r) }
        screen.update(c.compositor)
        display = screen
        c.updateSymmetry(ruler)
        CloneMirrorKit.tool(c).setSource(Vec2(size / 2f, size / 2f))
        return c
    }

    private class Run(val medianMs: Double, val worstMs: Double, val commitMs: Double)

    /** Where a stroke is after [travel] px: an arc of radius 150 about the centre. */
    private fun arc(travel: Float): Pair<Float, Float> {
        val r = 150f
        return (size / 2f + r * cos(travel / r)) to (size / 2f + r * sin(travel / r))
    }

    /**
     * A circle 120 px round inside the lattice cell (128..256, 192..256), round and round: a
     * stroke of 30 frames ends where it started, away from the ⊕ (Aligned moves it along).
     */
    private fun inCell(travel: Float): Pair<Float, Float> {
        val r = (60.0 / Math.PI).toFloat()
        return (192f + r * cos(travel / r)) to (224f + r * sin(travel / r))
    }

    /**
     * Median and worst frame and the commit time of a [frames]-frame stroke along [path], after a
     * warm-up stroke; [copies] is the ruler's number of copies on the canvas.
     */
    private fun frames(ruler: SymmetrySettings, copies: Int, brushPx: Float, path: (Float) -> Pair<Float, Float>, frames: Int = 30): Run {
        val c = setup(brushPx, ruler)
        var ms = 0L
        fun at(travel: Float): ToolPoint {
            val (x, y) = path(travel)
            return ToolPoint(x, y, 1f, ms)
        }
        val start = at(0f)
        val maps = ObjectCopies.mapsAt(c, start.x, start.y)
        assertEquals("copies", copies, if (ruler.type == SymmetryType.OFF) 0 else CloneTool.copiesOnCanvas(maps, start.x, start.y, size, size))
        val times = ArrayList<Double>()
        var commit = 0.0
        for (pass in 0 until 2) {
            times.clear()
            var travel = 0f
            c.pointerDown(at(travel))
            display!!.update(c.compositor)
            repeat(frames) {
                val t0 = System.nanoTime()
                travel += 12f; ms += 8; c.pointerMove(at(travel))
                travel += 12f; ms += 8; c.pointerMove(at(travel))
                display!!.update(c.compositor)
                times += (System.nanoTime() - t0) / 1e6
            }
            val t0 = System.nanoTime()
            c.pointerUp(at(travel))
            commit = (System.nanoTime() - t0) / 1e6
            display!!.update(c.compositor)
        }
        assertEquals("two strokes, two steps", 2, c.undoManager.undoCount)
        val sorted = times.sorted()
        return Run(sorted[sorted.size / 2], sorted.last(), commit)
    }

    private fun report(what: String, run: Run, budgetMs: Double) = println(
        "v18 D clone symmetry budget: $what: median ${"%.2f".format(run.medianMs)} ms, worst ${"%.2f".format(run.worstMs)} ms per frame " +
            "(budget $budgetMs ms), commit ${"%.1f".format(run.commitMs)} ms",
    )

    @Test
    fun rotation8With64PxBrushStaysWithinAFrame() {
        // §6.3 Q2.
        PerfBudget.until(
            what = "clone, Rotation 8, 64 px brush",
            measure = { frames(SymmetrySettings(), 0, 64f, ::arc) to frames(SymmetrySettings(type = SymmetryType.ROTATION, divisions = 8), 8, 64f, ::arc) },
        ) { (plain, rotation) ->
            report("Rotation 8, 64 px (no symmetry: ${"%.2f".format(plain.medianMs)} ms)", rotation, 16.0)
            when {
                rotation.medianMs > PerfBudget.ms(16.0) -> "median frame ${rotation.medianMs} ms"
                // Each copy costs about what the original's composite costs, no more.
                rotation.medianMs > 1.5 * 8 * plain.medianMs + PerfBudget.ms(1.0) -> "${rotation.medianMs} ms vs 8 × ${plain.medianMs} ms"
                else -> null
            }
        }
    }

    @Test
    fun anArray4By8LatticeWith50PxBrushStaysWithinTwoFramesAndCommitsQuickly() {
        // §6.3 Q3: 4 × 8 cells of 128 × 64 px fill the canvas (32 copies; the margin ring's maps
        // paint nothing for a stroke inside its cell).
        val lattice = SymmetrySettings(type = SymmetryType.ARRAY, spacingX = 128f, spacingY = 64f)
        PerfBudget.until(
            what = "clone, Array 4 × 8, 50 px brush",
            measure = { frames(SymmetrySettings(), 0, 50f, ::inCell) to frames(lattice, 32, 50f, ::inCell) },
        ) { (plain, many) ->
            report("Array 4 × 8, 50 px (no symmetry: ${"%.2f".format(plain.medianMs)} ms)", many, 33.0)
            when {
                many.medianMs > PerfBudget.ms(33.0) -> "median frame ${many.medianMs} ms"
                many.medianMs > 1.5 * 32 * plain.medianMs + PerfBudget.ms(1.0) -> "${many.medianMs} ms vs 32 × ${plain.medianMs} ms"
                many.commitMs > PerfBudget.ms(300.0) -> "commit ${many.commitMs} ms"
                else -> null
            }
        }
    }

    @Test
    fun aRotation32CircleWhoseCopiesOverlapStaysWithinFourFrames() {
        // The worst case for one buffer drawn n times: an arc about the centre under Rotation 32
        // lays every copy's trail over its neighbours' (copies 29 px apart on the ring), so a
        // dirty spot is redrawn by up to 25 copies. Guards against redrawing every copy over
        // every dirty tile (n², about 140 ms per frame before the copies kept their own cells).
        PerfBudget.until(
            what = "clone, Rotation 32 circle, 50 px brush",
            measure = { frames(SymmetrySettings(type = SymmetryType.ROTATION, divisions = 32), 32, 50f, ::arc) },
        ) { ring ->
            report("Rotation 32 circle, 50 px", ring, 66.0)
            when {
                ring.medianMs > PerfBudget.ms(66.0) -> "median frame ${ring.medianMs} ms"
                ring.commitMs > PerfBudget.ms(300.0) -> "commit ${ring.commitMs} ms"
                else -> null
            }
        }
    }

    @Test
    fun theGuidesOverlayIsASmallPartOfAFrame() {
        // §6.3: the guides overlay ≤ 0.2 ms on the T606 (a device check, on its GPU canvas). Here the
        // busiest guides (Kaleidoscope 32: 32 dashed axes) are drawn in software onto a phone-sized
        // bitmap; about 4 ms on the developer desktop, the guard catches an order of magnitude.
        val c = setup(10f, SymmetrySettings(type = SymmetryType.KALEIDOSCOPE, divisions = 32))
        c.selectTool(ToolId.CLONE)
        c.viewTransform.set(Matrix().apply { setScale(0.75f, 0.75f) })
        val screen = Bitmap.createBitmap(392, 512, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(screen)
        PerfBudget.measuredWithin(PerfBudget.ms(12.0), what = "guides overlay, Kaleidoscope 32") {
            repeat(20) { SymmetryGuides.drawFor(canvas, c.viewTransform, c.doc, ToolId.CLONE) }
            val times = DoubleArray(101) { PerfBudget.timeMs { SymmetryGuides.drawFor(canvas, c.viewTransform, c.doc, ToolId.CLONE) } }
            times.sort()
            println("v18 D guides overlay (Kaleidoscope 32): median ${"%.3f".format(times[50])} ms")
            times[50]
        }
    }
}
