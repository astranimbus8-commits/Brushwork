package com.brushwork.paint.assist

import com.brushwork.paint.model.RulerSettings
import com.brushwork.paint.model.RulerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RulerGeometryTest {
    private val docPerDp = 1f

    @Test
    fun angleSnapsToFifteenDegreesWithinTwo() {
        assertEquals(15f, RulerGeometry.snapAngle(14f), 0f)
        assertEquals(12.9f, RulerGeometry.snapAngle(12.9f), 0f)
        assertEquals(45f, RulerGeometry.snapAngle(46.5f), 0f)
        assertEquals(-90f, RulerGeometry.snapAngle(-91.9f), 0f)
        assertEquals(180f, RulerGeometry.snapAngle(178.5f), 0f)
        assertEquals(180f, RulerGeometry.snapAngle(-179f), 0f)
        assertEquals(37f, RulerGeometry.snapAngle(37f), 0f)
    }

    @Test
    fun anglesNormalizeToHalfOpenRange() {
        assertEquals(180f, RulerGeometry.normalizeAngle(-180f), 0f)
        assertEquals(-170f, RulerGeometry.normalizeAngle(190f), 1e-4f)
        assertEquals(10f, RulerGeometry.normalizeAngle(730f), 1e-4f)
    }

    @Test
    fun hitTestFindsHandlesAndPrefersResizeOverCenter() {
        val r = RulerSettings(type = RulerType.ELLIPSE, centerX = 100f, centerY = 100f, radiusX = 80f, radiusY = 40f)
        assertEquals(RulerHandle.CENTER, RulerGeometry.hitHandle(r, 103f, 98f, docPerDp))
        assertEquals(RulerHandle.RADIUS_X, RulerGeometry.hitHandle(r, 181f, 100f, docPerDp))
        assertEquals(RulerHandle.RADIUS_Y, RulerGeometry.hitHandle(r, 100f, 139f, docPerDp))
        assertEquals(RulerHandle.ROTATE, RulerGeometry.hitHandle(r, 100f + 80f + RulerGeometry.ELLIPSE_ROTATE_GAP_DP, 102f, docPerDp))
        assertNull(RulerGeometry.hitHandle(r, 300f, 300f, docPerDp))
        // A tiny circle: its radius handle sits on the center but still wins when touched there.
        val tiny = RulerSettings(type = RulerType.CIRCLE, centerX = 50f, centerY = 50f, radius = 2f)
        assertEquals(RulerHandle.RADIUS, RulerGeometry.hitHandle(tiny, 52f, 50f, docPerDp))
    }

    @Test
    fun draggingHandlesEditsTheRuler() {
        val r = RulerSettings(type = RulerType.STRAIGHT, centerX = 0f, centerY = 0f, angleDeg = 0f, radius = 100f, radiusX = 100f, radiusY = 50f)
        // Rotate by 90 degrees around the center.
        val rot = RulerGeometry.drag(r, RulerHandle.ROTATE, 88f, 0f, 0f, 88f)
        assertEquals(90f, rot.angleDeg, 1e-3f)
        // 1 degree past 30 snaps back to 30.
        val a = Math.toRadians(31.0)
        val snapped = RulerGeometry.drag(r, RulerHandle.ROTATE, 88f, 0f, (88 * kotlin.math.cos(a)).toFloat(), (88 * kotlin.math.sin(a)).toFloat())
        assertEquals(30f, snapped.angleDeg, 1e-4f)
        // Relative moves: grabbing off-center does not jump.
        val moved = RulerGeometry.drag(r, null, 500f, 500f, 510.5f, 497f)
        assertEquals(10.5f, moved.centerX, 1e-4f)
        assertEquals(-3f, moved.centerY, 1e-4f)
        val circle = r.copy(type = RulerType.CIRCLE)
        assertEquals(130f, RulerGeometry.drag(circle, RulerHandle.RADIUS, 102f, 0f, 132f, 0f).radius, 1e-3f)
        assertEquals(RulerGeometry.MIN_RADIUS, RulerGeometry.drag(circle, RulerHandle.RADIUS, 100f, 0f, 0f, 0f).radius, 0f)
        val ellipse = r.copy(type = RulerType.ELLIPSE, angleDeg = 90f)
        // Rotated 90 degrees: the x semi-axis points down, the y semi-axis points left.
        assertEquals(120f, RulerGeometry.drag(ellipse, RulerHandle.RADIUS_X, 0f, 100f, 3f, 120f).radiusX, 1e-3f)
        assertEquals(70f, RulerGeometry.drag(ellipse, RulerHandle.RADIUS_Y, -50f, 0f, -70f, 9f).radiusY, 1e-3f)
    }

    @Test
    fun handlePositionsMatchTheGeometry() {
        val r = RulerSettings(type = RulerType.ELLIPSE, centerX = 10f, centerY = 20f, angleDeg = 90f, radiusX = 30f, radiusY = 15f)
        val rx = RulerGeometry.handlePosition(r, RulerHandle.RADIUS_X, docPerDp)
        assertEquals(10f, rx.x, 1e-3f); assertEquals(50f, rx.y, 1e-3f)
        val ry = RulerGeometry.handlePosition(r, RulerHandle.RADIUS_Y, docPerDp)
        assertEquals(-5f, ry.x, 1e-3f); assertEquals(20f, ry.y, 1e-3f)
        val reset = RulerGeometry.reset(r.copy(angleDeg = 33f), 1000, 600)
        assertEquals(500f, reset.centerX, 0f)
        assertEquals(0f, reset.angleDeg, 0f)
        assertEquals(RulerType.ELLIPSE, reset.type)
    }

    @Test
    fun sanitizeKeepsNumbersFiniteAndInRange() {
        val ok = RulerSettings(enabled = true, centerX = -300f, centerY = 1200f, angleDeg = 30f)
        assertSame(ok, RulerGeometry.sanitize(ok, 1000, 800)) // off-canvas but sane: untouched
        val bad = ok.copy(
            centerX = Float.NaN, centerY = 1e20f, angleDeg = Float.POSITIVE_INFINITY,
            radius = 0f, radiusX = 1e12f, radiusY = Float.NaN, radialLines = 5000, nudgeStep = 0f,
        )
        val s = RulerGeometry.sanitize(bad, 1000, 800)
        assertEquals(500f, s.centerX, 0f)                    // NaN -> canvas center
        assertEquals(800f + 10 * 1000f, s.centerY, 0f)       // clamped to 10 canvas sizes away
        assertEquals(0f, s.angleDeg, 0f)
        assertEquals(RulerGeometry.MIN_RADIUS, s.radius, 0f)
        assertEquals(20 * 1000f, s.radiusX, 0f)
        assertTrue(s.radiusY.isFinite() && s.radiusY > 0f)
        assertEquals(RulerGeometry.MAX_RADIAL_LINES, s.radialLines)
        assertEquals(1f, s.nudgeStep, 0f)
        assertEquals(10f, RulerGeometry.sanitize(ok.copy(angleDeg = 370f), 1000, 800).angleDeg, 1e-4f)
    }

    @Test
    fun onlyTheDefaultCenterCountsAsUnplaced() {
        assertTrue(RulerGeometry.isUnplaced(RulerSettings()))
        val placed = RulerGeometry.resolved(RulerSettings(), 1000, 600)
        assertEquals(500f, placed.centerX, 0f)
        assertEquals(300f, placed.centerY, 0f)
        // An off-canvas vanishing point up-left of the canvas is a real position.
        val vp = RulerSettings(type = RulerType.RADIAL, centerX = -400f, centerY = -20f)
        assertFalse(RulerGeometry.isUnplaced(vp))
        assertSame(vp, RulerGeometry.resolved(vp, 1000, 600))
    }
}
