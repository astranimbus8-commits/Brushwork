package com.brushwork.paint.tools.text

import com.brushwork.paint.core.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** Pure geometry of text on a path: guides, arc length, orientation, bending, handles, transforms. */
class TextPathGeometryTest {

    private fun assertVec(msg: String, expected: Vec2, actual: Vec2, tol: Float = 1e-3f) {
        assertTrue("$msg: expected $expected, got $actual", abs(expected.x - actual.x) <= tol && abs(expected.y - actual.y) <= tol)
    }

    private fun guide(spec: TextPathSpec): TextPathGuide = requireNotNull(TextPathGeometry.guide(spec))

    private val circle = TextPathSpec(type = TextPathType.CIRCLE, cx = 500f, cy = 400f, radius = 100f, startAngleDeg = -90f)
    private val rect = TextPathSpec(type = TextPathType.RECT, cx = 200f, cy = 300f, width = 300f, height = 200f)
    private val curve = TextPathSpec(type = TextPathType.CURVE, x1 = 0f, y1 = 0f, cx1 = 100f, cy1 = -200f, cx2 = 300f, cy2 = 200f, x2 = 400f, y2 = 0f)
    private val line = TextPathSpec(type = TextPathType.LINE, x1 = 10f, y1 = 20f, x2 = 310f, y2 = 420f)

    // ------------------------------------------------------------------ guides

    @Test
    fun straightTextHasNoGuide() {
        assertNull(TextPathGeometry.guide(TextPathSpec()))
        assertEquals(0f, TextPathGeometry.pathLength(TextPathSpec()))
    }

    @Test
    fun lineLengthPositionTangentAndNormal() {
        val g = guide(line)
        assertFalse(g.closed)
        assertEquals(500.0, g.length, 1e-6)
        assertVec("start", Vec2(10f, 20f), g.posAt(0f))
        assertVec("middle", Vec2(160f, 220f), g.posAt(250f))
        assertVec("end", Vec2(310f, 420f), g.posAt(500f))
        assertVec("tangent", Vec2(0.6f, 0.8f), g.tangentAt(100f))
        // Left of the direction of travel on screen (y down).
        assertVec("normal", Vec2(0.8f, -0.6f), g.normalAt(100f))
        // Beyond the ends the text continues along the end tangents.
        assertVec("before the start", Vec2(-20f, -20f), g.posAt(-50f))
        assertVec("after the end", Vec2(340f, 460f), g.posAt(550f))
        assertVec("tangent after the end", Vec2(0.6f, 0.8f), g.tangentAt(900f))
    }

    @Test
    fun lettersOnALeftToRightLineFaceUp() {
        val g = guide(TextPathSpec(type = TextPathType.LINE, x1 = 0f, y1 = 50f, x2 = 100f, y2 = 50f))
        assertVec("up", Vec2(0f, -1f), g.normalAt(30f))
    }

    @Test
    fun degenerateLineStillPlacesText() {
        val g = guide(TextPathSpec(type = TextPathType.LINE, x1 = 40f, y1 = 60f, x2 = 40f, y2 = 60f))
        assertEquals(0.0, g.length, 0.0)
        assertVec("continues to the right", Vec2(45f, 60f), g.posAt(5f))
        assertVec("tangent", Vec2(1f, 0f), g.tangentAt(-3f))
    }

    @Test
    fun circleCircumferenceAnchorAndDirection() {
        val g = guide(circle)
        assertTrue(g.closed)
        assertEquals(2 * PI * 100, g.length, 1e-6)
        assertEquals(0.0, g.anchor, 0.0)
        assertVec("anchor at the start angle (top)", Vec2(500f, 300f), g.posAt(0f), 0.06f)
        assertVec("clockwise: to the right at the top", Vec2(1f, 0f), g.tangentAt(0f), 1e-3f)
        assertVec("up points away from the center", Vec2(0f, -1f), g.normalAt(0f), 1e-3f)
        val q = (g.length / 4).toFloat()
        assertVec("a quarter clockwise is the right side", Vec2(600f, 400f), g.posAt(q), 0.06f)
        assertVec("normal at the right side", Vec2(1f, 0f), g.normalAt(q), 1e-3f)
        assertVec("wraps around", g.posAt(10f), g.posAt((g.length + 10).toFloat()), 1e-2f)
        assertVec("wraps backwards", g.posAt((g.length - 10).toFloat()), g.posAt(-10f), 1e-2f)
        var d = 0f
        while (d < g.length) {
            assertEquals("on the circle at $d", 100f, g.posAt(d).distanceTo(Vec2(500f, 400f)), 0.06f)
            d += 3.7f
        }
    }

    @Test
    fun counterClockwiseCircleRunsTheOtherWayWithUpTowardTheCenter() {
        val g = guide(circle.copy(clockwise = false, startAngleDeg = 90f))
        assertVec("anchor at the bottom", Vec2(500f, 500f), g.posAt(0f), 0.06f)
        assertVec("reads left to right at the bottom", Vec2(1f, 0f), g.tangentAt(0f), 1e-3f)
        assertVec("up toward the center", Vec2(0f, -1f), g.normalAt(0f), 1e-3f)
        assertVec("a quarter on is the right side", Vec2(600f, 400f), g.posAt((g.length / 4).toFloat()), 0.06f)
    }

    @Test
    fun rectanglePerimeterWithSharpAndRoundedCorners() {
        assertEquals("sharp corners", 1000.0, guide(rect).length, 0.01)
        assertEquals("rounded corners", 1000.0 - (8 - 2 * PI) * 40, guide(rect.copy(cornerRadius = 40f)).length, 0.01)
        assertEquals("radius clamped to half the short side", 1000.0 - (8 - 2 * PI) * 100, guide(rect.copy(cornerRadius = 500f)).length, 0.01)
        assertEquals("rotation keeps the length", 1000.0, guide(rect.copy(rotationDeg = 33f)).length, 0.01)
    }

    @Test
    fun rectangleAnchorsTheTextOnTheMiddleOfTheTopEdge() {
        val g = guide(rect)
        assertVec("anchor", Vec2(200f, 200f), g.posAt(g.anchor.toFloat()))
        assertVec("reads to the right", Vec2(1f, 0f), g.tangentAt(g.anchor.toFloat()))
        assertVec("up is outwards", Vec2(0f, -1f), g.normalAt(g.anchor.toFloat()))
        // A quarter of the perimeter on: 150 px to the corner, then 100 px down the right side.
        assertVec("down the right edge", Vec2(350f, 300f), g.posAt((g.anchor + 250).toFloat()), 1e-2f)

        val ccw = guide(rect.copy(clockwise = false, startAngleDeg = 90f))
        assertVec("counter-clockwise anchor at the bottom", Vec2(200f, 400f), ccw.posAt(ccw.anchor.toFloat()))
        assertVec("reads left to right at the bottom", Vec2(1f, 0f), ccw.tangentAt(ccw.anchor.toFloat()))
        assertVec("up toward the center", Vec2(0f, -1f), ccw.normalAt(ccw.anchor.toFloat()))

        val side = guide(rect.copy(startAngleDeg = 0f))
        assertVec("text position on the right edge", Vec2(350f, 300f), side.posAt(side.anchor.toFloat()))

        val turned = guide(rect.copy(rotationDeg = 90f))
        assertVec("the anchor turns with the rectangle", Vec2(300f, 300f), turned.posAt(turned.anchor.toFloat()))
        assertVec("so does its tangent", Vec2(0f, 1f), turned.tangentAt(turned.anchor.toFloat()))
    }

    @Test
    fun curveLengthMatchesDenseSampling() {
        val g = guide(curve)
        fun b(t: Double, a: Float, c1: Float, c2: Float, d: Float): Double {
            val m = 1 - t
            return m * m * m * a + 3 * m * m * t * c1 + 3 * m * t * t * c2 + t * t * t * d
        }
        val n = 200_000
        var dense = 0.0
        var px = 0.0
        var py = 0.0
        val samples = ArrayList<Vec2>()
        for (i in 0..n) {
            val t = i.toDouble() / n
            val x = b(t, curve.x1, curve.cx1, curve.cx2, curve.x2)
            val y = b(t, curve.y1, curve.cy1, curve.cy2, curve.y2)
            if (i > 0) dense += hypot(x - px, y - py)
            px = x; py = y
            if (i % 100 == 0) samples += Vec2(x.toFloat(), y.toFloat())
        }
        assertEquals(dense, g.length, dense * 2e-4)
        assertVec("starts at p0", Vec2(0f, 0f), g.posAt(0f))
        assertVec("ends at p3", Vec2(400f, 0f), g.posAt(g.length.toFloat()))
        assertVec("starts toward c1", Vec2(100f, -200f).normalized(), g.tangentAt(0f))
        assertVec("ends coming from c2", Vec2(100f, -200f).normalized(), g.tangentAt(g.length.toFloat()))
        var d = 0f
        while (d < g.length) {
            val p = g.posAt(d)
            val nearest = samples.minOf { it.distanceTo(p) }
            assertTrue("point $d is on the curve (off by $nearest)", nearest < 1.5f)
            d += 11f
        }
    }

    @Test
    fun curveWithAControlPointOnItsEndHasADirection() {
        val g = guide(curve.copy(cx1 = 0f, cy1 = 0f))
        val t = g.tangentAt(0f)
        assertTrue("finite tangent $t", t.x.isFinite() && t.y.isFinite() && abs(t.length - 1f) < 1e-3f)
        assertTrue("points toward the second control point", t.dot(Vec2(300f, 200f).normalized()) > 0.9f)
        val flat = guide(curve.copy(x1 = 5f, y1 = 5f, cx1 = 5f, cy1 = 5f, cx2 = 5f, cy2 = 5f, x2 = 5f, y2 = 5f))
        assertEquals(0.0, flat.length, 0.0)
        assertVec("a point curve still places text", Vec2(15f, 5f), flat.posAt(10f))
    }

    // ------------------------------------------------------------------ placement

    @Test
    fun textStartFollowsAlignmentAndOffset() {
        val g = guide(TextPathSpec(type = TextPathType.LINE, x1 = 0f, y1 = 0f, x2 = 500f, y2 = 0f))
        fun start(a: TextPathAlign, off: Float = 0f) = TextPathGeometry.startDistance(TextPathSpec(type = TextPathType.LINE, align = a, offset = off), g, 100f)
        assertEquals(0.0, start(TextPathAlign.START), 1e-9)
        assertEquals(200.0, start(TextPathAlign.CENTER), 1e-9)
        assertEquals(400.0, start(TextPathAlign.END), 1e-9)
        assertEquals(210.0, start(TextPathAlign.CENTER, 10f), 1e-9)
        val cg = guide(circle)
        assertEquals(-50.0, TextPathGeometry.startDistance(circle, cg, 100f), 1e-9)
        assertEquals(0.0, TextPathGeometry.startDistance(circle.copy(align = TextPathAlign.START), cg, 100f), 1e-9)
        assertEquals(-100.0, TextPathGeometry.startDistance(circle.copy(align = TextPathAlign.END), cg, 100f), 1e-9)
    }

    /** Baseline point, cap-top point and up direction of the middle of a 200 px text on [spec] (capitals 70 px tall). */
    private fun letter(spec: TextPathSpec): Triple<Vec2, Vec2, Vec2> {
        val g = guide(spec)
        val s0 = TextPathGeometry.startDistance(spec, g, 200f)
        val shift = TextPathGeometry.heightOffset(spec, 70f)
        val base = TextPathGeometry.placePoint(g, s0, shift, 100f, 0f)
        val top = TextPathGeometry.placePoint(g, s0, shift, 100f, -70f)
        return Triple(base, top, (top - base).normalized())
    }

    @Test
    fun outsideClockwiseLettersStandOnTheCircleFacingOut() {
        val c = Vec2(500f, 400f)
        val (base, top, up) = letter(circle)
        assertVec("baseline on the top of the circle", Vec2(500f, 300f), base, 0.1f)
        assertEquals("capitals reach outwards", 170f, top.distanceTo(c), 0.2f)
        assertTrue("glyph up points away from the center", up.dot((base - c).normalized()) > 0.99f)
    }

    @Test
    fun insideCounterClockwiseLettersStandInsideFacingTheCenter() {
        val c = Vec2(500f, 400f)
        val spec = circle.copy(side = TextPathSide.INSIDE, clockwise = false, startAngleDeg = 90f)
        assertTrue(TextPathGeometry.isStanding(spec))
        val (base, top, up) = letter(spec)
        assertVec("baseline on the bottom of the circle", Vec2(500f, 500f), base, 0.1f)
        assertEquals("capitals reach inwards", 30f, top.distanceTo(c), 0.2f)
        assertTrue("glyph up points toward the center", up.dot((c - base).normalized()) > 0.99f)
        assertTrue("and reads left to right", guide(spec).tangentAt(0f).x > 0.99f)
    }

    @Test
    fun insideClockwiseLettersHangInsideTheCircle() {
        val c = Vec2(500f, 400f)
        val spec = circle.copy(side = TextPathSide.INSIDE)
        assertFalse(TextPathGeometry.isStanding(spec))
        val (base, top, up) = letter(spec)
        assertEquals("cap line on the circle", 100f, top.distanceTo(c), 0.2f)
        assertEquals("baseline inside", 30f, base.distanceTo(c), 0.2f)
        assertTrue("still upright at the top", up.y < -0.99f)
    }

    @Test
    fun outsideCounterClockwiseLettersHangOutsideTheCircle() {
        val c = Vec2(500f, 400f)
        val spec = circle.copy(clockwise = false, startAngleDeg = 90f)
        val (base, top, up) = letter(spec)
        assertEquals("cap line on the circle", 100f, top.distanceTo(c), 0.2f)
        assertEquals("baseline outside", 170f, base.distanceTo(c), 0.2f)
        assertTrue("upright at the bottom", up.y < -0.99f)
    }

    @Test
    fun baselineShiftMovesLettersAwayFromThePathOnTheirSide() {
        val c = Vec2(500f, 400f)
        val (standing, _, _) = letter(circle.copy(baselineShift = 10f))
        assertEquals(110f, standing.distanceTo(c), 0.2f)
        val (_, hangingTop, _) = letter(circle.copy(side = TextPathSide.INSIDE, baselineShift = 10f))
        assertEquals(90f, hangingTop.distanceTo(c), 0.2f)
        // Open paths: letters always stand on the path.
        assertEquals(12.0, TextPathGeometry.heightOffset(line.copy(side = TextPathSide.INSIDE, clockwise = false, baselineShift = 12f), 70f), 1e-9)
    }

    // ------------------------------------------------------------------ bending

    /** A closed box glyph from x0..x1, y from -height (top) to 0 (baseline), as android would outline it. */
    private fun bar(x0: Float, x1: Float, height: Float) = floatArrayOf(x0, 0f, x1, 0f, x1, -height, x0, -height, x0, 0f)

    @Test
    fun straightGlyphEdgesBendAlongTheCircle() {
        val g = guide(circle.copy(radius = 200f))
        val c = Vec2(500f, 400f)
        val s0 = TextPathGeometry.startDistance(circle, g, 300f)
        val out = TextPathWarp.warp(listOf(bar(0f, 300f, 50f)), g, s0, 0.0, 0.25).single()
        val pts = (0 until out.size / 2).map { Vec2(out[2 * it], out[2 * it + 1]) }
        assertTrue("edges were split (${pts.size} points)", pts.size > 40)
        for (p in pts) {
            val d = p.distanceTo(c)
            assertTrue("point at distance $d", d > 200f - 0.1f && d < 250f + 0.1f)
        }
        // The outer edge (at 250 px) is a smooth arc: every chord midpoint stays within tolerance.
        val outer = pts.filter { abs(it.distanceTo(c) - 250f) < 0.1f }
        assertTrue(outer.size > 20)
        for (i in 1 until outer.size) {
            val mid = (outer[i - 1] + outer[i]) / 2f
            if (outer[i - 1].distanceTo(outer[i]) < 60f) assertTrue(250f - mid.distanceTo(c) < 0.3f)
        }
    }

    @Test
    fun lettersFanAroundASharpCornerInsteadOfTearing() {
        val square = TextPathSpec(type = TextPathType.RECT, cx = 300f, cy = 300f, width = 400f, height = 400f)
        val g = guide(square)
        // The bar spans 150..250 px from the top middle: the top-right corner is at 200.
        val s0 = g.anchor + 150.0
        val out = TextPathWarp.warp(listOf(bar(0f, 100f, 60f)), g, s0, 0.0, 0.25).single()
        val pts = (0 until out.size / 2).map { Vec2(out[2 * it], out[2 * it + 1]) }
        val corner = Vec2(500f, 100f)
        // In the wedge beyond the corner the outline is finely subdivided (a torn letter would
        // jump across it with one long edge).
        fun inWedge(p: Vec2) = p.x >= corner.x - 0.01f && p.y <= corner.y + 0.01f
        var nearCorner = 0
        for (i in 1 until pts.size) {
            if (inWedge(pts[i - 1]) && inWedge(pts[i])) {
                nearCorner++
                assertTrue("no jump between ${pts[i - 1]} and ${pts[i]}", pts[i - 1].distanceTo(pts[i]) < 10f)
            }
        }
        assertTrue("the corner is subdivided ($nearCorner steps)", nearCorner >= 8)
        for (p in pts) {
            assertTrue("$p stays outside the square", max(abs(p.x - 300f), abs(p.y - 300f)) > 200f - 0.1f)
        }
        val diagonal = corner + Vec2(1f, -1f).normalized() * 60f
        assertTrue("the outer edge fans round the corner", pts.any { it.distanceTo(diagonal) < 0.5f })
    }

    @Test
    fun curvatureTellsTurnsApart() {
        val k = DoubleArray(2)
        guide(circle).curvatureIn(10.0, 40.0, k)
        assertEquals("clockwise turns right", 1.0 / 100, k[0], 1e-6)
        assertEquals(0.0, k[1], 0.0)
        guide(circle.copy(clockwise = false)).curvatureIn(-40.0, -10.0, k)
        assertEquals(0.0, k[0], 0.0)
        assertEquals("counter-clockwise turns left", 1.0 / 100, k[1], 1e-6)
        guide(line).curvatureIn(0.0, 500.0, k)
        assertEquals(0.0, max(k[0], k[1]), 0.0)
        val c = guide(curve)
        c.curvatureIn(-300.0, -10.0, k)
        assertEquals("before the start of an open path it is straight", 0.0, max(k[0], k[1]), 0.0)
        val sq = guide(rect)
        sq.curvatureIn(sq.anchor + 10, sq.anchor + 100, k)
        assertEquals("along an edge", 0.0, max(k[0], k[1]), 1e-9)
        sq.curvatureIn(sq.anchor + 140, sq.anchor + 160, k)
        assertTrue("a sharp corner is extremely curved (${k[0]})", k[0] > 100.0)
    }

    @Test
    fun lettersBendUnlessThePathTurnsTooTightlyForThem() {
        val sq = guide(rect)
        val a = sq.anchor
        // 40 px wide letters with ink from 10 px below to 50 px above the path.
        assertFalse("on an edge", TextPathGeometry.tooCurvedToBend(sq, a + 20, a + 60, -10.0, 50.0))
        assertTrue("across a sharp corner", TextPathGeometry.tooCurvedToBend(sq, a + 130, a + 170, -10.0, 50.0))
        val rounded = guide(rect.copy(cornerRadius = 60f))
        assertFalse("round a gentle corner", TextPathGeometry.tooCurvedToBend(rounded, rounded.anchor + 80, rounded.anchor + 120, -10.0, 50.0))
        assertTrue("hanging deep inside a tight corner", TextPathGeometry.tooCurvedToBend(rounded, rounded.anchor + 80, rounded.anchor + 120, -80.0, 0.0))
        val big = guide(circle.copy(radius = 300f))
        assertFalse(TextPathGeometry.tooCurvedToBend(big, 0.0, 40.0, -10.0, 50.0))
        val small = guide(circle.copy(radius = 30f, clockwise = false))
        assertTrue("tall letters inside a small circle would fold", TextPathGeometry.tooCurvedToBend(small, 0.0, 20.0, -5.0, 40.0))
    }

    @Test
    fun textLongerThanAClosedPathWrapsAround() {
        val g = guide(circle)
        val a = TextPathGeometry.placePoint(g, 0.0, 0.0, 10f, 0f)
        val b = TextPathGeometry.placePoint(g, 0.0, 0.0, (g.length + 10).toFloat(), 0f)
        assertVec("one lap later", a, b, 0.01f)
        val warped = TextPathWarp.warp(listOf(bar(0f, (g.length * 1.5).toFloat(), 20f)), g, 0.0, 0.0, 0.25).single()
        assertTrue(warped.isNotEmpty())
        for (i in 0 until warped.size / 2) {
            val d = Vec2(warped[2 * i], warped[2 * i + 1]).distanceTo(Vec2(500f, 400f))
            assertTrue(d > 99.8f && d < 120.2f)
        }
    }

    // ------------------------------------------------------------------ handles

    private val samples = listOf(
        line,
        circle.copy(startAngleDeg = -60f),
        rect.copy(cornerRadius = 30f, rotationDeg = 20f, startAngleDeg = -80f),
        rect.copy(clockwise = false, startAngleDeg = 90f),
        curve,
    )

    private fun assertSpecNear(msg: String, a: TextPathSpec, b: TextPathSpec, tol: Float = 2e-3f) {
        val fa = floatArrayOf(a.x1, a.y1, a.x2, a.y2, a.cx1, a.cy1, a.cx2, a.cy2, a.cx, a.cy, a.radius, a.width, a.height, a.cornerRadius, a.offset, a.baselineShift)
        val fb = floatArrayOf(b.x1, b.y1, b.x2, b.y2, b.cx1, b.cy1, b.cx2, b.cy2, b.cx, b.cy, b.radius, b.width, b.height, b.cornerRadius, b.offset, b.baselineShift)
        for (i in fa.indices) assertEquals("$msg field $i", fa[i], fb[i], tol * max(1f, abs(fa[i])))
        fun angle(x: Float, y: Float) = abs(TextPathGeometry.normalizeDegrees(x - y))
        assertTrue("$msg start angle ${a.startAngleDeg} vs ${b.startAngleDeg}", angle(a.startAngleDeg, b.startAngleDeg) < 0.01f)
        assertTrue("$msg rotation ${a.rotationDeg} vs ${b.rotationDeg}", angle(a.rotationDeg, b.rotationDeg) < 0.01f)
        assertEquals(msg, a.copy(x1 = 0f, y1 = 0f, x2 = 0f, y2 = 0f, cx1 = 0f, cy1 = 0f, cx2 = 0f, cy2 = 0f, cx = 0f, cy = 0f, radius = 0f, width = 0f, height = 0f, cornerRadius = 0f, offset = 0f, baselineShift = 0f, startAngleDeg = 0f, rotationDeg = 0f),
            b.copy(x1 = 0f, y1 = 0f, x2 = 0f, y2 = 0f, cx1 = 0f, cy1 = 0f, cx2 = 0f, cy2 = 0f, cx = 0f, cy = 0f, radius = 0f, width = 0f, height = 0f, cornerRadius = 0f, offset = 0f, baselineShift = 0f, startAngleDeg = 0f, rotationDeg = 0f))
    }

    @Test
    fun movingAHandleOntoItselfChangesNothing() {
        val counts = mapOf(TextPathType.LINE to 3, TextPathType.CIRCLE to 3, TextPathType.RECT to 5, TextPathType.CURVE to 5)
        for (s in samples) {
            val h = TextPathGeometry.handles(s)
            assertEquals("${s.type} handle count", counts.getValue(s.type), h.size)
            for (i in h.indices) assertSpecNear("${s.type} handle $i", s, TextPathGeometry.moveHandle(s, i, h[i]))
            assertEquals("unknown index", s, TextPathGeometry.moveHandle(s, 99, Vec2(1f, 2f)))
        }
    }

    @Test
    fun handlesSitWhereTheyShould() {
        val hc = TextPathGeometry.handles(circle)
        assertVec("circle center", Vec2(500f, 400f), hc[0])
        assertVec("radius handle opposite the text", Vec2(500f, 500f), hc[1])
        assertVec("text position handle", Vec2(500f, 300f), hc[2])
        val hr = TextPathGeometry.handles(rect)
        assertVec("rect center", Vec2(200f, 300f), hr[0])
        assertVec("size handle on the bottom-right corner", Vec2(350f, 400f), hr[1])
        assertVec("rotation handle beyond the right edge", Vec2(350f + 60f, 300f), hr[2])
        assertVec("text position on the top edge", Vec2(200f, 200f), hr[4])
        assertVec("curve middle", Vec2(200f, 0f), TextPathGeometry.handles(curve)[4])
        assertTrue(TextPathGeometry.handles(TextPathSpec()).isEmpty())
        assertEquals(TextPathSpec(), TextPathGeometry.moveHandle(TextPathSpec(), 0, Vec2(3f, 4f)))
    }

    @Test
    fun lineHandlesMoveTheEndsAndTheWholeLine() {
        val a = TextPathGeometry.moveHandle(line, 0, Vec2(0f, 0f))
        assertEquals(Vec2(0f, 0f), Vec2(a.x1, a.y1))
        assertEquals(Vec2(310f, 420f), Vec2(a.x2, a.y2))
        val b = TextPathGeometry.moveHandle(line, 2, Vec2(170f, 230f))
        assertEquals(Vec2(20f, 30f), Vec2(b.x1, b.y1))
        assertEquals(Vec2(320f, 430f), Vec2(b.x2, b.y2))
    }

    @Test
    fun circleHandlesSetRadiusAndTextPosition() {
        val r = TextPathGeometry.moveHandle(circle, 1, Vec2(500f, 650f))
        assertEquals(250f, r.radius, 1e-3f)
        assertEquals(-90f, r.startAngleDeg, 1e-3f)
        val a = TextPathGeometry.moveHandle(circle, 2, Vec2(900f, 400f))
        assertEquals(0f, a.startAngleDeg, 1e-3f)
        assertEquals("the text position handle keeps the radius", 100f, a.radius, 1e-3f)
        val tiny = TextPathGeometry.moveHandle(circle, 1, Vec2(500f, 400f))
        assertEquals(TextPathGeometry.MIN_EXTENT, tiny.radius, 0f)
        val moved = TextPathGeometry.moveHandle(circle, 0, Vec2(10f, 20f))
        assertEquals(Vec2(10f, 20f), Vec2(moved.cx, moved.cy))
    }

    @Test
    fun rectangleSizeHandleResizesAroundTheCenterAndCanKeepItSquare() {
        val free = TextPathGeometry.moveHandle(rect, 1, Vec2(400f, 350f))
        assertEquals(400f, free.width, 1e-3f)
        assertEquals(100f, free.height, 1e-3f)
        assertEquals(Vec2(200f, 300f), Vec2(free.cx, free.cy))
        val square = TextPathGeometry.moveHandle(rect.copy(keepSquare = true), 1, Vec2(400f, 350f))
        assertEquals(square.width, square.height, 1e-3f)
        assertEquals(250f, square.width, 1e-3f)
        val rounded = TextPathGeometry.moveHandle(rect.copy(cornerRadius = 90f), 1, Vec2(260f, 340f))
        assertEquals("corner radius shrinks with the rectangle", 40f, rounded.cornerRadius, 1e-3f)
        // Handles of a turned rectangle work in its own frame.
        val turned = rect.copy(rotationDeg = 90f)
        val t = TextPathGeometry.moveHandle(turned, 1, TextPathGeometry.handles(turned)[1] + Vec2(-20f, 0f))
        assertEquals(300f, t.width, 1e-3f)
        assertEquals(240f, t.height, 1e-3f)
    }

    @Test
    fun rectangleRotationCornerAndTextPositionHandles() {
        val rot = TextPathGeometry.moveHandle(rect, 2, Vec2(200f, 500f))
        assertEquals(90f, rot.rotationDeg, 1e-3f)
        val corner = TextPathGeometry.moveHandle(rect, 3, Vec2(50f + 100f, 200f + 100f))
        assertEquals("dragged to the middle: the largest radius", 100f, corner.cornerRadius, 1e-3f)
        val none = TextPathGeometry.moveHandle(rect, 3, Vec2(0f, 0f))
        assertEquals(0f, none.cornerRadius, 0f)
        val pos = TextPathGeometry.moveHandle(rect.copy(rotationDeg = 90f), 4, Vec2(200f, 900f))
        assertEquals("text position is relative to the rectangle", 0f, pos.startAngleDeg, 1e-3f)
    }

    @Test
    fun curveHandlesMoveControlPointsAndTheWholeCurve() {
        val c1 = TextPathGeometry.moveHandle(curve, 1, Vec2(7f, 8f))
        assertEquals(Vec2(7f, 8f), Vec2(c1.cx1, c1.cy1))
        val c2 = TextPathGeometry.moveHandle(curve, 2, Vec2(9f, 10f))
        assertEquals(Vec2(9f, 10f), Vec2(c2.cx2, c2.cy2))
        val end = TextPathGeometry.moveHandle(curve, 3, Vec2(11f, 12f))
        assertEquals(Vec2(11f, 12f), Vec2(end.x2, end.y2))
        val all = TextPathGeometry.moveHandle(curve, 4, Vec2(210f, 5f))
        assertEquals(Vec2(10f, 5f), Vec2(all.x1, all.y1))
        assertEquals(Vec2(310f, 205f), Vec2(all.cx2, all.cy2))
    }

    // ------------------------------------------------------------------ transforms

    @Test
    fun transformedMovesEveryHandleLikeAPointAndInvertsExactly() {
        val t = Vec2(40f, -25f)
        val pivot = Vec2(300f, 250f)
        val k = 1.5f
        val deg = 30f
        val rad = (deg * PI / 180).toFloat()
        for (s0 in samples) {
            val s = s0.copy(offset = 12f, baselineShift = 4f)
            val m = TextPathGeometry.transformed(s, t, k, deg, pivot)
            val before = TextPathGeometry.handles(s)
            val after = TextPathGeometry.handles(m)
            for (i in before.indices) {
                val expected = pivot + (before[i] - pivot).rotated(rad) * k + t
                assertVec("${s.type} handle $i", expected, after[i], 0.05f)
            }
            assertEquals(s.offset * k, m.offset, 1e-4f)
            assertEquals(s.baselineShift * k, m.baselineShift, 1e-4f)
            val back = TextPathGeometry.transformed(m, -t, 1f / k, -deg, pivot + t)
            assertSpecNear("${s.type} round trip", s, back)
            val len = TextPathGeometry.guide(s)!!.length * k
            assertEquals("${s.type} length scales", len, TextPathGeometry.guide(m)!!.length, 1e-3 * len)
        }
    }

    @Test
    fun transformedIgnoresNonsense() {
        val m = TextPathGeometry.transformed(circle, Vec2(Float.NaN, 1f), Float.NaN, Float.POSITIVE_INFINITY, Vec2(0f, 0f))
        assertEquals(circle.radius, m.radius, 0f)
        assertEquals(circle.cx, m.cx, 0f)
        assertEquals(circle.cy + 1f, m.cy, 0f)
    }

    // ------------------------------------------------------------------ defaults

    @Test
    fun defaultShapesKeepTheTextWhereItWas() {
        val center = Vec2(500f, 400f)
        val width = 600f
        val fs = 50f
        val baseline = 400f + 0.35f * fs
        val current = TextPathSpec(mode = TextPathMode.ROTATE, offset = 7f, radius = 123f, x1 = 1f)
        for (type in listOf(TextPathType.LINE, TextPathType.CIRCLE, TextPathType.RECT, TextPathType.CURVE)) {
            val s = TextPathGeometry.defaultFor(type, center, width, fs, current)
            assertEquals(type, s.type)
            assertEquals("mode kept", TextPathMode.ROTATE, s.mode)
            assertEquals("offset kept", 7f, s.offset)
            val g = guide(s)
            val mid = TextPathGeometry.placePoint(g, TextPathGeometry.startDistance(s.copy(offset = 0f), g, width), 0.0, width / 2f, 0f)
            assertVec("$type: middle of the baseline stays put", Vec2(500f, baseline), mid, 0.5f)
            assertTrue("$type: the text faces up", g.normalAt((TextPathGeometry.startDistance(s.copy(offset = 0f), g, width) + width / 2).toFloat()).y < -0.99f)
        }
        val l = TextPathGeometry.defaultFor(TextPathType.LINE, center, width, fs, current)
        assertEquals(200f, l.x1, 1e-3f)
        assertEquals(800f, l.x2, 1e-3f)
        assertEquals("other shapes keep their numbers", 123f, l.radius)
        val c = TextPathGeometry.defaultFor(TextPathType.CIRCLE, center, width, fs, current)
        assertEquals(width / (1.2f * PI.toFloat()), c.radius, 1e-3f)
        assertEquals(-90f, c.startAngleDeg)
        val small = TextPathGeometry.defaultFor(TextPathType.CIRCLE, center, 60f, fs, current)
        assertEquals("at least two font sizes", 100f, small.radius, 1e-3f)
        val r = TextPathGeometry.defaultFor(TextPathType.RECT, center, width, fs, current)
        assertEquals(r.width, r.height, 0f)
        assertEquals(baseline, r.cy - r.height / 2f, 1e-3f)
        val cu = TextPathGeometry.defaultFor(TextPathType.CURVE, center, width, fs, current)
        assertTrue("an arch: the ends are lower than the middle", cu.y1 > baseline && cu.y2 > baseline)
        assertTrue("as wide as the text", cu.x2 - cu.x1 >= width)
    }

    @Test
    fun defaultCircleForBottomTextSitsUnderTheOldPosition() {
        val center = Vec2(500f, 400f)
        val ccw = TextPathSpec(clockwise = false, side = TextPathSide.INSIDE)
        val s = TextPathGeometry.defaultFor(TextPathType.CIRCLE, center, 300f, 40f, ccw)
        assertEquals(90f, s.startAngleDeg)
        assertEquals("the bottom of the circle is the old baseline", 400f + 14f, s.cy + s.radius, 1e-3f)
        val hanging = TextPathGeometry.defaultFor(TextPathType.CIRCLE, center, 300f, 40f, TextPathSpec(clockwise = false))
        assertEquals("hanging letters: the cap line is on the circle", 400f + 14f - 28f, hanging.cy + hanging.radius, 1e-3f)
        assertEquals(TextPathType.NONE, TextPathGeometry.defaultFor(TextPathType.NONE, center, 1f, 1f, s).type)
    }

    // ------------------------------------------------------------------ nonsense input

    private fun finite(v: Vec2) = v.x.isFinite() && v.y.isFinite()

    /** Every shape with one number of it set to [bad]. */
    private fun spoiled(bad: Float): List<TextPathSpec> {
        val out = ArrayList<TextPathSpec>()
        for (base in samples + listOf(circle.copy(clockwise = false))) {
            out += base.copy(x1 = bad)
            out += base.copy(cy2 = bad)
            out += base.copy(cx = bad)
            out += base.copy(radius = bad)
            out += base.copy(width = bad)
            out += base.copy(height = bad)
            out += base.copy(cornerRadius = bad)
            out += base.copy(rotationDeg = bad)
            out += base.copy(startAngleDeg = bad)
            out += base.copy(offset = bad)
            out += base.copy(baselineShift = bad)
        }
        return out
    }

    @Test(timeout = 20_000)
    fun nonFiniteOrHugeNumbersNeitherCrashNorHang() {
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 1e30f, -1e30f, 3e38f)) {
            for (s in spoiled(bad)) {
                val g = TextPathGeometry.guide(s)!!
                assertTrue("$bad in $s: length ${g.length}", g.length.isFinite())
                if (s.type.isClosed) assertTrue("$bad: a closed path has a length", g.length > 0.0)
                val start = TextPathGeometry.startDistance(s, g, 300f)
                val shift = TextPathGeometry.heightOffset(s, 50f)
                assertTrue("$bad: start $start, shift $shift", start.isFinite() && shift.isFinite())
                assertTrue("$bad: placed", finite(TextPathGeometry.placePoint(g, start, shift, 10f, -20f)))
                val out = TextPathWarp.warp(listOf(bar(0f, 300f, 50f)), g, start, shift, 0.25).single()
                assertTrue("$bad: bent outline (${out.size / 2} points)", out.size in 8..2_000_000)
                assertTrue("$bad: bent points are numbers", out.all { it.isFinite() })
                for (h in TextPathGeometry.handles(s)) assertTrue("$bad: handle $h of $s", finite(h))
                for (i in TextPathGeometry.handles(s).indices) TextPathGeometry.moveHandle(s, i, Vec2(10f, 20f))
                assertTrue(TextPathGeometry.pathLength(s).isFinite())
            }
        }
    }

    @Test(timeout = 20_000)
    fun aHugeOffsetOnAClosedPathIsTheSameAsItsRemainder() {
        val g = guide(circle)
        val len = g.length
        for (laps in listOf(3.0, 1000.0, 1e7, 1e30)) {
            val off = (laps * len + 25.0).toFloat()
            val start = TextPathGeometry.startDistance(circle.copy(offset = off), g, 100f)
            if (off < TextPathGeometry.MAX_COORD) {
                val plain = TextPathGeometry.startDistance(circle.copy(offset = Math.IEEEremainder(off.toDouble(), len).toFloat()), g, 100f)
                assertEquals("$laps laps", plain, start, 1e-3)
                assertEquals("$laps laps: 25 px on", -25.0, start, 0.05 * laps)
            }
            assertTrue("kept within about a lap of the anchor ($start)", abs(start) <= len)
            // Bending at such an offset returns (and lands on the circle).
            val out = TextPathWarp.warp(listOf(bar(0f, 100f, 20f)), g, start, 0.0, 0.25).single()
            for (i in 0 until out.size / 2) {
                val d = Vec2(out[2 * i], out[2 * i + 1]).distanceTo(Vec2(500f, 400f))
                assertTrue("$laps laps: point at $d", d > 99.8f && d < 120.2f)
            }
        }
        // Far along an open path the text simply runs off along the end.
        val lg = guide(line)
        val far = TextPathGeometry.startDistance(line.copy(offset = 1e30f), lg, 100f)
        assertTrue(far.isFinite())
        assertTrue(TextPathWarp.warp(listOf(bar(0f, 100f, 20f)), lg, far, 0.0, 0.25).single().isNotEmpty())
    }

    @Test(timeout = 20_000)
    fun bendingNeverStallsFarAlongAClosedPath() {
        // Arc lengths so large that a lap no longer changes them: the outline still comes back.
        for (r in listOf(100f, 1f)) {
            val g = guide(circle.copy(radius = r))
            for (start in listOf(1e17, -1e17, 1e25)) {
                val out = TextPathWarp.warp(listOf(bar(0f, 100f, 20f)), g, start, 0.0, 0.25).single()
                assertTrue("r $r, $start: ${out.size / 2} points", out.size in 8..2_000_000)
            }
        }
    }

    @Test
    fun cleanSpecsAreLeftAloneAndBrokenOnesFixed() {
        for (s in samples + TextPathSpec()) assertTrue("${s.type} is the same instance", TextPathGeometry.sanitized(s) === s)
        val fixed = TextPathGeometry.sanitized(circle.copy(radius = Float.NaN, offset = Float.POSITIVE_INFINITY, cx = 1e30f, cy = -1e30f, rotationDeg = 725f))
        assertEquals("NaN falls back to the default", TextPathSpec().radius, fixed.radius, 0f)
        assertEquals(0f, fixed.offset, 0f)
        assertEquals(TextPathGeometry.MAX_COORD, fixed.cx, 0f)
        assertEquals(-TextPathGeometry.MAX_COORD, fixed.cy, 0f)
        assertEquals("angles beyond a turn come back", 5f, fixed.rotationDeg, 1e-3f)
        assertEquals("the rest is kept", circle.startAngleDeg, fixed.startAngleDeg, 0f)
    }

    @Test
    fun nonFinitePivotCenterOrSizeAreIgnored() {
        val t = TextPathGeometry.transformed(circle, Vec2(5f, 6f), 2f, 30f, Vec2(Float.NaN, 0f))
        assertEquals("translated only", 505f, t.cx, 1e-3f)
        assertEquals(406f, t.cy, 1e-3f)
        assertEquals(circle.radius, t.radius, 0f)
        val huge = TextPathGeometry.transformed(circle, Vec2(0f, 0f), 1e38f, 0f, Vec2(0f, 0f))
        assertTrue("clamped: ${huge.radius}, ${huge.cx}", huge.radius.isFinite() && huge.cx.isFinite() && huge.offset.isFinite())
        for (type in listOf(TextPathType.LINE, TextPathType.CIRCLE, TextPathType.RECT, TextPathType.CURVE)) {
            val d = TextPathGeometry.defaultFor(type, Vec2(Float.NaN, Float.POSITIVE_INFINITY), 300f, 40f, TextPathSpec())
            for (h in TextPathGeometry.handles(d)) assertTrue("$type default handle $h", finite(h))
            assertTrue(TextPathGeometry.guide(d)!!.length > 0.0)
        }
    }

    @Test
    fun circleGuideUsesTheExactArcLength() {
        for (r in listOf(1f, 3f, 40f, 1000f, 20000f)) {
            val g = guide(circle.copy(radius = r))
            assertEquals("r = $r", 2 * PI * r, g.length, 1e-6 * r)
            val p = g.posAt((g.length / 3).toFloat())
            val expected = Vec2(500f + r * cos(-PI / 2 + 2 * PI / 3).toFloat(), 400f + r * sin(-PI / 2 + 2 * PI / 3).toFloat())
            assertVec("r = $r", expected, p, 0.06f + r * 1e-6f)
        }
    }
}
