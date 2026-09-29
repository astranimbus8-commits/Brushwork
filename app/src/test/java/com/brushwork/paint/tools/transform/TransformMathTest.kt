package com.brushwork.paint.tools.transform

import com.brushwork.paint.core.Vec2
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** Pure-Kotlin tests for the transform state and handle math (no Android needed). */
class TransformMathTest {
    private val eps = 1e-3f

    private fun assertVec(expected: Vec2, actual: Vec2, tol: Float = eps) {
        assertEquals("x of $actual", expected.x, actual.x, tol)
        assertEquals("y of $actual", expected.y, actual.y, tol)
    }

    private fun assertBox(expected: DocBox, actual: DocBox, tol: Float = eps) {
        assertEquals("left", expected.left, actual.left, tol)
        assertEquals("top", expected.top, actual.top, tol)
        assertEquals("right", expected.right, actual.right, tol)
        assertEquals("bottom", expected.bottom, actual.bottom, tol)
    }

    @Test
    fun identityMapsCornersAndMatrix() {
        val s = TransformState.identity(10, 20, 30, 40)
        assertVec(Vec2(10f, 20f), s.corner(0))
        assertVec(Vec2(40f, 20f), s.corner(1))
        assertVec(Vec2(40f, 60f), s.corner(2))
        assertVec(Vec2(10f, 60f), s.corner(3))
        assertArrayEquals(floatArrayOf(1f, 0f, 10f, 0f, 1f, 20f, 0f, 0f, 1f), s.affineValues(), 0f)
        assertTrue(s.isAxisAligned)
        assertVec(Vec2(0.25f, 0.5f), s.unmap(Vec2(17.5f, 40f)))
    }

    @Test
    fun quarterTurnMatrixIsExact() {
        val s = TransformState.identity(0, 0, 4, 2).rotatedAbout(Vec2(2f, 1f), 90f)
        val v = s.affineValues()
        // cos/sin are snapped so no tiny skew terms make the resampler blur.
        assertEquals(0f, v[0], 0f)
        assertEquals(-1f, v[1], 0f)
        assertEquals(1f, v[3], 0f)
        assertEquals(0f, v[4], 0f)
    }

    @Test
    fun cornerDragWithoutAspectLockScalesAroundOppositeCorner() {
        val s = TransformState.identity(0, 0, 100, 50)
        val r = TransformHandles.corner(s, 2, Vec2(100f, 50f), Vec2(150f, 100f), keepAspect = false)
        assertEquals(150f, r.width, eps)
        assertEquals(100f, r.height, eps)
        assertVec(Vec2(0f, 0f), r.corner(0))
        assertVec(Vec2(150f, 100f), r.corner(2))
    }

    @Test
    fun cornerDragWithAspectLockProjectsOnDiagonal() {
        val s = TransformState.identity(0, 0, 100, 50)
        val r = TransformHandles.corner(s, 2, Vec2(100f, 50f), Vec2(200f, 60f), keepAspect = true)
        // Projection of (200, 60) on the (100, 50) diagonal: k = 23000 / 12500.
        assertEquals(184f, r.width, eps)
        assertEquals(92f, r.height, eps)
        assertEquals(2f, r.width / r.height, eps)
        assertVec(Vec2(0f, 0f), r.corner(0))
    }

    @Test
    fun cornerDragOnTopLeftKeepsBottomRight() {
        val s = TransformState.identity(10, 10, 100, 100)
        val r = TransformHandles.corner(s, 0, Vec2(10f, 10f), Vec2(-40f, 30f), keepAspect = false)
        assertVec(Vec2(110f, 110f), r.corner(2))
        assertEquals(150f, r.width, eps)
        assertEquals(80f, r.height, eps)
    }

    @Test
    fun cornerDragCannotCollapse() {
        val s = TransformState.identity(0, 0, 100, 50)
        val r = TransformHandles.corner(s, 2, Vec2(100f, 50f), Vec2(0f, 0f), keepAspect = false)
        assertEquals(TransformState.MIN_SIZE, r.width, eps)
        assertEquals(TransformState.MIN_SIZE, r.height, eps)
    }

    @Test
    fun cornerDragOnRotatedBoxUsesBoxAxes() {
        val s = TransformState.identity(0, 0, 100, 50).rotatedAbout(Vec2(50f, 25f), 90f)
        val anchor = s.corner(0)
        assertVec(Vec2(75f, -25f), anchor)
        val br = s.corner(2)
        assertVec(Vec2(25f, 75f), br)
        val r = TransformHandles.corner(s, 2, br, br + Vec2(-10f, 20f), keepAspect = false)
        assertEquals(120f, r.width, eps)
        assertEquals(60f, r.height, eps)
        assertVec(anchor, r.corner(0))
        assertVec(Vec2(15f, 95f), r.corner(2))
    }

    @Test
    fun edgeDragScalesOneAxis() {
        val s = TransformState.identity(0, 0, 100, 50)
        val right = TransformHandles.edge(s, 1, Vec2(100f, 25f), Vec2(130f, 40f))
        assertEquals(130f, right.width, eps)
        assertEquals(50f, right.height, eps)
        assertVec(Vec2(0f, 0f), right.corner(0))
        val top = TransformHandles.edge(s, 0, Vec2(50f, 0f), Vec2(70f, -20f))
        assertEquals(100f, top.width, eps)
        assertEquals(70f, top.height, eps)
        assertVec(Vec2(0f, -20f), top.corner(0))
        assertVec(Vec2(100f, 50f), top.corner(2))
    }

    @Test
    fun rotateHandleTurnsAroundCenter() {
        val s = TransformState.identity(0, 0, 100, 50)
        val c = s.center()
        val r = TransformHandles.rotate(s, c, Vec2(50f, -11f), Vec2(86f, 25f))
        assertEquals(90f, r.rotationDeg, eps)
        assertVec(c, r.center())
        assertVec(Vec2(75f, -25f), r.corner(0))
        assertBox(DocBox(25f, -25f, 75f, 75f), r.bounds())
    }

    @Test
    fun rotationSnapsNearMultiplesOf45() {
        val s = TransformState.identity(0, 0, 100, 50)
        val c = s.center()
        fun at(deg: Double) = Vec2(c.x + 36f * cos(Math.toRadians(deg)).toFloat(), c.y + 36f * sin(Math.toRadians(deg)).toFloat())
        val from = at(-90.0)
        assertEquals(90f, TransformHandles.rotate(s, c, from, at(-1.5)).rotationDeg, eps)
        assertEquals(80f, TransformHandles.rotate(s, c, from, at(-10.0)).rotationDeg, 0.01f)
        assertEquals(-45f, TransformHandles.rotate(s, c, from, at(-133.5)).rotationDeg, eps)
    }

    @Test
    fun moveUsesWholePixels() {
        val s = TransformState.identity(0, 0, 10, 10)
        val m = TransformHandles.move(s, Vec2(0f, 0f), Vec2(10.4f, -3.6f))
        assertVec(Vec2(10f, -4f), m.corner(0))
    }

    @Test
    fun fitAndPlacement() {
        assertBox(DocBox(0f, 25f, 100f, 75f), TransformState.fitted(200, 100, 100, 100).bounds())
        // Small pictures keep their size, centered.
        assertBox(DocBox(25f, 25f, 75f, 75f), TransformState.placement(50, 50, 100, 100).bounds())
        // Larger ones shrink to 90 % of the canvas; the top edge snaps to a whole pixel.
        val p = TransformState.placement(200, 100, 100, 100)
        assertEquals(0.45f, p.sx, eps)
        assertBox(DocBox(5f, 28f, 95f, 73f), p.bounds())
        // Fit to canvas keeps flips and drops rotation.
        val f = TransformState.identity(0, 0, 100, 50).flipped(true).withRotation(30f).fittedTo(400, 400)
        assertEquals(-4f, f.sx, eps)
        assertEquals(4f, f.sy, eps)
        assertEquals(0f, f.rotationDeg, eps)
        assertBox(DocBox(0f, 100f, 400f, 300f), f.bounds())
    }

    @Test
    fun flipsMirrorWithoutChangingRotation() {
        val s = TransformState.identity(0, 0, 100, 50)
        val h = s.flipped(true)
        assertEquals(-1f, h.sx, eps)
        assertEquals(0f, h.rotationDeg, eps)
        assertBox(s.bounds(), h.bounds())
        assertVec(Vec2(100f, 0f), h.corner(0))
        val v = s.flipped(false)
        assertVec(Vec2(0f, 50f), v.corner(0))
        assertTrue(s.sameGeometry(h.flipped(true)))
    }

    @Test
    fun quarterTurnLandsOnWholePixels() {
        // 3x2 block at (20, 20) turned clockwise becomes 2x3; the half-pixel offset is snapped.
        val r = TransformState.identity(20, 20, 3, 2).rotated90(clockwise = true)
        assertBox(DocBox(21f, 20f, 23f, 23f), r.bounds())
        assertVec(Vec2(23f, 20f), r.corner(0)) // the source's top-left is now top-right
        val back = r.rotated90(clockwise = false)
        assertEquals(0f, back.rotationDeg, eps)
    }

    @Test
    fun distortMovesCornersAndRejectsInvalidQuads() {
        val s = TransformState.identity(0, 0, 100, 100)
        val d = TransformHandles.distortCorner(s, 2, Vec2(100f, 100f), Vec2(120f, 130f))
        assertNotNull(d)
        d!!
        assertTrue(d.isDistorted)
        assertVec(Vec2(120f, 130f), d.corner(2))
        assertVec(Vec2(0f, 0f), d.corner(0))
        assertBox(DocBox(0f, 0f, 120f, 130f), d.bounds())
        // Dragging the top-left past the bottom-right would fold the quad: rejected.
        assertNull(TransformHandles.distortCorner(s, 0, Vec2(0f, 0f), Vec2(110f, 110f)))
        // Affine operations keep the distortion.
        val moved = d.translated(5f, 5f)
        assertVec(Vec2(125f, 135f), moved.corner(2))
        // Dragging the corner back restores a plain rectangle.
        val restored = TransformHandles.distortCorner(d, 2, Vec2(120f, 130f), Vec2(100f, 100f))
        assertNotNull(restored)
        assertFalse(restored!!.isDistorted)
        // Edge distort moves both corners of the edge.
        val e = TransformHandles.distortEdge(s, 1, Vec2(100f, 50f), Vec2(130f, 50f))!!
        assertVec(Vec2(130f, 0f), e.corner(1))
        assertVec(Vec2(130f, 100f), e.corner(2))
    }

    @Test
    fun convexity() {
        assertTrue(TransformState.isConvexQuad(listOf(Vec2(0f, 0f), Vec2(10f, 0f), Vec2(10f, 10f), Vec2(0f, 10f))))
        assertTrue(TransformState.isConvexQuad(listOf(Vec2(0f, 0f), Vec2(0f, 10f), Vec2(10f, 10f), Vec2(10f, 0f))))
        assertFalse(TransformState.isConvexQuad(listOf(Vec2(0f, 0f), Vec2(10f, 10f), Vec2(10f, 0f), Vec2(0f, 10f))))
        assertFalse(TransformState.isConvexQuad(listOf(Vec2(0f, 0f), Vec2(10f, 0f), Vec2(2f, 2f), Vec2(0f, 10f))))
        assertFalse(TransformState.isConvexQuad(listOf(Vec2(0f, 0f), Vec2(10f, 0f), Vec2(20f, 0f), Vec2(0f, 10f))))
    }

    @Test
    fun numericSetters() {
        val s = TransformState.identity(10, 10, 100, 50)
        val w = s.withSize(width = 200f, keepAspect = true)
        assertEquals(200f, w.width, eps)
        assertEquals(100f, w.height, eps)
        assertEquals(10f, w.bounds().left, eps)
        assertEquals(10f, w.bounds().top, eps)
        val h = s.withSize(height = 25f)
        assertEquals(100f, h.width, eps)
        assertEquals(25f, h.height, eps)
        assertBox(DocBox(10f, 10f, 110f, 35f), h.bounds())
        assertBox(DocBox(0f, 5f, 100f, 55f), s.withPosition(left = 0f, top = 5f).bounds())
        val r = s.withRotation(45f)
        assertEquals(45f, r.rotationDeg, eps)
        assertVec(s.center(), r.center())
        val half = s.withScalePercent(50f)
        assertEquals(50f, half.width, eps)
        assertEquals(25f, half.height, eps)
        assertEquals(50f, half.scalePercent, eps)
        // Scale % resets a stretched aspect ratio.
        val restored = h.withScalePercent(100f)
        assertEquals(100f, restored.width, eps)
        assertEquals(50f, restored.height, eps)
    }

    @Test
    fun angleNormalization() {
        assertEquals(-170f, TransformState.normalizeDeg(190f), eps)
        assertEquals(180f, TransformState.normalizeDeg(-180f), eps)
        assertEquals(180f, TransformState.normalizeDeg(540f), eps)
        assertEquals(0f, TransformState.normalizeDeg(360f), eps)
    }

    @Test
    fun handleLayoutHitTesting() {
        val s = TransformState.identity(0, 0, 200, 100)
        val l = HandleLayout.compute(s, { it }, density = 1f)
        assertEquals(HandleHit(HandleKind.CORNER, 0), l.hitTest(Vec2(3f, 2f)))
        assertEquals(HandleHit(HandleKind.CORNER, 2), l.hitTest(Vec2(205f, 95f)))
        assertEquals(HandleHit(HandleKind.EDGE, 0), l.hitTest(Vec2(100f, 1f)))
        assertEquals(HandleHit(HandleKind.EDGE, 1), l.hitTest(Vec2(199f, 50f)))
        assertEquals(0, l.rotateEdge)
        assertVec(Vec2(100f, -36f), l.rotateHandle)
        assertEquals(HandleHit(HandleKind.ROTATE), l.hitTest(Vec2(100f, -30f)))
        assertEquals(HandleHit(HandleKind.MOVE), l.hitTest(Vec2(100f, 50f)))
        assertEquals(HandleHit(HandleKind.MOVE), l.hitTest(Vec2(500f, 500f)))
        // Flipped vertically: the rotation handle still sticks out of the top-most edge.
        val flipped = HandleLayout.compute(s.flipped(false), { it }, 1f)
        assertEquals(2, flipped.rotateEdge)
        assertVec(Vec2(100f, -36f), flipped.rotateHandle)
        // Tiny boxes: edge handles hidden so the corners stay grabbable.
        val tiny = HandleLayout.compute(TransformState.identity(0, 0, 10, 10), { it }, 1f)
        assertTrue(tiny.edgeVisible.none { it })
        assertEquals(8f, tiny.cornerHitRadius, eps)
    }

    @Test
    fun contentBoundsScan() {
        val w = 5
        val buf = IntArray(w * 4)
        buf[2 * w + 1] = 0x10000000
        buf[1 * w + 3] = 0xFF123456.toInt()
        val acc = ContentBounds.newAccumulator(w, 4)
        ContentBounds.scanStrip(buf, w, 4, 0, alphaMode = true, emptyColor = 0, acc = acc)
        assertArrayEquals(intArrayOf(1, 1, 3, 2), acc)
        // Strip offset + merge.
        val acc2 = ContentBounds.newAccumulator(w, 10)
        ContentBounds.scanStrip(buf, w, 4, 6, alphaMode = true, emptyColor = 0, acc = acc2)
        ContentBounds.merge(acc, acc2)
        assertArrayEquals(intArrayOf(1, 1, 3, 8), acc)
        // Mask mode: everything equal to the background is empty.
        val white = -1
        val mask = IntArray(w * 3) { white }
        mask[w + 2] = 0xFF000000.toInt()
        val acc3 = ContentBounds.newAccumulator(w, 3)
        ContentBounds.scanStrip(mask, w, 3, 0, alphaMode = false, emptyColor = white, acc = acc3)
        assertArrayEquals(intArrayOf(2, 1, 2, 1), acc3)
        val none = ContentBounds.newAccumulator(w, 3)
        ContentBounds.scanStrip(IntArray(w * 3) { white }, w, 3, 0, alphaMode = false, emptyColor = white, acc = none)
        assertTrue(none[2] < 0)
    }

    @Test
    fun maskBackgroundMajority() {
        assertEquals(1, ContentBounds.majority(intArrayOf(1, 2, 2, 1)))
        assertEquals(3, ContentBounds.majority(intArrayOf(5, 3, 3, 3)))
        assertEquals(-1, ContentBounds.majority(intArrayOf(-1, -1, 0, 7)))
    }
}
