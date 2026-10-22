package com.brushwork.paint.v110.a6

import com.brushwork.paint.tools.vector.JoinStyle
import com.brushwork.paint.tools.vector.LineCapStyle
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.convert.PathToShape
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 A6 (R11 (e)): a converted path renders as it did: the shape's ink lies within 1 px of the
 * path's and the path's within 1 px of the shape's, for Curve, Polyline and Path-tool paths, open
 * and closed, lines, fills, bevel joins (closed: an open line shape joins round) and flat caps,
 * sharp points and tension, and even thickness factors other than 1.
 */
@RunWith(RobolectricTestRunner::class)
class PathToShapeParityTest {
    private val fill = VPaint.Solid(0xFFE04020.toInt())

    private fun assertSameLook(what: String, p: VPath) =
        A6Render.assertWithinOnePx(what, p, PathToShape.convert(p).shape)

    @Test
    fun curvesPolylinesAndSplinesRenderAsBefore() {
        for (closed in listOf(false, true)) {
            assertSameLook("curve closed=$closed", A6Paths.curve(closed))
            assertSameLook("polyline closed=$closed", A6Paths.polyline(closed))
            assertSameLook("spline cyclic=$closed", A6Paths.spline(closed))
        }
        assertSameLook("filled curve", A6Paths.curve(true).copy(fill = fill))
        assertSameLook("fill only", A6Paths.spline(true).copy(fill = fill, stroke = null))
        // (A line shape always joins round, `ShapeOutlines.join`: bevel joins on a closed outline.)
        val wide = VStrokeStyle(color = 0xFF203080.toInt(), width = 14f, cap = LineCapStyle.BUTT, join = JoinStyle.BEVEL)
        assertSameLook("bevel joins", A6Paths.polyline(true).copy(stroke = wide))
        assertSameLook("flat caps", A6Paths.polyline(false).copy(stroke = wide.copy(join = JoinStyle.ROUND)))
        val mixed = VPath(
            0,
            subpaths = listOf(VSubpath(listOf(VAnchor(80f, 80f), VAnchor(200f, 60f, sharp = true), VAnchor(320f, 120f), VAnchor(260f, 240f, sharp = true), VAnchor(100f, 220f)), closed = true)),
            tension = 0.4f,
            fill = fill,
            stroke = VStrokeStyle(color = 0xFF203080.toInt(), width = 6f),
        )
        assertSameLook("sharp points, tension 0.4", mixed)
        val doubled = A6Paths.curve(false).let { p -> p.copy(subpaths = listOf(p.subpaths[0].copy(anchors = p.subpaths[0].anchors.map { it.copy(width = 2f) }))) }
        assertSameLook("thickness factor 2 everywhere", doubled)
    }
}
