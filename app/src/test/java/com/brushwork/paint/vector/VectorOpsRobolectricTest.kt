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
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.ArrowHeads
import com.brushwork.paint.tools.vector.CornerStyle
import com.brushwork.paint.tools.vector.JoinStyle
import com.brushwork.paint.tools.vector.LineCapStyle
import com.brushwork.paint.tools.vector.PathOp
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapePoint
import com.brushwork.paint.tools.vector.ShapeStroke
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.tools.vector.VectorPath
import com.brushwork.paint.vector.render.VectorLayerRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * v1.5 F2: the reference [VectorOps] bodies A2, A3, A4 and A8 build on — bounds hold everything an
 * object paints, hit tests, touching, and [VectorOps.transformed] checked against drawing the
 * original through the same matrix (shapes stay shapes under similarities and become paths
 * otherwise; strokes scale their size by √|det|; gradients follow their objects).
 */
@RunWith(RobolectricTestRunner::class)
class VectorOpsRobolectricTest {
    private val w = 320
    private val h = 240
    private val tips = TipCache()

    /** Everything any test object can reach (the renderer culls by bounds against it). */
    private val everywhere = Rect(-4096, -4096, 8192, 8192)

    private fun values(m: Matrix) = FloatArray(9).also { m.getValues(it) }

    private fun matrix(values: FloatArray) = Matrix().apply { setValues(values) }

    /** [o] drawn alone into a fresh document-sized bitmap, through [m] when given. */
    private fun draw(o: VObject, m: Matrix? = null): Bitmap = drawAll(listOf(o), m)

    private fun drawAll(objects: List<VObject>, m: Matrix? = null): Bitmap {
        val b = BitmapUtils.createLayerBitmap(w, h)
        val cv = Canvas(b)
        if (m != null) cv.concat(m)
        VectorLayerRenderer.render(cv, VectorContent(objects = objects), everywhere, tips = tips)
        return b
    }

    private fun pixels(b: Bitmap) = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }

    /** Overlap of the opaque-ish (alpha ≥ 128) areas of [a] and [b]: intersection / union. */
    private fun iou(a: Bitmap, b: Bitmap): Double {
        val pa = pixels(a); val pb = pixels(b)
        var inter = 0; var union = 0
        for (i in pa.indices) {
            val ia = pa[i] ushr 24 >= 128
            val ib = pb[i] ushr 24 >= 128
            if (ia && ib) inter++
            if (ia || ib) union++
        }
        assertTrue("something is drawn", union > 200)
        return inter.toDouble() / union
    }

    private fun stroke(vararg xy: Float, size: Float = 20f, preset: String? = null): VStroke {
        val n = xy.size / 2
        val p = (preset?.let { BrushLibrary.byId(it)!! } ?: BrushLibrary.defaultBrush).copy(size = size)
        return VStroke(
            1, preset = p, color = 0xFF2050C0.toInt(), seed = 3L, stylus = false,
            points = PackedPoints(FloatArray(n) { xy[it * 2] }, FloatArray(n) { xy[it * 2 + 1] }, FloatArray(n) { 1f }),
        )
    }

    private fun rect(l: Float, t: Float, r: Float, b: Float) =
        VSubpath(listOf(VAnchor(l, t, true), VAnchor(r, t, true), VAnchor(r, b, true), VAnchor(l, b, true)), closed = true)

    private fun shape(
        type: ShapeType,
        style: ShapeStyle = ShapeStyle.STROKE_FILL,
        rotation: Float = 10f,
        points: List<ShapePoint>? = null,
        strokeWith: ShapeStroke = ShapeStroke.PLAIN,
        brushId: String? = null,
    ): VShape {
        val line = type.isLineLike
        val brush = strokeWith == ShapeStroke.BRUSH
        return VShape(
            1, seed = 9L,
            shape = ShapeObject(
                type, cx = 150f, cy = 115f, w = if (line) 150f else 120f, h = if (line) 0f else 70f, rotation = rotation,
                style = style, strokeWidth = 6f, strokeColor = 0xFF103080.toInt(), fillColor = 0xFF60C0E0.toInt(),
                corner = if (type == ShapeType.RECTANGLE) CornerStyle.ROUND else CornerStyle.SHARP, cornerRadius = 12f,
                sides = 6, arrowHeads = ArrowHeads.BOTH, lineCap = LineCapStyle.SQUARE, points = points,
                strokeWith = strokeWith, brushTool = if (brush) ToolId.BRUSH.name else null,
                brushPreset = if (brush) (brushId?.let { BrushLibrary.byId(it)!! } ?: BrushLibrary.defaultBrush).copy(size = 10f) else null,
            ),
        )
    }

    /** Every kind of shape, plus a custom (point-edited) closed outline and a custom line. */
    private fun allShapes(style: ShapeStyle = ShapeStyle.STROKE_FILL): List<Pair<String, VShape>> =
        ShapeType.entries.map { it.name to shape(it, style) } + listOf(
            "custom" to shape(
                ShapeType.RECTANGLE, style,
                points = listOf(
                    ShapePoint(-0.5f, -0.5f), ShapePoint(0.5f, -0.3f, smooth = true),
                    ShapePoint(0.3f, 0.5f), ShapePoint(-0.4f, 0.4f, handleIn = com.brushwork.paint.tools.vector.ShapeHandle(0.1f, -0.2f)),
                ),
            ),
            "custom line" to shape(
                ShapeType.LINE, style,
                points = listOf(ShapePoint(-0.5f, 0f), ShapePoint(0f, 0.2f, smooth = true), ShapePoint(0.5f, 0f)),
            ),
        )

    // ------------------------------------------------------------------ bounds

    @Test
    fun boundsHoldEverythingEachKindPaints() {
        val widths = VSubpath(listOf(VAnchor(40f, 200f, width = 0.5f), VAnchor(160f, 170f, width = 3f), VAnchor(280f, 210f, width = 1f)))
        val objects = listOf(
            "stroke" to stroke(40f, 60f, 120f, 30f, 200f, 90f, 280f, 40f, size = 24f),
            "chalk stroke" to stroke(60f, 200f, 260f, 120f, size = 30f, preset = "chalk"),
            "airbrush stroke" to stroke(60f, 60f, 250f, 180f, size = 40f, preset = "airbrush"),
            "miter path" to VPath(
                1, subpaths = listOf(VSubpath(listOf(VAnchor(60f, 200f, true), VAnchor(160f, 60f, true), VAnchor(180f, 200f, true)))),
                stroke = VStrokeStyle(color = -0x1000000, width = 12f, join = JoinStyle.MITER, miter = 10f, cap = LineCapStyle.SQUARE),
            ),
            "brush path" to VPath(
                1, subpaths = listOf(VSubpath(listOf(VAnchor(50f, 120f), VAnchor(150f, 40f), VAnchor(270f, 160f)))),
                stroke = VStrokeStyle(kind = VStrokeKind.BRUSH, color = -0x1000000, width = 16f, brushTool = ToolId.BRUSH, brush = BrushLibrary.byId("softround")!!.copy(size = 16f), seed = 4L),
            ),
            "varying width" to VPath(1, subpaths = listOf(widths), polyline = true, stroke = VStrokeStyle(color = -0x1000000, width = 10f)),
            "varying brush" to VPath(
                1, subpaths = listOf(widths), polyline = true,
                stroke = VStrokeStyle(kind = VStrokeKind.BRUSH, color = -0x1000000, width = 10f, brushTool = ToolId.BRUSH, brush = BrushLibrary.defaultBrush.copy(size = 10f)),
            ),
            "filled path" to VPath(1, subpaths = listOf(rect(80f, 50f, 230f, 190f)), fill = VPaint.Solid(-0x1000000)),
        ) + allShapes() + listOf("chalk ellipse" to shape(ShapeType.ELLIPSE, strokeWith = ShapeStroke.BRUSH, brushId = "chalk"))
        for ((name, o) in objects) {
            val b = VectorOps.bounds(o)
            val px = pixels(draw(o))
            var painted = 0
            for (y in 0 until h) for (x in 0 until w) {
                if (px[y * w + x] == 0) continue
                painted++
                assertTrue("$name: ($x, $y) inside $b", b.contains(x + 0.5f, y + 0.5f))
            }
            assertTrue("$name paints", painted > 100)
        }
    }

    // ------------------------------------------------------------------ hit tests

    @Test
    fun hitTestsFollowWhatIsDrawn() {
        // A stroke: within the largest dab's radius (+ tolerance) of its points.
        val s = stroke(40f, 100f, 240f, 100f, size = 20f)
        assertTrue(VectorOps.hit(s, Vec2(140f, 100f), 0f))
        assertTrue(VectorOps.hit(s, Vec2(140f, 109f), 0f))
        assertFalse(VectorOps.hit(s, Vec2(140f, 114f), 0f))
        assertTrue(VectorOps.hit(s, Vec2(140f, 114f), 5f))
        assertTrue(VectorOps.hit(s, Vec2(32f, 100f), 0f))
        assertFalse(VectorOps.hit(s, Vec2(20f, 100f), 0f))

        // A filled path with a hole: even-odd leaves the hole out, non-zero (same winding) fills it.
        val holed = VPath(1, subpaths = listOf(rect(40f, 40f, 200f, 200f), rect(90f, 90f, 150f, 150f)), fillRule = VFillRule.EVENODD, fill = VPaint.Solid(-1))
        assertTrue(VectorOps.hit(holed, Vec2(60f, 60f), 0f))
        assertFalse(VectorOps.hit(holed, Vec2(120f, 120f), 0f))
        assertTrue(VectorOps.hit(holed.copy(fillRule = VFillRule.NONZERO), Vec2(120f, 120f), 0f))
        assertFalse(VectorOps.hit(holed, Vec2(220f, 120f), 0f))
        assertTrue(VectorOps.hit(holed, Vec2(220f, 120f), 21f))

        // An outline only: on the line (half its width + tolerance), not inside.
        val outline = VPath(1, subpaths = listOf(rect(40f, 40f, 200f, 200f)), stroke = VStrokeStyle(color = -1, width = 4f))
        assertFalse(VectorOps.hit(outline, Vec2(120f, 120f), 0f))
        assertTrue(VectorOps.hit(outline, Vec2(41.5f, 120f), 0f))
        assertFalse(VectorOps.hit(outline, Vec2(46f, 120f), 0f))
        assertTrue(VectorOps.hit(outline, Vec2(46f, 120f), 5f))
        // The closing edge counts.
        assertTrue(VectorOps.hit(outline, Vec2(40f, 150f), 0f))

        // Shapes: the Shape tool's own hit test.
        val e = shape(ShapeType.ELLIPSE, ShapeStyle.FILL, rotation = 0f)
        assertTrue(VectorOps.hit(e, Vec2(150f, 115f), 0f))
        assertFalse(VectorOps.hit(e, Vec2(150f, 160f), 0f))
        assertFalse(VectorOps.hit(shape(ShapeType.ELLIPSE, ShapeStyle.STROKE, rotation = 0f), Vec2(150f, 115f), 0f))
    }

    @Test
    fun touchingUsesWhatAnObjectPaintsNotItsBox() {
        val outline = VPath(1, subpaths = listOf(rect(40f, 40f, 200f, 200f)), stroke = VStrokeStyle(color = -1, width = 4f))
        val diagonal = stroke(20f, 20f, 300f, 220f, size = 10f).withId(2)
        val content = VectorContent(objects = listOf(outline, diagonal), nextId = 3)
        fun sel(l: Int, t: Int, r: Int, b: Int): Selection {
            val m = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
            Canvas(m).drawRect(Rect(l, t, r, b), Paint().apply { color = -0x1000000 })
            return Selection.wrap(m)
        }
        // Inside the hollow outline, away from the diagonal: neither.
        assertEquals(emptySet<Long>(), VectorOps.touching(content, sel(60, 150, 90, 190)))
        // Over the outline's left edge.
        assertEquals(setOf(1L), VectorOps.touching(content, sel(30, 150, 50, 160)))
        // Over the diagonal inside the box.
        assertEquals(setOf(2L), VectorOps.touching(content, sel(110, 95, 130, 115)))
        // In the diagonal's box but off it.
        assertEquals(emptySet<Long>(), VectorOps.touching(content, sel(250, 30, 300, 60)))
        assertEquals(setOf(1L, 2L), VectorOps.touching(content, sel(30, 30, 60, 60)))
    }

    @Test
    fun touchingTestsLargeFootprintsTileByTile() {
        // Objects spanning many test tiles: a hit in a far tile counts, and a large box alone
        // (the footprint not under the selection) does not.
        val dw = 1700
        val dh = 1500
        val ring = VPath(1, subpaths = listOf(rect(20f, 20f, 1650f, 1450f)), stroke = VStrokeStyle(color = -1, width = 4f))
        val diagonal = stroke(10f, 10f, 1680f, 1480f, size = 12f).withId(2)
        val filled = VPath(3, subpaths = listOf(rect(1100f, 1100f, 1600f, 1400f)), fill = VPaint.Solid(-1))
        val content = VectorContent(objects = listOf(ring, diagonal, filled), nextId = 4)
        fun sel(l: Int, t: Int, r: Int, b: Int): Selection {
            val m = Bitmap.createBitmap(dw, dh, Bitmap.Config.ALPHA_8)
            Canvas(m).drawRect(Rect(l, t, r, b), Paint().apply { color = -0x1000000 })
            return Selection.wrap(m)
        }
        // On the diagonal near its far end (inside the filled box too).
        assertEquals(setOf(2L, 3L), VectorOps.touching(content, sel(1395, 1225, 1425, 1255)))
        // On the ring's right edge, half way down.
        assertEquals(setOf(1L), VectorOps.touching(content, sel(1640, 700, 1660, 760)))
        // Inside the ring, away from everything: nothing.
        assertEquals(emptySet<Long>(), VectorOps.touching(content, sel(1200, 300, 1260, 360)))
        // A long strip (several 512 px test tiles) inside the ring: the diagonal crosses it only
        // in its third tile, the filled box from the third on, the ring nowhere.
        assertEquals(setOf(2L, 3L), VectorOps.touching(content, sel(40, 1300, 1630, 1330)))
        assertEquals(setOf(1L, 2L), VectorOps.touching(content, sel(10, 10, 30, 1700)))
    }

    // ------------------------------------------------------------------ transforms

    @Test
    fun strokesMapTheirPointsAndScaleTheirSize() {
        val s = stroke(40f, 60f, 120f, 80f, 200f, 70f)
        val scale = values(Matrix().apply { setScale(2f, 2f); postTranslate(10f, -5f) })
        val t = VectorOps.transformed(s, scale) as VStroke
        assertEquals(2f, t.sizeScale, 1e-5f)
        for (i in 0 until s.points.size) {
            assertEquals(s.points.x[i] * 2f + 10f, t.points.x[i], 1e-4f)
            assertEquals(s.points.y[i] * 2f - 5f, t.points.y[i], 1e-4f)
            assertEquals(s.points.p[i], t.points.p[i], 0f)
        }
        assertEquals(s.preset, t.preset)
        assertEquals(s.seed, t.seed)
        // Rotation and an area-preserving stretch keep the size; scaling twice multiplies.
        assertEquals(1f, (VectorOps.transformed(s, values(Matrix().apply { setRotate(37f) })) as VStroke).sizeScale, 1e-5f)
        assertEquals(1f, (VectorOps.transformed(s, values(Matrix().apply { setScale(3f, 1f / 3f) })) as VStroke).sizeScale, 1e-5f)
        assertEquals(6f, (VectorOps.transformed(t, values(Matrix().apply { setScale(3f, 3f) })) as VStroke).sizeScale, 1e-4f)
        // A scaled stroke draws like the original drawn through the same scale.
        val small = stroke(30f, 40f, 70f, 60f, 110f, 45f, 150f, 70f, size = 8f)
        val m = Matrix().apply { setScale(1.8f, 1.8f, 30f, 40f) }
        assertTrue(iou(draw(small, m), draw(VectorOps.transformed(small, values(m)))) >= 0.9)
        // A homography divides by w.
        val persp = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0.001f, 0f, 1f)
        val tp = VectorOps.transformed(s, persp) as VStroke
        assertEquals(200f / 1.2f, tp.points.x[2], 1e-3f)
        assertEquals(70f / 1.2f, tp.points.y[2], 1e-3f)
        // Garbage matrices change nothing.
        assertTrue(VectorOps.transformed(s, floatArrayOf(Float.NaN, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)) === s)
    }

    @Test
    fun pathsMapTheirAnchorsHandlesWidthsAndGradients() {
        val p = VPath(
            1,
            subpaths = listOf(
                VSubpath(
                    listOf(
                        VAnchor(60f, 160f, sharp = true, outX = 30f, outY = -80f),
                        VAnchor(150f, 60f, sharp = true, inX = -20f, inY = 0f, outX = 40f, outY = 10f, width = 2f),
                        VAnchor(250f, 170f, sharp = true, inX = 10f, inY = -60f),
                    ),
                ),
            ),
            stroke = VStrokeStyle(color = -0x1000000, width = 6f),
        )
        val m = Matrix().apply { setRotate(20f, 150f, 120f); postScale(1.2f, 0.9f, 150f, 120f); postSkew(0.15f, 0f); postTranslate(-12f, 8f) }
        val mv = values(m)
        val t = VectorOps.transformed(p, mv) as VPath
        // Every curve point equals the mapped original (Bézier curves are affine-invariant).
        val a = VectorOps.toVectorPath(p).transformed { q -> val o = FloatArray(2); m.mapPoints(o, floatArrayOf(q.x, q.y)); Vec2(o[0], o[1]) }.flatten(0.05f)
        val b = VectorOps.toVectorPath(t).flatten(0.05f)
        assertEquals(a.size, b.size)
        assertEquals(a[0].points.size, b[0].points.size)
        for (i in a[0].points.indices) assertEquals(0f, a[0].points[i].distanceTo(b[0].points[i]), 2e-3f)
        assertEquals(2f, t.subpaths[0].anchors[1].width, 0f)
        val det = mv[0] * mv[4] - mv[1] * mv[3]
        assertEquals(6f * sqrt(abs(det)), t.stroke!!.width, 1e-4f)
    }

    @Test
    fun gradientFillsFollowTheirObjects() {
        val stops = listOf(VStop(0f, 0xFF000000.toInt()), VStop(0.5f, 0xFFE02040.toInt()), VStop(1f, 0xFFFFFFFF.toInt()))
        val linear = VPath(1, subpaths = listOf(rect(70f, 50f, 230f, 180f)), fill = VPaint.Linear(90f, 60f, 210f, 150f, stops))
        val radial = VPath(1, subpaths = listOf(rect(70f, 50f, 230f, 180f)), fill = VPaint.Radial(150f, 115f, 70f, stops))
        val skewedRadial = radial.copy(fill = VPaint.Radial(0f, 0f, 70f, stops, listOf(1.2f, 0.1f, -0.2f, 0.8f, 150f, 115f)))
        val m = Matrix().apply { setRotate(30f, 150f, 115f); postScale(1.15f, 0.8f, 150f, 115f); postTranslate(8f, 6f) }
        for ((name, o) in listOf("linear" to linear, "radial" to radial, "radial with a matrix" to skewedRadial)) {
            val expected = pixels(draw(o, m))
            val actual = pixels(draw(VectorOps.transformed(o, values(m))))
            var interior = 0
            for (i in expected.indices) {
                // Away from the anti-aliased edges, the colors match.
                if (expected[i] ushr 24 != 255 || actual[i] ushr 24 != 255) continue
                interior++
                for (sh in intArrayOf(16, 8, 0)) {
                    val d = abs(((expected[i] shr sh) and 0xFF) - ((actual[i] shr sh) and 0xFF))
                    assertTrue("$name pixel $i channel $sh off by $d", d <= 3)
                }
            }
            assertTrue("$name interior", interior > 5000)
        }
    }

    @Test
    fun shapesStayShapesUnderSimilarities() {
        val m = Matrix().apply { setScale(1.3f, 1.3f, 150f, 115f); postRotate(25f, 150f, 115f); postTranslate(10f, 5f) }
        val mv = values(m)
        for ((name, s) in allShapes() + listOf("brush ellipse" to shape(ShapeType.ELLIPSE, strokeWith = ShapeStroke.BRUSH))) {
            val t = VectorOps.transformed(s, mv)
            assertTrue("$name stays a shape", t is VShape)
            t as VShape
            assertEquals(6f * 1.3f, t.shape.strokeWidth, 1e-4f)
            assertEquals(s.seed, t.seed)
            val score = iou(draw(s, m), draw(t))
            assertTrue("$name: IoU $score", score >= 0.97)
        }
    }

    @Test
    fun otherMapsTurnShapesIntoPaths() {
        val stretch = Matrix().apply { setScale(1.4f, 0.75f, 150f, 115f); postSkew(0.2f, 0f, 150f, 115f); postTranslate(4f, 3f) }
        val mirror = Matrix().apply { setScale(-1f, 1f, 160f, 120f) }
        for (m in listOf(stretch, mirror)) {
            val mv = values(m)
            val det = abs(mv[0] * mv[4] - mv[1] * mv[3])
            // Filled outlines draw exactly like the shape drawn through the matrix.
            for ((name, s) in allShapes(ShapeStyle.FILL).filter { !it.second.shape.type.isLineLike }) {
                val t = VectorOps.transformed(s, mv)
                assertTrue("$name becomes a path", t is VPath)
                val score = iou(draw(s, m), draw(t))
                assertTrue("$name: IoU $score", score >= 0.98)
            }
            // Outlines keep their style, the width scaled by √|det|.
            for ((name, s) in allShapes()) {
                val t = VectorOps.transformed(s, mv) as VPath
                assertNotNull(name, t.stroke)
                val st = t.stroke!!
                assertEquals(name, 6f * sqrt(det), st.width, 1e-3f)
                assertEquals(name, VStrokeKind.PLAIN, st.kind)
                if (!s.shape.type.isLineLike) assertEquals(name, VPaint.Solid(s.shape.fillColor), t.fill)
                if (s.shape.type == ShapeType.ARROW) assertEquals("$name heads", VPaint.Solid(s.shape.strokeColor), t.fill)
            }
            // A brush outline stays a brush outline with the brush scaled.
            val b = VectorOps.transformed(shape(ShapeType.ELLIPSE, strokeWith = ShapeStroke.BRUSH), mv) as VPath
            assertEquals(VStrokeKind.BRUSH, b.stroke!!.kind)
            assertEquals(10f * sqrt(det), b.stroke!!.brush!!.size, 1e-3f)
            assertEquals(9L, b.stroke!!.seed)
        }
        // A homography maps the anchors themselves.
        val persp = floatArrayOf(1f, 0.1f, 5f, 0f, 1f, 0f, 0.0008f, 0f, 1f)
        val rectShape = shape(ShapeType.RECTANGLE, ShapeStyle.FILL, rotation = 0f).let { it.copy(shape = it.shape.copy(corner = CornerStyle.SHARP)) }
        val t = VectorOps.transformed(rectShape, persp) as VPath
        val corners = listOf(Vec2(90f, 80f), Vec2(210f, 80f), Vec2(210f, 150f), Vec2(90f, 150f))
        val mapped = corners.map { c ->
            val wv = persp[6] * c.x + persp[7] * c.y + persp[8]
            Vec2((persp[0] * c.x + persp[1] * c.y + persp[2]) / wv, (persp[3] * c.x + persp[4] * c.y + persp[5]) / wv)
        }
        val anchors = t.subpaths.flatMap { it.anchors }.map { Vec2(it.x, it.y) }
        for (q in mapped) assertTrue("$q is an anchor of $anchors", anchors.any { it.distanceTo(q) < 1e-2f })
    }

    /** Largest difference of the premultiplied channels of [a] and [b] (0..255). */
    private fun premultipliedDiff(a: Int, b: Int): Int {
        val aa = a ushr 24; val ab = b ushr 24
        var d = abs(aa - ab)
        for (sh in intArrayOf(16, 8, 0)) d = max(d, abs(((a ushr sh) and 0xFF) * aa / 255 - ((b ushr sh) and 0xFF) * ab / 255))
        return d
    }

    @Test
    fun shapesAsPathsDrawLikeTheShapes() {
        for ((name, s) in allShapes()) {
            val paths = VectorOps.toPaths(s)
            assertTrue(paths.all { it.id == s.id })
            val a = pixels(draw(s))
            val b = pixels(drawAll(paths))
            var close = 0
            var maxDiff = 0
            for (i in a.indices) {
                val d = premultipliedDiff(a[i], b[i])
                if (d <= 4) close++
                maxDiff = max(maxDiff, d)
            }
            // The same geometry; straight cubics come back as lines (anti-aliased a bit differently).
            assertTrue("$name: ${close * 100.0 / a.size} % within ±4 (max $maxDiff)", close >= a.size * 0.998)
            assertTrue("$name: max $maxDiff", maxDiff <= 32)
        }
    }

    // ------------------------------------------------------------------ outlines

    @Test
    fun subpathsOfAndToVectorPathRoundTrip() {
        val src = VectorPath(
            listOf(
                PathOp.MoveTo(Vec2(40f, 50f)),
                PathOp.LineTo(Vec2(120f, 50f)),
                PathOp.CubicTo(Vec2(160f, 40f), Vec2(180f, 120f), Vec2(140f, 150f)),
                PathOp.CubicTo(Vec2(100f, 180f), Vec2(60f, 140f), Vec2(40f, 50f)),
                PathOp.Close,
                PathOp.MoveTo(Vec2(200f, 60f)),
                PathOp.CubicTo(Vec2(260f, 20f), Vec2(280f, 140f), Vec2(230f, 200f)),
                PathOp.LineTo(Vec2(200f, 210f)),
            ),
        )
        val subs = VectorOps.subpathsOf(src)
        assertEquals(2, subs.size)
        assertTrue(subs[0].closed)
        assertFalse(subs[1].closed)
        // The closed sub-path's end point merged into its start (which took the last curve's handle).
        assertEquals(3, subs[0].anchors.size)
        assertTrue(subs.all { s -> s.anchors.all { it.sharp } })
        val back = VectorOps.toVectorPath(VPath(1, subpaths = subs))
        val a = src.flatten(0.05f)
        val b = back.flatten(0.05f)
        assertEquals(a.size, b.size)
        for (k in a.indices) {
            assertEquals(a[k].closed, b[k].closed)
            // Same curve: every flattened point of one lies on the other.
            for (q in a[k].points) assertTrue("$q", VectorOps.distanceToOutline(listOf(b[k]), q) < 0.06f)
            for (q in b[k].points) assertTrue("$q", VectorOps.distanceToOutline(listOf(a[k]), q) < 0.06f)
        }
        // Broken tangents on sharp anchors are kept exactly.
        val ops = back.ops
        val cubic = ops.filterIsInstance<PathOp.CubicTo>().first { it.p == Vec2(140f, 150f) }
        assertEquals(Vec2(160f, 40f), cubic.c1)
        assertEquals(Vec2(180f, 120f), cubic.c2)
        assertTrue(VectorOps.subpathsOf(VectorPath.EMPTY).isEmpty())
    }
}
