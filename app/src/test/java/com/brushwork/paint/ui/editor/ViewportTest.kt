package com.brushwork.paint.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** Pure-JVM tests of the canvas view transform math. */
class ViewportTest {
    private val out = FloatArray(2)
    private val eps = 1e-2f

    private fun viewport(w: Int = 1080, h: Int = 1920) = Viewport().apply { resize(w, h) }

    private fun assertMaps(v: Viewport, docX: Float, docY: Float, sx: Float, sy: Float) {
        v.docToScreen(docX, docY, out)
        assertEquals("screen x", sx, out[0], eps)
        assertEquals("screen y", sy, out[1], eps)
    }

    private fun docAt(v: Viewport, sx: Float, sy: Float): Pair<Float, Float> {
        v.screenToDoc(sx, sy, out)
        return out[0] to out[1]
    }

    // ------------------------------------------------------------------ fit

    @Test
    fun fitCentersDocumentWithFivePercentMargin() {
        val v = viewport()
        v.fit(1000, 2000)
        // Height is the limiting side: 1920 * 0.9 / 2000.
        assertEquals(0.864f, v.scale, 1e-5f)
        assertEquals(0f, v.rotation, 0f)
        assertMaps(v, 500f, 1000f, 540f, 960f)
        // 5 % of the view height above the document.
        assertMaps(v, 0f, 0f, 540f - 500f * 0.864f, 96f)
        assertFalse(v.userAdjusted)
    }

    @Test
    fun fitCentersInTheAreaBetweenChrome() {
        val v = viewport()
        v.fit(1000, 1000, top = 200f, bottom = 1720f)
        assertEquals(1080f * 0.9f / 1000f, v.scale, 1e-5f)
        assertMaps(v, 500f, 500f, 540f, 960f)
    }

    @Test
    fun fitIgnoresChromeThatCoversAlmostEverything() {
        val v = viewport()
        v.fit(1000, 1000, top = 1800f, bottom = 1900f)
        assertMaps(v, 500f, 500f, 540f, 960f)
    }

    @Test
    fun fitClampsToZoomRange() {
        val v = viewport()
        v.fit(2, 2)
        assertEquals(Viewport.MAX_ZOOM, v.scale, 0f)
        v.fit(1_000_000, 1_000_000)
        assertEquals(Viewport.MIN_ZOOM, v.scale, 0f)
    }

    @Test
    fun fitResetsRotation() {
        val v = viewport()
        v.fit(800, 600)
        v.rotateAround(540f, 960f, 33f)
        v.fit(800, 600)
        assertEquals(0f, v.rotation, 0f)
    }

    // ------------------------------------------------------------------ zoom

    @Test
    fun zoomAroundKeepsTheFocalPointFixed() {
        val v = viewport()
        v.fit(1000, 2000)
        v.rotateAround(540f, 960f, 25f)
        val before = docAt(v, 300f, 400f)
        val s0 = v.scale
        v.zoomAround(300f, 400f, s0 * 3f)
        assertEquals(s0 * 3f, v.scale, 1e-5f)
        val after = docAt(v, 300f, 400f)
        assertEquals(before.first, after.first, eps)
        assertEquals(before.second, after.second, eps)
    }

    @Test
    fun zoomIsClampedBetweenOnePercentAnd6400Percent() {
        val v = viewport()
        v.fit(1000, 1000)
        v.zoomAround(10f, 10f, 1000f)
        assertEquals(64f, v.scale, 0f)
        v.zoomAround(10f, 10f, 0.00001f)
        assertEquals(0.01f, v.scale, 0f)
    }

    @Test
    fun actualPixelsIsOneToOneAroundTheViewCenter() {
        val v = viewport()
        v.fit(4000, 5000)
        val center = docAt(v, 540f, 960f)
        v.actualPixels()
        assertEquals(1f, v.scale, 0f)
        val after = docAt(v, 540f, 960f)
        assertEquals(center.first, after.first, eps)
        assertEquals(center.second, after.second, eps)
    }

    // ------------------------------------------------------------------ rotation

    @Test
    fun rotationSnapsToRightAnglesWithinFourDegrees() {
        assertEquals(0f, Viewport.snapRotation(3.9f), 0f)
        assertEquals(0f, Viewport.snapRotation(-4f), 0f)
        assertEquals(4.5f, Viewport.snapRotation(4.5f), 0f)
        assertEquals(90f, Viewport.snapRotation(87f), 0f)
        assertEquals(-90f, Viewport.snapRotation(-92.5f), 0f)
        assertEquals(-180f, Viewport.snapRotation(178f), 0f)
        assertEquals(-180f, Viewport.snapRotation(-177f), 0f)
        assertEquals(45f, Viewport.snapRotation(45f), 0f)
        assertEquals(-95f, Viewport.snapRotation(-95f), 0f)
    }

    @Test
    fun normalizeDegreesWrapsIntoHalfOpenRange() {
        assertEquals(-180f, Viewport.normalizeDegrees(180f), 0f)
        assertEquals(-90f, Viewport.normalizeDegrees(270f), 0f)
        assertEquals(10f, Viewport.normalizeDegrees(370f), 1e-4f)
        assertEquals(170f, Viewport.normalizeDegrees(-190f), 1e-4f)
    }

    @Test
    fun resetRotationKeepsCenterAndZoom() {
        val v = viewport()
        v.fit(1000, 1000)
        v.rotateAround(540f, 960f, 57f)
        val s = v.scale
        val center = docAt(v, 540f, 960f)
        v.resetRotation()
        assertEquals(0f, v.rotation, 0f)
        assertEquals(s, v.scale, 0f)
        val after = docAt(v, 540f, 960f)
        assertEquals(center.first, after.first, eps)
        assertEquals(center.second, after.second, eps)
    }

    @Test
    fun rightAngleRotationIsExactlyAxisAligned() {
        val v = viewport()
        v.fit(1000, 1000)
        v.rotateAround(540f, 960f, 90f)
        val m = FloatArray(9)
        v.matrixValues(m)
        assertEquals(0f, m[0], 0f) // MSCALE_X
        assertEquals(0f, m[4], 0f) // MSCALE_Y
    }

    // ------------------------------------------------------------------ gestures

    private fun fingersAround(cx: Float, cy: Float, radius: Float, angleDeg: Float): Pair<FloatArray, FloatArray> {
        val a = Math.toRadians(angleDeg.toDouble())
        val dx = (cos(a) * radius).toFloat(); val dy = (sin(a) * radius).toFloat()
        return floatArrayOf(cx - dx, cx + dx) to floatArrayOf(cy - dy, cy + dy)
    }

    @Test
    fun pinchZoomsAroundTheFingersAndSnapsRotation() {
        val v = viewport()
        v.fit(1000, 1000)
        val s0 = v.scale
        val (x0, y0) = fingersAround(500f, 900f, 100f, 0f)
        v.beginGesture(x0, y0, 2)
        val anchor = docAt(v, 500f, 900f)
        // Fingers spread to twice the distance, rotate 88 degrees and move 60 px right.
        val (x1, y1) = fingersAround(560f, 900f, 200f, 88f)
        v.updateGesture(x1, y1, 2)
        assertEquals(s0 * 2f, v.scale, 1e-4f)
        assertEquals(90f, v.rotation, 0f)
        val now = docAt(v, 560f, 900f)
        assertEquals(anchor.first, now.first, eps)
        assertEquals(anchor.second, now.second, eps)
        assertTrue(v.userAdjusted)
    }

    @Test
    fun rotationOutsideSnapRangeFollowsFingers() {
        val v = viewport()
        v.fit(1000, 1000)
        val (x0, y0) = fingersAround(500f, 900f, 100f, 0f)
        v.beginGesture(x0, y0, 2)
        val (x1, y1) = fingersAround(500f, 900f, 100f, 30f)
        v.updateGesture(x1, y1, 2)
        assertEquals(30f, v.rotation, 1e-3f)
    }

    @Test
    fun oneFingerGesturePansOnly() {
        val v = viewport()
        v.fit(1000, 1000)
        val s0 = v.scale
        v.beginGesture(floatArrayOf(100f), floatArrayOf(100f), 1)
        val p = docAt(v, 100f, 100f)
        v.updateGesture(floatArrayOf(150f), floatArrayOf(80f), 1)
        assertEquals(s0, v.scale, 0f)
        assertEquals(0f, v.rotation, 0f)
        val q = docAt(v, 150f, 80f)
        assertEquals(p.first, q.first, eps)
        assertEquals(p.second, q.second, eps)
    }

    @Test
    fun changingPointerCountReanchorsWithoutJumping() {
        val v = viewport()
        v.fit(1000, 1000)
        val (x0, y0) = fingersAround(500f, 900f, 100f, 0f)
        v.beginGesture(x0, y0, 2)
        v.updateGesture(floatArrayOf(380f, 580f), floatArrayOf(900f, 900f), 2)
        val before = v.snapshot()
        // A third finger lands: the transform must not change until the fingers move.
        v.updateGesture(floatArrayOf(380f, 580f, 700f), floatArrayOf(900f, 900f, 1200f), 3)
        assertEquals(before, v.snapshot())
    }

    // ------------------------------------------------------------------ mirror

    @Test
    fun mirroredMappingRoundTrips() {
        for (mirrored in listOf(false, true)) {
            val v = viewport(1000, 800)
            v.fit(640, 480)
            v.rotateAround(500f, 400f, 37f)
            v.zoomAround(300f, 200f, v.scale * 1.7f)
            v.mirrored = mirrored
            for ((x, y) in listOf(0f to 0f, 640f to 480f, 123.5f to 77.25f, -50f to 900f)) {
                v.docToScreen(x, y, out)
                v.screenToDoc(out[0], out[1], out)
                assertEquals(x, out[0], eps)
                assertEquals(y, out[1], eps)
            }
        }
    }

    @Test
    fun mirrorFlipsAroundTheViewCenter() {
        val v = viewport(1000, 800)
        v.fit(640, 480)
        v.rotateAround(500f, 400f, 20f)
        v.docToScreen(100f, 60f, out)
        val plainX = out[0]; val plainY = out[1]
        v.mirrored = true
        v.docToScreen(100f, 60f, out)
        assertEquals(1000f - plainX, out[0], eps)
        assertEquals(plainY, out[1], eps)
        // The document center stays in place (it is at the view center).
        assertMaps(v, 320f, 240f, 500f, 400f)
    }

    @Test
    fun mirroredPanFollowsTheFinger() {
        val v = viewport(1000, 800)
        v.fit(640, 480)
        v.mirrored = true
        v.beginGesture(floatArrayOf(200f), floatArrayOf(300f), 1)
        val p = docAt(v, 200f, 300f)
        v.updateGesture(floatArrayOf(260f), floatArrayOf(330f), 1)
        val q = docAt(v, 260f, 330f)
        assertEquals(p.first, q.first, eps)
        assertEquals(p.second, q.second, eps)
    }

    @Test
    fun matrixValuesMatchPointMapping() {
        for (mirrored in listOf(false, true)) {
            val v = viewport(1000, 800)
            v.fit(640, 480)
            v.rotateAround(500f, 400f, -61f)
            v.mirrored = mirrored
            val m = FloatArray(9)
            v.matrixValues(m)
            for ((x, y) in listOf(0f to 0f, 640f to 0f, 17f to 333f)) {
                val sx = m[0] * x + m[1] * y + m[2]
                val sy = m[3] * x + m[4] * y + m[5]
                assertMaps(v, x, y, sx, sy)
            }
            assertEquals(1f, m[8], 0f)
        }
    }

    // ------------------------------------------------------------------ stylus orientation

    private val halfPi = (Math.PI / 2).toFloat()
    private val pi = Math.PI.toFloat()

    @Test
    fun screenAngleIsUnchangedOnAnUnrotatedView() {
        val v = viewport()
        v.fit(1000, 1000)
        assertEquals(0f, v.screenAngleToDoc(0f), 1e-5f)
        assertEquals(halfPi, v.screenAngleToDoc(halfPi), 1e-5f)
        assertEquals(-1f, v.screenAngleToDoc(-1f), 1e-5f)
        assertEquals(0f, v.screenAngleToDoc(Float.NaN), 0f)
    }

    @Test
    fun screenAngleRemovesTheViewRotation() {
        val v = viewport()
        v.fit(1000, 1000)
        // View rotated 90 degrees clockwise: the document's up points to the screen's right, so a
        // pen pointing right on screen points "up" on the paper, and screen-up is paper-left.
        v.rotateAround(540f, 960f, 90f)
        assertEquals(0f, v.screenAngleToDoc(halfPi), 1e-5f)
        assertEquals(-halfPi, v.screenAngleToDoc(0f), 1e-5f)
        // Direction check against the actual mapping: document up (0, -1) is on screen at +90 degrees.
        v.docToScreen(500f, 500f, out); val cx = out[0]; val cy = out[1]
        v.docToScreen(500f, 400f, out)
        assertEquals(halfPi, kotlin.math.atan2(out[0] - cx, -(out[1] - cy)), 1e-4f)
        // Rotated half a turn: pen up on screen points down on the paper; the result stays in (-PI, PI].
        v.rotateAround(540f, 960f, 180f)
        assertEquals(pi, v.screenAngleToDoc(0f), 1e-5f)
        assertEquals(-halfPi, v.screenAngleToDoc(halfPi), 1e-5f)
    }

    @Test
    fun screenAngleRemovesTheMirror() {
        val v = viewport()
        v.fit(1000, 1000)
        v.mirrored = true
        // On a mirrored display, a pen pointing right points left on the paper.
        assertEquals(-halfPi, v.screenAngleToDoc(halfPi), 1e-5f)
        assertEquals(0f, v.screenAngleToDoc(0f), 1e-5f)
        v.rotateAround(540f, 960f, 30f)
        val thirty = Math.toRadians(30.0).toFloat()
        assertEquals(-0.5f - thirty, v.screenAngleToDoc(0.5f), 1e-5f)
    }

    @Test
    fun normalizeRadiansWrapsIntoHalfOpenRange() {
        assertEquals(pi, Viewport.normalizeRadians(-pi), 1e-5f)
        assertEquals(pi, Viewport.normalizeRadians(pi), 1e-5f)
        assertEquals(-halfPi, Viewport.normalizeRadians(3 * halfPi), 1e-5f)
        assertEquals(0.25f, Viewport.normalizeRadians(0.25f + 4 * pi), 1e-4f)
    }

    // ------------------------------------------------------------------ resize / snapshot

    @Test
    fun resizeKeepsTheDocumentPointAtTheViewCenter() {
        val v = viewport(1080, 1920)
        v.fit(3000, 4000)
        v.zoomAround(200f, 300f, v.scale * 2.5f)
        val center = docAt(v, 540f, 960f)
        val s = v.scale
        v.resize(1920, 1080)
        assertEquals(s, v.scale, 0f)
        val after = docAt(v, 960f, 540f)
        assertEquals(center.first, after.first, eps)
        assertEquals(center.second, after.second, eps)
    }

    @Test
    fun resizeAsksForANewFitUntilTheUserAdjustsTheView() {
        // v1.6: the chrome's fit insets are constant, so a rotation refits through the resize.
        val v = viewport(1080, 1920)
        v.fit(3000, 4000)
        v.fittedDocVersion = 7
        v.resize(1080, 1920)
        assertEquals("the same size changes nothing", 7, v.fittedDocVersion)
        v.resize(1920, 1080)
        assertEquals("an untouched view asks for a new fit", Int.MIN_VALUE, v.fittedDocVersion)
        v.fit(3000, 4000)
        v.fittedDocVersion = 8
        v.userAdjusted = true
        v.resize(1080, 1920)
        assertEquals("a view the user zoomed keeps its framing", 8, v.fittedDocVersion)
    }

    @Test
    fun snapshotRestoresTheTransform() {
        val v = viewport()
        v.fit(1000, 1000)
        val s = v.snapshot()
        v.zoomAround(10f, 10f, 5f)
        v.rotateAround(10f, 10f, 45f)
        v.restore(s)
        assertEquals(s, v.snapshot())
    }
}
