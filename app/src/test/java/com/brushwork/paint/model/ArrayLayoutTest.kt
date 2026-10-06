package com.brushwork.paint.model

import android.graphics.RectF
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VSubpath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.cos
import kotlin.math.sin

/**
 * v1.7 F1 (item 3, I14): [ArrayLayout] places the copies as §3.3 says, copy 0 is always the
 * source itself, and [ArraySpec.mapped] moves a spec with the document (copies under a map M are
 * M·Cₖ·M⁻¹). (Robolectric: RectF.)
 */
@RunWith(RobolectricTestRunner::class)
class ArrayLayoutTest {
    /** 40 × 20, centre (30, 30). */
    private val src = RectF(10f, 20f, 50f, 40f)
    private val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    private fun map(m: FloatArray, x: Float, y: Float) = floatArrayOf(m[0] * x + m[1] * y + m[2], m[3] * x + m[4] * y + m[5])

    private fun translate(dx: Float, dy: Float) = floatArrayOf(1f, 0f, dx, 0f, 1f, dy, 0f, 0f, 1f)

    /** Rotation by [deg] (clockwise on screen) about (cx, cy). */
    private fun rotation(deg: Double, cx: Float = 0f, cy: Float = 0f): FloatArray {
        val r = Math.toRadians(deg)
        val c = cos(r).toFloat(); val s = sin(r).toFloat()
        return floatArrayOf(c, -s, cx - c * cx + s * cy, s, c, cy - s * cx - c * cy, 0f, 0f, 1f)
    }

    private fun assertMatrix(msg: String, expected: FloatArray, actual: FloatArray, eps: Float = 1e-3f) {
        for (i in 0 until 9) assertEquals("$msg [$i] of ${actual.toList()}", expected[i], actual[i], eps)
    }

    /** An L-shaped guide: (0, 0) to (100, 0) to (100, 100), a corner in the middle. */
    private val lGuide = VSubpath(listOf(VAnchor(0f, 0f), VAnchor(100f, 0f, sharp = true), VAnchor(100f, 100f)))

    private fun everyMode(count: Int) = listOf(
        ArraySpec(count = count),
        ArraySpec(mode = ArrayMode.CIRCLE, count = count),
        ArraySpec(mode = ArrayMode.CIRCLE, count = count, rotateCopies = false, sweepDeg = 120f),
        ArraySpec(mode = ArrayMode.CURVE, count = count, guide = lGuide),
        ArraySpec(mode = ArrayMode.TRANSFORM, count = count),
    )

    @Test
    fun copyZeroIsTheSourceInEveryMode() {
        for (spec in everyMode(5)) {
            val m = ArrayLayout.matrices(spec, src)
            assertEquals(spec.mode.name, 5, m.size)
            assertArrayEquals(spec.mode.name, identity, m[0], 0f)
        }
        // An array of one changes nothing; so does a CURVE array without a usable guide.
        for (spec in everyMode(1)) assertEquals(1, ArrayLayout.matrices(spec, src).size)
        for (g in listOf(null, VSubpath(listOf(VAnchor(5f, 5f))), VSubpath(listOf(VAnchor(5f, 5f), VAnchor(5f, 5f))))) {
            val m = ArrayLayout.matrices(ArraySpec(mode = ArrayMode.CURVE, count = 6, guide = g), src)
            assertEquals(1, m.size)
            assertArrayEquals(identity, m[0], 0f)
        }
    }

    @Test
    fun countIsSanitized() {
        assertEquals(1, ArrayLayout.matrices(ArraySpec(count = 0), src).size)
        assertEquals(ArraySpec.MAX_COUNT, ArrayLayout.matrices(ArraySpec(count = 100_000), src).size)
    }

    @Test
    fun lineRelativeHundredPercentMovesByTheWidth() {
        val m = ArrayLayout.matrices(ArraySpec(count = 4), src)
        for (k in 0 until 4) assertMatrix("copy $k", translate(40f * k, 0f), m[k])
        // Relative and constant offsets add, per copy.
        val n = ArrayLayout.matrices(ArraySpec(count = 3, relativeX = 0f, relativeY = 0.5f, constantX = 5f, constantY = 1f), src)
        assertMatrix("copy 2", translate(10f, 22f), n[2])
        assertEquals(RectF(10f, 20f, 130f, 40f), ArrayLayout.bounds(ArraySpec(count = 3), src))
    }

    @Test
    fun circleFullTurnOfFourStepsByNinetyDegrees() {
        val spec = ArraySpec(mode = ArrayMode.CIRCLE, count = 4, centerX = 100f, centerY = 100f)
        val m = ArrayLayout.matrices(spec, src)
        for (k in 1 until 4) assertMatrix("copy $k", rotation(90.0 * k, 100f, 100f), m[k])
        // The source's centre (30, 30) goes a quarter turn clockwise round (100, 100).
        val c = map(m[1], 30f, 30f)
        assertEquals(170f, c[0], 1e-3f)
        assertEquals(30f, c[1], 1e-3f)
    }

    @Test
    fun circlePartialSweepIncludesBothEnds() {
        val m = ArrayLayout.matrices(ArraySpec(mode = ArrayMode.CIRCLE, count = 3, sweepDeg = 180f, centerX = 0f, centerY = 0f), src)
        assertMatrix("copy 1", rotation(90.0), m[1])
        assertMatrix("copy 2", rotation(180.0), m[2])
        val back = ArrayLayout.matrices(ArraySpec(mode = ArrayMode.CIRCLE, count = 3, sweepDeg = -180f, centerX = 0f, centerY = 0f), src)
        assertMatrix("copy 1 the other way", rotation(-90.0), back[1])
    }

    @Test
    fun circleDefaultCentreIsBelowTheSourceAndCopiesMayKeepTheirAngle() {
        // Centre (30, 30 + 1.2 × 40) = (30, 78); half a turn takes the source's centre to (30, 126).
        val spec = ArraySpec(mode = ArrayMode.CIRCLE, count = 2, rotateCopies = false)
        val m = ArrayLayout.matrices(spec, src)
        assertMatrix("copy 1", translate(0f, 96f), m[1])
        assertEquals(30f to 78f, ArrayLayout.center(spec, src))
        val turned = ArrayLayout.matrices(spec.copy(rotateCopies = true), src)
        assertMatrix("copy 1 turned", rotation(180.0, 30f, 78f), turned[1])
    }

    @Test
    fun transformTurnThirtyPutsCopyThreeAtNinety() {
        val m = ArrayLayout.matrices(ArraySpec(mode = ArrayMode.TRANSFORM, count = 4, moveX = 0f, turnDeg = 30f), src)
        assertMatrix("copy 3", rotation(90.0, 30f, 30f), m[3])
        // Move and scale compound: copy 2 = M², the pivot goes 100 then 50 px.
        val s = ArrayLayout.matrices(ArraySpec(mode = ArrayMode.TRANSFORM, count = 3, moveX = 100f, turnDeg = 0f, scale = 0.5f), src)
        val p = map(s[2], 30f, 30f)
        assertEquals(180f, p[0], 1e-3f)
        assertEquals(30f, p[1], 1e-3f)
        assertEquals(0.25f, s[2][0], 1e-6f)
        assertEquals(0.25f, s[2][4], 1e-6f)
    }

    @Test
    fun curveSpreadsCopiesOverTheGuide() {
        val straight = VSubpath(listOf(VAnchor(0f, 0f), VAnchor(300f, 0f)))
        val m = ArrayLayout.matrices(ArraySpec(mode = ArrayMode.CURVE, count = 4, guide = straight), src)
        for (k in 0 until 4) assertMatrix("copy $k", translate(100f * k, 0f), m[k])
        // A spacing places copies every 120 px and drops those past the end.
        val spaced = ArrayLayout.matrices(ArraySpec(mode = ArrayMode.CURVE, count = 5, guide = straight, spacing = 120f), src)
        assertEquals(3, spaced.size)
        assertMatrix("copy 2", translate(240f, 0f), spaced[2])
        // A closed guide spreads count copies over its whole length (none on top of the source).
        val square = VSubpath(
            listOf(VAnchor(0f, 0f, sharp = true), VAnchor(100f, 0f, sharp = true), VAnchor(100f, 100f, sharp = true), VAnchor(0f, 100f, sharp = true)),
            closed = true,
        )
        val ring = ArrayLayout.matrices(ArraySpec(mode = ArrayMode.CURVE, count = 8, guide = square, alignToCurve = false), src)
        assertEquals(8, ring.size)
        assertMatrix("copy 2", translate(100f, 0f), ring[2])
        assertMatrix("copy 5", translate(50f, 100f), ring[5])
    }

    @Test
    fun curveAlignTurnsCopiesWithTheGuide() {
        // Length 200: copy 3 sits at 150, at (100, 50), heading down (90°).
        val m = ArrayLayout.matrices(ArraySpec(mode = ArrayMode.CURVE, count = 5, guide = lGuide), src)
        val c = map(m[3], 30f, 30f)
        assertEquals(130f, c[0], 1e-2f)
        assertEquals(80f, c[1], 1e-2f)
        assertEquals(0f, m[3][0], 1e-4f)
        assertEquals(-1f, m[3][1], 1e-4f)
        assertEquals(1f, m[3][3], 1e-4f)
        // Without align only the position follows.
        val plain = ArrayLayout.matrices(ArraySpec(mode = ArrayMode.CURVE, count = 5, guide = lGuide, alignToCurve = false), src)
        assertMatrix("copy 3 unaligned", translate(100f, 50f), plain[3], 1e-2f)
    }

    // ------------------------------------------------------------------ ArraySpec.mapped

    private fun mappedRect(m: FloatArray, r: RectF): RectF {
        val xs = floatArrayOf(r.left, r.right, r.right, r.left)
        val ys = floatArrayOf(r.top, r.top, r.bottom, r.bottom)
        val out = RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in 0 until 4) {
            val p = map(m, xs[i], ys[i])
            out.left = minOf(out.left, p[0]); out.right = maxOf(out.right, p[0])
            out.top = minOf(out.top, p[1]); out.bottom = maxOf(out.bottom, p[1])
        }
        return out
    }

    private fun times(p: FloatArray, q: FloatArray) = FloatArray(9) { i ->
        val r = i / 3; val c = i % 3
        p[3 * r] * q[c] + p[3 * r + 1] * q[3 + c] + p[3 * r + 2] * q[6 + c]
    }

    @Test
    fun mappedSpecsMoveTheCopiesWithTheDocument() {
        val maps = mapOf(
            "move" to translate(17f, -5f),
            "flip" to floatArrayOf(-1f, 0f, 200f, 0f, 1f, 0f, 0f, 0f, 1f),
            "quarter turn" to times(translate(300f, 10f), rotation(90.0, 50f, 50f)),
            "scale" to floatArrayOf(2f, 0f, 7f, 0f, 2f, -3f, 0f, 0f, 1f),
        )
        val specs = listOf(
            ArraySpec(count = 4, relativeX = 1f, relativeY = 0.5f, constantX = 5f, constantY = -3f),
            ArraySpec(mode = ArrayMode.CIRCLE, count = 4, sweepDeg = 270f).resolved(src),
            ArraySpec(mode = ArrayMode.CIRCLE, count = 3, rotateCopies = false).resolved(src),
            ArraySpec(mode = ArrayMode.TRANSFORM, count = 4, moveX = 40f, moveY = 10f, turnDeg = 25f, scale = 0.8f).resolved(src),
            ArraySpec(mode = ArrayMode.CURVE, count = 5, guide = lGuide),
            ArraySpec(mode = ArrayMode.CURVE, count = 5, guide = lGuide, spacing = 60f, alignToCurve = false),
        )
        val corners = listOf(src.left to src.top, src.right to src.top, src.right to src.bottom, src.left to src.bottom)
        for ((name, m) in maps) for (spec in specs) {
            val before = ArrayLayout.matrices(spec, src)
            val after = ArrayLayout.matrices(spec.mapped(m), mappedRect(m, src))
            assertEquals("$name ${spec.mode}", before.size, after.size)
            for (k in before.indices) for ((x, y) in corners) {
                val a = map(before[k], x, y).let { map(m, it[0], it[1]) }
                val b = map(m, x, y).let { map(after[k], it[0], it[1]) }
                assertEquals("$name ${spec.mode} copy $k x", a[0], b[0], 2e-2f)
                assertEquals("$name ${spec.mode} copy $k y", a[1], b[1], 2e-2f)
            }
        }
    }

    @Test
    fun mappedRefusesSingularMapsAndKeepsUnsetCentres() {
        val spec = ArraySpec(mode = ArrayMode.CIRCLE, count = 4)
        assertSame(spec, spec.mapped(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f)))
        assertSame(spec, spec.mapped(floatArrayOf(Float.NaN, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)))
        assertSame(spec, spec.mapped(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0.1f, 0f, 1f)))
        val moved = spec.mapped(translate(10f, 10f))
        assertEquals(null, moved.centerX)
        assertEquals(null, moved.pivotX)
        val flipped = ArraySpec(mode = ArrayMode.TRANSFORM, turnDeg = 30f, sweepDeg = 90f).mapped(floatArrayOf(-1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f))
        assertEquals(-30f, flipped.turnDeg, 0f)
        assertEquals(-90f, flipped.sweepDeg, 0f)
        assertEquals(-100f, flipped.moveX, 0f)
    }

    @Test
    fun sanitizedKeepsUsableValues() {
        val ok = ArraySpec(mode = ArrayMode.CURVE, guide = lGuide)
        assertSame(ok, ok.sanitized())
        val bad = ArraySpec(
            count = -4, relativeX = Float.NaN, constantY = Float.POSITIVE_INFINITY, centerX = Float.NaN, centerY = 5f,
            sweepDeg = 999f, spacing = -3f, turnDeg = Float.NaN, scale = 1000f, pivotX = 3e9f,
            guide = VSubpath(listOf(VAnchor(Float.NaN, 0f), VAnchor(1f, 2f, inX = Float.NaN, inY = 1f, outX = 1f, outY = 1f))),
            editingSource = true,
        ).sanitized()
        assertEquals(1, bad.count)
        assertEquals(1f, bad.relativeX, 0f)
        assertEquals(0f, bad.constantY, 0f)
        assertEquals(null, bad.centerX)
        assertEquals(5f, bad.centerY!!, 0f)
        assertEquals(360f, bad.sweepDeg, 0f)
        assertEquals(0f, bad.spacing, 0f)
        assertEquals(30f, bad.turnDeg, 0f)
        assertEquals(ArraySpec.MAX_SCALE, bad.scale, 0f)
        assertEquals(ArraySpec.MAX_COORD, bad.pivotX!!, 0f)
        assertEquals(listOf(VAnchor(1f, 2f, outX = 1f, outY = 1f)), bad.guide!!.anchors)
        // The loader, which knows the source kind, clears editingSource.
        assertEquals(true, bad.editingSource)
        val long = ArraySpec(guide = VSubpath(List(100) { VAnchor(it.toFloat(), 0f) })).sanitized()
        assertEquals(ArraySpec.MAX_GUIDE_ANCHORS, long.guide!!.anchors.size)
    }
}
