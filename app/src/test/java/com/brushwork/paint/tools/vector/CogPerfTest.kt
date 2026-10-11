package com.brushwork.paint.tools.vector

import android.graphics.Canvas
import android.graphics.Rect
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.render.VectorLayerRenderer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.8 F (design §3.8 budgets): building a 64-tooth cog (outline with rounded teeth and its hole)
 * takes at most 1 ms, and a frame of the "Number of teeth" slider (the setting changed, the
 * preview rebuilt, the cog drawn on a 512 × 512 layer) at most 8 ms, on a desktop (PerfBudget
 * scales the budgets on CI). Each is the average of a batch after a warm-up.
 */
@RunWith(RobolectricTestRunner::class)
@Category(Timing::class)
class CogPerfTest {
    private val rig = CogRig(512, 512)

    @After
    fun close() = rig.close()

    @Test
    fun aCogOf64TeethBuildsWithinOneMsAndATeethSliderFrameWithinEight() {
        // 1. The geometry: 64 teeth, rounded, with a hole, as the tool builds it (box to document).
        val box = ShapeBox(256f, 256f, 480f, 480f, 10f)
        val params = OutlineParams(corner = CornerStyle.ROUND, cornerRadius = 2f, cog = CogParams(teeth = 64, depth = 0.1f, hole = 0.4f))
        var sink = 0
        repeat(300) { sink += ShapeGeometry.outline(ShapeType.COG, box, params).ops.size }
        PerfBudget.measuredWithin(PerfBudget.ms(1.0), what = "64 teeth") {
            val n = 100
            val t0 = System.nanoTime()
            repeat(n) { sink += ShapeGeometry.outline(ShapeType.COG, box, params).ops.size }
            (System.nanoTime() - t0) / 1e6 / n
        }

        // 2. The teeth slider: every frame sets the teeth, rebuilds the preview and draws the cog.
        val c = rig.controller()
        val tool = rig.cogTool(c, CogParams(hole = 0.3f))
        rig.drag(c, 16f to 16f, 256f to 256f, 496f to 496f)
        val bitmap = BitmapUtils.createLayerBitmap(512, 512)
        val canvas = Canvas(bitmap)
        val whole = Rect(0, 0, 512, 512)
        val tips = TipCache()
        fun frame(teeth: Int) {
            tool.update { it.copy(cogTeeth = teeth) }
            val o = tool.pendingObject()!!
            bitmap.eraseColor(0)
            VectorLayerRenderer.render(canvas, VectorContent.EMPTY.plus(listOf(VShape(0L, shape = o))).first, whole, tips = tips, document = whole)
        }
        for (t in CogParams.MIN_TEETH..CogParams.MAX_TEETH) frame(t)
        PerfBudget.measuredWithin(PerfBudget.ms(8.0), what = "teeth slider frame") {
            val t0 = System.nanoTime()
            var frames = 0
            for (t in CogParams.MAX_TEETH downTo CogParams.MIN_TEETH) { frame(t); frames++ }
            (System.nanoTime() - t0) / 1e6 / frames
        }
        assertEquals(CogParams.MIN_TEETH, tool.pendingObject()!!.cogTeeth)
        assert(sink > 0)
    }
}
