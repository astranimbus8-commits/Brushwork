package com.brushwork.paint.tools.vector

import com.brushwork.paint.core.Geometry
import com.brushwork.paint.core.Vec2
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/** v1.5 foundation (§5.10 item 12): custom handles on sharp anchors, v1.4 paths unchanged (JVM). */
class CurveGeometryV15Test {

    /** CurveGeometry.handles exactly as v1.4 (3f3e849) had it. */
    private fun v14Handles(anchors: List<CurveAnchor>, i: Int, closed: Boolean, tension: Float): Pair<Vec2, Vec2> {
        val a = anchors[i]
        val hi = a.handleIn; val ho = a.handleOut
        if (!a.sharp && hi != null && ho != null) return hi to ho
        val n = anchors.size
        val prev = if (i > 0) anchors[i - 1].pos else if (closed && n > 2) anchors[n - 1].pos else null
        val next = if (i < n - 1) anchors[i + 1].pos else if (closed && n > 2) anchors[0].pos else null
        val k = (1f - tension).coerceIn(0f, 1f)
        val p = a.pos
        if (a.sharp) {
            val hIn = prev?.let { (it - p) * (k / 6f) } ?: Vec2.ZERO
            val hOut = next?.let { (it - p) * (k / 6f) } ?: Vec2.ZERO
            return hIn to hOut
        }
        val m = when {
            prev != null && next != null -> (next - prev) * (k / 2f)
            next != null -> (next - p) * k
            prev != null -> (p - prev) * k
            else -> Vec2.ZERO
        }
        return (m / -3f) to (m / 3f)
    }

    /** Anchors as v1.4 could make them: sharp anchors never had custom handles. */
    private fun v14Anchors(r: Random, n: Int): List<CurveAnchor> = List(n) {
        val sharp = r.nextInt(3) == 0
        val custom = !sharp && r.nextBoolean()
        CurveAnchor(
            r.nextFloat() * 500f, r.nextFloat() * 500f, sharp,
            if (custom) Vec2(r.nextFloat() * 40f - 20f, r.nextFloat() * 40f - 20f) else null,
            if (custom) Vec2(r.nextFloat() * 40f - 20f, r.nextFloat() * 40f - 20f) else null,
        )
    }

    @Test
    fun v14AnchorsGiveIdenticalHandlesAndPaths() {
        val r = Random(15)
        repeat(200) {
            val n = 2 + r.nextInt(7)
            val anchors = v14Anchors(r, n)
            val closed = r.nextBoolean()
            val tension = r.nextFloat()
            for (i in anchors.indices) assertEquals(v14Handles(anchors, i, closed, tension), CurveGeometry.handles(anchors, i, closed, tension))
            assertEquals(v14Path(anchors, closed, tension), CurveGeometry.toPath(anchors, closed, tension, polyline = false).ops)
        }
    }

    /** CurveGeometry.toPath as v1.4 built it (from [v14Handles]). */
    private fun v14Path(anchors: List<CurveAnchor>, closed: Boolean, tension: Float): List<PathOp> {
        val n = anchors.size
        val ops = ArrayList<PathOp>()
        ops += PathOp.MoveTo(anchors[0].pos)
        for (s in 0 until CurveGeometry.segmentCount(n, closed)) {
            val a = anchors[s].pos
            val b = anchors[(s + 1) % n].pos
            val c1 = a + v14Handles(anchors, s, closed, tension).second
            val c2 = b + v14Handles(anchors, (s + 1) % n, closed, tension).first
            val straight = Geometry.distanceToSegment(c1, a, b) < 1e-3f && Geometry.distanceToSegment(c2, a, b) < 1e-3f
            ops += if (straight) PathOp.LineTo(b) else PathOp.CubicTo(c1, c2, b)
        }
        if (closed && n > 2) ops += PathOp.Close
        return ops
    }

    @Test
    fun sharpAnchorsHonourTheirCustomHandles() {
        val a = listOf(
            CurveAnchor(0f, 0f),
            CurveAnchor(100f, 0f, sharp = true, handleIn = Vec2(-10f, 20f), handleOut = null),
            CurveAnchor(200f, 0f),
        )
        val (hIn, hOut) = CurveGeometry.handles(a, 1, closed = false, tension = 0f)
        assertEquals("a set handle is used as is (broken tangent)", Vec2(-10f, 20f), hIn)
        assertEquals("an unset one keeps the chord handle", Vec2(100f, 0f) * (1f / 6f), hOut)
        val seg = CurveGeometry.segment(a, 0, closed = false, tension = 0f, polyline = false)
        assertEquals(Vec2(90f, 20f), seg[2])
        val seg2 = CurveGeometry.segment(a, 1, closed = false, tension = 0f, polyline = false)
        assertEquals(Vec2(100f, 0f) + Vec2(100f, 0f) * (1f / 6f), seg2[1])
        // Smooth anchors still need both handles to override the automatic tangent.
        val half = listOf(CurveAnchor(0f, 0f), CurveAnchor(100f, 0f, handleOut = Vec2(5f, 5f)), CurveAnchor(200f, 0f))
        assertEquals(v14Handles(half, 1, false, 0f), CurveGeometry.handles(half, 1, false, 0f))
    }

    @Test
    fun anchorWidthDefaultsToOne() {
        assertEquals(1f, CurveAnchor(1f, 2f).width, 0f)
        assertEquals(true, CurveSettings().useBrushSize)
        // v1.4 settings JSON has no such field: it takes the new default (linked).
        val old = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(CurveSettings.serializer(), """{"closed":true,"plainWidth":3.0,"stroke":"PLAIN"}""")
        assertEquals(true, old.useBrushSize)
        assertEquals(3f, old.plainWidth, 0f)
    }
}
