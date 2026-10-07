package com.brushwork.paint.tools.vector

import com.brushwork.paint.core.Vec2
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.acos
import kotlin.random.Random

/**
 * v1.7 item 2 (design §3.2, I5): a custom Shape outline whose points have no roundness of their
 * own ([ShapeAnchor.radius] all null) is the v1.6 outline bit for bit. The reference below is a
 * verbatim copy of v1.6's `ShapePoints.outline` (main 22a791d); the outlines are compared op by op
 * with exact float equality, on random shapes of every corner style, with curves and with points
 * in the middle of straight runs.
 */
class ShapeOutlineParityTest {

    @Test
    fun allNullRadiiIsTheV16Outline() {
        val rnd = Random(1707)
        var compared = 0
        repeat(400) { k ->
            val a = randomAnchors(rnd, curves = k % 3 != 0, through = k % 4 == 1)
            for (corner in CornerStyle.entries) {
                for (radius in floatArrayOf(0f, 3f, 17.5f, 60f, 1e4f)) {
                    for (closed in booleanArrayOf(true, false)) {
                        val expected = v16Outline(a, closed, corner, radius).ops
                        val actual = ShapePoints.outline(a, closed, corner, radius).ops
                        assertEquals("shape $k, $corner $radius closed=$closed", expected, actual)
                        compared++
                    }
                }
            }
        }
        assertEquals(400 * CornerStyle.entries.size * 5 * 2, compared)
    }

    @Test
    fun regularShapesConvertedToPointsKeepTheirV16Outline() {
        val box = ShapeBox(120f, 90f, 160f, 100f, 20f)
        for (corner in CornerStyle.entries) for (type in listOf(ShapeType.RECTANGLE, ShapeType.POLYGON, ShapeType.STAR, ShapeType.ELLIPSE)) {
            val params = OutlineParams(sides = 6, starPoints = 5, innerRatio = 0.4f, corner = corner, cornerRadius = 14f)
            val pts = ShapePoints.fromRegular(type, params)
            val o = ShapeObject(type = type, cx = box.cx, cy = box.cy, w = box.w, h = box.h, rotation = box.rotationDeg,
                corner = corner, cornerRadius = 14f, sides = 6, starPoints = 5, innerRatio = 0.4f, points = pts)
            val c = if (type.hasCorners) corner else CornerStyle.SHARP
            val expected = v16Outline(ShapePoints.localAnchors(pts, box.w, box.h), true, c, 14f).transformed { box.toDoc(it) }
            assertEquals("$type $corner", expected.ops, ShapeOutlines.outline(o).ops)
        }
    }

    @Test
    fun ownRadiiEqualToTheShapesDrawTheSameOutline() {
        // Every point given the shape's own radius explicitly (a treated style): the same outline.
        val rnd = Random(17)
        repeat(200) { k ->
            val a = randomAnchors(rnd, curves = k % 2 == 0, through = k % 3 == 0)
            for (corner in listOf(CornerStyle.ROUND, CornerStyle.BEVEL, CornerStyle.INVERTED)) {
                val radius = 5f + rnd.nextFloat() * 40f
                val own = a.map { it.copy(radius = radius) }
                assertEquals("shape $k $corner", ShapePoints.outline(a, true, corner, radius).ops, ShapePoints.outline(own, true, corner, radius).ops)
            }
        }
    }

    private fun randomAnchors(rnd: Random, curves: Boolean, through: Boolean): List<ShapeAnchor> {
        val n = 3 + rnd.nextInt(6)
        val out = ArrayList<ShapeAnchor>()
        for (i in 0 until n) {
            val ang = 2 * PI * i / n + rnd.nextDouble(-0.2, 0.2)
            val r = 40.0 + rnd.nextDouble() * 60.0
            val p = Vec2((r * kotlin.math.cos(ang)).toFloat(), (r * kotlin.math.sin(ang)).toFloat())
            val smooth = curves && rnd.nextInt(3) == 0
            val explicit = smooth && rnd.nextBoolean()
            out += ShapeAnchor(
                p, smooth,
                if (explicit) Vec2(rnd.nextFloat() * 10f - 5f, rnd.nextFloat() * 10f - 5f) else null,
                if (explicit) Vec2(rnd.nextFloat() * 10f - 5f, rnd.nextFloat() * 10f - 5f) else null,
            )
        }
        if (through && out.size >= 2 && !out[0].smooth && !out[1].smooth) {
            // A point in the middle of the straight side from point 0 to point 1.
            out.add(1, ShapeAnchor(out[0].pos.lerp(out[1].pos, 0.3f)))
        }
        return out
    }

    // ------------------------------------------------------------------ v1.6 reference (verbatim)

    private fun v16Outline(a: List<ShapeAnchor>, closed: Boolean, corner: CornerStyle, radius: Float): VectorPath {
        val n = a.size
        if (!closed || n < ShapePoints.MIN_CLOSED || corner == CornerStyle.SHARP || radius <= 0f) return ShapePoints.path(a, closed)
        val segs = List(n) { ShapePoints.segment(a, it, true) }
        val straight = BooleanArray(n) { ShapePoints.isStraight(segs[it]) }
        val verts = a.map { it.pos }
        val through = BooleanArray(n) { i -> straight[i] && straight[(i - 1 + n) % n] && passesThrough(verts[(i - 1 + n) % n], verts[i], verts[(i + 1) % n]) }
        val keep = (0 until n).filter { !through[it] }
        if (keep.size < ShapePoints.MIN_CLOSED) return ShapePoints.path(a, closed)
        if (straight.all { it }) return ShapeGeometry.cornerPath(keep.map { verts[it] }, corner, radius)
        val m = keep.size
        val corners = arrayOfNulls<ShapeGeometry.Corner>(n)
        for (k in 0 until m) {
            val i = keep[k]
            if (!straight[i] || !straight[(i - 1 + n) % n]) continue
            val prev = verts[keep[(k - 1 + m) % m]]
            val next = verts[keep[(k + 1) % m]]
            val v = verts[i]
            val cut = minOf(radius, minOf(v.distanceTo(prev), v.distanceTo(next)) / 2f)
            corners[i] = ShapeGeometry.corner(v, prev, next, cut)
        }
        val ops = ArrayList<PathOp>(n * 4 + 2)
        val first = keep[0]
        ops += PathOp.MoveTo(corners[first]?.b ?: verts[first])
        for (k in 0 until m) {
            val i = keep[k]
            val j = keep[(k + 1) % m]
            val c = corners[j]
            val seg = segs[i]
            ops += if (straight[i]) PathOp.LineTo(c?.a ?: verts[j]) else PathOp.CubicTo(seg[1], seg[2], seg[3])
            if (c != null) ShapeGeometry.appendCorner(c, corner, ops)
        }
        ops += PathOp.Close
        return VectorPath(ops)
    }

    private fun passesThrough(prev: Vec2, p: Vec2, next: Vec2): Boolean {
        val toPrev = prev - p
        val toNext = next - p
        val lp = toPrev.length
        val ln = toNext.length
        if (lp < 1e-6f) return true
        if (ln < 1e-6f) return false
        val cos = (toPrev.dot(toNext) / (lp * ln)).coerceIn(-1f, 1f)
        return acos(cos) > PI.toFloat() - 1e-3f
    }
}
