package com.brushwork.paint.tools.vector.spline

import android.graphics.Bitmap
import android.graphics.Canvas
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.controller
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tap
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tool
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.6 §3.2a / §3.3: what the Path tool draws over the canvas. Control points are hollow circles
 * of 12 dp, the selected one filled orange (#FFA000), the control polygon a dashed grey line,
 * and "Handle size" scales the circles (and the Curve tool's handles).
 */
@RunWith(RobolectricTestRunner::class)
class PathOverlayRobolectricTest {

    private fun alpha(c: Int) = c ushr 24
    private fun red(c: Int) = (c shr 16) and 0xFF
    private fun green(c: Int) = (c shr 8) and 0xFF
    private fun blue(c: Int) = c and 0xFF

    @Test
    fun controlPointsPolygonAndHandleSize() {
        val c = controller()
        val t = c.tool(ToolId.PATH)
        c.tap(60f, 220f)
        c.tap(200f, 40f)
        c.tap(340f, 220f)
        t.select(0)
        fun draw(): Bitmap {
            val b = Bitmap.createBitmap(CurveToolTestSupport.W, CurveToolTestSupport.H, Bitmap.Config.ARGB_8888)
            t.drawOverlay(Canvas(b), c.viewTransform)
            return b
        }
        val dp = c.viewTransform.density
        var b = draw()
        // The middle control point (off the curve; the polygon meets there): a hollow ring of radius 6 dp.
        // (3 dp in: inside the ring's dark rim, between the two polygon lines meeting there.)
        val inside = b.getPixel(200, 40 + Math.round(3 * dp))
        assertTrue("hollow inside ${Integer.toHexString(inside)}", alpha(inside) < 100)
        val ring = b.getPixel(200 + Math.round(6 * dp), 40)
        assertTrue("white ring ${Integer.toHexString(ring)}", alpha(ring) > 150 && red(ring) > 150)
        // The selected first point is filled orange.
        val sel = b.getPixel(60, 220)
        assertTrue("orange ${Integer.toHexString(sel)}", red(sel) > 200 && green(sel) in 110..200 && blue(sel) < 90)
        // The dashed control polygon from the first point to the second (the curve keeps away from
        // it past 40 %): some of its pixels are painted, not all.
        var painted = 0
        for (k in 40..90) {
            val x = 60 + (200 - 60) * k / 100
            val y = 220 + (40 - 220) * k / 100
            if (alpha(b.getPixel(x, y)) > 60) painted++
        }
        assertTrue("dashed: $painted of 51", painted in 10..45)
        // Handle size 200 %: the ring is twice as far out.
        t.setHandleSize(2f)
        b = draw()
        assertTrue(alpha(b.getPixel(200 + Math.round(6 * dp), 40)) < 60)
        assertTrue(alpha(b.getPixel(200 + Math.round(12 * dp), 40)) > 150)
    }
}
