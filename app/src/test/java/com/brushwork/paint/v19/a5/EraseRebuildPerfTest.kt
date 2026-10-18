package com.brushwork.paint.v19.a5

import android.graphics.Canvas
import android.graphics.Matrix
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.draw.DoomPreview
import com.brushwork.paint.vector.draw.VectorEraseMode
import com.brushwork.paint.vector.draw.VectorEraserModes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.9 (R11 c, A5 step 9): the vector eraser's preview on a layer of 2000 objects. A frame of a
 * stroke (the moves since the last frame, each querying [com.brushwork.paint.vector.geom.ObjectIndex]
 * per capsule, then the frame's one mask rebuild where they changed it and the layer drawn through
 * it) takes at most 8 ms, the median of a stroke's frames (x4 on CI); the mask is rebuilt at most
 * once per frame, in every mode. The objects are 1600 short strokes and 400 small filled paths on
 * a 512 × 512 document; each stroke crosses the layer and is cancelled (nothing lands).
 */
@RunWith(RobolectricTestRunner::class)
@Category(Timing::class)
class EraseRebuildPerfTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val size = 512

    private fun setup(): EditorController {
        val app = RuntimeEnvironment.getApplication()
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", size, size)
        doc.layers += Layer(doc.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(size, size)).also { it.vector = VectorContent.EMPTY }
        doc.activeLayerIndex = 0
        return EditorController(app, doc, scope, settings).also {
            it.viewTransform.set(Matrix())
            it.tools
        }
    }

    private val pen = BrushLibrary.defaultBrush.copy(size = 3f)

    /** 2000 objects in a 50 × 40 grid: a short stroke in four cells of five, a small filled square in the fifth. */
    private fun objects(): List<VObject> = List(COLUMNS * ROWS) { i ->
        val x = 4f + (i % COLUMNS) * 10f
        val y = 4f + (i / COLUMNS) * 12.5f
        if (i % 5 == 4) {
            VPath(0, subpaths = listOf(VSubpath(listOf(VAnchor(x, y), VAnchor(x + 6f, y), VAnchor(x + 6f, y + 6f), VAnchor(x, y + 6f)), closed = true)), fill = VPaint.Solid(0xFF3070C0.toInt()))
        } else {
            VStroke(0, preset = pen, color = 0xFF203040.toInt(), seed = i.toLong(), stylus = false, points = PackedPoints(floatArrayOf(x, x + 2f, x + 4f, x + 6f), floatArrayOf(y, y + 3f, y + 4f, y + 6f), FloatArray(4) { 1f }))
        }
    }

    /**
     * Frame times (ms) of one stroke of the eraser in [mode] across the layer at height [y]
     * ([MOVES_PER_FRAME] moves of 4 px a frame), cancelled at the end; checks one rebuild a frame at most.
     */
    private fun stroke(c: EditorController, mode: VectorEraseMode, y: Float): List<Double> {
        VectorEraserModes.setMode(c, mode)
        val target = BitmapUtils.createLayerBitmap(size, size)
        val canvas = Canvas(target)
        val times = ArrayList<Double>()
        var x = 2f
        c.pointerDown(ToolPoint(x, y))
        var frames = 0
        while (x < size - 8f) {
            times += PerfBudget.timeMs {
                repeat(MOVES_PER_FRAME) {
                    x += 4f
                    c.pointerMove(ToolPoint(x, y + (x % 24f) - 12f))
                }
                (c.renderOverride as DoomPreview).drawContent(canvas)
            }
            frames++
            val rebuilds = (c.renderOverride as DoomPreview).rebuilds
            assertTrue("$mode: $rebuilds rebuilds in $frames frames", rebuilds <= frames)
        }
        c.pointerCancel()
        return times
    }

    private fun median(xs: List<Double>): Double = xs.sorted()[xs.size / 2]

    @Test
    fun aFrameRebuildsWithin8MsWith2000Objects() {
        val c = setup()
        val layer = c.doc.layers[0]
        c.vectors.addObjects(layer, objects(), "Add")
        assertEquals(COLUMNS * ROWS, layer.vector!!.objects.size)
        c.selectTool(ToolId.ERASER)
        c.eraser = BrushLibrary.defaultEraser.copy(size = 16f)
        // A warm-up stroke (JIT), then the guard; every mode's frames are within the budget.
        stroke(c, VectorEraseMode.OBJECT, 100f)
        for (mode in VectorEraseMode.entries) {
            val ms = PerfBudget.measuredWithin(PerfBudget.ms(FRAME_MS), what = "a $mode eraser frame with 2000 objects (median)") { attempt ->
                median(stroke(c, mode, 60f + 90f * attempt))
            }
            println("v19 A5 erase frame, $mode, 2000 objects: median ${"%.2f".format(ms)} ms (budget ${PerfBudget.ms(FRAME_MS)})")
        }
        assertEquals("nothing landed", COLUMNS * ROWS, layer.vector!!.objects.size)
    }

    private companion object {
        const val COLUMNS = 50
        const val ROWS = 40
        const val MOVES_PER_FRAME = 3
        const val FRAME_MS = 8.0
    }
}
