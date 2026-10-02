package com.brushwork.paint.tools.vector.spline

import android.graphics.RectF
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.exchange.VectorFormat
import com.brushwork.paint.exchange.export.ExportOptions
import com.brushwork.paint.exchange.export.ExportSceneBuilder
import com.brushwork.paint.exchange.export.SvgWriter
import com.brushwork.paint.exchange.export.TextSource
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.controller
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tool
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VSpline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * v1.6 §3.2a/d: the quick starts. The Capsule (the user's Blender example: 12 points, cyclic,
 * order 4, 3 : 1) stays within 1 % of the height of an exact stadium, its straight sides are
 * exactly straight, it fits 60 % of the visible area; the Circle within 0.5 % of a circle. The
 * committed capsule exports to SVG as a closed path of M, C and Z commands (plus L for the two
 * exactly straight sides).
 */
@RunWith(RobolectricTestRunner::class)
class CapsulePresetTest {

    /** Distance from [q] to the outline of a stadium of [height] (3 × as wide) centred on [c]. */
    private fun stadiumDistance(q: Vec2, c: Vec2, height: Float): Float {
        val r = height / 2f
        val half = height // straight part from −h to +h, caps around (±h, 0)
        val x = abs(q.x - c.x)
        val y = q.y - c.y
        return if (x <= half) abs(abs(y) - r) else abs(hypot(x - half, y) - r)
    }

    private fun curvePoints(s: VSpline, n: Int = 4000): List<Vec2> = SplineTestSupport.splineSamples(s, n)

    @Test
    fun theCapsuleIsWithinOnePercentOfAStadium() {
        for (h in listOf(10f, 120f, 900f)) {
            val c = Vec2(500f, 400f)
            val s = VSpline(SplinePresets.capsule(c, h), order = 4, cyclic = true)
            assertEquals(12, s.points.size)
            var worst = 0f
            for (q in curvePoints(s)) worst = max(worst, stadiumDistance(q, c, h))
            assertTrue("height $h: worst ${worst / h * 100f} % of the height", worst <= 0.01f * h)
            // And the converted Bézier form too (what is drawn and exported).
            val poly = SplineTestSupport.polyline(SplineBezier.toSubpath(s))
            var drawn = 0f
            for (q in poly) drawn = max(drawn, stadiumDistance(q, c, h))
            assertTrue("drawn: ${drawn / h * 100f} %", drawn <= 0.01f * h)
            // The middle of each long side is exactly straight (4 collinear control points).
            val top = curvePoints(s).filter { abs(it.x - c.x) < 0.25f * h && it.y < c.y }
            assertTrue(top.isNotEmpty())
            for (q in top) assertEquals(c.y - h / 2f, q.y, 1e-3f * h)
        }
    }

    @Test
    fun theCircleIsWithinHalfAPercent() {
        val c = Vec2(200f, 150f)
        for (r in listOf(5f, 100f, 1000f)) {
            val s = VSpline(SplinePresets.circle(c, r), order = 4, cyclic = true)
            assertEquals(8, s.points.size)
            for (q in curvePoints(s)) assertEquals(r, q.distanceTo(c), 0.005f * r)
        }
    }

    @Test
    fun quickStartsFitSixtyPercentOfTheVisibleArea() {
        val area = RectF(100f, 50f, 700f, 450f) // 600 × 400
        val (cc, r) = SplinePresets.circleIn(area)
        assertEquals(Vec2(400f, 250f), cc)
        assertEquals(0.6f * 400f / 2f, r, 1e-3f)
        val (kc, h) = SplinePresets.capsuleIn(area)
        assertEquals(Vec2(400f, 250f), kc)
        // 60 % of the width (360) is narrower than 3 × 60 % of the height: the width decides.
        assertEquals(360f / 3f, h, 1e-3f)
        val tall = SplinePresets.capsuleIn(RectF(0f, 0f, 1000f, 100f))
        assertEquals("a flat area: the height decides", 60f, tall.second, 1e-3f)
    }

    @Test
    fun theCommittedCapsuleExportsToSvgAsAClosedCubicPath() {
        val c = controller()
        val t = c.tool(ToolId.PATH)
        assertTrue(t.startShape(CurveTool.PathShape.CAPSULE, RectF(0f, 0f, CurveToolTestSupport.W.toFloat(), CurveToolTestSupport.H.toFloat())))
        t.commit()
        val p = c.activeLayer.vector!!.objects.single() as VPath
        assertTrue(SplineBezier.matches(p))
        val scene = runBlocking { ExportSceneBuilder(c, ExportOptions(VectorFormat.SVG), TextSource.Default, Dispatchers.Unconfined, Dispatchers.Unconfined).build() }
        val out = ByteArrayOutputStream()
        runBlocking { SvgWriter(scene).write(out) }
        val dom = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(ByteArrayInputStream(out.toByteArray()))
        val paths = dom.getElementsByTagNameNS("http://www.w3.org/2000/svg", "path")
        val ds = (0 until paths.length).map { (paths.item(it) as org.w3c.dom.Element).getAttribute("d") }.filter { it.isNotBlank() }
        assertEquals("one path: $ds", 1, ds.size)
        val commands = ds[0].filter { it.isLetter() && it != 'e' && it != 'E' }
        assertTrue("only M, C, L and Z: $commands", commands.all { it in "MCLZ" })
        assertEquals("one subpath", 1, commands.count { it == 'M' })
        assertTrue("closed", commands.endsWith("Z"))
        assertTrue("curved ends are cubics", commands.count { it == 'C' } >= 8)
        assertTrue("only the two straight sides are lines", commands.count { it == 'L' } <= 2)
    }
}
