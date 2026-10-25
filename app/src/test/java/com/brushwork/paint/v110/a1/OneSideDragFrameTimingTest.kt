package com.brushwork.paint.v110.a1

import android.graphics.Canvas
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.v110.a1.A1Kit.turn
import com.brushwork.paint.v110.a1.A1Kit.withMaps
import com.brushwork.paint.vector.copies.CopyPick
import com.brushwork.paint.vector.lift.CopyLift
import com.brushwork.paint.vector.lift.VectorLift
import com.brushwork.paint.vector.select.ObjectTestKit
import com.brushwork.paint.vector.select.vec
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 (R1 (c)): a frame of dragging one picked side of an object with 8 copies (a Rotation of
 * 8): the move, then the Transform preview drawn as the canvas draws it (the hole, "the object
 * without map k", and the one side's floating bitmap). The same cost as v1.9's lift of one
 * object: within PC 5.5 ms (the T606's 33 ms over the x 6 proxy; x 4 on CI), the median of 15
 * frames after 3 warm-ups. The frame of all 8 sides lifted (v1.9's lift) is printed beside it.
 */
@RunWith(RobolectricTestRunner::class)
@Category(Timing::class)
class OneSideDragFrameTimingTest {
    private val kit = ObjectTestKit()

    @After
    fun tearDown() = kit.close()

    private fun median(v: DoubleArray): Double = v.sorted().let { it[it.size / 2] }

    private fun EditorController.tap(x: Float, y: Float) {
        pointerDown(ToolPoint(x, y))
        pointerUp(ToolPoint(x, y))
    }

    /** The median ms of [count] drag frames after [warm] warm-ups: a half-pixel move, then the preview drawn into [canvas]. */
    private fun frames(c: EditorController, tool: TransformTool, canvas: Canvas, count: Int = 15, warm: Int = 3): Double {
        val ms = DoubleArray(count)
        var dir = 1f
        for (k in 0 until warm + count) {
            if (k % 10 == 0) dir = -dir
            val t0 = System.nanoTime()
            tool.moveBy(0.5f * dir, 0.25f * dir)
            kit.idle()
            canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)
            assertTrue("the preview draws", c.renderOverride!!.drawContent(canvas))
            if (k >= warm) ms[k - warm] = (System.nanoTime() - t0) / 1e6
        }
        return median(ms)
    }

    @Test
    fun aDragFrameOfOneSideOf8Copies() {
        val c = kit.controller()
        val maps = List(8) { turn(45.0 * it) }
        c.vectors.addObjects(c.vec, listOf(withMaps(kit.box(300f, 170f, 360f, 214f), maps)), "Add")
        assertTrue(kit.idleUntil { !c.vectors.isRendering })
        var tool = kit.transform(c)
        assertTrue(kit.idleUntil { c.renderOverride != null && !c.vectors.isRendering && !tool.isPreparing })
        val canvas = Canvas(BitmapUtils.createLayerBitmap(kit.w, kit.h))

        // v1.9's lift of the object (all 8 sides, the tool's activation lifts them), for the printout.
        val allMs = frames(c, tool, canvas)
        tool.discard()

        // The side, with a new tap cycle (the tool chosen again).
        c.selectTool(ToolId.BRUSH)
        tool = kit.transform(c)
        assertTrue(kit.idleUntil { !c.vectors.isRendering && !tool.isPreparing })
        val b = CopyPick.boundsOf(c.vec.vector!!.objects.single(), 3)
        c.tap(b.centerX(), b.centerY())
        assertTrue(kit.idleUntil { VectorLift.activeLift(c) is CopyLift && VectorLift.activeLift(c)?.side == 3 })
        val ms = PerfBudget.measuredWithin(PerfBudget.ms(5.5), what = "a drag frame of one side of 8 copies") {
            frames(c, tool, canvas)
        }
        assertTrue("still the side", VectorLift.activeLift(c)?.side == 3)
        tool.discard()
        println("v110 A1 OneSideDragFrameTimingTest: one side of 8 copies: median %.3f ms of 15 after 3 warm-ups (x 6 = %.1f ms; device budget 33 ms); all 8 sides lifted: %.3f ms".format(ms, ms * 6, allMs))
    }
}
