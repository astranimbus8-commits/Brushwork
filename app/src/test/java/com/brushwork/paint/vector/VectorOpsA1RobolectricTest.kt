package com.brushwork.paint.vector

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Selection
import com.brushwork.paint.tools.vector.ArrowHeads
import com.brushwork.paint.tools.vector.CurveGeometry
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapePoint
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.tools.vector.VectorPath
import com.brushwork.paint.vector.geom.ObjectMapping
import com.brushwork.paint.vector.render.VectorLayerRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.5 A1 (VectorOps precision): homographies split curves before mapping them, reflections keep
 * symmetric shapes (and custom points) as shapes, and selections touch strokes by their dabs.
 */
@RunWith(RobolectricTestRunner::class)
class VectorOpsA1RobolectricTest {
    private val w = 320
    private val h = 240

    private fun px(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun draw(o: VObject): IntArray {
        val b = BitmapUtils.createLayerBitmap(w, h)
        VectorLayerRenderer.render(Canvas(b), VectorContent(objects = listOf(o)), Rect(0, 0, w, h), tips = TipCache(), document = Rect(0, 0, w, h))
        return px(b)
    }

    /** [o] drawn through the affine [m] (the reference for reflections). */
    private fun drawMapped(o: VObject, m: Matrix): IntArray {
        val b = BitmapUtils.createLayerBitmap(w, h)
        val c = Canvas(b)
        c.concat(m)
        VectorLayerRenderer.render(c, VectorContent(objects = listOf(o)), Rect(-2000, -2000, 2000, 2000), tips = TipCache())
        return px(b)
    }

    private fun iou(a: IntArray, b: IntArray): Double {
        var inter = 0; var union = 0
        for (i in a.indices) {
            val x = (a[i] ushr 24) > 127; val y = (b[i] ushr 24) > 127
            if (x && y) inter++
            if (x || y) union++
        }
        return if (union == 0) 1.0 else inter.toDouble() / union
    }

    private fun shape(type: ShapeType, rotation: Float = 23f, points: List<ShapePoint>? = null, heads: ArrowHeads = ArrowHeads.END) = VShape(
        0, shape = ShapeObject(
            type, cx = 150f, cy = 115f, w = 120f, h = 80f, rotation = rotation, style = ShapeStyle.STROKE_FILL, strokeWidth = 6f,
            strokeColor = 0xFF003060.toInt(), fillColor = 0xFF90C0F0.toInt(), points = points, arrowHeads = heads,
        ),
    )

    private fun values(m: Matrix) = FloatArray(9).also { m.getValues(it) }

    @Test
    fun mirroredSymmetricShapesStayShapesAndDrawLikeTheMirror() {
        val mirrors = listOf(
            Matrix().apply { setScale(-1f, 1f, 160f, 120f) },
            Matrix().apply { setScale(1f, -1f, 160f, 120f) },
            Matrix().apply { setScale(-1.2f, 1.2f, 160f, 120f); postRotate(30f, 160f, 120f) },
        )
        val custom = listOf(ShapePoint(-0.5f, -0.5f), ShapePoint(0.4f, -0.3f, smooth = true), ShapePoint(0.5f, 0.5f), ShapePoint(-0.2f, 0.3f))
        val cases = ShapeType.entries.filter { it != ShapeType.ARROW }.map { it.name to shape(it) } +
            listOf("custom points" to shape(ShapeType.RECTANGLE, points = custom), "arrow" to shape(ShapeType.ARROW, rotation = 0f), "double arrow" to shape(ShapeType.ARROW, heads = ArrowHeads.BOTH))
        for (m in mirrors) {
            val mv = values(m)
            for ((name, s) in cases) {
                val t = VectorOps.transformed(s, mv)
                val score = iou(drawMapped(s, m), draw(t))
                assertTrue("$name: IoU $score", score >= 0.97)
                if (name in setOf("RECTANGLE", "ELLIPSE", "custom points", "double arrow")) assertTrue("$name stays a shape", t is VShape)
            }
        }
    }

    @Test
    fun homographiesSplitCurvesSoTheResultFollowsTheTrueImage() {
        // A curvy path under a strong perspective: points of the true image stay within a
        // fraction of a pixel of the mapped path, where mapping only the anchors drifts away.
        val p = VPath(
            1, subpaths = listOf(VSubpath(listOf(VAnchor(40f, 200f), VAnchor(110f, 40f), VAnchor(200f, 210f), VAnchor(290f, 50f)))), tension = 0f,
            stroke = VStrokeStyle(color = -16777216, width = 3f, ),
        )
        val m = floatArrayOf(1.1f, 0.2f, -10f, 0.05f, 0.9f, 15f, 0.0016f, 0.0009f, 1f)
        fun map(q: Vec2): Vec2 {
            val wv = m[6] * q.x + m[7] * q.y + m[8]
            return Vec2((m[0] * q.x + m[1] * q.y + m[2]) / wv, (m[3] * q.x + m[4] * q.y + m[5]) / wv)
        }
        val truth = ArrayList<Vec2>()
        val anchors = VectorOps.curveAnchors(p.subpaths[0])
        for (seg in 0 until CurveGeometry.segmentCount(anchors.size, false)) {
            val (p0, c1, c2, p1) = CurveGeometry.segment(anchors, seg, false, 0f, false)
            for (k in 0..40) truth += map(VectorPath.cubicPoint(p0, c1, c2, p1, k / 40f))
        }
        fun maxError(path: VPath): Float {
            val polys = VectorOps.toVectorPath(path).flatten(0.05f)
            return truth.maxOf { VectorOps.distanceToOutline(polys, it) }
        }
        val split = VectorOps.transformed(p, m) as VPath
        val naive = p.copy(subpaths = p.subpaths.map { s -> s.copy(anchors = s.anchors.map { a -> map(Vec2(a.x, a.y)).let { q -> a.copy(x = q.x, y = q.y) } }) })
        val e = maxError(split)
        println("homography error: split $e px, anchors only ${maxError(naive)} px")
        assertTrue("split: $e px", e < 0.6f)
        assertTrue(maxError(naive) > e)
        assertEquals(1 + 3 * ObjectMapping.HOMOGRAPHY_PIECES, split.subpaths[0].anchors.size)
        // Straight segments stay one piece (a projective map keeps them straight).
        val poly = VPath(2, subpaths = listOf(VSubpath(listOf(VAnchor(10f, 10f, true), VAnchor(100f, 10f, true), VAnchor(100f, 80f, true)))), stroke = VStrokeStyle(color = -1, width = 2f))
        assertEquals(3, (VectorOps.transformed(poly, m) as VPath).subpaths[0].anchors.size)
        // Widths blend along the split pieces.
        val wide = p.copy(subpaths = listOf(VSubpath(p.subpaths[0].anchors.mapIndexed { i, a -> a.copy(width = if (i == 1) 3f else 1f) })))
        val ws = (VectorOps.transformed(wide, m) as VPath).subpaths[0].anchors.map { it.width }
        assertEquals(3f, ws[4], 1e-5f)
        assertTrue(ws[2] in 1f..3f && ws[6] in 1f..3f)
    }

    @Test
    fun selectionsTouchStrokesWhereTheirDabsAre() {
        // A long-tapered finger stroke: a selection beside its thin tip doesn't touch it, the same
        // selection beside its full-width middle does.
        val preset = BrushLibrary.defaultBrush.copy(size = 30f, taperStart = 120f, taperEnd = 120f)
        val s = VStroke(1, preset = preset, color = -16777216, seed = 1, stylus = false, points = PackedPoints(floatArrayOf(20f, 160f, 300f), floatArrayOf(120f, 120f, 120f), floatArrayOf(1f, 1f, 1f)))
        val content = VectorContent(objects = listOf(s), nextId = 2)
        fun sel(l: Int, t: Int, r: Int, b: Int): Selection {
            val m = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
            Canvas(m).drawRect(Rect(l, t, r, b), Paint().apply { color = 0xFF000000.toInt() })
            return Selection.wrap(m)
        }
        assertFalse("beside the tip", 1L in VectorOps.touching(content, sel(18, 128, 30, 140)))
        assertTrue("beside the middle", 1L in VectorOps.touching(content, sel(155, 128, 167, 140)))
        assertTrue("on the tip", 1L in VectorOps.touching(content, sel(18, 117, 30, 123)))
    }
}
