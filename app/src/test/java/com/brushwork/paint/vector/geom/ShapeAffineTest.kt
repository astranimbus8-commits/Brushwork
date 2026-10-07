package com.brushwork.paint.vector.geom

import android.graphics.Matrix
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.core.Geometry
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CornerStyle
import com.brushwork.paint.tools.vector.ShapeHandle
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeOutlines
import com.brushwork.paint.tools.vector.ShapePoint
import com.brushwork.paint.tools.vector.ShapeStroke
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.tools.vector.VectorPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VectorOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * v1.7 (design §4.5, F4): every affine map keeps a shape a shape, within 0.01 px of the shape's
 * outline drawn through the map; a regular type under a skew becomes a point shape; an ellipse
 * stays an ellipse (SVD). Only homographies are refused (null).
 */
@RunWith(RobolectricTestRunner::class)
class ShapeAffineTest {

    private fun values(m: Matrix) = FloatArray(9).also { m.getValues(it) }

    private fun map(m: FloatArray, p: Vec2) = Vec2(m[0] * p.x + m[1] * p.y + m[2], m[3] * p.x + m[4] * p.y + m[5])

    private fun shape(type: ShapeType, points: List<ShapePoint>? = null, corner: CornerStyle = CornerStyle.SHARP) = ShapeObject(
        type, cx = 150f, cy = 115f, w = if (type.isLineLike) 150f else 120f, h = if (type.isLineLike) 0f else 70f, rotation = 10f,
        style = ShapeStyle.STROKE_FILL, strokeWidth = 6f, corner = corner, cornerRadius = 12f, sides = 6, points = points,
    )

    private val custom = shape(
        ShapeType.RECTANGLE,
        points = listOf(
            ShapePoint(-0.5f, -0.5f), ShapePoint(0.5f, -0.3f, smooth = true),
            ShapePoint(0.3f, 0.5f), ShapePoint(-0.4f, 0.4f, handleIn = ShapeHandle(0.1f, -0.2f)),
        ),
    )
    private val customLine = shape(ShapeType.LINE, points = listOf(ShapePoint(-0.5f, 0f), ShapePoint(0f, 0.2f, smooth = true), ShapePoint(0.5f, 0f)))

    private val shapes: List<Pair<String, ShapeObject>> =
        ShapeType.entries.map { it.name to shape(it) } + listOf("custom" to custom, "custom line" to customLine)

    private val skew = values(Matrix().apply { setScale(1.4f, 0.75f, 150f, 115f); postSkew(0.2f, 0f, 150f, 115f); postTranslate(4f, 3f) })
    private val docStretch = values(Matrix().apply { setScale(2f, 0.5f, 100f, 100f) })
    private val mirrorStretch = values(Matrix().apply { setScale(-1.5f, 0.8f, 150f, 115f); postRotate(30f, 150f, 115f) })
    private val similarity = values(Matrix().apply { setScale(1.3f, 1.3f, 150f, 115f); postRotate(25f, 150f, 115f); postTranslate(10f, 5f) })

    /** A stretch along the shapes' own axes (rotation 10°): 2 along, 0.5 across, mirrored across. */
    private val ownAxes = values(Matrix().apply { setRotate(-10f, 150f, 115f); postScale(2f, -0.5f, 150f, 115f); postRotate(10f, 150f, 115f) })

    private val affines = listOf("skew" to skew, "doc stretch" to docStretch, "mirror stretch" to mirrorStretch, "similarity" to similarity, "own axes" to ownAxes)

    /** True when every flattened point of [a] lies within [tol] of [b]'s flattened outline, and back. */
    private fun sameOutline(a: VectorPath, b: VectorPath, tol: Float): Pair<Boolean, Float> {
        val pa = a.flatten(0.002f)
        val pb = b.flatten(0.002f)
        fun worst(from: List<com.brushwork.paint.tools.vector.Polyline>, to: List<com.brushwork.paint.tools.vector.Polyline>): Float {
            var w = 0f
            for (pl in from) for (p in pl.points) {
                var best = Float.POSITIVE_INFINITY
                for (ql in to) {
                    val q = ql.points
                    val n = if (ql.closed) q.size else q.size - 1
                    for (i in 0 until n) best = minOf(best, Geometry.distanceToSegment(p, q[i], q[(i + 1) % q.size]))
                    if (q.size == 1) best = minOf(best, p.distanceTo(q[0]))
                }
                w = maxOf(w, best)
            }
            return w
        }
        val e = maxOf(worst(pa, pb), worst(pb, pa))
        return (e <= tol) to e
    }

    /** The largest distance (approximately) of the true mapped ellipse of [s] from the ellipse [t]. */
    private fun ellipseError(s: ShapeObject, m: FloatArray, t: ShapeObject): Float {
        val th = Math.toRadians(s.rotation.toDouble())
        val tt = Math.toRadians(t.rotation.toDouble())
        var worst = 0.0
        for (i in 0 until 360) {
            val u = Math.toRadians(i.toDouble())
            val lx = s.w / 2.0 * cos(u)
            val ly = s.h / 2.0 * sin(u)
            val p = Vec2((s.cx + lx * cos(th) - ly * sin(th)).toFloat(), (s.cy + lx * sin(th) + ly * cos(th)).toFloat())
            val q = map(m, p)
            val dx = q.x - t.cx.toDouble()
            val dy = q.y - t.cy.toDouble()
            val x = dx * cos(tt) + dy * sin(tt)
            val y = -dx * sin(tt) + dy * cos(tt)
            val rx = t.w / 2.0
            val ry = t.h / 2.0
            val r = sqrt(x * x / (rx * rx) + y * y / (ry * ry))
            worst = maxOf(worst, abs(r - 1.0) * minOf(rx, ry))
        }
        return worst.toFloat()
    }

    @Test
    fun everyAffineKeepsEveryShapeWithinAHundredthOfAPixel() {
        for ((mapName, m) in affines) {
            val det = m[0] * m[4] - m[1] * m[3]
            for ((name, s) in shapes) {
                val t = ShapeAffine.mapped(s, m)
                assertNotNull("$name under $mapName", t)
                t!!
                assertEquals("$name under $mapName: stroke × sqrt|det|", 6f * sqrt(abs(det)), t.strokeWidth, 1e-4f)
                assertEquals("$name under $mapName: corner radius × sqrt|det|", 12f * sqrt(abs(det)), t.cornerRadius, 1e-4f)
                if (s.type == ShapeType.ELLIPSE && !ShapeOutlines.isCustom(t)) {
                    val e = ellipseError(s, m, t)
                    assertTrue("$name under $mapName: off by $e px", e <= 0.01f)
                } else {
                    val expected = ShapeOutlines.outline(s).transformed { map(m, it) }
                    val (ok, e) = sameOutline(expected, ShapeOutlines.outline(t), 0.01f)
                    assertTrue("$name under $mapName: off by $e px", ok)
                }
            }
        }
    }

    @Test
    fun regularTypesBecomePointShapesOnlyUnderASkew() {
        for ((name, s) in shapes.filter { it.second.points == null }) {
            val skewed = ShapeAffine.mapped(s, skew)!!
            val ownAxes = ShapeAffine.mapped(s, ownAxes)!!
            when (s.type) {
                ShapeType.RECTANGLE, ShapeType.POLYGON, ShapeType.STAR -> {
                    assertTrue("$name becomes a point shape under a skew", ShapeOutlines.isCustom(skewed))
                    assertEquals(s.type, skewed.type)
                    assertNull("$name stays regular along its own axes", ownAxes.points)
                    assertEquals(240f, ownAxes.w, 1e-3f)
                    assertEquals(35f, ownAxes.h, 1e-3f)
                }
                ShapeType.ELLIPSE -> {
                    assertNull("an ellipse stays an ellipse", skewed.points)
                    assertNull(ownAxes.points)
                }
                ShapeType.LINE, ShapeType.ARROW -> {
                    assertNull("$name stays a two-point line", skewed.points)
                    assertEquals(300f, ownAxes.w, 1e-3f)
                }
            }
        }
        // A rounded rectangle under a skew keeps its corner style on the point shape.
        val round = ShapeAffine.mapped(shape(ShapeType.RECTANGLE, corner = CornerStyle.ROUND), skew)!!
        assertTrue(ShapeOutlines.isCustom(round))
        assertEquals(CornerStyle.ROUND, round.corner)
    }

    @Test
    fun homographiesAndDegenerateMapsAreRefused() {
        val persp = floatArrayOf(1f, 0.1f, 5f, 0f, 1f, 0f, 0.0008f, 0f, 1f)
        for ((_, s) in shapes) assertNull(ShapeAffine.mapped(s, persp))
        assertNull(ShapeAffine.mapped(custom, floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f)))
        assertNull(ShapeAffine.mapped(custom, floatArrayOf(Float.NaN, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)))
        assertNull(ShapeAffine.mapped(custom, FloatArray(6)))
    }

    @Test
    fun pointRadiiAndBrushesScale() {
        val withRadius = custom.copy(points = custom.points!!.mapIndexed { i, p -> if (i == 0) p.copy(radius = 8f) else p })
        val t = ShapeAffine.mapped(withRadius, skew)!!
        val det = skew[0] * skew[4] - skew[1] * skew[3]
        assertEquals(8f * sqrt(abs(det)), t.points!!.mapNotNull { it.radius }.single(), 1e-4f)
        assertSame(custom.points, ShapeAffine.scaledRadii(custom.points, 2f))
        // VectorOps keeps v1.6's similarity result first, and scales the point radii there too.
        val sim = VectorOps.transformed(VShape(1, shape = withRadius), similarity) as VShape
        assertEquals(8f * 1.3f, sim.shape.points!!.mapNotNull { it.radius }.single(), 1e-4f)
        // A brush outline: the tip scales by sqrt|det|.
        val brushed = shape(ShapeType.ELLIPSE).copy(
            strokeWith = ShapeStroke.BRUSH, brushTool = ToolId.BRUSH.name, brushPreset = BrushLibrary.defaultBrush.copy(size = 10f),
        )
        val b = ShapeAffine.mapped(brushed, skew)!!
        assertEquals(10f * sqrt(abs(det)), b.brushPreset!!.size, 1e-3f)
    }
}
