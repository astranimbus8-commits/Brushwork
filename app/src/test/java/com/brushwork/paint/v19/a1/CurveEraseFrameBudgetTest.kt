package com.brushwork.paint.v19.a1

import android.os.Looper
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.VectorPreview
import com.brushwork.paint.v19.a1.A1Tools.curveTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * v1.9 R11 (a) (A1 review, the T606 hazard of steps 6 and 7): a frame of a curve tool's live erase
 * on a raster layer while one point's thickness changes. A plain line of uniform width erases
 * with the path itself; a fill or a varying thickness with two rotation copies is one region
 * united with PathOps every frame (the line's outline, the fill, each copy), which the paint
 * preview never builds. The costliest kind (varying thickness and copies) on a closed Polyline of
 * 50 points stays within 4 ms on the desktop JVM (x 4 on CI); the four kinds at 500 points are
 * printed for the device row (the T606 is about 6 x slower than the desktop).
 */
@RunWith(RobolectricTestRunner::class)
@Category(Timing::class)
class CurveEraseFrameBudgetTest {

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun median(v: DoubleArray): Double = v.sorted().let { it[it.size / 2] }

    /** A closed Polyline of [n] points round the document's middle, on a red raster layer, with two rotation copies. */
    private fun polyline(n: Int, fill: Boolean, varying: Boolean): Pair<EditorController, CurveTool> {
        val c = A1Tools.controller(vector = false)
        c.doc.activeLayer.bitmap.eraseColor(0xFFD02020.toInt())
        c.updateSymmetry(SymmetrySettings(type = SymmetryType.ROTATION, divisions = 3))
        val t = c.curveTool(ToolId.POLYLINE)
        t.update { it.copy(stroke = CurveStroke.PLAIN, useBrushSize = false, plainWidth = 6f, fill = fill, closed = true) }
        for (i in 0 until n) {
            val a = 2.0 * PI * i / n
            val r = 70f + 12f * sin(i * 0.4f)
            assertTrue(t.addAnchor(Vec2(A1Tools.W / 2f + r * cos(a).toFloat(), A1Tools.H / 2f + r * sin(a).toFloat())))
        }
        t.deselect()
        assertEquals(n, t.pointCount)
        assertEquals("two copies", 3, t.copyMapsNow.size)
        if (varying) t.setWidth(0, 2f)
        t.setErase(true)
        assertTrue("the erase shows live", c.renderOverride is VectorPreview)
        return c to t
    }

    /**
     * The median of [count] frames after a warm-up: [varying], each a new thickness at a new point
     * (an unchanged value is no frame); else a point moved half a pixel (the width stays uniform).
     */
    private fun frames(t: CurveTool, varying: Boolean, count: Int = 15, warm: Int = 5): Double {
        val n = t.pointCount
        var frame = 0
        val ms = DoubleArray(count)
        for (k in 0 until warm + count) {
            val f = frame++
            val t0 = System.nanoTime()
            val i = 1 + f % (n - 1)
            if (varying) t.setWidth(i, 1f + 0.01f * (f % 100 + 1)) else t.moveAnchor(i, t.pointAt(i).let { Vec2(it.x + 0.5f, it.y) })
            idle()
            if (k >= warm) ms[k - warm] = (System.nanoTime() - t0) / 1e6
        }
        return median(ms)
    }

    @Test
    fun anEraseFrameOfTheCostliestKindAt50PointsAndThe500PointRows() {
        val ms = PerfBudget.measuredWithin(PerfBudget.ms(4.0), what = "50 points, varying thickness, two copies: an erase frame") {
            val (_, t) = polyline(50, fill = false, varying = true)
            frames(t, varying = true).also { t.discard() }
        }
        println("50 points, varying thickness, two copies: an erase frame %.3f ms (median)".format(ms))
        for (fill in listOf(false, true)) for (varying in listOf(false, true)) {
            val (_, t) = polyline(500, fill, varying)
            println("500 points, fill $fill, varying thickness $varying, two copies: an erase frame %.3f ms (median)".format(frames(t, varying)))
            t.discard()
        }
    }
}
